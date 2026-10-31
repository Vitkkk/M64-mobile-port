package com.ppss55

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Dark, minimal building blocks shared by the PPSS55 screens (no AndroidX, no XML layouts). */
object Ui {
    val BG = Color.rgb(7, 9, 14)
    val SURFACE = Color.rgb(17, 21, 30)
    val STROKE = Color.argb(70, 170, 200, 255)
    val ACCENT = Color.rgb(74, 156, 255)
    val TEXT = Color.rgb(232, 238, 248)
    val DIM = Color.rgb(138, 150, 170)
    val OK = Color.rgb(96, 214, 150)
    val WARN = Color.rgb(255, 196, 92)
    val ERR = Color.rgb(255, 110, 110)

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, 14)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = dp(context, 16).toFloat()
            setStroke(dp(context, 1), STROKE)
        }
    }

    fun text(context: Context, value: CharSequence, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(context, 2), 0, dp(context, 2))
        }

    /** Glass-outlined pill button, filled with the accent colour when [primary]. */
    fun button(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val h = dp(context, 16)
            val v = dp(context, 10)
            setPadding(h, v, h, v)
            val shape = GradientDrawable().apply {
                cornerRadius = dp(context, 22).toFloat()
                setColor(if (primary) ACCENT else Color.argb(40, 120, 150, 210))
                setStroke(dp(context, 1), if (primary) Color.argb(200, 190, 220, 255) else Color.argb(120, 200, 220, 255))
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(80, 120, 180, 255)), shape, null)
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun row(context: Context, vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(context, 8) }) }
    }

    fun spacer(context: Context, h: Int = 12): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, h))
    }
}
