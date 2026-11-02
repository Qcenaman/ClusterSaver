package com.qcenaman.clustersaver.core

/** Decides which apps get frozen while the cluster runs. Only user-installed apps are ever candidates. */
object FreezePlan {
    val ALWAYS_KEEP = setOf(
        "com.qcenaman.clustersaver",
        "com.termux",
        "com.termux.api",
        "com.termux.boot",
        "com.termux.widget",
        "moe.shizuku.privileged.api",
        "com.catchingnow.icebox",
        "com.anthropic.claude",
    )
    val DEFAULT_KEEP = listOf("com.whatsapp", "com.whatsapp.w4b")

    private val PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    fun isValidPackage(p: String): Boolean = PKG.matches(p)

    fun parsePackageList(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { isValidPackage(it) }
            .distinct()
            .toList()

    fun parseKeep(text: String): Set<String> =
        text.split(',', '\n', ' ', ';').map { it.trim() }.filter { isValidPackage(it) }.toSet()

    fun candidates(installed: List<String>, keep: Set<String>): List<String> =
        installed.filter { it !in ALWAYS_KEEP && it !in keep && isValidPackage(it) }.sorted()

    fun freezeScript(packages: List<String>, userId: Int): String =
        packages.filter { isValidPackage(it) }
            .joinToString("\n") { "am force-stop $it; pm suspend --user $userId $it > /dev/null 2>&1" } +
            "\nam kill-all\necho FROZEN"

    fun restoreScript(packages: List<String>, userId: Int): String =
        packages.filter { isValidPackage(it) }
            .joinToString("\n") { "pm unsuspend --user $userId $it > /dev/null 2>&1" } +
            "\necho RESTORED"
}
