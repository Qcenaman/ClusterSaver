package com.qcenaman.clustersaver

import android.content.Context
import android.content.pm.PackageManager
import com.qcenaman.clustersaver.core.FreezePlan
import rikka.shizuku.Shizuku

/** Runs shell commands with ADB-level rights through Shizuku. */
object ShizukuShell {
    fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted(): Boolean = running() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun request(code: Int) {
        if (running()) Shizuku.requestPermission(code)
    }

    /** Returns exit code and combined output. */
    fun exec(script: String): Pair<Int, String> {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
        val out = StringBuilder()
        val errThread = Thread { p.errorStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }
        errThread.start()
        p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } }
        errThread.join()
        val code = p.waitFor()
        return code to out.toString()
    }
}

/** Freezes (suspends) user apps while the cluster runs and puts them back afterwards. */
object Freezer {
    private fun userId(): Int = android.os.Process.myUserHandle().hashCode()

    fun freeze(ctx: Context, cfg: DeviceConfig): Int {
        if (!ShizukuShell.granted()) {
            Cluster.log("Shizuku not ready – apps were not frozen")
            return 0
        }
        val uid = userId()
        val installed = FreezePlan.parsePackageList(ShizukuShell.exec("pm list packages -3 --user $uid").second)
        val keep = FreezePlan.parseKeep(cfg.keep) + protectedNow(ctx)
        val targets = FreezePlan.candidates(installed, keep)
        if (targets.isEmpty()) return 0
        // Save first, so a crash mid-way can still be undone.
        Prefs.setFrozen(ctx, Prefs.frozen(ctx) + targets)
        ShizukuShell.exec(FreezePlan.freezeScript(targets, uid))
        return targets.size
    }

    fun restore(ctx: Context): Int {
        val list = Prefs.frozen(ctx)
        if (list.isEmpty()) return 0
        if (!ShizukuShell.granted()) {
            Cluster.log("Shizuku not ready – can't restore ${list.size} apps yet. Start Shizuku and tap Restore.")
            return -1
        }
        ShizukuShell.exec(FreezePlan.restoreScript(list.sorted(), userId()))
        Prefs.setFrozen(ctx, emptySet())
        return list.size
    }

    /** Launcher and keyboard must never be frozen. */
    private fun protectedNow(ctx: Context): Set<String> {
        val s = mutableSetOf(ctx.packageName)
        runCatching {
            val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { s += it }
        }
        runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, "default_input_method")
                ?.substringBefore('/')?.let { s += it }
        }
        return s
    }
}
