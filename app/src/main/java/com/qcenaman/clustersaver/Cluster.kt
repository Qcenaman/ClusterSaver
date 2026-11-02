package com.qcenaman.clustersaver

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.qcenaman.clustersaver.core.Announcement
import com.qcenaman.clustersaver.core.Member
import com.qcenaman.clustersaver.core.ModelProfile
import com.qcenaman.clustersaver.core.PeerSeen
import com.qcenaman.clustersaver.core.Planner
import com.qcenaman.clustersaver.core.Scripts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The app's brain: one per process. Drives Termux, freezing, discovery and the host watchdog. */
object Cluster {
    enum class State(val label: String) {
        IDLE("Idle"),
        SETTING_UP("Building llama.cpp in Termux…"),
        STARTING("Starting…"),
        WORKER_RUNNING("Worker running"),
        PREPARING_MODEL("Getting the model ready…"),
        LOADING("Loading the model across devices…"),
        SERVING("Model running"),
        FAILED("Stopped with an error"),
        STOPPING("Stopping…"),
    }

    data class Peer(val ann: Announcement, val lastSeenMs: Long)

    private const val PEER_TIMEOUT_MS = 20_000L
    private const val MAX_RESTARTS = 3

    @Volatile var state = State.IDLE
        private set
    @Volatile var listener: (() -> Unit)? = null
    @Volatile var activeEndpoints: List<String> = emptyList()
        private set
    val peers = ConcurrentHashMap<String, Peer>()

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lines = ArrayDeque<String>()

    @Volatile private var role = Role.WORKER
    @Volatile private var model: ModelProfile? = null
    @Volatile private var modelBytes = 0L
    @Volatile private var loadingSince = 0L
    @Volatile private var healthFails = 0
    @Volatile private var restarts = 0
    @Volatile private var rpcMisses = 0
    @Volatile private var restartPending = false

    fun init(ctx: Context) {
        if (!::app.isInitialized) {
            app = ctx.applicationContext
            role = Prefs.config(app).role
        }
    }

