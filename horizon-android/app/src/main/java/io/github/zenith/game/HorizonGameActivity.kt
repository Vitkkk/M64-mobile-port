package io.github.zenith.game

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import io.github.zenith.controls.ControlAction
import io.github.zenith.controls.ControlsOverlayView
import io.github.zenith.controls.LayoutStore
import io.github.zenith.data.InstanceStore
import io.github.zenith.data.LauncherSettings
import io.github.zenith.data.Paths
import io.github.zenith.data.KeyValueFile
import kotlin.math.abs

/**
 * A tela do jogo. O GameActivity (androidx.games) carrega libhorizon_android.so e
 * chama o `android_main` do Rust, que roda o Horizon nesta janela. Por cima vai o
 * overlay de toque, e as teclas do controle físico viram comandos do pad virtual
 * do Horizon pelo socket de controle.
 *
 * Roda no processo `:game`: quando o jogo termina, o processo acaba junto.
 */
class HorizonGameActivity : GameActivity(), ControlsOverlayView.Sink {
    private lateinit var settings: LauncherSettings
    private lateinit var control: HorizonControl
    private lateinit var overlay: ControlsOverlayView
    private lateinit var keyboardField: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var selectedSlot = 1
    private var screen = ""
    private var lastPadAxes = FloatArray(8)

