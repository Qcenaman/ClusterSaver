package com.qcenaman.clustersaver.core

/** What a worker broadcasts on the LAN every few seconds. Plain text so it needs no JSON library. */
data class Announcement(
    val name: String,
    val ip: String,
    val port: Int,
    val freeMb: Long,
    val sharePct: Int,
    val ready: Boolean,
    val leaving: Boolean,
) {
    val endpoint: String get() = "$ip:$port"

    fun encode(): String = listOf(
        PREFIX,
        name.replace("|", " ").trim().take(40),
        ip,
        port,
        freeMb,
        sharePct,
        if (ready) 1 else 0,
        if (leaving) 1 else 0,
    ).joinToString("|")

    companion object {
        const val PREFIX = "CS1"
        private val IP = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun decode(text: String): Announcement? {
            val p = text.trim().split("|")
            if (p.size != 8 || p[0] != PREFIX || !IP.matches(p[2])) return null
            return try {
                val port = p[3].toInt()
                if (port !in 1..65535) return null
                Announcement(
                    name = p[1],
                    ip = p[2],
                    port = port,
                    freeMb = p[4].toLong(),
                    sharePct = p[5].toInt().coerceIn(0, 100),
                    ready = p[6] == "1",
                    leaving = p[7] == "1",
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
