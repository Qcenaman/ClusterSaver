package com.qcenaman.clustersaver.core

data class ModelProfile(val name: String, val url: String, val ctx: Int) {
    val file: String get() = Scripts.fileFromUrl(url)

    companion object {
        val DEFAULTS = listOf(
            ModelProfile(
                "Smoke test – Qwen3 1.7B",
                "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                4096,
            ),
            ModelProfile(
                "Qwen3 14B (Q4)",
                "https://huggingface.co/unsloth/Qwen3-14B-GGUF/resolve/main/Qwen3-14B-Q4_K_M.gguf",
                4096,
            ),
            ModelProfile(
                "Qwen3 30B-A3B (Q3)",
                "https://huggingface.co/unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF/resolve/main/Qwen3-30B-A3B-Instruct-2507-Q3_K_M.gguf",
                4096,
            ),
        )
    }
}
