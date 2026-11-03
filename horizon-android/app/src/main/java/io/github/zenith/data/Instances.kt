package io.github.zenith.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Uma instância: uma pasta de jogo própria (mundos, options.txt, packs, mods,
 * prints), com nome, ícone e opções de lançamento. Como os perfis dos launchers
 * de PC.
 */
data class Instance(
    val id: String,
    val name: String,
    val color: Long = 0xFF4FB477,
    val icon: String = "grass",
    val created: Long = System.currentTimeMillis(),
    val lastPlayed: Long = 0,
    val playSeconds: Long = 0,
    /** Mundo de saves/ aberto direto ao iniciar (`--quickPlaySingleplayer`). */
    val quickWorld: String = "",
    /** Servidor em que entrar ao iniciar (`--quickPlayMultiplayer`). */
    val quickServer: String = "",
    /** Argumentos extras para o horizon-client, separados por espaço. */
    val extraArgs: String = "",
    /** Layout de controles desta instância; vazio usa o ativo. */
    val layout: String = "",
) {
    val root get() = File(Paths.instancesDir, id)
    val gameDir get() = File(root, "game")
    val homeDir get() = File(root, "home")
    val logsDir get() = File(homeDir, "logs")
    val crashDir get() = File(homeDir, "crash-reports")
    val savesDir get() = File(gameDir, "saves")
    val optionsFile get() = File(gameDir, "options.txt")
    val graphicsFile get() = File(gameDir, "horizon-graphics.txt")
    val modsDir get() = File(gameDir, "horizon-mods")
    val nativeModsDir get() = File(gameDir, "horizon-native-mods")
    val modsStateFile get() = File(gameDir, "horizon-mods.txt")
    val resourcePacksDir get() = File(gameDir, "resourcepacks")
    val shaderPacksDir get() = File(gameDir, "shaderpacks")
    val screenshotsDir get() = File(gameDir, "screenshots")

    fun worlds(): List<String> =
        savesDir.listFiles { f -> f.isDirectory && File(f, "level.dat").exists() }
            ?.sortedByDescending { File(it, "level.dat").lastModified() }
            ?.map { it.name } ?: emptyList()

    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("color", color).put("icon", icon)
        .put("created", created).put("lastPlayed", lastPlayed).put("playSeconds", playSeconds)
        .put("quickWorld", quickWorld).put("quickServer", quickServer)
        .put("extraArgs", extraArgs).put("layout", layout)

    companion object {
        fun fromJson(o: JSONObject) = Instance(
            id = o.getString("id"),
            name = o.optString("name", "Instância"),
            color = o.optLong("color", 0xFF4FB477),
            icon = o.optString("icon", "grass"),
            created = o.optLong("created", 0),
            lastPlayed = o.optLong("lastPlayed", 0),
            playSeconds = o.optLong("playSeconds", 0),
            quickWorld = o.optString("quickWorld", ""),
            quickServer = o.optString("quickServer", ""),
            extraArgs = o.optString("extraArgs", ""),
            layout = o.optString("layout", ""),
        )

        val ICONS = listOf("grass", "stone", "diamond", "redstone", "tnt", "chest", "sword", "pickaxe")
        val COLORS = listOf(0xFF4FB477, 0xFF3D7BD9, 0xFFE0A030, 0xFFD9534F, 0xFF9B59B6, 0xFF1ABC9C, 0xFF8D6E63, 0xFF607D8B)
    }
}

/** As instâncias, em `files/instances.json`. */
object InstanceStore {
    private val lock = Any()

    fun all(): List<Instance> = synchronized(lock) {
        val file = Paths.instancesFile
        val list = if (file.isFile) {
            runCatching {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { Instance.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        } else emptyList()
        if (list.isEmpty()) {
            val first = Instance(id = "main", name = "Principal")
            write(listOf(first))
            prepare(first)
            listOf(first)
        } else list
    }

    fun get(id: String?): Instance? = all().firstOrNull { it.id == id }

    fun selected(settings: LauncherSettings): Instance =
        get(settings.selectedInstance) ?: all().first()

    fun save(instance: Instance) = synchronized(lock) {
        val list = all().toMutableList()
        val at = list.indexOfFirst { it.id == instance.id }
        if (at >= 0) list[at] = instance else list.add(instance)
        write(list)
        prepare(instance)
    }

    fun create(name: String, color: Long, icon: String, copyOptionsFrom: Instance? = null): Instance {
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "instancia" } +
            "-" + UUID.randomUUID().toString().take(6)
        val instance = Instance(id = id, name = name, color = color, icon = icon)
        save(instance)
        if (copyOptionsFrom != null) {
            for (fileName in listOf("options.txt", "horizon-graphics.txt", "servers.dat")) {
                val src = File(copyOptionsFrom.gameDir, fileName)
                if (src.isFile) src.copyTo(File(instance.gameDir, fileName), overwrite = true)
            }
        }
        return instance
    }

    fun duplicate(source: Instance): Instance {
        val copy = create("${source.name} (cópia)", source.color, source.icon)
        source.gameDir.copyRecursively(copy.gameDir, overwrite = true)
        val saved = copy.copy(extraArgs = source.extraArgs, layout = source.layout)
        save(saved)
        return saved
    }

    fun delete(instance: Instance) {
        synchronized(lock) {
            val list = all().filter { it.id != instance.id }
            if (list.isEmpty()) return
            write(list)
        }
        instance.root.deleteRecursively()
    }

    private fun write(list: List<Instance>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Paths.instancesFile.parentFile?.mkdirs()
        Paths.instancesFile.writeText(arr.toString(2))
    }

    /** Pastas e opções iniciais pensadas para celular. */
    private fun prepare(instance: Instance) {
        instance.gameDir.mkdirs()
        instance.homeDir.mkdirs()
        if (!instance.optionsFile.exists()) GameOptions.writeMobileDefaults(instance)
    }
}