    val busy: Boolean get() = state != State.IDLE && state != State.FAILED

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lines) {
            lines.addLast("$t  $msg")
            while (lines.size > 200) lines.removeFirst()
        }
        notifyUi()
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    private fun notifyUi() {
        main.post { listener?.invoke() }
    }

    private fun set(s: State) {
        state = s
        notifyUi()
        if (::app.isInitialized) ClusterService.update(app)
    }

    // ---- user actions ----

    fun setup() {
        if (busy) { log("Stop the cluster first"); return }
        if (TermuxRunner.run(app, "setup", Scripts.setup())) {
            set(State.SETTING_UP)
            log("Building llama.cpp in Termux. The first time takes 15–40 minutes; keep the device plugged in.")
        }
    }

    fun start(profile: ModelProfile?) {
        if (busy) return
        val cfg = Prefs.config(app)
        role = cfg.role
        if (!Prefs.setupDone(app)) log("If llama.cpp isn't built on this device yet, tap Set up Termux first.")
        if (role == Role.HOST) {
            if (profile == null || !Scripts.isValidModelFile(profile.file)) {
                log("Pick a model profile whose URL ends in .gguf")
                return
            }
            model = profile
        }
        restarts = 0
        rpcMisses = 0
        restartPending = false
        activeEndpoints = emptyList()
        set(State.STARTING)
        ClusterService.start(app)
        io.execute {
            if (cfg.freeze) {
                val n = runCatching { Freezer.freeze(app, cfg) }.getOrElse { log("Freezing failed: ${it.message}"); 0 }
                if (n > 0) log("Froze $n apps")
            }
            log("Free memory: ${Net.freeMb(app)} of ${Net.totalMb(app)} MB")
            val host = role == Role.HOST
            val script = Scripts.startRpc(if (host) "127.0.0.1" else "0.0.0.0", cfg.rpcPort, cfg.threads)
            main.post {
                if (!TermuxRunner.run(app, if (host) "host-rpc" else "worker-rpc", script)) fail("Couldn't start Termux")
            }
        }
    }

    fun stop() {
        val wasWorker = role == Role.WORKER && state == State.WORKER_RUNNING
        if (state == State.IDLE) {
            io.execute { restoreApps() }
            return
        }
        set(State.STOPPING)
        io.execute {
            if (wasWorker) Discovery.sendLeaving(app)
            main.post { TermuxRunner.run(app, "stop", Scripts.stopAll()) }
            restoreApps()
            activeEndpoints = emptyList()
            main.post {
                set(State.IDLE)
                log("Stopped")
                ClusterService.stop(app)
            }
        }
    }

    fun restoreApps() {
        val n = runCatching { Freezer.restore(app) }.getOrElse { log("Restore failed: ${it.message}"); -1 }
        if (n > 0) log("Restored $n apps")
    }

    fun rebalance() {
        if (role != Role.HOST || model == null) return
        if (state == State.LOADING || state == State.SERVING || state == State.FAILED) {
            restarts = 0
            restart("rebalance requested")
        }
    }

    private fun fail(msg: String) {
        log(msg)
        set(State.FAILED)
    }

    // ---- results and events ----

    fun onTermuxResult(tag: String, exit: Int, stdout: String, stderr: String) {
        val ok = exit == 0
        val tail = (stdout + "\n" + stderr).lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n")
        val cfg = Prefs.config(app)
        when (tag) {
            "setup" -> {
                if (ok && stdout.contains("SETUP_OK")) {
                    Prefs.setSetupDone(app, true)
                    log("llama.cpp is built and ready on this device")
                } else log("Setup failed:\n$tail")
                if (state == State.SETTING_UP) set(State.IDLE)
            }
            "worker-rpc" -> if (state == State.STARTING) {
                if (ok) { set(State.WORKER_RUNNING); log("Worker ready on port ${cfg.rpcPort}") }
                else fail("rpc-server didn't start:\n$tail")
            }
            "worker-restart" -> log(if (ok) "rpc-server is back" else "rpc-server restart failed:\n$tail")
            "host-rpc" -> if (state == State.STARTING) {
                val m = model
                if (ok && m != null) {
                    set(State.PREPARING_MODEL)
                    log("Checking ${m.file} (downloads the first time, can take a while)…")
                    if (!TermuxRunner.run(app, "model", Scripts.prepareModel(m.url, m.file))) fail("Couldn't reach Termux")
                } else fail("Local rpc-server didn't start:\n$tail")
            }
            "model" -> if (state == State.PREPARING_MODEL) {
                val size = Scripts.parseSize(stdout)
                if (ok && size != null) {
                    modelBytes = size
                    log("Model ready (${size / 1_048_576} MB)")
                    launch()
                } else fail("Model download failed:\n$tail")
            }
            "server" -> if (ok) log("llama-server is loading the model") else fail("llama-server didn't start:\n$tail")
            "server-log" -> log("Server log:\n$tail")
            else -> Unit
        }
    }

    fun onAnnouncement(a: Announcement) {
        if (role != Role.HOST || a.ip == Net.localIp()) return
        val isNew = !peers.containsKey(a.endpoint)
        peers[a.endpoint] = Peer(a, System.currentTimeMillis())
        if (isNew) log("Found ${a.name} (${a.endpoint})")
        notifyUi()
    }

    /** What this device broadcasts while it is a running worker. */
    fun ownAnnouncement(): Announcement? {
        if (!::app.isInitialized || state != State.WORKER_RUNNING) return null
        val cfg = Prefs.config(app)
        val ip = Net.localIp() ?: return null
        return Announcement(cfg.name, ip, cfg.rpcPort, Net.freeMb(app), cfg.sharePct,
            Net.portOpen("127.0.0.1", cfg.rpcPort), false)
    }

    // ---- host logic ----

    private fun currentMembers(): List<Member> {
        val cfg = Prefs.config(app)
        val now = System.currentTimeMillis()
        val local = Member("127.0.0.1:${cfg.rpcPort}", "${cfg.name} (host)", Net.freeMb(app), cfg.sharePct)
        val others = peers.values
            .filter { it.ann.ready && !it.ann.leaving && now - it.lastSeenMs < PEER_TIMEOUT_MS }
            .map { Member(it.ann.endpoint, it.ann.name, it.ann.freeMb, it.ann.sharePct) }
        return Planner.select(listOf(local) + others)
    }

    private fun launch() {
        val m = model ?: return fail("No model selected")
        val cfg = Prefs.config(app)
        val members = currentMembers()
        if (members.isEmpty()) return fail("No device has enough free memory")
        log("Using ${members.size} device(s): " + members.joinToString { "${it.name} ${Planner.usableMb(it)} MB" })
        if (!Planner.fits(members, modelBytes)) {
            log("Warning: ${Planner.totalUsableMb(members)} MB usable for ${modelBytes / 1_048_576} MB of weights – it may not fit. Trying anyway.")
        }
        activeEndpoints = members.map { it.endpoint }
        loadingSince = System.currentTimeMillis()
        healthFails = 0
        set(State.LOADING)
        val script = Scripts.startServer(m.file, activeEndpoints, Planner.tensorSplit(members), m.ctx, cfg.threads)
        if (!TermuxRunner.run(app, "server", script)) fail("Couldn't start llama-server")
    }

    private fun restart(reason: String) {
        if (restartPending) return
        restarts++
        if (restarts > MAX_RESTARTS) {
            fail("Restarted too many times ($reason). Check the devices, then tap Rebalance.")
            return
        }
        restartPending = true
        log("Reloading the model: $reason")
        set(State.LOADING)
        TermuxRunner.run(app, "kill-server", Scripts.stopServer())
        // Give workers time to free their memory and report it before re-planning.
        main.postDelayed({
            restartPending = false
            if (state == State.LOADING) launch()
        }, 10_000)
    }

    /** Called by the service every 5 s, off the main thread. */
    fun tick() {
        if (!::app.isInitialized) return
        val cfg = Prefs.config(app)
        when (state) {
            State.WORKER_RUNNING -> {
                if (Net.portOpen("127.0.0.1", cfg.rpcPort)) rpcMisses = 0
                else if (++rpcMisses >= 3) {
                    rpcMisses = 0
                    log("rpc-server stopped – restarting it")
                    main.post { TermuxRunner.run(app, "worker-restart", Scripts.startRpc("0.0.0.0", cfg.rpcPort, cfg.threads)) }
                }
            }
            State.LOADING, State.SERVING -> if (!restartPending) hostWatch()
            else -> Unit
        }
    }

    private fun hostWatch() {
        val now = System.currentTimeMillis()
        val seen = peers.mapValues { PeerSeen(it.value.ann.leaving, it.value.lastSeenMs) }
        val lost = Planner.lostMembers(activeEndpoints, seen, now, PEER_TIMEOUT_MS)
        if (lost.isNotEmpty()) {
            val names = lost.joinToString { peers[it]?.ann?.name ?: it }
            main.post { restart("$names left the cluster") }
            return
        }
        val h = Net.health(Scripts.SERVER_PORT)
        if (state == State.LOADING) {
            if (h == 200) {
                restarts = 0
                main.post {
                    set(State.SERVING)
                    log("Model loaded. Chat at ${serverUrl()}")
                }
            } else if (h == -1 && now - loadingSince > 60_000) crashed()
        } else {
            if (h == 200) healthFails = 0 else if (++healthFails >= 3) crashed()
        }
    }

    private fun crashed() {
        healthFails = 0
        main.post {
            TermuxRunner.run(app, "server-log", Scripts.serverLogTail())
            restart("the server stopped responding")
        }
    }

    fun serverUrl(): String = "http://${Net.localIp() ?: "this-device"}:${Scripts.SERVER_PORT}"

    fun peersSummary(): String {
        val now = System.currentTimeMillis()
        if (peers.isEmpty()) return "No workers found yet"
        return peers.values.sortedBy { it.ann.name }.joinToString("\n") { p ->
            val age = (now - p.lastSeenMs) / 1000
            val status = when {
                p.ann.leaving -> "left"
                age * 1000 > PEER_TIMEOUT_MS -> "offline"
                !p.ann.ready -> "not ready"
                p.ann.endpoint in activeEndpoints -> "in use"
                else -> "ready"
            }
            "• ${p.ann.name}  ${p.ann.ip}  ${p.ann.freeMb} MB free, share ${p.ann.sharePct}%  – $status"
        }
    }
}
