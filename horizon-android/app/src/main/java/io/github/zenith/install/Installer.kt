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
    ) {
        val running get() = step != Step.IDLE && step != Step.DONE && step != Step.FAILED
        /** Progresso geral aproximado, pelas etapas e pelo andamento da atual. */
        val overall: Float get() {
            val (start, span) = when (step) {
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

    private fun set(update: State.() -> State) {
        _state.value = _state.value.update()
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

            // 4. Java
            val runtime = JavaRuntime.forAbi(Build.SUPPORTED_ABIS.first())
                ?: throw IOException("Não há Java 25 para Android neste processador.")
            val prep = preparer(context)
            preparer = prep
            set { copy(step = Step.JAVA, message = "OpenJDK 25 para Android", fraction = 0f, bytes = 0, bytesTotal = runtime.size, files = 0, filesTotal = 1) }
            val jre = File(Paths.internal, "installer/jre25")
            JavaRuntime.install(runtime, jre, Downloader(threads = 1), { cancelRequested }) { p ->
                set { copy(bytes = p.bytes, bytesTotal = p.bytesTotal, speed = p.speed, fraction = if (p.bytesTotal > 0) p.bytes.toFloat() / p.bytesTotal else -1f) }
            }
            check()

            // 5. Preparo
            set { copy(step = Step.PREPARE, message = "Abrindo o kit do Horizon", fraction = -1f, bytes = 0, bytesTotal = 0, speed = 0) }
            context.assets.open(GameVersions.KIT_ASSET).use { prep.unpackKit(it) }
            val output = File(Paths.resourcesRoot, ".preparing")
            output.deleteRecursively()
            prep.prepare(
                minecraftDir, files.serverJar, output,
                onMessage = { m -> log(m); set { copy(message = m) } },
                onFraction = { f -> set { copy(fraction = f) } },
            )
            check()

            // 6. No lugar
            set { copy(step = Step.FINISH, message = "Colocando os recursos no lugar", fraction = -1f) }
            GameResources.publish(output)
            for (dir in listOf(jre, File(Paths.internal, "installer/kit/.local"), File(Paths.internal, "installer/tmp"))) {
                dir.deleteRecursively()
            }
            if (!keepDownloads) minecraftDir.deleteRecursively()
            log("Instalação concluída")
            set { copy(step = Step.DONE, message = "Minecraft ${pinned.version} pronto para jogar", fraction = 1f, error = null) }
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
