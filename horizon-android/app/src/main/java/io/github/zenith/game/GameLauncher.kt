package io.github.zenith.game

import android.content.Context
import android.content.Intent
import android.system.Os
import io.github.zenith.auth.MicrosoftAuth
import io.github.zenith.data.Account
import io.github.zenith.data.AccountStore
import io.github.zenith.data.GameResources
import io.github.zenith.data.Instance
import io.github.zenith.data.InstanceStore
import io.github.zenith.data.LauncherSettings
import io.github.zenith.data.Paths
import io.github.zenith.data.Skins
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Prepara e abre uma partida, seguindo a Launch API do Horizon
 * (docs/api/launch.md): `--resources`, `--game-dir`, `--home`, a sessão por
 * `--session-file` (lida uma vez e apagada pelo jogo) e o socket de controle para
 * os controles de toque.
 */
object GameLauncher {

    sealed class Problem(val message: String) {
        class NoEngine : Problem(
            "Este APK foi compilado sem o motor do Horizon (libhorizon_android.so). " +
                "Compile com horizon-android/tools/build-native.sh ou baixe o APK do CI."
        )
        class NoResources(detail: String) : Problem("O jogo ainda não está instalado ($detail). Toque em BAIXAR para instalar aqui no celular.")
        class NoOwner : Problem("Entre com uma conta Microsoft que tenha o Minecraft: Java Edition para jogar.")
        class NoAccount : Problem("Escolha uma conta (Microsoft ou nome offline) na aba Conta.")
        class Auth(detail: String) : Problem(detail)
    }

    data class Options(val world: String? = null, val server: String? = null)

    fun engineInstalled(): Boolean = Paths.engineLibrary.isFile

    /** O que impede de jogar agora (sem rede: só confere arquivos e contas). */
    fun check(settings: LauncherSettings, account: Account?): Problem? {
        if (!engineInstalled()) return Problem.NoEngine()
        val res = GameResources.status()
        if (!res.ready) return Problem.NoResources(res.message)
        if (account == null) return Problem.NoAccount()
        if (!settings.ownerGateBypass && !AccountStore.anyOwner()) return Problem.NoOwner()
        return null
    }

    /**
     * Renova a conta (se Microsoft), escreve o arquivo de lançamento e abre o jogo.
     * Roda fora da thread principal.
     */
    suspend fun launch(context: Context, instance: Instance, account: Account, options: Options = Options()) {
        val settings = LauncherSettings.get(context)
        check(settings, account)?.let { throw LaunchException(it) }

        // A conta Microsoft: token renovado e posse conferida de novo (o "owner gate").
        var sessionJson: String? = null
        if (account.isMicrosoft) {
            val auth = try { MicrosoftAuth(settings.msaClientId) } catch (e: Exception) { null }
            val secrets = if (auth != null) auth.refresh(account) else AccountStore.secrets(account.id)
            if (secrets != null) {
                if (!secrets.owns && !settings.ownerGateBypass) throw LaunchException(Problem.NoOwner())
                sessionJson = MicrosoftAuth.sessionJson(AccountStore.get(account.id) ?: account, secrets)
            }
        }

        val dm = context.resources.displayMetrics
        val width = maxOf(dm.widthPixels, dm.heightPixels)
        val height = minOf(dm.widthPixels, dm.heightPixels)

        instance.gameDir.mkdirs()
        instance.homeDir.mkdirs()
        Paths.runDir.mkdirs()
        Paths.launchDir.mkdirs()
        Paths.controlSocket.delete()
        Paths.exitFile.delete()

        val args = mutableListOf(
            "--resources", GameResources.dir().path,
            "--game-dir", instance.gameDir.path,
            "--home", instance.homeDir.path,
            "--width", width.toString(),
            "--height", height.toString(),
            "--fullscreen",
            "--control-socket", Paths.controlSocket.path,
        )
        if (sessionJson != null) {
            writePrivate(Paths.sessionFile, sessionJson)
            args += listOf("--session-file", Paths.sessionFile.path)
        } else {
            Paths.sessionFile.delete()
            args += listOf("--name", account.name)
        }
        val world = options.world ?: instance.quickWorld.takeIf { it.isNotBlank() }
        val server = options.server ?: instance.quickServer.takeIf { it.isNotBlank() }
        when {
            server != null -> args += listOf("--quickPlayMultiplayer", server)
            world != null -> args += listOf("--quickPlaySingleplayer", world)
        }
        args += splitArgs(instance.extraArgs)

        val env = JSONObject()
        Skins.gameEnv(account).forEach { (k, v) -> env.put(k, v) }
        settings.extraEnv.lines().map { it.trim() }.filter { it.contains('=') && !it.startsWith("#") }.forEach {
            env.put(it.substringBefore('='), it.substringAfter('='))
        }

        val launch = JSONObject()
            .put("version", 1)
            .put("game_dir", instance.gameDir.path)
            .put("args", JSONArray(args))
            .put("env", env)
        writePrivate(Paths.launchFile, launch.toString(2))

        InstanceStore.save(instance.copy(lastPlayed = System.currentTimeMillis()))
        settings.selectedInstance = instance.id
        settings.selectedAccount = account.id

        val intent = Intent(context, HorizonGameActivity::class.java)
            .putExtra(HorizonGameActivity.EXTRA_INSTANCE, instance.id)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Como o jogo terminou da última vez (files/launch/exit.json), se terminou com erro. */
    data class LastExit(val code: Int, val error: String?, val time: Long)

    fun lastExit(): LastExit? = runCatching {
        val o = JSONObject(Paths.exitFile.readText())
        LastExit(o.optInt("code"), o.optString("error").takeIf { it.isNotBlank() && it != "null" }, o.optLong("time"))
    }.getOrNull()

    fun clearLastExit() = Paths.exitFile.delete()

    /** Divide argumentos como um shell simples (aspas duplas agrupam). */
    fun splitArgs(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var has = false
        for (c in text) {
            when {
                c == '"' -> { quoted = !quoted; has = true }
                c.isWhitespace() && !quoted -> { if (has) { out += cur.toString(); cur.clear(); has = false } }
                else -> { cur.append(c); has = true }
            }
        }
        if (has) out += cur.toString()
        return out
    }

    /** Arquivo só do app, modo 0600 (a Launch API exige isso para a sessão). */
    private fun writePrivate(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.delete()
        file.writeText(text)
        runCatching { Os.chmod(file.path, "600".toInt(8)) }
    }

    class LaunchException(val problem: Problem) : Exception(problem.message)
}
