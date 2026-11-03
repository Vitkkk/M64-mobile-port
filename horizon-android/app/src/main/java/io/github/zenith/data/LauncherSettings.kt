package io.github.zenith.data

import android.content.Context
import android.content.SharedPreferences
import io.github.zenith.BuildConfig

/** Preferências do launcher (as do jogo ficam no options.txt de cada instância). */
class LauncherSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var theme: String
        get() = prefs.getString("theme", "system")!!
        set(v) = prefs.edit().putString("theme", v).apply()

    /** Cor de destaque da interface. */
    var accent: String
        get() = prefs.getString("accent", "grass")!!
        set(v) = prefs.edit().putString("accent", v).apply()

    var selectedInstance: String?
        get() = prefs.getString("selectedInstance", null)
        set(v) = prefs.edit().putString("selectedInstance", v).apply()

    var selectedAccount: String?
        get() = prefs.getString("selectedAccount", null)
        set(v) = prefs.edit().putString("selectedAccount", v).apply()

    /** Client ID do Azure informado pelo jogador; vazio usa o do build. */
    var msaClientIdOverride: String
        get() = prefs.getString("msaClientId", "")!!
        set(v) = prefs.edit().putString("msaClientId", v.trim()).apply()

    val msaClientId: String
        get() = msaClientIdOverride.ifBlank { BuildConfig.MSA_CLIENT_ID }

    /**
     * Desenvolvimento: deixa jogar sem uma conta Microsoft dona do Java Edition.
     * Desligado por padrão, como no launcher oficial do Horizon (docs/microsoft-account.md).
     */
    var ownerGateBypass: Boolean
        get() = prefs.getBoolean("ownerGateBypass", false)
        set(v) = prefs.edit().putBoolean("ownerGateBypass", v).apply()

    var activeLayout: String
        get() = prefs.getString("activeLayout", "default")!!
        set(v) = prefs.edit().putString("activeLayout", v).apply()

    var lookSensitivity: Float
        get() = prefs.getFloat("lookSensitivity", 1.0f)
        set(v) = prefs.edit().putFloat("lookSensitivity", v).apply()

    /** Toque rápido na tela = usar/colocar, segurar = quebrar (estilo Bedrock). */
    var tapGestures: Boolean
        get() = prefs.getBoolean("tapGestures", true)
        set(v) = prefs.edit().putBoolean("tapGestures", v).apply()

    var holdToBreakMs: Int
        get() = prefs.getInt("holdToBreakMs", 300)
        set(v) = prefs.edit().putInt("holdToBreakMs", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    var autoSprint: Boolean
        get() = prefs.getBoolean("autoSprint", true)
        set(v) = prefs.edit().putBoolean("autoSprint", v).apply()

    /** Joystick que aparece onde o dedo toca, em vez de fixo. */
    var floatingJoystick: Boolean
        get() = prefs.getBoolean("floatingJoystick", false)
        set(v) = prefs.edit().putBoolean("floatingJoystick", v).apply()

    var overlayOpacity: Float
        get() = prefs.getFloat("overlayOpacity", 0.6f)
        set(v) = prefs.edit().putFloat("overlayOpacity", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.edit().putBoolean("keepScreenOn", v).apply()

    /** Controle Bluetooth/USB repassado ao pad virtual do Horizon. */
    var gamepadEnabled: Boolean
        get() = prefs.getBoolean("gamepad", true)
        set(v) = prefs.edit().putBoolean("gamepad", v).apply()

    var gamepadDeadzone: Float
        get() = prefs.getFloat("gamepadDeadzone", 0.15f)
        set(v) = prefs.edit().putFloat("gamepadDeadzone", v).apply()

    /** Mostra o console de comandos (API de controle do Horizon) no menu do jogo. */
    var showConsole: Boolean
        get() = prefs.getBoolean("showConsole", false)
        set(v) = prefs.edit().putBoolean("showConsole", v).apply()

    /** Deixa outros apps iniciarem o jogo por zenith://play sem confirmação. */
    var trustDeepLinks: Boolean
        get() = prefs.getBoolean("trustDeepLinks", false)
        set(v) = prefs.edit().putBoolean("trustDeepLinks", v).apply()

    /** Variáveis de ambiente extras para o jogo (uma por linha, CHAVE=valor). */
    var extraEnv: String
        get() = prefs.getString("extraEnv", "")!!
        set(v) = prefs.edit().putString("extraEnv", v).apply()

    var acceptedNotice: Boolean
        get() = prefs.getBoolean("acceptedNotice", false)
        set(v) = prefs.edit().putBoolean("acceptedNotice", v).apply()

    companion object {
        @Volatile private var instance: LauncherSettings? = null
        fun get(context: Context): LauncherSettings =
            instance ?: synchronized(this) {
                instance ?: LauncherSettings(context.applicationContext).also { instance = it }
            }
    }
}
