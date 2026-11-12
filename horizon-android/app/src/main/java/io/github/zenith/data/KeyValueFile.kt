package io.github.zenith.data

import java.io.File

/**
 * Um arquivo `chave:valor` por linha, como o options.txt do Minecraft e o
 * horizon-graphics.txt do Horizon. Mantém a ordem e as chaves que o launcher não
 * conhece, para não apagar nada que o jogo escreveu.
 */
class KeyValueFile(val file: File) {
    private val lines = mutableListOf<String>()
    private val index = LinkedHashMap<String, Int>()

    init {
        if (file.isFile) {
            file.readLines().forEach { add(it) }
        }
    }

    private fun add(line: String) {
        val colon = line.indexOf(':')
        if (colon > 0) index[line.substring(0, colon)] = lines.size
        lines.add(line)
    }

    operator fun get(key: String): String? =
        index[key]?.let { lines[it].substring(key.length + 1) }

    fun getInt(key: String, default: Int) = get(key)?.trim()?.toIntOrNull() ?: default
    fun getFloat(key: String, default: Float) = get(key)?.trim()?.toFloatOrNull() ?: default
    fun getBool(key: String, default: Boolean) = when (get(key)?.trim()) {
        "true" -> true
        "false" -> false
        else -> default
    }

    operator fun set(key: String, value: String) {
        val at = index[key]
        if (at != null) lines[at] = "$key:$value" else add("$key:$value")
    }

    fun set(key: String, value: Int) = set(key, value.toString())
    fun set(key: String, value: Boolean) = set(key, value.toString())
    fun set(key: String, value: Float) = set(key, value.toString())

    fun keys(): Set<String> = index.keys

    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        /** Uma lista JSON de strings como a de `resourcePacks:["vanilla","file/x.zip"]`. */
        fun parseStringList(value: String?): MutableList<String> {
            if (value.isNullOrBlank()) return mutableListOf()
            return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(value)
                .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
                .toMutableList()
        }

        fun formatStringList(items: List<String>): String =
            items.joinToString(",", "[", "]") {
                "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
    }
}
