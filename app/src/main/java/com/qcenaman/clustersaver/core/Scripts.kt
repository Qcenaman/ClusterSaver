package com.qcenaman.clustersaver.core

/** Bash scripts sent to Termux. Every script logs to a file and prints its tail so results come back short. */
object Scripts {
    const val SERVER_PORT = 8080
    private const val BIN = "\$HOME/llama.cpp/build/bin"
    private val FILE = Regex("^[A-Za-z0-9._-]+\\.gguf$")
    private val ENDPOINT = Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}$")
    private val BIND = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun logged(name: String, body: String): String =
        "LOG=\"\$HOME/cs-$name.log\"\n(\n$body\n) > \"\$LOG\" 2>&1\nrc=\$?\ntail -n 15 \"\$LOG\"\nexit \$rc\n"

    fun setup(): String = logged(
        "setup",
        """
        set -e
        termux-wake-lock || true
        export DEBIAN_FRONTEND=noninteractive
        yes | pkg upgrade -y -o Dpkg::Options::=--force-confnew || true
        pkg install -y git cmake clang curl
        cd "${'$'}HOME"
        if [ -d llama.cpp/.git ]; then git -C llama.cpp pull --ff-only || true
        else git clone --depth 1 https://github.com/ggml-org/llama.cpp; fi
        cd llama.cpp
        cmake -B build -DGGML_RPC=ON -DLLAMA_CURL=OFF -DCMAKE_BUILD_TYPE=Release
        cmake --build build --config Release -j"${'$'}(nproc)" --target rpc-server llama-server
        test -x build/bin/rpc-server && test -x build/bin/llama-server
        echo SETUP_OK
        """.trimIndent()
    )

    fun startRpc(bind: String, port: Int, threads: Int): String {
        require(BIND.matches(bind)) { "bad bind address" }
        require(port in 1..65535 && threads in 1..64)
        return logged(
            "rpc-start",
            """
            termux-wake-lock || true
            pkill -f 'build/bin/rpc-server' || true
            sleep 1
            nohup $BIN/rpc-server -H $bind -p $port -t $threads -c > "${'$'}HOME/cs-rpc.log" 2>&1 &
            sleep 3
            if pgrep -f 'build/bin/rpc-server' > /dev/null; then echo RPC_OK; else tail -n 20 "${'$'}HOME/cs-rpc.log"; exit 1; fi
            """.trimIndent()
        )
    }

    fun prepareModel(url: String, file: String): String {
        require(FILE.matches(file)) { "model file name must look like name.gguf" }
        require(url.startsWith("https://")) { "model URL must start with https://" }
        val f = quote(file)
        return logged(
            "model",
            """
            set -e
            mkdir -p "${'$'}HOME/models"
            cd "${'$'}HOME/models"
            if [ ! -f $f ]; then
              curl -fL --retry 5 -C - -o $f.part ${quote(url)}
              mv $f.part $f
            fi
            echo SIZE=${'$'}(stat -c %s $f)
            """.trimIndent()
        )
    }

    fun startServer(file: String, endpoints: List<String>, split: List<Long>, ctx: Int, threads: Int): String {
        require(FILE.matches(file))
        require(endpoints.isNotEmpty() && endpoints.size == split.size)
        require(endpoints.all { ENDPOINT.matches(it) })
        require(ctx in 256..262144 && threads in 1..64)
        val rpc = endpoints.joinToString(",")
        val ts = split.joinToString(",") { it.coerceAtLeast(1).toString() }
        return logged(
            "server-start",
            """
            pkill -f 'build/bin/llama-server' || true
            sleep 1
            nohup $BIN/llama-server -m "${'$'}HOME/models/$file" --rpc $rpc --tensor-split $ts -ngl 999 -c $ctx -t $threads --host 0.0.0.0 --port $SERVER_PORT > "${'$'}HOME/cs-server.log" 2>&1 &
            sleep 2
            echo SERVER_STARTED
            """.trimIndent()
        )
    }

    fun stopServer(): String = "pkill -f 'build/bin/llama-server' || true\necho STOPPED\n"

    fun stopAll(): String =
        "pkill -f 'build/bin/llama-server' || true\npkill -f 'build/bin/rpc-server' || true\ntermux-wake-unlock || true\necho STOPPED\n"

    fun serverLogTail(): String = "tail -n 25 \"\$HOME/cs-server.log\" 2>/dev/null || echo 'no server log'\n"

    fun parseSize(stdout: String): Long? =
        stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("SIZE=") }
            ?.removePrefix("SIZE=")?.toLongOrNull()

    fun fileFromUrl(url: String): String = url.substringBefore('?').substringAfterLast('/')

    fun isValidModelFile(file: String): Boolean = FILE.matches(file)
}
