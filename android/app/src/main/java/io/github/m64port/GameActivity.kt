package io.github.m64port

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Tela do jogo: superfície de vídeo (destino do renderizador) + controle por
 * toque por cima.
 */
class GameActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.addView(SurfaceView(this).apply { setBackgroundColor(Color.BLACK) })

        val info = TextView(this).apply {
            setTextColor(Color.WHITE)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty()
        info.text = "$summary\n\n${getString(R.string.runtime_pending)}"
        root.addView(info)

        root.addView(TouchControllerView(this).apply {
            onStateChanged = { buttons, x, y ->
                NativeBridge.setController(0, buttons, x, y)
                info.text = "$summary\n\n${getString(R.string.runtime_pending)}\n\n" +
                    "botões=0x%04X  analógico=(%d, %d)".format(buttons, x, y)
            }
        })

        setContentView(root)
        hideSystemBars()
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

    companion object {
        const val EXTRA_SUMMARY = "summary"
    }
}
