/*
 * Normalised, immutable overlay layout with global + per-game scopes, adapted from Vita3K's Android
 * overlay (android/.../overlay/OverlayLayout.kt, OverlayStore.kt; Vita3K project, GPL-2.0).
 */
package com.kytyps5.android.input

import org.json.JSONException
import org.json.JSONObject

/** How a control is drawn and how touches on it are interpreted. */
enum class ControlKind { BUTTON, SHOULDER, TRIGGER, DPAD, STICK, PILL, SYSTEM, SMALL_PILL }

/**
 * Every on-screen control with its default centre (fractions of view width/height) and size
 * (fraction of the view's shorter side). Defaults target a landscape tablet.
 */
enum class OverlayControl(
    val kind: ControlKind,
    val label: String,
    val defaultX: Float,
    val defaultY: Float,
    val defaultSize: Float,
    val defaultVisible: Boolean = true,
) {
    // PPSS55 defaults follow the DualSense-style reference layout (20:9 phone in landscape) while
    // staying non-overlapping down to 4:3 (OverlayGeometryTest).
    DPAD(ControlKind.DPAD, "D-pad", 0.125f, 0.50f, 0.29f),
    LEFT_STICK(ControlKind.STICK, "Analógico esq.", 0.25f, 0.81f, 0.245f),
    RIGHT_STICK(ControlKind.STICK, "Analógico dir.", 0.735f, 0.81f, 0.245f),
    TRIANGLE(ControlKind.BUTTON, "Triângulo", 0.877f, 0.44f, 0.105f),
    CROSS(ControlKind.BUTTON, "Cruz", 0.877f, 0.67f, 0.105f),
    SQUARE(ControlKind.BUTTON, "Quadrado", 0.835f, 0.555f, 0.105f),
    CIRCLE(ControlKind.BUTTON, "Círculo", 0.919f, 0.555f, 0.105f),
    L1(ControlKind.SHOULDER, "L1", 0.125f, 0.26f, 0.14f),
    L2(ControlKind.TRIGGER, "L2", 0.085f, 0.13f, 0.14f),
    R1(ControlKind.SHOULDER, "R1", 0.875f, 0.26f, 0.14f),
    R2(ControlKind.TRIGGER, "R2", 0.915f, 0.13f, 0.14f),
    L3(ControlKind.BUTTON, "L3", 0.40f, 0.72f, 0.08f, defaultVisible = false),
    R3(ControlKind.BUTTON, "R3", 0.60f, 0.72f, 0.08f, defaultVisible = false),
    SHARE(ControlKind.SMALL_PILL, "SHARE", 0.39f, 0.91f, 0.075f),
    PS(ControlKind.SYSTEM, "PS", 0.50f, 0.905f, 0.085f),
    OPTIONS(ControlKind.SMALL_PILL, "OPTIONS", 0.61f, 0.91f, 0.075f),
    TOUCHPAD(ControlKind.PILL, "TOUCH PAD", 0.50f, 0.07f, 0.08f),
    ;

    /** The pad button this control presses (sticks/D-pad report through axes and directions). */
    val button: PadButton?
        get() = when (this) {
            TRIANGLE -> PadButton.TRIANGLE
            CROSS -> PadButton.CROSS
            SQUARE -> PadButton.SQUARE
            CIRCLE -> PadButton.CIRCLE
            L1 -> PadButton.L1
            R1 -> PadButton.R1
            L3 -> PadButton.L3
            R3 -> PadButton.R3
            OPTIONS -> PadButton.OPTIONS
            TOUCHPAD -> PadButton.TOUCHPAD
            else -> null
        }

    val trigger: PadAxis?
        get() = when (this) {
            L2 -> PadAxis.L2
            R2 -> PadAxis.R2
            else -> null
        }

    /**
     * PS and SHARE have no guest pad bit (on a real PS5 both are system buttons that games never
     * see): PS opens the emulator menu and SHARE takes a screenshot (see GameOverlays).
     */
    val isSystemAction: Boolean get() = this == PS || this == SHARE

    val defaultPlacement: ControlPlacement get() = ControlPlacement(defaultX, defaultY, 1f, defaultVisible)
}

data class ControlPlacement(val x: Float, val y: Float, val scale: Float, val visible: Boolean) {
    fun normalized(): ControlPlacement = copy(
        x = x.finiteIn(0f, 1f, 0.5f),
        y = y.finiteIn(0f, 1f, 0.5f),
        scale = scale.finiteIn(MIN_SCALE, MAX_SCALE, 1f),
    )

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.5f
    }
}

data class OverlayLayout(
    val placements: Map<OverlayControl, ControlPlacement> = emptyMap(),
    val opacity: Float = DEFAULT_OPACITY,
) {
    fun placement(control: OverlayControl): ControlPlacement = placements[control] ?: control.defaultPlacement

    fun with(control: OverlayControl, placement: ControlPlacement): OverlayLayout =
        copy(placements = placements + (control to placement.normalized()))

    fun withOpacity(value: Float): OverlayLayout = copy(opacity = value.finiteIn(MIN_OPACITY, 1f, DEFAULT_OPACITY))

    fun toJson(): String {
        val controls = JSONObject()
        placements.forEach { (control, p) ->
            controls.put(
                control.name,
                JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble())
                    .put("scale", p.scale.toDouble()).put("visible", p.visible),
            )
        }
        return JSONObject().put("version", VERSION).put("opacity", opacity.toDouble()).put("controls", controls).toString()
    }

    companion object {
        const val VERSION = 1
        const val DEFAULT_OPACITY = 0.85f
        /** 0% keeps the controls working but invisible (requested: opacity 0-100%). */
        const val MIN_OPACITY = 0f
        val DEFAULT = OverlayLayout()

        /** Parses [toJson] output; unknown controls are skipped, bad values clamped, garbage -> null. */
        fun fromJson(json: String): OverlayLayout? = try {
            val root = JSONObject(json)
            val controls = root.optJSONObject("controls") ?: JSONObject()
            val placements = OverlayControl.entries.mapNotNull { control ->
                val obj = controls.optJSONObject(control.name) ?: return@mapNotNull null
                control to ControlPlacement(
                    x = obj.optDouble("x", control.defaultX.toDouble()).toFloat(),
                    y = obj.optDouble("y", control.defaultY.toDouble()).toFloat(),
                    scale = obj.optDouble("scale", 1.0).toFloat(),
                    visible = obj.optBoolean("visible", control.defaultVisible),
                ).normalized()
            }.toMap()
            OverlayLayout(placements).withOpacity(root.optDouble("opacity", DEFAULT_OPACITY.toDouble()).toFloat())
        } catch (e: JSONException) {
            null
        }
    }
}

private fun Float.finiteIn(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback
