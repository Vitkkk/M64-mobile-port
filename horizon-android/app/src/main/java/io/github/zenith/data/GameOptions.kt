package io.github.zenith.data

/**
 * As opções do jogo de uma instância, nos arquivos que o próprio Horizon lê:
 *
 * - `options.txt`: as chaves e o formato do vanilla (o Horizon compara esse arquivo
 *   com o do vanilla). FOV fica salvo como (fov - 70) / 40; enums entre aspas.
 * - `horizon-graphics.txt`: as opções do Horizon (preset, API gráfica, escala de
 *   resolução, modo bateria, LOD, overlay de FPS...), docs/graphics.md.
 *
 * O jogo reescreve os dois ao salvar; o launcher só muda as chaves que mostra.
 */
class GameOptions(val instance: Instance) {
    val options = KeyValueFile(instance.optionsFile)
    val graphics = KeyValueFile(instance.graphicsFile)

    // --- Vídeo (options.txt) ---
    var renderDistance: Int
        get() = options.getInt("renderDistance", 12)
        set(v) = options.set("renderDistance", v.coerceIn(2, 128))
    var simulationDistance: Int
        get() = options.getInt("simulationDistance", 12)
        set(v) = options.set("simulationDistance", v.coerceIn(5, 32))
    /** 10..250; 260 = sem limite. */
    var maxFps: Int
        get() = options.getInt("maxFps", 120)
        set(v) = options.set("maxFps", (v / 10 * 10).coerceIn(10, 260))
    var vsync: Boolean
        get() = options.getBool("enableVsync", true)
        set(v) = options.set("enableVsync", v)
    /** 0 = automático. */
    var guiScale: Int
        get() = options.getInt("guiScale", 0)
        set(v) = options.set("guiScale", v.coerceAtLeast(0))
    var fov: Int
        get() = options.get("fov")?.toDoubleOrNull()?.let { (it * 40 + 70).toInt() } ?: 70
        set(v) = options.set("fov", ((v.coerceIn(30, 110) - 70) / 40.0).toString())
    var gamma: Float
        get() = options.getFloat("gamma", 0.5f)
        set(v) = options.set("gamma", v.coerceIn(0f, 1f))
    /** "fast", "fancy", "fabulous" ou "custom". */
    var graphicsPreset: String
        get() = options["graphicsPreset"]?.trim('"') ?: "fancy"
        set(v) = options.set("graphicsPreset", "\"$v\"")
    /** 0 todas, 1 reduzidas, 2 mínimas. */
    var particles: Int
        get() = options.getInt("particles", 0)
        set(v) = options.set("particles", v.coerceIn(0, 2))
    /** "false", "fast" ou "true". */
    var clouds: String
        get() = options["renderClouds"]?.trim('"') ?: "true"
        set(v) = options.set("renderClouds", "\"$v\"")
    var entityShadows: Boolean
        get() = options.getBool("entityShadows", true)
        set(v) = options.set("entityShadows", v)
    var smoothLighting: Boolean
        get() = options.getBool("ao", true)
        set(v) = options.set("ao", v)
    var biomeBlend: Int
        get() = options.getInt("biomeBlendRadius", 2)
        set(v) = options.set("biomeBlendRadius", v.coerceIn(0, 7))
    var mipmaps: Int
        get() = options.getInt("mipmapLevels", 4)
        set(v) = options.set("mipmapLevels", v.coerceIn(0, 4))
    var viewBobbing: Boolean
        get() = options.getBool("bobView", true)
        set(v) = options.set("bobView", v)

    // --- Jogo ---
    var lang: String
        get() = options["lang"] ?: "en_us"
        set(v) = options.set("lang", v)
    var autoJump: Boolean
        get() = options.getBool("autoJump", false)
        set(v) = options.set("autoJump", v)
    var toggleCrouch: Boolean
        get() = options.getBool("toggleCrouch", false)
        set(v) = options.set("toggleCrouch", v)
    var toggleSprint: Boolean
        get() = options.getBool("toggleSprint", false)
        set(v) = options.set("toggleSprint", v)
    var mouseSensitivity: Float
        get() = options.getFloat("mouseSensitivity", 0.5f)
        set(v) = options.set("mouseSensitivity", v.coerceIn(0f, 1f))
    var showSubtitles: Boolean
        get() = options.getBool("showSubtitles", false)
        set(v) = options.set("showSubtitles", v)
    var pauseOnLostFocus: Boolean
        get() = options.getBool("pauseOnLostFocus", true)
        set(v) = options.set("pauseOnLostFocus", v)

    // --- Som ---
    fun volume(category: String): Float = options.getFloat("soundCategory_$category", 1f)
    fun setVolume(category: String, v: Float) = options.set("soundCategory_$category", v.coerceIn(0f, 1f))

    // --- Horizon (horizon-graphics.txt) ---
    /** "performance", "balanced", "quality", "ultra" ou "custom". */
    var horizonPreset: String
        get() = graphics["preset"] ?: "custom"
        set(v) = graphics.set("preset", v)
    /** "auto", "vulkan" ou "opengl" (OpenGL ES no Android). */
    var graphicsApi: String
        get() = graphics["graphicsApi"] ?: "auto"
        set(v) = graphics.set("graphicsApi", v)
    /** 50..200 %. */
    var resolutionScale: Int
        get() = graphics.getInt("resolutionScale", 100)
        set(v) = graphics.set("resolutionScale", v.coerceIn(50, 200))
    var batteryMode: Boolean
        get() = graphics.getBool("batteryMode", false)
        set(v) = graphics.set("batteryMode", v)
    /** "off", "fps" ou "graph". */
    var overlay: String
        get() = graphics["overlay"] ?: "off"
        set(v) = graphics.set("overlay", v)
    /** 0 = automático. */
    var workers: Int
        get() = graphics.getInt("workers", 0)
        set(v) = graphics.set("workers", v.coerceIn(0, 64))
    var fxaa: Boolean
        get() = graphics.getBool("fxaa", false)
        set(v) = graphics.set("fxaa", v)
    var taa: Boolean
        get() = graphics.getBool("taa", false)
        set(v) = graphics.set("taa", v)
    /** 0 = desligado; senão 64.. chunks de terreno distante. */
    var lodDistance: Int
        get() = graphics.getInt("lodDistance", 0)
        set(v) = graphics.set("lodDistance", v)
    var caveCulling: Boolean
        get() = graphics.getBool("caveCulling", true)
        set(v) = graphics.set("caveCulling", v)
    var animatedTextures: Boolean
        get() = graphics.getBool("animatedTextures", true)
        set(v) = graphics.set("animatedTextures", v)
    /** "efficient" ou "precise". */
    var framePacing: String
        get() = graphics["framePacing"] ?: "efficient"
        set(v) = graphics.set("framePacing", v)
    var shaderPack: String
        get() = graphics["pickedShaderPack"] ?: ""
        set(v) = graphics.set("pickedShaderPack", v)

    fun save() {
        if (options["version"] == null) options.set("version", DATA_VERSION)
        options.save()
        graphics.save()
    }

    /** Aplica um preset do launcher, pensado para celulares. */
    fun applyMobilePreset(preset: MobilePreset) {
        when (preset) {
            MobilePreset.BATTERY -> {
                renderDistance = 6; simulationDistance = 5; maxFps = 30; resolutionScale = 70
                graphicsPreset = "fast"; particles = 2; clouds = "false"; entityShadows = false
                biomeBlend = 0; mipmaps = 0; smoothLighting = false; batteryMode = true
                horizonPreset = "performance"; animatedTextures = false
            }
            MobilePreset.BALANCED -> {
                renderDistance = 8; simulationDistance = 6; maxFps = 60; resolutionScale = 85
                graphicsPreset = "fast"; particles = 1; clouds = "fast"; entityShadows = false
                biomeBlend = 1; mipmaps = 2; smoothLighting = true; batteryMode = false
                horizonPreset = "performance"; animatedTextures = true
            }
            MobilePreset.QUALITY -> {
                renderDistance = 12; simulationDistance = 8; maxFps = 60; resolutionScale = 100
                graphicsPreset = "fancy"; particles = 0; clouds = "true"; entityShadows = true
                biomeBlend = 2; mipmaps = 4; smoothLighting = true; batteryMode = false
                horizonPreset = "balanced"; animatedTextures = true
            }
            MobilePreset.ULTRA -> {
                renderDistance = 16; simulationDistance = 10; maxFps = 120; resolutionScale = 100
                graphicsPreset = "fancy"; particles = 0; clouds = "true"; entityShadows = true
                biomeBlend = 3; mipmaps = 4; smoothLighting = true; batteryMode = false
                horizonPreset = "quality"; animatedTextures = true
            }
        }
    }

    enum class MobilePreset(val label: String, val description: String) {
        BATTERY("Bateria", "30 FPS, 6 chunks, resolução 70%"),
        BALANCED("Equilibrado", "60 FPS, 8 chunks, resolução 85%"),
        QUALITY("Qualidade", "60 FPS, 12 chunks, gráficos bonitos"),
        ULTRA("Ultra", "120 FPS, 16 chunks (celulares topo de linha)"),
    }

    companion object {
        /** `horizon_core::version::DATA_VERSION` do Horizon fixado (26.3). */
        const val DATA_VERSION = 5023

        fun writeMobileDefaults(instance: Instance) {
            val o = GameOptions(instance)
            o.lang = "pt_br"
            o.autoJump = true
            o.guiScale = 0
            o.vsync = true
            o.pauseOnLostFocus = true
            o.graphicsApi = "auto"
            o.applyMobilePreset(MobilePreset.BALANCED)
            o.save()
        }

        val LANGUAGES = listOf(
            "pt_br" to "Português (Brasil)", "pt_pt" to "Português (Portugal)",
            "en_us" to "English (US)", "es_es" to "Español", "fr_fr" to "Français",
            "de_de" to "Deutsch", "it_it" to "Italiano", "ja_jp" to "日本語", "ru_ru" to "Русский",
        )

        val SOUND_CATEGORIES = listOf(
            "master" to "Geral", "music" to "Música", "record" to "Toca-discos",
            "weather" to "Clima", "block" to "Blocos", "hostile" to "Criaturas hostis",
            "neutral" to "Criaturas amigáveis", "player" to "Jogadores", "ambient" to "Ambiente",
            "voice" to "Voz",
        )
    }
}
