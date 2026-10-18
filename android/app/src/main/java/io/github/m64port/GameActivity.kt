package io.github.m64port

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
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
    private var inputText = ""
    private var running = false
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        val video = SurfaceView(this)
        root.addView(video)

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

        // O RT64 precisa da superfície pronta; o jogo começa quando ela existe.
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!started) {
                    started = true
                    startGame(holder)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun startGame(holder: SurfaceHolder) {
        NativeBridge.startLogCapture(logFile(this).absolutePath)
        val rom = File(intent.getStringExtra(EXTRA_ROM) ?: return finish())
        val syms = intent.getStringExtra(EXTRA_SYMS) ?: return finish()
        thread(name = "m64-game") {
            val prepared = NativeBridge.prepareGame(rom.absolutePath, syms)
            if (!prepared.ok) {
                ui.post { info.text = "Falha na recompilação:\n${prepared.message}" }
                return@thread
            }
            running = true
            // Mostra o resultado da recompilação por alguns segundos e depois só o contador.
            ui.post { info.text = "${prepared.message}\n${getString(R.string.runtime_running)}" }
            ui.postDelayed(::refresh, 4000)
            // A pasta da ROM também guarda saves e configuração do runtime.
            val error = NativeBridge.runGame(rom.parentFile!!.absolutePath, holder.surface)
            running = false
            ui.post { info.text = if (error != null) "Erro no runtime:\n$error" else "Jogo encerrado." }
        }
    }

    private fun refresh() {
        if (!running) return
        // Uma linha discreta no canto, para não cobrir a imagem do jogo.
        info.textSize = 11f
        info.alpha = 0.6f
        info.text = "quadros: ${NativeBridge.frameCount()}  $inputText\n" +
            NativeBridge.videoStatus() + "\n" + NativeBridge.recentLog(5)
        ui.postDelayed(::refresh, 500)
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

        fun logFile(context: android.content.Context) = File(context.filesDir, "ultimo_log.txt")

        fun copyLog(context: android.content.Context, text: String) {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("log do M64 Mobile Port", text))
            android.widget.Toast.makeText(context, "Log copiado", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
