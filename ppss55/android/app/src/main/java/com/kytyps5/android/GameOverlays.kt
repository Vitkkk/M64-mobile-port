package com.kytyps5.android

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.InputDevice
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.kytyps5.android.hud.PerformanceHud
import com.kytyps5.android.input.ControllerEditor
import com.kytyps5.android.input.GamepadBridge
import com.kytyps5.android.input.OverlayControl
import com.kytyps5.android.input.OverlayProfileStore
import com.kytyps5.android.input.SdlPadSink
import com.kytyps5.android.input.TouchControllerView
import java.io.File
import org.libsdl.app.KytyVirtualPad

/**
 * Everything GameActivity draws over the SDL surface: the touch controller, its layout editor, the
 * performance HUD and a small menu button to toggle them. The container itself never consumes
 * touches, so with the controller hidden SDL receives them as before (tap = Cross).
 */
class GameOverlays(
    private val activity: Activity,
    private val gamePath: String?,
    touchControls: Boolean,
    hud: Boolean,
    /** The SDL surface the game renders to (for SHARE screenshots); null before SDL creates it. */
    private val surface: () -> SurfaceView? = { null },
) {
    private val density = activity.resources.displayMetrics.density
    private val Int.dp: Int get() = (this * density).toInt()
    private val bridge = GamepadBridge(SdlPadSink)
    private val store = OverlayProfileStore(File(activity.filesDir, OverlayProfileStore.DIRECTORY_NAME))
    private val controller = TouchControllerView(activity) { bridge.update(it) }.apply {
        onSystemAction = { control ->
            when (control) {
                OverlayControl.PS -> showMenu(this)
                OverlayControl.SHARE -> Screenshot.capture(activity, surface())
                else -> Unit
            }
        }
    }
    private val editor = ControllerEditor(activity, controller, store, gamePath) { updateMenuButton() }
    private val performanceHud = PerformanceHud(activity)
    private var hudWanted = hud
    private val menuButton = TextView(activity).apply {
        text = "⋯"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        alpha = 0.7f
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(120, 0, 0, 0))
        }
        setOnClickListener { showMenu(it) }
    }

    val root: View = FrameLayout(activity).apply {
        addView(controller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Top-left corner just below L2/L1: game titles and HUDs are usually centred at the top.
        addView(performanceHud.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            val metrics = activity.resources.displayMetrics
            topMargin = (minOf(metrics.widthPixels, metrics.heightPixels) * HUD_TOP_FRACTION).toInt()
            leftMargin = 12.dp
        })
        addView(editor.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = 8.dp
        })
        addView(menuButton, FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = 6.dp
        })
    }

    init {
        KytyVirtualPad.install()
        controller.layout = store.load(gamePath).layout
        setControllerVisible(touchControls && !hasPhysicalGamepad())
        updateMenuButton()
        performanceHud.view.visibility = if (hudWanted) View.VISIBLE else View.GONE
    }

    fun onResume() {
        if (hudWanted) performanceHud.start()
    }

    fun onPause() {
        controller.releaseAll()
        performanceHud.stop()
    }

    fun onTitle(title: CharSequence?) = performanceHud.onTitle(title)

    private fun setControllerVisible(visible: Boolean) {
        controller.releaseAll()
        controller.visibility = if (visible) View.VISIBLE else View.GONE
        // Once SDL knows the virtual pad it stays connected; hiding only stops input.
        if (visible) KytyVirtualPad.setWanted(true)
        updateMenuButton()
    }

    /** The on-screen PS button opens the menu; the small menu button is only needed without it. */
    private fun updateMenuButton() {
        val psShown = controller.visibility == View.VISIBLE && controller.layout.placement(OverlayControl.PS).visible
        menuButton.visibility = if (psShown || editor.isOpen) View.GONE else View.VISIBLE
    }

    private fun setHudVisible(visible: Boolean) {
        hudWanted = visible
        performanceHud.view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) performanceHud.start() else performanceHud.stop()
    }

    private fun showMenu(anchor: View) {
        val controlsShown = controller.visibility == View.VISIBLE
        PopupMenu(activity, anchor, Gravity.CENTER).apply {
            menu.add(0, MENU_CONTROLS, 0, if (controlsShown) "Esconder controles virtuais" else "Mostrar controles virtuais")
            menu.add(0, MENU_EDIT, 1, "Editar layout dos controles…")
            menu.add(0, MENU_HUD, 2, if (hudWanted) "Esconder FPS / desempenho" else "Mostrar FPS / desempenho")
            menu.add(0, MENU_SCREENSHOT, 3, "Capturar tela")
            menu.add(0, MENU_EXIT, 4, "Encerrar jogo")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CONTROLS -> setControllerVisible(!controlsShown)
                    MENU_EDIT -> {
                        setControllerVisible(true)
                        menuButton.visibility = View.GONE
                        editor.open()
                    }
                    MENU_HUD -> setHudVisible(!hudWanted)
                    MENU_SCREENSHOT -> Screenshot.capture(activity, surface())
                    // The game runs in its own process (":game"): ending it cannot take the launcher down.
                    MENU_EXIT -> {
                        activity.finishAndRemoveTask()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
                true
            }
        }.show()
    }

    /** A real controller makes the touch overlay redundant; it can still be shown from the menu. */
    private fun hasPhysicalGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        !device.isVirtual && device.supportsSource(InputDevice.SOURCE_GAMEPAD)
    }

    private companion object {
        const val MENU_CONTROLS = 1
        const val MENU_EDIT = 2
        const val MENU_HUD = 3
        const val MENU_SCREENSHOT = 4
        const val MENU_EXIT = 5
        const val HUD_TOP_FRACTION = 0.13f
    }
}
