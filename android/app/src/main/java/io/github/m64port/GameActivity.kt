package io.github.m64port

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Tela do jogo, num processo próprio (:game). Recompila a ROM, roda o jogo
 * sobre o runtime e desenha o controle por toque por cima da superfície de
 * vídeo (destino do renderizador).
 */
class GameActivity : Activity() {
    private lateinit var info: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var summary = ""
    private var inputText = ""
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.addView(SurfaceView(this).apply { setBackgroundColor(Color.BLACK) })

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            text = "Recompilando para código nativo…"
        }
        root.addView(info)

        root.addView(TouchControllerView(this).apply {
            onStateChanged = { buttons, x, y ->
                NativeBridge.setController(0, buttons, x, y)
                inputText = "botões=0x%04X  analógico=(%d, %d)".format(buttons, x, y)
            }
        })

        setContentView(root)
        hideSystemBars()
        startGame()
    }

    private fun startGame() {
        val rom = File(intent.getStringExtra(EXTRA_ROM) ?: return finish())
        val syms = intent.getStringExtra(EXTRA_SYMS) ?: return finish()
        thread(name = "m64-game") {
            val prepared = NativeBridge.prepareGame(rom.absolutePath, syms)
            if (!prepared.ok) {
                ui.post { info.text = "Falha na recompilação:\n${prepared.message}" }
                return@thread
            }
            summary = prepared.message
            running = true
            ui.post(::refresh)
            // A pasta da ROM também guarda saves e configuração do runtime.
            val error = NativeBridge.runGame(rom.parentFile!!.absolutePath)
            running = false
            ui.post { info.text = if (error != null) "Erro no runtime:\n$error" else "Jogo encerrado." }
        }
    }

    private fun refresh() {
        if (!running) return
        info.text = "$summary\n\n${getString(R.string.runtime_running)}\n\n" +
            "quadros: ${NativeBridge.frameCount()}\n$inputText"
        ui.postDelayed(::refresh, 250)
    }

    private fun hideSystemBars() {
        window.decorView.post {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } ?: run {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.setController(0, 0, 0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // O runtime não pode ser reiniciado no mesmo processo: encerra o
        // processo do jogo; a próxima partida abre um novo.
        if (isFinishing) {
            NativeBridge.stopGame()
            exitProcess(0)
        }
    }

    companion object {
        const val EXTRA_ROM = "rom"
        const val EXTRA_SYMS = "syms"
    }
}
