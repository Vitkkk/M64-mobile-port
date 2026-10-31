package com.kytyps5.android

import android.content.Context
import com.kytyps5.android.library.GameSettings

/** Launcher preferences persisted in SharedPreferences. Reads fall back to defaults on any failure. */
class LauncherSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var gamesRoot: String
        get() = prefs.getString(KEY_GAMES_ROOT, DEFAULT_GAMES_ROOT) ?: DEFAULT_GAMES_ROOT
        set(value) {
            prefs.edit().putString(KEY_GAMES_ROOT, value).apply()
        }

    var resolution: Resolution
        get() = Resolution.fromStorage(prefs.getString(KEY_RESOLUTION, null))
        set(value) {
            prefs.edit().putString(KEY_RESOLUTION, value.storageValue).apply()
        }

    /** Internal render scale unless a game overrides it (see [RenderScale]). */
    var renderScale: RenderScale
        get() = RenderScale.fromStorage(prefs.getString(KEY_RENDER_SCALE, null))
        set(value) {
            prefs.edit().putString(KEY_RENDER_SCALE, value.storageValue).apply()
        }

    /** Global GPU driver: an imported driver id from [GpuDriverStore], or "" for the system driver. */
    var gpuDriverId: String
        get() = prefs.getString(KEY_GPU_DRIVER, SYSTEM_DRIVER) ?: SYSTEM_DRIVER
        set(value) {
            prefs.edit().putString(KEY_GPU_DRIVER, value).apply()
        }

    /** Version of the bundled driver whose one-time default setup already ran (0 = never). */
    var bundledDriverVersion: Int
        get() = prefs.getInt(KEY_BUNDLED_DRIVER_VERSION, 0)
        set(value) {
            prefs.edit().putInt(KEY_BUNDLED_DRIVER_VERSION, value).apply()
        }

    /** Show the performance HUD in game unless a game overrides it. */
    var hudEnabled: Boolean
        get() = prefs.getBoolean(KEY_HUD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HUD, value).apply()
        }

    /** Show the on-screen controller in game unless a game overrides it (hidden while a gamepad is attached). */
    var touchControlsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_CONTROLS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TOUCH_CONTROLS, value).apply()
        }

    /** Max GPU clock (--gpu-turbo) unless a game overrides it: ~1.85x faster GPU work, runs hotter. */
    var gpuTurbo: Boolean
        get() = prefs.getBoolean(KEY_GPU_TURBO, DEFAULT_GPU_TURBO)
        set(value) {
            prefs.edit().putBoolean(KEY_GPU_TURBO, value).apply()
        }

    /** PPSS55: the welcome / first-folder flow has been completed. */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
        }

    /** The SAF tree URI the user granted for the games folder (persisted permission), or null. */
    var gamesTreeUri: String?
        get() = prefs.getString(KEY_GAMES_TREE_URI, null)
        set(value) {
            prefs.edit().putString(KEY_GAMES_TREE_URI, value).apply()
        }

    /** Swapchain present mode passed as --present-mode (FIFO = VSync, the only frame cap the core has). */
    var presentMode: PresentMode
        get() = PresentMode.fromStorage(prefs.getString(KEY_PRESENT_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_PRESENT_MODE, value.storageValue).apply()
        }

    /** Audio queue target passed as KYTY_AUDIO_LATENCY_MS (core patch 0001). */
    var audioLatencyMs: Int
        get() = prefs.getInt(KEY_AUDIO_LATENCY, DEFAULT_AUDIO_LATENCY_MS).takeIf { it in AUDIO_LATENCY_CHOICES } ?: DEFAULT_AUDIO_LATENCY_MS
        set(value) {
            prefs.edit().putInt(KEY_AUDIO_LATENCY, value).apply()
        }

    /** Rendering workaround: KYTY_RP_OPS=0 (no clear folding into a pass's loadOp CLEAR). */
    var rpOpsOff: Boolean
        get() = prefs.getBoolean(KEY_RP_OPS_OFF, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RP_OPS_OFF, value).apply()
        }

    /** Rendering workaround: TU_DEBUG=sysmem (Turnip renders straight to memory, no GMEM tiles). */
    var turnipSysmem: Boolean
        get() = prefs.getBoolean(KEY_TU_SYSMEM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TU_SYSMEM, value).apply()
        }

    /** Detailed core log in the session file (--printf-direction Console). Slower; on while diagnosing. */
    var coreLog: Boolean
        get() = prefs.getBoolean(KEY_CORE_LOG, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CORE_LOG, value).apply()
        }

    /** Environment lines from the launcher-wide settings; a game's own lines come after and win. */
    fun globalEnv(): List<String> = buildList {
        add("$ENV_AUDIO_LATENCY=$audioLatencyMs")
        if (rpOpsOff) add("KYTY_RP_OPS=0")
        if (turnipSysmem) add("TU_DEBUG=sysmem")
    }

    /** --shader-disk-cache: keep translated shaders between runs. */
    var shaderDiskCache: Boolean
        get() = prefs.getBoolean(KEY_SHADER_CACHE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHADER_CACHE, value).apply()
        }

    fun gameSettings(gamePath: String): GameSettings =
        GameSettings.fromJson(prefs.getString(KEY_GAME_SETTINGS_PREFIX + gamePath, null))

    fun setGameSettings(gamePath: String, settings: GameSettings) {
        val key = KEY_GAME_SETTINGS_PREFIX + gamePath
        prefs.edit().apply { if (settings == GameSettings.DEFAULT) remove(key) else putString(key, settings.toJson()) }.apply()
    }

    /** Per-game GPU driver override (keyed by game path): null follows [gpuDriverId]. */
    fun gameGpuDriverOverride(gamePath: String): String? =
        prefs.getString(KEY_GPU_DRIVER_GAME_PREFIX + gamePath, null)

    fun setGameGpuDriverOverride(gamePath: String, driverId: String?) {
        val key = KEY_GPU_DRIVER_GAME_PREFIX + gamePath
        prefs.edit().apply { if (driverId == null) remove(key) else putString(key, driverId) }.apply()
    }

    /** Ids of the shipped config presets (GamePresets) turned on for a title; none by default (opt-in). */
    fun enabledPresets(titleId: String): Set<String> =
        prefs.getStringSet(KEY_GAME_PRESETS_PREFIX + titleId, null)?.toSet().orEmpty()

    fun setEnabledPresets(titleId: String, presetIds: Set<String>) {
        val key = KEY_GAME_PRESETS_PREFIX + titleId
        prefs.edit().apply { if (presetIds.isEmpty()) remove(key) else putStringSet(key, presetIds.toSet()) }.apply()
    }

    companion object {
        /** Stored driver id meaning "the device's own Vulkan driver". */
        const val SYSTEM_DRIVER = ""
        const val DEFAULT_GAMES_ROOT = "/sdcard/PPSS55/games"
        const val DEFAULT_GPU_TURBO = true
        private const val PREFS_NAME = "kytyps5_launcher"
        private const val KEY_GAMES_ROOT = "games_root"
        const val ENV_AUDIO_LATENCY = "KYTY_AUDIO_LATENCY_MS"
        val AUDIO_LATENCY_CHOICES = listOf(40, 80, 120, 200)
        /** 40 ms crackled on the REDMAGIC 9S Pro (first user report); 120 ms is the starting point. */
        const val DEFAULT_AUDIO_LATENCY_MS = 120
        private const val KEY_AUDIO_LATENCY = "ppss55_audio_latency_ms"
        private const val KEY_RP_OPS_OFF = "ppss55_rp_ops_off"
        private const val KEY_CORE_LOG = "ppss55_core_log"
        private const val KEY_TU_SYSMEM = "ppss55_tu_sysmem"
        private const val KEY_ONBOARDED = "ppss55_onboarded"
        private const val KEY_GAMES_TREE_URI = "ppss55_games_tree_uri"
        private const val KEY_PRESENT_MODE = "ppss55_present_mode"
        private const val KEY_SHADER_CACHE = "ppss55_shader_disk_cache"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_RENDER_SCALE = "render_scale"
        private const val KEY_GPU_DRIVER = "gpu_driver"
        private const val KEY_GPU_DRIVER_GAME_PREFIX = "gpu_driver_game:"
        private const val KEY_BUNDLED_DRIVER_VERSION = "bundled_driver_version"
        private const val KEY_HUD = "hud_enabled"
        private const val KEY_TOUCH_CONTROLS = "touch_controls_enabled"
        private const val KEY_GPU_TURBO = "gpu_turbo"
        private const val KEY_GAME_SETTINGS_PREFIX = "game_settings:"
        private const val KEY_GAME_PRESETS_PREFIX = "game_presets:"
    }
}
