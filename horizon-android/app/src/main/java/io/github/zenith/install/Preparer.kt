package io.github.zenith.install

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Roda o `horizon-prepare` do Horizon no celular (libhorizon_prepare.so, da pasta de
 * bibliotecas do APK) sobre os arquivos baixados, como na Launch API do Horizon:
 *
 *     horizon-prepare --root KIT --prepare --minecraft-dir MC --server-jar SERVER
 *                     --output SAÍDA --complete --progress json
 *
 * `--progress json` imprime uma linha JSON por evento ({"message"}, {"fraction"}, {"error"}).
 * Os exportadores rodam no OpenJDK baixado pelos lançadores do APK (libzenith_java.so /
 * libzenith_javac.so), que o patch do Zenith deixa o preparador usar (HORIZON_PREPARE_JAVA).
 */
class Preparer(
    private val nativeLibDir: File,
    private val kitDir: File,
    private val jreDir: File,
    private val tmpDir: File,
    private val homeDir: File,
    private val logFile: File,
    /** Quantas tarefas paralelas (HORIZON_PREPARE_JOBS); 1 em celulares com pouca RAM. */
    private val jobs: Int,
) {
    val program get() = File(nativeLibDir, "libhorizon_prepare.so")
    private val java get() = File(nativeLibDir, "libzenith_java.so")
    private val javac get() = File(nativeLibDir, "libzenith_javac.so")

    fun available(): Boolean = program.isFile && java.isFile && javac.isFile &&
        File(nativeLibDir, "libc++_shared.so").isFile

    @Volatile private var process: Process? = null

    fun cancel() { process?.destroy() }

    /** Descompacta o kit (research-lock.json e as fontes dos exportadores) em [kitDir]. */
    fun unpackKit(kit: java.io.InputStream) {
        // As fontes são trocadas; o trabalho em .local fica (cache dos exportadores compilados).
        for (name in listOf("research-lock.json", "crates", "mods")) File(kitDir, name).deleteRecursively()
        kitDir.mkdirs()
        val root = kitDir.canonicalFile
        ZipInputStream(kit).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val target = File(root, e.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw IOException("Kit com caminho inválido: ${e.name}")
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
    }

    /** Prepara em [output]; [onMessage] e [onFraction] recebem o progresso do preparador. */
    /**
     * Prepara em [output]. Com [complete] (os assets estão todos lá) também copia os sons e exige
     * que o `--check` passe inteiro. Sem ele (importação sem assets), aceita que faltem só os
     * extras que o Horizon deixa faltar (sons, idiomas, panorama, fontes, packs: check.rs EXTRAS),
     * e devolve essa lista para a tela avisar.
     */
    fun prepare(
        minecraftDir: File,
        serverJar: File,
        output: File,
        onMessage: (String) -> Unit,
        onFraction: (Float) -> Unit,
        complete: Boolean = true,
    ): List<String> {
        val args = mutableListOf(
            "--root", kitDir.path, "--prepare",
            "--minecraft-dir", minecraftDir.path, "--server-jar", serverJar.path,
            "--output", output.path, "--progress", "json",
        )
        if (complete) args += "--complete"
        val code = run(args, onMessage, onFraction)
        if (code != 0) throw IOException(lastError ?: "O horizon-prepare terminou com código $code (veja o log da instalação)")
        val gaps = mutableListOf<String>()
        val check = run(listOf("--root", kitDir.path, "--check", "--output", output.path), { gaps += it }, {})
        if (check == 0) return emptyList()
        val serious = gaps.filterNot { isExtra(it) }
        if (complete || serious.isNotEmpty() || gaps.isEmpty()) {
            throw IOException("A preparação ficou incompleta: ${(serious.ifEmpty { gaps }).take(3).joinToString(" · ")}")
        }
        return gaps
    }

    @Volatile var lastError: String? = null
        private set
    private val lastLines = ArrayDeque<String>()

    private fun run(args: List<String>, onMessage: (String) -> Unit, onFraction: (Float) -> Unit): Int {
        tmpDir.mkdirs()
        logFile.parentFile?.mkdirs()
        lastError = null
        val pb = ProcessBuilder(listOf(program.path) + args)
            .directory(kitDir)
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        val env = pb.environment()
        env["HORIZON_PREPARE_JAVA"] = java.path
        env["HORIZON_PREPARE_JAVAC"] = javac.path
        env["ZENITH_JAVA_HOME"] = jreDir.path
        env["JAVA_HOME"] = jreDir.path
        // As bibliotecas do runtime se acham pelo nome (libnio.so -> libnet.so) só por este caminho.
        env["LD_LIBRARY_PATH"] = "${File(jreDir, "lib").path}:${nativeLibDir.path}"
        env["TMPDIR"] = tmpDir.path
        env["HOME"] = homeDir.path
        env["JAVA_TOOL_OPTIONS"] = "-Djava.io.tmpdir=${tmpDir.path}"
        env["HORIZON_PREPARE_JOBS"] = jobs.toString()
        env.remove("HORIZON_SERVER_JAR")
        logFile.appendText("\n$ horizon-prepare ${args.joinToString(" ")}\n")
        val p = pb.start()
        process = p
        try {
            p.outputStream.close()
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    logFile.appendText(line + "\n")
                    remember(line)
                    val o = runCatching { JSONObject(line) }.getOrNull()
                    when {
                        o == null -> if (line.isNotBlank()) onMessage(line.trim())
                        o.has("error") -> { lastError = o.optString("error"); onMessage(lastError!!) }
                        o.has("fraction") -> onFraction(o.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f))
                        o.has("message") -> onMessage(o.optString("message"))
                    }
                }
            }
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroy(); throw IOException("O horizon-prepare não terminou")
            }
            return p.exitValue()
        } finally {
            process = null
        }
    }

    private fun remember(line: String) {
        synchronized(lastLines) {
            lastLines.addLast(line)
            while (lastLines.size > 20) lastLines.removeFirst()
        }
    }

    companion object {
        /** Uma linha do `--check` que é só um extra faltando ("the sounds missing: horizon-prepare sounds"). */
        fun isExtra(line: String): Boolean =
            Regex("missing: horizon-prepare (packs|panorama|fonts|languages|sounds)$").containsMatchIn(line.trim())

        /** Lê uma linha de `--progress json` (para os testes e para quem quiser reaproveitar). */
        fun parseProgress(line: String): Pair<String, Any>? {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
            return when {
                o.has("error") -> "error" to o.optString("error")
                o.has("fraction") -> "fraction" to o.optDouble("fraction")
                o.has("message") -> "message" to o.optString("message")
                else -> null
            }
        }
    }
}
