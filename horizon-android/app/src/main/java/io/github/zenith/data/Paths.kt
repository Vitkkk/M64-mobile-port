package io.github.zenith.data

import android.content.Context
import io.github.zenith.BuildConfig
import java.io.File

/**
 * Onde o launcher guarda cada coisa.
 *
 * - Armazenamento interno (`files/`): o que é privado ou precisa de um sistema de
 *   arquivos de verdade: o socket de controle do jogo, o arquivo de lançamento,
 *   segredos das contas, skins e layouts de controle.
 * - Armazenamento externo do app (`Android/data/<pacote>/files/`): instâncias
 *   (mundos, packs, mods, prints) e os recursos preparados, que são grandes e que o
 *   jogador pode querer abrir num gerenciador de arquivos. Ambos são apagados ao
 *   desinstalar o app.
 */
object Paths {
    lateinit var internal: File
        private set
    lateinit var external: File
        private set
    lateinit var cache: File
        private set
    lateinit var nativeLibDir: File
        private set

    fun init(context: Context) {
        internal = context.filesDir
        external = context.getExternalFilesDir(null) ?: context.filesDir
        cache = context.cacheDir
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
    }

    /** Recursos preparados pelo `horizon-prepare` a partir da sua cópia do jogo. */
    val resourcesRoot get() = File(external, "resources")
    fun resources(version: String = BuildConfig.GAME_VERSION) = File(resourcesRoot, version)

    val instancesDir get() = File(external, "instances")
    val runDir get() = File(internal, "run")
    val launchDir get() = File(internal, "launch")
    val launchFile get() = File(launchDir, "launch.json")
    val exitFile get() = File(launchDir, "exit.json")
    val sessionFile get() = File(launchDir, "session.json")

    /** O socket de controle do Horizon (`--control-socket`). Fica no interno: sockets
     *  Unix não funcionam no armazenamento externo (FUSE). */
    val controlSocket get() = File(runDir, "control.sock")

    val skinsDir get() = File(internal, "skins")
    val layoutsDir get() = File(internal, "controls")
    val accountsFile get() = File(internal, "accounts.json")
    val secretsFile get() = File(internal, "account-secrets.bin")
    val instancesFile get() = File(internal, "instances.json")

    /** A biblioteca do jogo que vem no APK (tools/build-native.sh). */
    val engineLibrary get() = File(nativeLibDir, "libhorizon_android.so")
}