    private val poll = object : Runnable {
        override fun run() {
            control.query("state") { json ->
                if (json != null && !json.has("error")) {
                    val s = json.optString("screen")
                    val paused = json.optBoolean("paused")
                    val sel = json.optInt("selected", selectedSlot)
                    handler.post {
                        selectedSlot = sel
                        overlay.playing = s == "playing" && !paused
                        if (s != screen) onScreenChanged(screen, s)
                        screen = s
                    }
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = LauncherSettings.get(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        hideSystemBars()

        control = HorizonControl(Paths.controlSocket)
        control.start()

        val instance = InstanceStore.get(intent.getStringExtra(EXTRA_INSTANCE))
        val layoutId = instance?.layout?.takeIf { it.isNotBlank() } ?: settings.activeLayout
        val guiScale = instance?.let { KeyValueFile(it.optionsFile).getInt("guiScale", 0) } ?: 0
        overlay = ControlsOverlayView(this, LayoutStore.get(layoutId), this, overlayConfig(guiScale))

        keyboardField = EditText(this).apply {
            alpha = 0f
            // Só recebe foco enquanto o teclado na tela está aberto: um teclado físico
            // vai direto para o jogo (winit), sem passar por aqui.
            isFocusable = false
            isFocusableInTouchMode = false
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setOnEditorActionListener { _, _, _ -> command("key enter tap"); true }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DEL) { command("key backspace tap"); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s == null) return
                    repeat(before) { command("key backspace tap") }
                    val typed = s.subSequence(start, start + count).toString()
                    typeText(typed)
                }
                override fun afterTextChanged(s: Editable?) {
                    // Mantém o campo curto; o texto de verdade está no jogo.
                    if ((s?.length ?: 0) > 64) s?.clear()
                }
            })
        }
        val root = FrameLayout(this)
        root.addView(keyboardField, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        handler.postDelayed(poll, 1000)
    }

    private fun overlayConfig(guiScale: Int) = ControlsOverlayView.Config(
        lookSensitivity = settings.lookSensitivity,
        tapGestures = settings.tapGestures,
        holdToBreakMs = settings.holdToBreakMs,
        haptics = settings.haptics,
        autoSprint = settings.autoSprint,
        floatingJoystick = settings.floatingJoystick,
        opacity = settings.overlayOpacity,
        guiScale = guiScale,
    )

    private fun onScreenChanged(from: String, to: String) {
        // O chat e as telas de texto abrem o teclado do Android sozinhos.
        if (to in TEXT_SCREENS) showKeyboard() else if (from in TEXT_SCREENS) hideKeyboard()
    }

    private fun typeText(text: String) {
        if (text.isEmpty()) return
        val parts = text.split(' ')
        parts.forEachIndexed { i, part ->
            if (part.isNotEmpty()) command("type $part")
            if (i < parts.size - 1) command("key space tap")
        }
    }

    private fun showKeyboard() {
        keyboardField.isFocusable = true
        keyboardField.isFocusableInTouchMode = true
        keyboardField.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(keyboardField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(keyboardField.windowToken, 0)
        keyboardField.text?.clear()
        keyboardField.clearFocus()
        keyboardField.isFocusable = false
        keyboardField.isFocusableInTouchMode = false
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // --- ControlsOverlayView.Sink ---

    override fun command(line: String) = control.send(line)
    override fun look(dx: Double, dy: Double) = control.look(dx, dy)
    override fun stick(x: Float, y: Float) = control.moveStick(x, y)
    override fun selectSlot(slot: Int) {
        selectedSlot = slot
        control.send("select $slot")
    }

    override fun special(action: String) {
        when (action) {
            ControlAction.KEYBOARD -> showKeyboard()
            ControlAction.HOTBAR_NEXT -> selectSlot(if (selectedSlot >= 9) 1 else selectedSlot + 1)
            ControlAction.HOTBAR_PREV -> selectSlot(if (selectedSlot <= 1) 9 else selectedSlot - 1)
            ControlAction.OVERLAY_MENU -> showOverlayMenu()
            ControlAction.RIGHT_CLICK_MODE -> overlay.rightClickMode = !overlay.rightClickMode
        }
    }

    // --- Menu do launcher dentro do jogo ---

    private fun showOverlayMenu() {
        val items = mutableListOf(
            "Continuar",
            if (overlay.hidden) "Mostrar os botões" else "Esconder os botões",
            "Sensibilidade e opacidade…",
            "Teclado",
            "Tirar print (F2)",
            "Salvar o mundo",
            "Sair do jogo (salva antes)",
        )
        if (settings.showConsole) items.add(3, "Console de comandos (API)")
        AlertDialog.Builder(this)
            .setTitle("Zenith")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Mostrar os botões", "Esconder os botões" -> overlay.hidden = !overlay.hidden
                    "Sensibilidade e opacidade…" -> showTuning()
                    "Teclado" -> showKeyboard()
                    "Tirar print (F2)" -> command("key f2 tap")
                    "Salvar o mundo" -> control.query("save") { toast(if (it?.has("error") == false) "Mundo salvo" else "Nada para salvar") }
                    "Sair do jogo (salva antes)" -> quitGame()
                    "Console de comandos (API)" -> showConsole()
                }
            }
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    private fun showTuning() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        fun slider(label: String, value: Float, max: Float, set: (Float) -> Unit) {
            val t = TextView(this).apply { text = "$label: %.2f".format(value) }
            val s = SeekBar(this).apply {
                this.max = 100
                progress = (value / max * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = (p / 100f * max).coerceAtLeast(0.05f)
                        t.text = "$label: %.2f".format(v); set(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            box.addView(t); box.addView(s)
        }
        slider("Sensibilidade do olhar", settings.lookSensitivity, 4f) {
            settings.lookSensitivity = it
            overlay.config = overlay.config.copy(lookSensitivity = it)
        }
        slider("Opacidade dos botões", settings.overlayOpacity, 1f) {
            settings.overlayOpacity = it
            overlay.config = overlay.config.copy(opacity = it); overlay.invalidate()
        }
        AlertDialog.Builder(this).setTitle("Ajustes rápidos").setView(box)
            .setPositiveButton("OK", null).setOnDismissListener { hideSystemBars() }.show()
    }

    /** Console da API de controle do Horizon: qualquer comando do horizon-ctl. */
    private fun showConsole() {
        val out = TextView(this).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f }
        val input = EditText(this).apply { hint = "ex.: state, give diamond 3, time 6000, tp 0 100 0"; isSingleLine = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0)
            addView(input)
            addView(ScrollView(context).apply { addView(out) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Console (API de controle)").setView(box)
            .setPositiveButton("Enviar", null).setNegativeButton("Fechar", null)
            .setOnDismissListener { hideSystemBars() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cmd = input.text.toString().trim()
                if (cmd.isEmpty()) return@setOnClickListener
                out.append("> $cmd\n")
                control.query(cmd) { json ->
                    handler.post { out.append((json?.toString(2) ?: "(sem resposta)").take(4000) + "\n\n") }
                }
                input.text.clear()
            }
        }
        dialog.show()
    }

    private fun quitGame() {
        control.query("quit") { reply ->
            // Se o jogo não respondeu (ainda carregando), encerra o processo mesmo assim.
            if (reply == null) handler.post { finishGame() }
        }
        handler.postDelayed({ finishGame() }, 8000)
    }

    private fun finishGame() {
        finish()
        Process.killProcess(Process.myPid())
    }

    private fun toast(text: String) = handler.post { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    // --- Teclas: voltar e controle físico ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) command("key esc tap")
            return true
        }
        padButton(keyCode)?.let { if (event.repeatCount == 0) command("pad $it down"); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        padButton(keyCode)?.let { command("pad $it up"); return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun padButton(keyCode: Int): String? {
        if (!settings.gamepadEnabled) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "south"
            KeyEvent.KEYCODE_BUTTON_B -> "east"
            KeyEvent.KEYCODE_BUTTON_X -> "west"
            KeyEvent.KEYCODE_BUTTON_Y -> "north"
            KeyEvent.KEYCODE_BUTTON_L1 -> "lb"
            KeyEvent.KEYCODE_BUTTON_R1 -> "rb"
            KeyEvent.KEYCODE_BUTTON_L2 -> "lt"
            KeyEvent.KEYCODE_BUTTON_R2 -> "rt"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "select"
            KeyEvent.KEYCODE_BUTTON_START -> "start"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "ls"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "rs"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (settings.gamepadEnabled && ev.isFromSource(InputDevice.SOURCE_JOYSTICK) && ev.action == MotionEvent.ACTION_MOVE) {
            val dz = settings.gamepadDeadzone
            fun axis(a: Int): Float = ev.getAxisValue(a).let { if (abs(it) < dz) 0f else it }
            val lx = axis(MotionEvent.AXIS_X); val ly = -axis(MotionEvent.AXIS_Y)
            val rx = axis(MotionEvent.AXIS_Z); val ry = -axis(MotionEvent.AXIS_RZ)
            val lt = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
            val rt = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
            val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val axes = floatArrayOf(lx, ly, rx, ry, lt, rt, hx, hy)
            if (!axes.contentEquals(lastPadAxes)) {
                if (lx != lastPadAxes[0] || ly != lastPadAxes[1]) control.moveStick(lx, ly)
                if (rx != lastPadAxes[2] || ry != lastPadAxes[3]) control.rightStick(rx, ry)
                if (lt != lastPadAxes[4]) command("pad trigger left %.2f".format(java.util.Locale.ROOT, lt))
                if (rt != lastPadAxes[5]) command("pad trigger right %.2f".format(java.util.Locale.ROOT, rt))
                hat(lastPadAxes[6], hx, "left", "right")
                hat(lastPadAxes[7], hy, "up", "down")
                lastPadAxes = axes
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun hat(old: Float, new: Float, neg: String, pos: String) {
        val o = if (old < -0.5f) neg else if (old > 0.5f) pos else null
        val n = if (new < -0.5f) neg else if (new > 0.5f) pos else null
        if (o == n) return
        o?.let { command("pad $it up") }
        n?.let { command("pad $it down") }
    }

    override fun onStop() {
        super.onStop()
        // Indo para o fundo: salva o mundo. A volta para o app ainda não é garantida
        // (o Horizon não recria a superfície), então nada se perde se o processo morrer.
        control.send("save")
        overlay.releaseAll()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        control.close()
        super.onDestroy()
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    companion object {
        const val EXTRA_INSTANCE = "instance"
        private val TEXT_SCREENS = setOf("chat", "sign", "book", "command-block")
    }
}

/** Para o editor de controles mostrar o overlay sem jogo. */
object NoSink : ControlsOverlayView.Sink {
    override fun command(line: String) {}
    override fun look(dx: Double, dy: Double) {}
    override fun stick(x: Float, y: Float) {}
    override fun special(action: String) {}
    override fun selectSlot(slot: Int) {}
}
