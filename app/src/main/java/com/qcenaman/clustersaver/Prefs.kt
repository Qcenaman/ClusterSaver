package com.qcenaman.clustersaver

import android.content.Context
import android.os.Build
import com.qcenaman.clustersaver.core.FreezePlan
import com.qcenaman.clustersaver.core.ModelProfile
import org.json.JSONArray
import org.json.JSONObject

enum class Role { WORKER, HOST }

data class DeviceConfig(
    val name: String,
    val role: Role,
    val sharePct: Int,
    val threads: Int,
    val rpcPort: Int,
    val keep: String,
    val freeze: Boolean,
)

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("clustersaver", Context.MODE_PRIVATE)

    private fun defaultShare(): Int = if (Build.MODEL.uppercase().startsWith("SM-S928")) 60 else 100

    fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    fun config(ctx: Context): DeviceConfig {
        val p = sp(ctx)
        return DeviceConfig(
            name = p.getString("name", null) ?: Build.MODEL,
            role = runCatching { Role.valueOf(p.getString("role", Role.WORKER.name)!!) }.getOrDefault(Role.WORKER),
            sharePct = p.getInt("share", defaultShare()),
            threads = p.getInt("threads", defaultThreads()),
            rpcPort = p.getInt("rpcPort", 50052),
            keep = p.getString("keep", null) ?: FreezePlan.DEFAULT_KEEP.joinToString(", "),
            freeze = p.getBoolean("freeze", true),
        )
    }

    fun saveConfig(ctx: Context, c: DeviceConfig) {
        sp(ctx).edit()
            .putString("name", c.name)
            .putString("role", c.role.name)
            .putInt("share", c.sharePct.coerceIn(1, 100))
            .putInt("threads", c.threads.coerceIn(1, 64))
            .putInt("rpcPort", c.rpcPort)
            .putString("keep", c.keep)
            .putBoolean("freeze", c.freeze)
            .apply()
    }

    fun profiles(ctx: Context): MutableList<ModelProfile> {
        val raw = sp(ctx).getString("profiles", null) ?: return ModelProfile.DEFAULTS.toMutableList()
        return runCatching {
            val a = JSONArray(raw)
            MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                ModelProfile(o.getString("name"), o.getString("url"), o.optInt("ctx", 4096))
            }
        }.getOrElse { ModelProfile.DEFAULTS.toMutableList() }
    }

    fun saveProfiles(ctx: Context, list: List<ModelProfile>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("ctx", it.ctx)) }
        sp(ctx).edit().putString("profiles", a.toString()).apply()
    }

    fun selectedProfile(ctx: Context): Int = sp(ctx).getInt("selectedProfile", 0)
    fun setSelectedProfile(ctx: Context, i: Int) = sp(ctx).edit().putInt("selectedProfile", i).apply()

    fun frozen(ctx: Context): Set<String> = sp(ctx).getStringSet("frozen", emptySet())!!.toSet()
    fun setFrozen(ctx: Context, s: Set<String>) = sp(ctx).edit().putStringSet("frozen", s).commit()

    fun setupDone(ctx: Context): Boolean = sp(ctx).getBoolean("setupDone", false)
    fun setSetupDone(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("setupDone", v).apply()
}
