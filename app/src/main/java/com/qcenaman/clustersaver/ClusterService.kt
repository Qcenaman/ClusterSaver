package com.qcenaman.clustersaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.qcenaman.clustersaver.core.Announcement
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object Discovery {
    fun send(a: Announcement) {
        val data = a.encode().toByteArray()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                Net.broadcastTargets().forEach { addr ->
                    runCatching { s.send(DatagramPacket(data, data.size, addr, Net.DISCOVERY_PORT)) }
                }
            }
        }
    }

    fun sendLeaving(ctx: Context) {
        val cfg = Prefs.config(ctx)
        val ip = Net.localIp() ?: return
        val a = Announcement(cfg.name, ip, cfg.rpcPort, 0, cfg.sharePct, false, true)
        repeat(3) {
            send(a)
            Thread.sleep(300)
        }
    }
}

/** Keeps the process, CPU and Wi-Fi awake while the cluster runs, and runs discovery and the watchdog. */
class ClusterService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var sched: ScheduledExecutorService? = null
    @Volatile private var socket: DatagramSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Cluster.init(this)
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cluster", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, build())
        }
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClusterSaver:run").apply { acquire() }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        multicast = wm.createMulticastLock("ClusterSaver").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ClusterSaver").apply {
            setReferenceCounted(false); acquire()
        }
        sched = Executors.newScheduledThreadPool(2).also { s ->
            s.scheduleWithFixedDelay({ runCatching { Cluster.ownAnnouncement()?.let { Discovery.send(it) } } }, 1, 3, TimeUnit.SECONDS)
            s.scheduleWithFixedDelay({ runCatching { Cluster.tick() } }, 5, 5, TimeUnit.SECONDS)
        }
        Thread(::listen, "discovery").apply { isDaemon = true; start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun listen() {
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Net.DISCOVERY_PORT))
            }
            socket = s
            val buf = ByteArray(512)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                Announcement.decode(String(p.data, 0, p.length))?.let { Cluster.onAnnouncement(it) }
            }
        } catch (e: Exception) {
            if (socket?.isClosed == false) Cluster.log("Discovery stopped: ${e.message}")
        }
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("ClusterSaver")
            .setContentText(Cluster.state.label)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        instance = null
        sched?.shutdownNow()
        socket?.close()
        runCatching { wake?.release() }
        runCatching { multicast?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cluster"
        private const val NOTIF_ID = 7
        @Volatile private var instance: ClusterService? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ClusterService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ClusterService::class.java))
        }

        fun update(ctx: Context) {
            val s = instance ?: return
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, s.build())
        }
    }
}
