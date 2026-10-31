package com.kytyps5.android

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SHARE button: copies the frame currently on the game's surface (what the emulator presented, read
 * back with PixelCopy) to a PNG in the app's external pictures folder. Nothing is captured when the
 * surface has no frame yet.
 */
object Screenshot {
    const val DIRECTORY = "Screenshots"

    fun directory(activity: Activity): File =
        File(activity.getExternalFilesDir(null) ?: activity.filesDir, DIRECTORY)

    fun capture(activity: Activity, surface: SurfaceView?) {
        val view = surface
        if (view == null || view.width == 0 || view.height == 0 || !view.holder.surface.isValid) {
            Toast.makeText(activity, "Ainda não há imagem para capturar.", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val thread = HandlerThread("ppss55-screenshot").apply { start() }
        PixelCopy.request(view, bitmap, { result ->
            val message = if (result == PixelCopy.SUCCESS) {
                runCatching { save(activity, bitmap) }.fold(
                    onSuccess = { "Captura salva: ${it.path}" },
                    onFailure = { "Falha ao salvar a captura: ${it.message}" },
                )
            } else {
                "Falha na captura (PixelCopy código $result)."
            }
            bitmap.recycle()
            thread.quitSafely()
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
        }, Handler(thread.looper))
    }

    private fun save(activity: Activity, bitmap: Bitmap): File {
        val dir = directory(activity).apply { mkdirs() }
        val name = "PPSS55_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        return File(dir, name).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
