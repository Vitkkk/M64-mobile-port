package io.github.zenith.install

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.zenith.auth.MicrosoftAuth
import io.github.zenith.data.Account
import io.github.zenith.data.AccountStore
import io.github.zenith.data.GameResources
import io.github.zenith.data.LauncherSettings
import io.github.zenith.data.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * A instalação de uma versão no próprio celular, do zero até "Instalação concluída":
 *
 * 1. Conta: os arquivos do jogo só são baixados para uma conta Microsoft que tem o Minecraft:
 *    Java Edition (a posse é perguntada de novo à Mojang), como o Horizon pede a launchers.
 * 2. Metadados: o JSON da versão pelo hash fixado e o índice de assets.
 * 3. Arquivos: client, server, bibliotecas e assets, conferidos por SHA-1, retomáveis.
 * 4. Java: OpenJDK 25 para Android, conferido por SHA-256.
 * 5. Preparo: horizon-prepare com os exportadores do Horizon sobre os arquivos baixados.
 * 6. Recursos no lugar; o Java e os arquivos de trabalho são apagados.
 */
object Installer {
    enum class Step(val label: String) {
        IDLE("Pronto para baixar"),
        VERIFY("Verificando o JAR"),
        IMPORT("Copiando os arquivos locais"),
        ACCOUNT("Conferindo a conta"),
        METADATA("Lendo os manifestos da Mojang"),
        DOWNLOAD("Baixando os arquivos do jogo"),
        JAVA("Baixando o Java para preparar"),
        PREPARE("Preparando os recursos"),
        FINISH("Terminando"),
        DONE("Instalação concluída"),
        FAILED("A instalação parou"),
    }

