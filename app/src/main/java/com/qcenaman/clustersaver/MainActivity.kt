package com.qcenaman.clustersaver

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.qcenaman.clustersaver.core.ModelProfile
import com.qcenaman.clustersaver.core.Scripts
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var stateTv: TextView
    private lateinit var checksTv: TextView
    private lateinit var peersTv: TextView
    private lateinit var urlTv: TextView
    private lateinit var logTv: TextView
    private lateinit var nameEt: EditText
    private lateinit var shareEt: EditText
    private lateinit var threadsEt: EditText
    private lateinit var keepEt: EditText
    private lateinit var freezeCb: CheckBox
    private lateinit var workerRb: RadioButton
    private lateinit var hostRb: RadioButton
    private lateinit var hostBox: LinearLayout
    private lateinit var profileSp: Spinner
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var shizukuBtn: Button
    private lateinit var termuxBtn: Button
    private lateinit var batteryBtn: Button
    private var profiles = mutableListOf<ModelProfile>()

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cluster.init(this)
        setContentView(buildUi())
        loadConfig()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        Cluster.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        saveConfig()
        Cluster.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    // ---- UI ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, dp(8), 0, 0) }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun edit(type: Int) = EditText(this).apply { inputType = type }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        stateTv = TextView(this).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(stateTv)
        urlTv = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        root.addView(urlTv)

        root.addView(header("Checks"))
        checksTv = TextView(this)
        root.addView(checksTv)
        shizukuBtn = button("Allow Shizuku") { ShizukuShell.request(1) }
        termuxBtn = button("Allow Termux commands") { requestPermissions(arrayOf(TermuxRunner.PERMISSION), 3) }
        batteryBtn = button("Set battery to Unrestricted") { askBattery() }
        root.addView(shizukuBtn); root.addView(termuxBtn); root.addView(batteryBtn)
        root.addView(label("Once in Termux, paste this line so ClusterSaver may run commands there:"))
        root.addView(TextView(this).apply {
            text = TermuxRunner.ENABLE_LINE
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        })
        root.addView(button("Copy that line") {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("termux", TermuxRunner.ENABLE_LINE))
            Toast.makeText(this, "Copied – paste it in Termux", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("Set up Termux (build llama.cpp)") { saveConfig(); Cluster.setup() })

        root.addView(header("This device"))
        root.addView(label("Name"))
        nameEt = edit(InputType.TYPE_CLASS_TEXT); root.addView(nameEt)
        val roleGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        workerRb = RadioButton(this).apply { text = "Worker"; id = View.generateViewId() }
        hostRb = RadioButton(this).apply { text = "Host"; id = View.generateViewId() }
        roleGroup.addView(workerRb); roleGroup.addView(hostRb)
        roleGroup.setOnCheckedChangeListener { _, _ -> hostBox.visibility = if (hostRb.isChecked) View.VISIBLE else View.GONE }
        root.addView(label("Role")); root.addView(roleGroup)
        root.addView(label("Share of free memory to give (%)"))
        shareEt = edit(InputType.TYPE_CLASS_NUMBER); root.addView(shareEt)
        root.addView(label("CPU threads"))
        threadsEt = edit(InputType.TYPE_CLASS_NUMBER); root.addView(threadsEt)
        freezeCb = CheckBox(this).apply { text = "Freeze other apps while running" }
        root.addView(freezeCb)
        root.addView(label("Apps to keep running (package names, comma separated)"))
        keepEt = edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE); root.addView(keepEt)

        hostBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hostBox.addView(header("Model (host only)"))
        profileSp = Spinner(this)
        hostBox.addView(profileSp)
        val profRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profRow.addView(button("New") { editProfile(-1) })
        profRow.addView(button("Edit") { editProfile(profileSp.selectedItemPosition) })
        profRow.addView(button("Delete") { deleteProfile(profileSp.selectedItemPosition) })
        hostBox.addView(profRow)
        hostBox.addView(header("Workers"))
        peersTv = TextView(this)
        hostBox.addView(peersTv)
        hostBox.addView(button("Rebalance now (reload with current devices)") { Cluster.rebalance() })
        root.addView(hostBox)

        root.addView(header("Run"))
        startBtn = button("Start") {
            saveConfig()
            Cluster.start(profiles.getOrNull(profileSp.selectedItemPosition))
        }
        stopBtn = button("Stop / leave cluster") { Cluster.stop() }
        root.addView(startBtn); root.addView(stopBtn)
        root.addView(button("Restore frozen apps") { Cluster.restoreApps(); refresh() })

        root.addView(header("Log"))
        logTv = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(logTv)

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun loadConfig() {
        val c = Prefs.config(this)
        nameEt.setText(c.name)
        shareEt.setText(c.sharePct.toString())
        threadsEt.setText(c.threads.toString())
        keepEt.setText(c.keep)
        freezeCb.isChecked = c.freeze
        if (c.role == Role.HOST) hostRb.isChecked = true else workerRb.isChecked = true
        hostBox.visibility = if (c.role == Role.HOST) View.VISIBLE else View.GONE
        profiles = Prefs.profiles(this)
        bindProfiles(Prefs.selectedProfile(this))
    }

    private fun bindProfiles(select: Int) {
        profileSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.name })
        if (profiles.isNotEmpty()) profileSp.setSelection(select.coerceIn(0, profiles.size - 1))
        profileSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = Prefs.setSelectedProfile(this@MainActivity, pos)
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun saveConfig() {
        val old = Prefs.config(this)
        Prefs.saveConfig(this, DeviceConfig(
            name = nameEt.text.toString().trim().ifEmpty { Build.MODEL },
            role = if (hostRb.isChecked) Role.HOST else Role.WORKER,
            sharePct = shareEt.text.toString().toIntOrNull() ?: old.sharePct,
            threads = threadsEt.text.toString().toIntOrNull() ?: old.threads,
            rpcPort = old.rpcPort,
            keep = keepEt.text.toString(),
            freeze = freezeCb.isChecked,
        ))
    }

    private fun editProfile(index: Int) {
        val p = profiles.getOrNull(index)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val name = edit(InputType.TYPE_CLASS_TEXT).apply { hint = "Name"; setText(p?.name ?: "") }
        val url = edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { hint = "https://…/model.gguf"; setText(p?.url ?: "") }
        val ctx = edit(InputType.TYPE_CLASS_NUMBER).apply { hint = "Context size"; setText((p?.ctx ?: 4096).toString()) }
        box.addView(name); box.addView(url); box.addView(ctx)
        AlertDialog.Builder(this)
            .setTitle(if (p == null) "New model" else "Edit model")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val np = ModelProfile(
                    name.text.toString().trim().ifEmpty { "Model" },
                    url.text.toString().trim(),
                    ctx.text.toString().toIntOrNull()?.coerceIn(256, 262144) ?: 4096,
                )
                if (!np.url.startsWith("https://") || !Scripts.isValidModelFile(np.file)) {
                    Toast.makeText(this, "The URL must be https and end in a .gguf file name", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val sel = if (p == null) { profiles.add(np); profiles.size - 1 } else { profiles[index] = np; index }
                Prefs.saveProfiles(this, profiles)
                bindProfiles(sel)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteProfile(index: Int) {
        if (index !in profiles.indices) return
        profiles.removeAt(index)
        Prefs.saveProfiles(this, profiles)
        bindProfiles(0)
    }

    private fun askBattery() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        Toast.makeText(this, "Also set Termux's battery usage to Unrestricted", Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val st = Cluster.state
        stateTv.text = st.label
        urlTv.text = if (st == Cluster.State.SERVING) "Chat: ${Cluster.serverUrl()}  (open it in any browser on your Wi-Fi)" else ""
        val shizukuOk = ShizukuShell.granted()
        val termuxInstalled = TermuxRunner.installed(this)
        val termuxOk = termuxInstalled && TermuxRunner.permitted(this)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        checksTv.text = listOf(
            (if (shizukuOk) "✓" else "✗") + " Shizuku " + when {
                shizukuOk -> "ready"
                ShizukuShell.running() -> "running, needs permission"
                else -> "not running"
            },
            (if (termuxOk) "✓" else "✗") + " Termux " + when {
                !termuxInstalled -> "not installed (get it from F-Droid or GitHub)"
                termuxOk -> "allowed"
                else -> "needs permission"
            },
            (if (Prefs.setupDone(this)) "✓" else "✗") + " llama.cpp built",
            (if (batteryOk) "✓" else "✗") + " battery unrestricted",
            "Frozen apps: ${Prefs.frozen(this).size}",
            "Wi-Fi IP: ${Net.localIp() ?: "none"}",
        ).joinToString("\n")
        shizukuBtn.visibility = if (shizukuOk) View.GONE else View.VISIBLE
        termuxBtn.visibility = if (termuxOk || !termuxInstalled) View.GONE else View.VISIBLE
        batteryBtn.visibility = if (batteryOk) View.GONE else View.VISIBLE
        startBtn.isEnabled = !Cluster.busy
        stopBtn.isEnabled = Cluster.state != Cluster.State.IDLE
        peersTv.text = Cluster.peersSummary()
        logTv.text = Cluster.logText()
    }
}
