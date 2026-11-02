package com.qcenaman.clustersaver.core

/** One device taking part in a model run. */
data class Member(val endpoint: String, val name: String, val freeMb: Long, val sharePct: Int)

/** Last thing heard from a worker. */
data class PeerSeen(val leaving: Boolean, val lastSeenMs: Long)

object Planner {
    /** Memory kept free on every device so Android and the server itself have room. */
    const val RESERVE_MB = 1024L
    const val MIN_USABLE_MB = 512L
    const val LOCAL_PREFIX = "127.0.0.1:"

    fun usableMb(m: Member): Long =
        (m.freeMb - RESERVE_MB).coerceAtLeast(0) * m.sharePct.coerceIn(0, 100) / 100

    /** Devices worth using, in a stable order: local first, then by endpoint. */
    fun select(members: List<Member>): List<Member> =
        members.filter { usableMb(it) >= MIN_USABLE_MB }
            .sortedWith(compareBy({ !it.endpoint.startsWith(LOCAL_PREFIX) }, { it.endpoint }))

    /** llama.cpp --tensor-split values, in the same order as the --rpc list. */
    fun tensorSplit(members: List<Member>): List<Long> = members.map { usableMb(it) }

    fun totalUsableMb(members: List<Member>): Long = members.sumOf { usableMb(it) }

    /** Rough check: weights plus ~10% and 512 MB for context and buffers. */
    fun fits(members: List<Member>, modelBytes: Long): Boolean {
        val needMb = modelBytes / (1024 * 1024) * 11 / 10 + 512
        return totalUsableMb(members) >= needMb
    }

    /** Workers in the current run that left, went quiet or vanished. The host's own device is never "lost". */
    fun lostMembers(
        active: List<String>,
        seen: Map<String, PeerSeen>,
        nowMs: Long,
        timeoutMs: Long,
    ): List<String> = active.filter { ep ->
        if (ep.startsWith(LOCAL_PREFIX)) return@filter false
        val s = seen[ep] ?: return@filter true
        s.leaving || nowMs - s.lastSeenMs > timeoutMs
    }
}