    data class State(
        val step: Step = Step.IDLE,
        val version: String = "",
        val message: String = "",
        /** 0..1 da etapa atual, ou -1 se indeterminado. */
        val fraction: Float = -1f,
        val bytes: Long = 0,
        val bytesTotal: Long = 0,
        val speed: Long = 0,
        val files: Int = 0,
        val filesTotal: Int = 0,
        val error: String? = null,
        val startedAt: Long = 0,
        /** Etapas já concluídas nesta operação, para a lista com ✓. */
        val done: List<Step> = emptyList(),
        /** O arquivo ou pasta escolhido (importação). */
        val source: String = "",
        /** O que foi detectado nele (versão, tipo) e se é compatível. */
        val detected: String = "",
        val compatible: Boolean? = null,
        /** Avisos de uma instalação que terminou sem tudo (ex.: sem sons). */
        val warnings: List<String> = emptyList(),
    ) {
        val running get() = step != Step.IDLE && step != Step.DONE && step != Step.FAILED
        /** Progresso geral aproximado, pelas etapas e pelo andamento da atual. */
        val overall: Float get() {
            val (start, span) = when (step) {
                Step.VERIFY, Step.IMPORT -> 0f to 0.5f
                Step.ACCOUNT, Step.METADATA -> 0f to 0.02f
                Step.DOWNLOAD -> 0.02f to 0.58f
                Step.JAVA -> 0.60f to 0.07f
                Step.PREPARE -> 0.67f to 0.30f
                Step.FINISH -> 0.97f to 0.03f
                Step.DONE -> return 1f
                else -> return 0f
            }
            return start + span * (if (fraction in 0f..1f) fraction else 0f)
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var cancelRequested = false
    @Volatile private var preparer: Preparer? = null

    fun cancel() {
        cancelRequested = true
        preparer?.cancel()
    }

    fun reset() {
        if (!_state.value.running) _state.value = State()
    }

    @Synchronized
    private fun set(update: State.() -> State) {
        val before = _state.value
        var after = before.update()
        if (after.step != before.step && before.running && before.step !in after.done) {
            after = after.copy(done = after.done + before.step)
        }
        _state.value = after
    }

    /** O que impede de baixar agora (sem rede), ou null. */
    fun blocker(context: Context): String? {
        val pinned = GameVersions.pinned(context)
            ?: return "Este APK foi compilado sem o preparador do Horizon (prepare-kit.zip)."
        if (!preparer(context).available()) return "Este APK foi compilado sem o preparador do Horizon (libhorizon_prepare.so)."
        if (JavaRuntime.forAbi(Build.SUPPORTED_ABIS.firstOrNull() ?: "") == null) {
            return "Não há Java 25 para Android neste tipo de processador (${Build.SUPPORTED_ABIS.firstOrNull()})."
        }
        if (owner() == null) {
            return "Para baixar o Minecraft ${pinned.version}, entre com uma conta Microsoft que tenha o Java Edition " +
                "(aba Conta). Depois disso, contas offline jogam normalmente."
        }
        return null
    }

    /** A conta Microsoft dona do jogo que libera o download: a selecionada, senão qualquer uma. */
    fun owner(selected: Account? = null): Account? {
        val accounts = AccountStore.all().filter { it.isMicrosoft && AccountStore.secrets(it.id)?.owns == true }
        return accounts.firstOrNull { it.id == selected?.id } ?: accounts.firstOrNull()
    }

    private fun preparer(context: Context): Preparer {
        val work = File(Paths.internal, "installer")
        return Preparer(
            nativeLibDir = Paths.nativeLibDir,
            kitDir = File(work, "kit"),
            jreDir = File(work, "jre25"),
            tmpDir = File(work, "tmp"),
            homeDir = Paths.internal,
            logFile = logFile,
            jobs = jobs(context),
        )
    }

    val logFile: File get() = File(Paths.internal, "installer/install.log")
    /** Os arquivos baixados da Mojang (grandes: no armazenamento externo do app). */
    val minecraftDir: File get() = File(Paths.external, "installer/minecraft")

    /** Celulares com menos de 6 GB de RAM preparam uma tarefa por vez (cada JVM usa ~500 MB). */
    private fun jobs(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return if (info.totalMem >= 6L shl 30) 2 else 1
    }

    /** O espaço que a instalação usa enquanto roda (aprox.): downloads, cópia de trabalho e saída. */
    const val SPACE_NEEDED = 2_200L shl 20

    /** Roda a instalação inteira; chamado pelo InstallService, numa thread de fundo. */
    fun run(context: Context, keepDownloads: Boolean) {
        if (_state.value.running) return
        cancelRequested = false
        val pinned = GameVersions.pinned(context)
        _state.value = State(step = Step.ACCOUNT, version = pinned?.version ?: "", startedAt = System.currentTimeMillis())
        try {
            if (pinned == null) throw IOException(blocker(context))
            logFile.parentFile?.mkdirs()
            logFile.writeText("Zenith: instalando Minecraft ${pinned.version} (Horizon)\n")
            val free = Paths.external.usableSpace
            if (free in 1 until SPACE_NEEDED) {
                throw IOException("Pouco espaço livre: ${free shr 20} MB. A instalação precisa de uns ${SPACE_NEEDED shr 20} MB enquanto roda.")
            }

            // 1. Conta dona do jogo, conferida de novo com a Mojang quando houver rede.
            val settings = LauncherSettings.get(context)
            val owner = owner(AccountStore.selected(settings))
                ?: throw IOException(blocker(context) ?: "Entre com uma conta Microsoft que tenha o Java Edition.")
            // Sem Client ID neste build a renovação não roda: vale a última resposta da Mojang.
            val auth = runCatching { MicrosoftAuth(settings.msaClientId) }.getOrNull()
            val secrets = if (auth == null) AccountStore.secrets(owner.id) else try {
                kotlinx.coroutines.runBlocking { auth.refresh(owner) }
            } catch (e: MicrosoftAuth.AuthException) {
                throw IOException(e.message)
            }
            if (secrets?.owns != true) throw IOException("A conta ${owner.name} não tem o Minecraft: Java Edition.")
            log("Conta ${owner.name}: tem o Java Edition")

            // 2. Metadados
            set { copy(step = Step.METADATA, message = "piston-meta.mojang.com", fraction = -1f) }
            val files = MojangFiles(minecraftDir, pinned)
            val manifest = files.manifest()
            val index = files.assetIndex(manifest)
            val plan = files.plan(manifest, index)
            log("Plano: ${plan.size} arquivos, ${plan.sumOf { it.size ?: 0 } shr 20} MB")

            // 3. Arquivos do jogo
            set { copy(step = Step.DOWNLOAD, message = "Conferindo o que já foi baixado", fraction = 0f) }
            Downloader(threads = 8).run(plan, { cancelRequested }) { p ->
                set {
                    copy(
                        step = Step.DOWNLOAD, message = p.current, bytes = p.bytes, bytesTotal = p.bytesTotal,
                        speed = p.speed, files = p.files, filesTotal = p.filesTotal,
                        fraction = if (p.bytesTotal > 0) p.bytes.toFloat() / p.bytesTotal else -1f,
                    )
                }
            }
            check()

            // 4-6. Java, preparo e recursos no lugar (o mesmo da importação local)
            prepareStage(context, pinned, files, complete = true, keepDownloads = keepDownloads)
        } catch (e: Throwable) {
            val cancelled = cancelRequested || e is Downloader.Cancelled
            val msg = if (cancelled) "Cancelado. O que já foi baixado fica guardado para a próxima vez." else (e.message ?: e.javaClass.simpleName)
            runCatching { log("ERRO: $msg\n${e.stackTraceToString()}") }
            set { copy(step = Step.FAILED, error = msg, message = msg) }
        } finally {
            preparer = null
        }
    }

    private fun check() {
        if (cancelRequested) throw Downloader.Cancelled()
    }

    val jreDir: File get() = File(Paths.internal, "installer/jre25")

    fun javaReady(): Boolean =
        JavaRuntime.forAbi(Build.SUPPORTED_ABIS.firstOrNull() ?: "")?.let { JavaRuntime.installed(it, jreDir) } == true

    /** Java (baixado, se ainda não foi importado), horizon-prepare e a troca da pasta de recursos. */
    private fun prepareStage(context: Context, pinned: PinnedVersion, files: MojangFiles, complete: Boolean, keepDownloads: Boolean) {
        val runtime = JavaRuntime.forAbi(Build.SUPPORTED_ABIS.first())
            ?: throw IOException("Não há Java 25 para Android neste processador.")
        val prep = preparer(context)
        preparer = prep
        if (!JavaRuntime.installed(runtime, jreDir)) {
            set { copy(step = Step.JAVA, message = "OpenJDK 25 para Android", fraction = 0f, bytes = 0, bytesTotal = runtime.size, files = 0, filesTotal = 1) }
            JavaRuntime.install(runtime, jreDir, Downloader(threads = 1), { cancelRequested }) { p ->
                set { copy(bytes = p.bytes, bytesTotal = p.bytesTotal, speed = p.speed, fraction = if (p.bytesTotal > 0) p.bytes.toFloat() / p.bytesTotal else -1f) }
            }
            check()
        }
        set { copy(step = Step.PREPARE, message = "Abrindo o kit do Horizon", fraction = -1f, bytes = 0, bytesTotal = 0, speed = 0) }
        context.assets.open(GameVersions.KIT_ASSET).use { prep.unpackKit(it) }
        val output = File(Paths.resourcesRoot, ".preparing")
        output.deleteRecursively()
        val gaps = prep.prepare(
            minecraftDir, files.serverJar, output,
            onMessage = { m -> log(m); set { copy(message = m) } },
            onFraction = { f -> set { copy(fraction = f) } },
            complete = complete,
        )
        check()
        set { copy(step = Step.FINISH, message = "Validando e colocando os recursos no lugar", fraction = -1f) }
        GameResources.publish(output)
        val status = GameResources.status()
        if (!status.ready) throw IOException("Os recursos preparados não passaram na validação: ${status.message}")
        for (dir in listOf(jreDir, File(Paths.internal, "installer/kit/.local"), File(Paths.internal, "installer/tmp"))) {
            dir.deleteRecursively()
        }
        if (!keepDownloads) minecraftDir.deleteRecursively()
        val warnings = gaps.map { it.substringBefore(" missing").let { what -> "Faltou: ${translateExtra(what)}" } }
        log("Instalação concluída" + if (warnings.isEmpty()) "" else " (${warnings.joinToString()})")
        set { copy(step = Step.DONE, message = "Minecraft ${pinned.version} pronto para jogar", fraction = 1f, error = null, warnings = warnings) }
    }

    private fun translateExtra(what: String) = when {
        what.contains("sounds") -> "os sons (o jogo fica mudo)"
        what.contains("languages") -> "os idiomas (só inglês)"
        what.contains("panorama") -> "o panorama do título"
        what.contains("Unifont") -> "as fontes Unifont"
        what.contains("packs") -> "os packs embutidos"
        else -> what
    }

    // --- Importação local (sem baixar o que já está no celular) ---

    /** Roda [block] como uma operação com estado, cancelamento e log. */
    private fun job(first: Step, source: String, block: () -> Unit) {
        if (_state.value.running) return
        cancelRequested = false
        _state.value = State(step = first, source = source, startedAt = System.currentTimeMillis())
        try {
            logFile.parentFile?.mkdirs()
            log("Zenith: ${first.label} — $source")
            block()
        } catch (e: Throwable) {
            val cancelled = cancelRequested || e is Downloader.Cancelled
            val msg = if (cancelled) "Cancelado. O que já foi copiado fica guardado." else (e.message ?: e.javaClass.simpleName)
            runCatching { log("ERRO: $msg\n${e.stackTraceToString()}") }
            set { copy(step = Step.FAILED, error = msg, message = msg) }
        } finally {
            preparer = null
        }
    }

    private fun pinnedOrFail(context: Context): PinnedVersion =
        GameVersions.pinned(context) ?: throw IOException("Este APK foi compilado sem o preparador do Horizon (prepare-kit.zip).")

    /** Copia um arquivo escolhido no seletor para [target], com progresso em bytes. */
    private fun copyIn(open: () -> java.io.InputStream, total: Long, target: File) {
        target.parentFile?.mkdirs()
        var done = 0L
        open().use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    check()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done % (1 shl 20) < (1 shl 16)) set { copy(bytes = done, bytesTotal = total, fraction = if (total > 0) done.toFloat() / total else -1f) }
                }
            }
        }
    }

    /**
     * Importa um JAR (client ou server) escolhido no celular: copia, lê a versão e o tipo, confere o
     * SHA-1 contra o Horizon fixado e põe no lugar. Termina em DONE só com o JAR; o preparo é outra
     * operação (precisa também do servidor e das bibliotecas).
     */
    fun importJar(context: Context, name: String, size: Long, open: () -> java.io.InputStream) = job(Step.VERIFY, name) {
        val pinned = pinnedOrFail(context)
        val files = MojangFiles(minecraftDir, pinned)
        val temp = File(Paths.cache, "import-${System.nanoTime()}.tmp")
        try {
            set { copy(message = "Lendo $name", fraction = 0f) }
            copyIn(open, size, temp)
            // Um .json (o 26.3.json da versão) também vem por aqui: o seletor é o mesmo.
            val looksJson = name.endsWith(".json", ignoreCase = true) ||
                temp.inputStream().use { it.read() }.let { it == '{'.code }
            if (looksJson) {
                val info = LocalImport.inspectJson(temp, name, pinned)
                log("JSON: ${info.message} (sha1 ${info.sha1})")
                set { copy(detected = "JSON da versão ${info.versionId ?: "?"}", compatible = info.usable, message = info.message) }
                if (!info.usable) throw IOException(info.message)
                files.versionDir.mkdirs()
                temp.copyTo(files.manifestFile, overwrite = true)
                set { copy(step = Step.DONE, message = "JSON da versão ${pinned.version} importado", fraction = 1f) }
                return@job
            }
            val info = LocalImport.inspect(temp, name, pinned)
            log("JAR: ${info.message} (sha1 ${info.sha1})")
            set {
                copy(
                    detected = listOfNotNull(info.versionId?.let { "Minecraft $it" }, info.kind.label.takeIf { info.kind != LocalImport.Kind.OTHER }).joinToString(" · ").ifBlank { "não reconhecido" },
                    compatible = info.compatible, message = info.message,
                )
            }
            if (!info.compatible) throw IOException(info.message)
            LocalImport.place(temp, info, files)
            // O JSON da versão: o do disco se já foi importado, senão o fixado, da Mojang (alguns KB).
            val warnings = mutableListOf<String>()
            if (!manifestReady(files)) {
                set { copy(step = Step.METADATA, message = "Buscando o JSON da versão na Mojang", fraction = -1f) }
                runCatching { files.manifest() }.onFailure { e ->
                    warnings += "Não deu para buscar o JSON da versão: ${explain(e)}. Importe o ${pinned.version}.json ou tente de novo."
                }
            }
            set {
                copy(
                    step = Step.DONE, warnings = warnings, fraction = 1f,
                    message = "${info.kind.label.replaceFirstChar { it.uppercase() }} ${pinned.version} importado",
                )
            }
        } finally {
            temp.delete()
        }
    }

    fun manifestReady(files: MojangFiles): Boolean =
        files.manifestFile.isFile && files.pinned.manifests.any { it.equals(Downloader.hashOf(files.manifestFile), true) }

    /** Um erro de rede em palavras: sem internet, recusado pelo servidor, hash diferente. */
    fun explain(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return when (root) {
            is java.net.UnknownHostException -> "sem internet (não achou ${root.message})"
            is java.net.SocketTimeoutException -> "a conexão demorou demais"
            is java.net.ConnectException -> "não conectou (${root.message})"
            is javax.net.ssl.SSLException -> "erro de segurança na conexão (${root.message})"
            else -> e.message ?: root.javaClass.simpleName
        }
    }

    /** Busca só o JSON da versão (alguns KB), pelo hash que o Horizon fixa. */
    fun fetchManifest(context: Context) = job(Step.METADATA, "JSON da versão") {
        val pinned = pinnedOrFail(context)
        val files = MojangFiles(minecraftDir, pinned)
        set { copy(message = "piston-meta.mojang.com/v1/packages/${pinned.manifests.first().take(8)}…/${pinned.version}.json") }
        try {
            files.manifest()
        } catch (e: Exception) {
            throw IOException("Não deu para buscar o JSON da versão ${pinned.version}: ${explain(e)}")
        }
        set { copy(step = Step.DONE, detected = "JSON da versão ${pinned.version}", compatible = true, message = "JSON da versão ${pinned.version} pronto", fraction = 1f) }
    }

    /** Importa uma instalação inteira (pasta escolhida no seletor de pastas do Android). */
    fun importInstallation(context: Context, tree: LocalImport.Tree) = job(Step.IMPORT, tree.name) {
        val pinned = pinnedOrFail(context)
        val files = MojangFiles(minecraftDir, pinned)
        val report = LocalImport.importInstallation(tree, files, Build.SUPPORTED_ABIS.first(), { cancelRequested }) { done, total, current ->
            set { copy(files = done, filesTotal = total, message = current, fraction = if (total > 0) done.toFloat() / total else -1f) }
        }
        log("Importação: ${report.description}; ${report.copied} copiados, ${report.reused} já estavam; fora: ${report.skipped}")
        val client = files.clientJar.isFile
        set {
            copy(
                step = Step.DONE,
                detected = report.description,
                compatible = client,
                warnings = report.skipped.take(8),
                message = "${report.copied} arquivos copiados" + (if (report.reused > 0) ", ${report.reused} já estavam" else "") +
                    (if (report.skipped.isNotEmpty()) "; ${report.skipped.size} não conferiram e ficaram de fora" else ""),
                fraction = 1f,
            )
        }
    }

    /** Importa o Java 25 para Android (.tar.xz) já baixado, conferido pelo SHA-256 fixado. */
    fun importJava(name: String, size: Long, open: () -> java.io.InputStream) = job(Step.JAVA, name) {
        val runtime = JavaRuntime.forAbi(Build.SUPPORTED_ABIS.first()) ?: throw IOException("Não há Java 25 para Android neste processador.")
        val archive = File(jreDir.parentFile, jreDir.name + ".tar.xz")
        copyIn(open, size, archive)
        val sha = Downloader.hashOf(archive, "SHA-256")
        if (!sha.equals(runtime.sha256, true)) {
            archive.delete()
            throw IOException("Este .tar.xz não é o OpenJDK 25 para Android fixado (SHA-256 $sha). Use ${runtime.url.substringAfterLast('/')}.")
        }
        set { copy(message = "Descompactando", fraction = -1f) }
        JavaRuntime.install(runtime, jreDir, Downloader(threads = 1), { cancelRequested }) {}
        set { copy(step = Step.DONE, message = "Java 25 pronto", fraction = 1f) }
    }

    /**
     * Prepara a partir dos arquivos importados: confere o que é obrigatório, baixa só o Java se
     * faltar, roda o horizon-prepare (com --complete se os assets estiverem todos lá) e valida.
     */
    fun prepareLocal(context: Context, keepFiles: Boolean) = job(Step.VERIFY, "arquivos importados") {
        val pinned = pinnedOrFail(context)
        val files = MojangFiles(minecraftDir, pinned)
        set { copy(message = "Conferindo os arquivos importados", fraction = -1f) }
        if (!manifestReady(files)) {
            set { copy(message = "Buscando o JSON da versão na Mojang (alguns KB)") }
            try {
                files.manifest()
            } catch (e: Exception) {
                throw IOException("Falta o JSON da versão ${pinned.version} e não deu para buscá-lo: ${explain(e)}. Importe o ${pinned.version}.json (está em .minecraft/versions/${pinned.version}/) ou tente com internet.")
            }
        }
        val missing = LocalImport.requirements(files, Build.SUPPORTED_ABIS.first(), javaReady = true)
            .filter { it.required && !it.ok }
        if (missing.isNotEmpty()) {
            throw IOException("Ainda falta: " + missing.joinToString("; ") { "${it.label} (${it.detail})" })
        }
        prepareStage(context, pinned, files, complete = LocalImport.assetsComplete(files), keepDownloads = keepFiles)
    }

    private fun log(line: String) {
        runCatching { logFile.appendText(line + "\n") }
    }

    /** Apaga os arquivos baixados e de trabalho (não mexe nos recursos instalados). */
    fun clearCache() {
        if (_state.value.running) return
        minecraftDir.deleteRecursively()
        File(Paths.internal, "installer").deleteRecursively()
    }

    fun cacheSize(): Long =
        listOf(minecraftDir, File(Paths.internal, "installer")).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
}
