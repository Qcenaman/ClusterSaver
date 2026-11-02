package com.qcenaman.clustersaver

import android.app.ActivityManager
import android.content.Context
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

object Net {
    const val DISCOVERY_PORT = 50050

    private fun wifiAddresses() = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
    }.getOrDefault(emptyList())

    fun localIp(): String? = wifiAddresses().firstOrNull()?.address?.hostAddress

    fun broadcastTargets(): List<InetAddress> {
        val list = wifiAddresses().mapNotNull { it.broadcast }.toMutableList()
        runCatching { list += InetAddress.getByName("255.255.255.255") }
        return list.distinct()
    }

    fun portOpen(host: String, port: Int, timeoutMs: Int = 600): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) { false }

    /** HTTP status of llama-server's /health, or -1 if nothing answers. */
    fun health(port: Int): Int = try {
        val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 2500
        val code = c.responseCode
        c.disconnect()
        code
    } catch (e: Exception) { -1 }

    fun freeMb(ctx: Context): Long {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    fun totalMb(ctx: Context): Long {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }
}
