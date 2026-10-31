package com.ppss55

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile

/**
 * Loading card shown over the game until it presents its first frame. The step text is *real*: it
 * follows markers the core writes to the session log (module relocation and start, Vulkan swapchain)
 * and the card disappears when the core's window title reports frame >= 1 (window.cpp
 * "frame: N, fps: F"). Nothing is simulated; without the detailed log (off by default) only the
 * generic first step and the elapsed time are shown.
 */
class LaunchOverlay(private val activity: Activity, private val log: File?, gamePath: String?, title: String) {

    private enum class Step(val text: String, val marker: String?) {
        CORE("Carregando o jogo (núcleo, módulos e GPU)…", null),
        MODULES("Carregando e ligando os módulos do jogo…", "--- Relocate program"),
        START("Iniciando as bibliotecas do jogo…", "--- Start module"),
        GPU("Preparando a GPU (Vulkan) e a tela…", "Vulkan presentation:"),
        FIRST_FRAME("Aguardando o primeiro quadro (compilando shaders)…", "Equeue wait: Flip"),
    }

    private val handler = Handler(Looper.getMainLooper())
    private var step = Step.CORE
    private var done = false
    private val startedAt = System.currentTimeMillis()
    private val stepText = TextView(activity).apply { setTextColor(Ui.DIM); textSize = 14f; text = step.text }
    private val timeText = TextView(activity).apply { setTextColor(Ui.DIM); textSize = 12f }

    val view: View = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true // swallow touches while loading
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                cornerRadius = Ui.dp(activity, 28).toFloat()
                setStroke(Ui.dp(activity, 1), Ui.STROKE)
            }
            clipToOutline = true
            iconFor(gamePath)?.let { bmp ->
                addView(ImageView(activity).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(Ui.dp(activity, 150), Ui.dp(activity, 150)))
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val p = Ui.dp(activity, 22)
                setPadding(p, p, p, p)
                addView(TextView(activity).apply { text = title; setTextColor(Ui.TEXT); textSize = 22f })
                addView(stepText)
                addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                }, LinearLayout.LayoutParams(Ui.dp(activity, 380), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(timeText)
            })
        }
        addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            readStep()?.let { if (it.ordinal > step.ordinal) step = it }
            stepText.text = step.text
            timeText.text = "${(System.currentTimeMillis() - startedAt) / 1000} s"
            if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) finish() else handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() = handler.post(poll)

    /** From GameActivity.onTitleChanged: the core's title carries "frame: N". */
    fun onTitle(title: CharSequence?) {
        val frame = title?.let { FRAME.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: return
        if (frame >= 1) finish()
    }

    fun finish() {
        if (done) return
        done = true
        handler.removeCallbacks(poll)
        view.animate().alpha(0f).setDuration(350).withEndAction { view.visibility = View.GONE }.start()
    }

    /** Latest marker in the last 256 KB of the session log. */
    private fun readStep(): Step? {
        val file = log ?: return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val from = maxOf(0L, len - TAIL_BYTES)
                val buf = ByteArray((len - from).toInt())
                raf.seek(from)
                raf.readFully(buf)
                val text = String(buf, Charsets.ISO_8859_1)
                Step.entries.lastOrNull { s -> s.marker != null && text.contains(s.marker) }
            }
        }.getOrNull()
    }

    private fun iconFor(gamePath: String?): android.graphics.Bitmap? {
        val icon = gamePath?.let { File(it, "sce_sys/icon0.png") }?.takeIf { it.isFile } ?: return null
        return runCatching {
            BitmapFactory.decodeFile(icon.path, BitmapFactory.Options().apply { inSampleSize = 2 })
        }.getOrNull()
    }

    private companion object {
        val FRAME = Regex("frame: (\\d+)")
        const val POLL_MS = 250L
        const val TAIL_BYTES = 256 * 1024L
        const val TIMEOUT_MS = 180_000L
    }
}
