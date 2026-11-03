package io.github.zenith.controls

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.zenith.data.LauncherSettings
import io.github.zenith.game.NoSink
import java.util.UUID

/**
 * Editor de layout: a tela do jogo em branco com os botões por cima. Arraste para
 * mover; toque para escolher e mude tamanho, rótulo, ação e quando aparece.
 */
class ControlsEditorActivity : AppCompatActivity() {
    private lateinit var editor: ControlsOverlayView
    private lateinit var editLayout: ControlLayout
    private lateinit var info: TextView
    private lateinit var sizeBar: SeekBar
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val settings = LauncherSettings.get(this)
        val id = intent.getStringExtra(EXTRA_LAYOUT) ?: settings.activeLayout
        editLayout = LayoutStore.get(id).let {
            // Os layouts de fábrica viram uma cópia editável.
            if (LayoutStore.isBuiltIn(it.id)) it.copy(id = "custom-" + UUID.randomUUID().toString().take(8), name = it.name + " (meu)") else it
        }

        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(120, 170, 230), Color.rgb(90, 140, 70)))
        editor = ControlsOverlayView(this, editLayout, NoSink, ControlsOverlayView.Config(opacity = settings.overlayOpacity))
        editor.editing = true
        editor.onLayoutChanged = { editLayout = it; dirty = true }
        editor.onSelect = { refreshInfo() }
        root.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(200, 20, 22, 28))
            setPadding(16, 8, 16, 8)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; setOnClickListener { onClick() }
            bar.addView(this)
        }
        button("Salvar") { save(finishAfter = true) }
        button("+ Botão") { addButton() }
        button("Editar") { editSelected() }
        button("Apagar") { deleteSelected() }
        button("Nome") { rename() }
        button("Restaurar") { restore() }
        info = TextView(this).apply { setTextColor(Color.WHITE); setPadding(24, 0, 12, 0) }
        bar.addView(info)
        sizeBar = SeekBar(this).apply {
            max = 160
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val size = 30f + p
                    val sel = editor.selectedId ?: return
                    editLayout = if (sel == ControlsOverlayView.JOYSTICK_ID) editLayout.copy(joystickSize = size * 2)
                    else editLayout.copy(buttons = editLayout.buttons.map { if (it.id == sel) it.copy(size = size) else it })
                    editor.layout = editLayout; dirty = true; refreshInfo()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bar.addView(sizeBar, LinearLayout.LayoutParams(320, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroller = HorizontalScrollView(this).apply { addView(bar) }
        root.addView(scroller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        refreshInfo()
    }

    private fun selected(): ControlButton? = editLayout.buttons.firstOrNull { it.id == editor.selectedId }

    private fun refreshInfo() {
        val sel = editor.selectedId
        info.text = when {
            sel == ControlsOverlayView.JOYSTICK_ID -> "Joystick · tamanho ${editLayout.joystickSize.toInt()}dp"
            selected() != null -> selected()!!.let { "${it.label} · ${it.action.describe()} · ${it.size.toInt()}dp" }
            else -> "Toque num botão para editar · ${editLayout.name}"
        }
        sizeBar.isEnabled = sel != null
        sizeBar.progress = when {
            sel == ControlsOverlayView.JOYSTICK_ID -> (editLayout.joystickSize / 2 - 30).toInt()
            selected() != null -> (selected()!!.size - 30).toInt()
            else -> 26
        }
    }

    private fun addButton() {
        val b = ControlButton(
            id = "b" + UUID.randomUUID().toString().take(6), label = "F", x = 0.5f, y = 0.5f, size = 52f,
            action = ControlAction.key("f"), round = true,
        )
        editLayout = editLayout.copy(buttons = editLayout.buttons + b)
        editor.layout = editLayout
        editor.selectedId = b.id
        dirty = true
        editButton(b)
    }

    private fun editSelected() {
        val b = selected() ?: return toast("Escolha um botão primeiro")
        editButton(b)
    }

    private fun editButton(b: ControlButton) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val label = EditText(this).apply { hint = "Rótulo (texto ou emoji)"; setText(b.label) }
        val types = ControlAction.Type.entries.toTypedArray()
        val typeNames = arrayOf("Tecla", "Mouse", "Controle (pad)", "Comando da API", "Especial")
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, typeNames)
            setSelection(types.indexOf(b.action.type))
        }
        val value = EditText(this).apply { hint = "Valor"; setText(b.action.value) }
        val release = EditText(this).apply { hint = "Comando ao soltar (opcional)"; setText(b.action.release) }
        val help = TextView(this).apply { textSize = 12f }
        fun updateHelp() {
            help.text = when (types[type.selectedItemPosition]) {
                ControlAction.Type.KEY -> "Teclas: " + ControlAction.KEYS.joinToString(" ")
                ControlAction.Type.MOUSE -> "left, right ou middle"
                ControlAction.Type.PAD -> ControlAction.PAD_BUTTONS.joinToString(" ")
                ControlAction.Type.COMMAND -> "Qualquer comando do horizon-ctl: chat /time set day, give torch 64, key f2 tap, drop all…"
                ControlAction.Type.SPECIAL -> ControlAction.SPECIALS.joinToString("\n") { "${it.first}: ${it.second}" }
            }
        }
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = updateHelp()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        updateHelp()
        val toggle = CheckBox(this).apply { text = "Fica preso (liga/desliga)"; isChecked = b.toggle }
        val round = CheckBox(this).apply { text = "Redondo"; isChecked = b.round }
        val visNames = arrayOf("Só jogando", "Só em menus", "Sempre")
        val vis = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, visNames)
            setSelection(b.visibility.ordinal)
        }
        listOf(label, type, value, release, help, toggle, round, vis).forEach { box.addView(it) }
        AlertDialog.Builder(this)
            .setTitle("Botão")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("OK") { _, _ ->
                val updated = b.copy(
                    label = label.text.toString().ifBlank { "?" }.take(6),
                    action = ControlAction(types[type.selectedItemPosition], value.text.toString().trim(), release.text.toString().trim()),
                    toggle = toggle.isChecked,
                    round = round.isChecked,
                    visibility = Visibility.entries[vis.selectedItemPosition],
                )
                editLayout = editLayout.copy(buttons = editLayout.buttons.map { if (it.id == b.id) updated else it })
                editor.layout = editLayout; dirty = true; refreshInfo()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteSelected() {
        val b = selected() ?: return toast("Escolha um botão primeiro")
        editLayout = editLayout.copy(buttons = editLayout.buttons.filter { it.id != b.id })
        editor.layout = editLayout; editor.selectedId = null; dirty = true; refreshInfo()
    }

    private fun rename() {
        val field = EditText(this).apply { setText(editLayout.name) }
        AlertDialog.Builder(this).setTitle("Nome do editLayout").setView(field)
            .setPositiveButton("OK") { _, _ ->
                editLayout = editLayout.copy(name = field.text.toString().ifBlank { editLayout.name }); dirty = true; refreshInfo()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun restore() {
        AlertDialog.Builder(this).setTitle("Restaurar o editLayout clássico?")
            .setPositiveButton("Restaurar") { _, _ ->
                editLayout = DefaultLayouts.classic().copy(id = editLayout.id, name = editLayout.name)
                editor.layout = editLayout; editor.selectedId = null; dirty = true; refreshInfo()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun save(finishAfter: Boolean) {
        LayoutStore.save(editLayout)
        LauncherSettings.get(this).activeLayout = editLayout.id
        dirty = false
        toast("Layout \"${editLayout.name}\" salvo e ativado")
        if (finishAfter) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!dirty) return super.onBackPressed()
        AlertDialog.Builder(this).setTitle("Salvar as mudanças?")
            .setPositiveButton("Salvar") { _, _ -> save(finishAfter = true) }
            .setNegativeButton("Descartar") { _, _ -> finish() }
            .setNeutralButton("Continuar editando", null).show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_LAYOUT = "editLayout"
    }
}
