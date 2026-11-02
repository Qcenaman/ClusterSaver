package com.qcenaman.clustersaver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

object TermuxRunner {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val ENABLE_LINE =
        "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"
    const val EXTRA_TAG = "cs_tag"
    private val requestCodes = AtomicInteger(1000)

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun permitted(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Runs a bash script in Termux in the background; the result comes back to [TermuxResultReceiver]. */
    fun run(ctx: Context, tag: String, script: String): Boolean {
        if (!installed(ctx)) { Cluster.log("Termux is not installed"); return false }
        if (!permitted(ctx)) { Cluster.log("Allow ClusterSaver to run Termux commands first"); return false }
        val code = requestCodes.incrementAndGet()
        val result = Intent(ctx, TermuxResultReceiver::class.java)
            .setAction("com.qcenaman.clustersaver.RESULT.$code")
            .putExtra(EXTRA_TAG, tag)
        var flags = PendingIntent.FLAG_ONE_SHOT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(ctx, code, result, flags)
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName(PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        return try {
            ctx.startForegroundService(intent)
            true
        } catch (e: Exception) {
            Cluster.log("Couldn't reach Termux: ${e.message}. In Termux run: $ENABLE_LINE")
            false
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Cluster.init(context)
        val tag = intent.getStringExtra(TermuxRunner.EXTRA_TAG) ?: return
        val b = intent.getBundleExtra("result")
        val stdout = b?.getString("stdout").orEmpty()
        val stderr = b?.getString("stderr").orEmpty()
        val exit = b?.getInt("exitCode", -1) ?: -1
        val errMsg = b?.getString("errmsg").orEmpty()
        Cluster.onTermuxResult(tag, exit, stdout, stderr + if (errMsg.isNotBlank()) "\n$errMsg" else "")
    }
}
