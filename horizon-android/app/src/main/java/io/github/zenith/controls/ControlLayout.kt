package io.github.zenith.controls

import io.github.zenith.data.Paths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Um layout de controles de toque: o joystick, a área de olhar e os botões, cada
 * um com posição (fração da tela, 0..1, pelo centro), tamanho (dp) e ação.
 * Guardado como JSON em files/controls/<id>.json; dá para exportar e importar.
 */
data class ControlLayout(
    val id: String,
    val name: String,
    val joystickX: Float = 0.13f,
    val joystickY: Float = 0.72f,
    val joystickSize: Float = 150f,
    val buttons: List<ControlButton>,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", version).put("id", id).put("name", name)
        .put("joystick", JSONObject().put("x", joystickX.toDouble()).put("y", joystickY.toDouble()).put("size", joystickSize.toDouble()))
        .put("buttons", JSONArray().apply { buttons.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): ControlLayout {
            val j = o.optJSONObject("joystick") ?: JSONObject()
            val arr = o.optJSONArray("buttons") ?: JSONArray()
            return ControlLayout(
                id = o.optString("id", "custom"),
                name = o.optString("name", "Personalizado"),
                joystickX = j.optDouble("x", 0.13).toFloat(),
                joystickY = j.optDouble("y", 0.72).toFloat(),
                joystickSize = j.optDouble("size", 150.0).toFloat(),
                buttons = (0 until arr.length()).map { ControlButton.fromJson(arr.getJSONObject(it)) },
                version = o.optInt("version", 1),
            )
        }
    }
}

/** Quando o botão aparece. */
enum class Visibility { PLAYING, MENU, ALWAYS }

data class ControlButton(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val size: Float = 56f,
    val action: ControlAction,
    /** Fica preso ao tocar uma vez (agachar, correr). */
    val toggle: Boolean = false,
    val visibility: Visibility = Visibility.PLAYING,
    val round: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("x", x.toDouble()).put("y", y.toDouble())
        .put("size", size.toDouble()).put("action", action.toJson()).put("toggle", toggle)
        .put("visibility", visibility.name).put("round", round)

    companion object {
        fun fromJson(o: JSONObject) = ControlButton(
            id = o.optString("id"),
            label = o.optString("label"),
            x = o.optDouble("x", 0.5).toFloat(),
            y = o.optDouble("y", 0.5).toFloat(),
            size = o.optDouble("size", 56.0).toFloat(),
            action = ControlAction.fromJson(o.optJSONObject("action") ?: JSONObject()),
            toggle = o.optBoolean("toggle", false),
            visibility = runCatching { Visibility.valueOf(o.optString("visibility")) }.getOrDefault(Visibility.PLAYING),
            round = o.optBoolean("round", true),
        )
    }
}

/**
 * O que um botão faz, em comandos do socket de controle do Horizon:
 * - KEY: `key <nome> down|up` (w a s d space shift ctrl e q esc f1..f12 t 1-9 ...)
 * - MOUSE: `mouse left|right|middle down|up`
 * - PAD: `pad <botão> down|up` (south east west north lb rb lt rt select start ls rs up down left right)
 * - COMMAND: um comando ao apertar e outro (opcional) ao soltar; ex.: `chat /time set day`
 * - SPECIAL: ações do launcher (teclado, rodar a hotbar, menu do overlay, esconder botões)
 */
data class ControlAction(val type: Type, val value: String, val release: String = "") {
    enum class Type { KEY, MOUSE, PAD, COMMAND, SPECIAL }

    fun pressCommand(): String? = when (type) {
        Type.KEY -> "key $value down"
        Type.MOUSE -> "mouse $value down"
        Type.PAD -> "pad $value down"
        Type.COMMAND -> value
        Type.SPECIAL -> null
    }

    fun releaseCommand(): String? = when (type) {
        Type.KEY -> "key $value up"
        Type.MOUSE -> "mouse $value up"
        Type.PAD -> "pad $value up"
        Type.COMMAND -> release.ifBlank { null }
        Type.SPECIAL -> null
    }

    fun describe(): String = when (type) {
        Type.KEY -> "Tecla ${value.uppercase()}"
        Type.MOUSE -> "Mouse $value"
        Type.PAD -> "Controle: $value"
        Type.COMMAND -> "Comando: $value"
        Type.SPECIAL -> SPECIALS.firstOrNull { it.first == value }?.second ?: value
    }

    fun toJson(): JSONObject = JSONObject().put("type", type.name).put("value", value).put("release", release)

    companion object {
        fun fromJson(o: JSONObject) = ControlAction(
            runCatching { Type.valueOf(o.optString("type")) }.getOrDefault(Type.KEY),
            o.optString("value", "space"),
            o.optString("release", ""),
        )

        fun key(k: String) = ControlAction(Type.KEY, k)
        fun mouse(b: String) = ControlAction(Type.MOUSE, b)
        fun command(c: String, release: String = "") = ControlAction(Type.COMMAND, c, release)
        fun special(s: String) = ControlAction(Type.SPECIAL, s)

        const val KEYBOARD = "keyboard"
        const val HOTBAR_NEXT = "hotbar_next"
        const val HOTBAR_PREV = "hotbar_prev"
        const val OVERLAY_MENU = "overlay_menu"
        const val HIDE_CONTROLS = "hide_controls"
        const val RIGHT_CLICK_MODE = "right_click_mode"

        val SPECIALS = listOf(
            KEYBOARD to "Abrir o teclado",
            HOTBAR_NEXT to "Próximo item da hotbar",
            HOTBAR_PREV to "Item anterior da hotbar",
            OVERLAY_MENU to "Menu do launcher (no jogo)",
            HIDE_CONTROLS to "Esconder/mostrar os botões",
            RIGHT_CLICK_MODE to "Próximo toque no menu = clique direito",
        )

        /** Teclas comuns para o editor (nomes que o `key` do Horizon entende). */
        val KEYS = listOf(
            "space", "shift", "ctrl", "e", "q", "t", "esc", "f1", "f2", "f3", "f5", "tab", "enter", "backspace",
            "w", "a", "s", "d", "r", "f", "g", "c", "v", "x", "z", "l", "p", "slash",
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "up", "down", "left", "right",
        )
        val PAD_BUTTONS = listOf("south", "east", "west", "north", "lb", "rb", "lt", "rt", "select", "start", "ls", "rs", "up", "down", "left", "right")
    }
}

object LayoutStore {
    fun all(): List<ControlLayout> {
        val dir = Paths.layoutsDir
        val custom = dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { ControlLayout.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val defaults = listOf(DefaultLayouts.classic(), DefaultLayouts.minimal())
        return defaults.filter { d -> custom.none { it.id == d.id } } + custom
    }

    fun get(id: String): ControlLayout = all().firstOrNull { it.id == id } ?: DefaultLayouts.classic()

    fun save(layout: ControlLayout) {
        Paths.layoutsDir.mkdirs()
        File(Paths.layoutsDir, "${layout.id}.json").writeText(layout.toJson().toString(2))
    }

    fun delete(id: String) {
        File(Paths.layoutsDir, "$id.json").delete()
    }

    fun isBuiltIn(id: String) = id == "default" || id == "minimal"
}

object DefaultLayouts {
    private fun b(
        id: String, label: String, x: Float, y: Float, action: ControlAction, size: Float = 56f,
        toggle: Boolean = false, visibility: Visibility = Visibility.PLAYING, round: Boolean = true,
    ) = ControlButton(id, label, x, y, size, action, toggle, visibility, round)

    /** Parecido com o dos launchers de Java no celular: tudo à mão. */
    fun classic() = ControlLayout(
        id = "default",
        name = "Clássico",
        buttons = listOf(
            b("jump", "⤒", 0.90f, 0.72f, ControlAction.key("space"), size = 76f),
            b("sneak", "Agachar", 0.80f, 0.86f, ControlAction.key("shift"), toggle = true),
            b("sprint", "Correr", 0.13f, 0.42f, ControlAction.key("ctrl"), toggle = true),
            b("attack", "⚔", 0.80f, 0.56f, ControlAction.mouse("left"), size = 62f),
            b("use", "✋", 0.92f, 0.46f, ControlAction.mouse("right"), size = 62f),
            b("inventory", "E", 0.70f, 0.93f, ControlAction.key("e"), size = 46f, round = false),
            b("drop", "Q", 0.30f, 0.93f, ControlAction.key("q"), size = 46f, round = false),
            b("chat", "💬", 0.40f, 0.06f, ControlAction.key("t"), size = 44f, round = false),
            b("camera", "F5", 0.50f, 0.06f, ControlAction.key("f5"), size = 44f, round = false),
            b("pause", "❚❚", 0.96f, 0.07f, ControlAction.key("esc"), size = 44f, round = false, visibility = Visibility.PLAYING),
            b("debug", "F3", 0.60f, 0.06f, ControlAction.key("f3"), size = 44f, round = false),
            b("menu", "☰", 0.04f, 0.07f, ControlAction.special(ControlAction.OVERLAY_MENU), size = 44f, round = false, visibility = Visibility.ALWAYS),
            b("keyboard", "⌨", 0.04f, 0.22f, ControlAction.special(ControlAction.KEYBOARD), size = 44f, round = false, visibility = Visibility.MENU),
            b("back", "←", 0.96f, 0.07f, ControlAction.key("esc"), size = 44f, round = false, visibility = Visibility.MENU),
            b("rclick", "🖱R", 0.04f, 0.37f, ControlAction.special(ControlAction.RIGHT_CLICK_MODE), size = 44f, round = false, visibility = Visibility.MENU),
            b("shiftmenu", "⇧", 0.04f, 0.52f, ControlAction.key("shift"), size = 44f, round = false, toggle = true, visibility = Visibility.MENU),
        ),
    )

    /** Só o essencial, para quem usa os gestos (tocar = usar, segurar = quebrar). */
    fun minimal() = ControlLayout(
        id = "minimal",
        name = "Mínimo (gestos)",
        buttons = listOf(
            b("jump", "⤒", 0.90f, 0.75f, ControlAction.key("space"), size = 80f),
            b("sneak", "⇩", 0.80f, 0.88f, ControlAction.key("shift"), toggle = true, size = 50f),
            b("inventory", "E", 0.70f, 0.93f, ControlAction.key("e"), size = 46f, round = false),
            b("pause", "❚❚", 0.96f, 0.07f, ControlAction.key("esc"), size = 44f, round = false),
            b("menu", "☰", 0.04f, 0.07f, ControlAction.special(ControlAction.OVERLAY_MENU), size = 44f, round = false, visibility = Visibility.ALWAYS),
            b("keyboard", "⌨", 0.04f, 0.22f, ControlAction.special(ControlAction.KEYBOARD), size = 44f, round = false, visibility = Visibility.MENU),
            b("back", "←", 0.96f, 0.07f, ControlAction.key("esc"), size = 44f, round = false, visibility = Visibility.MENU),
        ),
    )
}
