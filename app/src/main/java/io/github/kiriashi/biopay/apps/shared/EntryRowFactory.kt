/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.shared

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kiriashi.biopay.BuildConfig
import io.github.kiriashi.biopay.core.util.dp
import io.github.kiriashi.biopay.settings.ui.Theme

internal class EntryRowFactory(private val onClick: (Activity) -> Unit) {
    internal fun createRow(
        activity: Activity,
        label: String,
        height: Int,
        horizontalPadding: Int,
        rounded: Boolean,
        styleAnchor: View? = null,
        icon: Boolean = false,
        rightPadding: Int = if (horizontalPadding == 0) 18 else horizontalPadding
    ): LinearLayout {
        val colors = Theme.colors(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = ENTRY_TAG
            isClickable = true
            setOnClickListener { onClick(activity) }
            minimumHeight = activity.dp(height)
            val normal = if (Theme.isDark(activity)) 0xFF191919.toInt() else Color.WHITE
            val pressed = if (Theme.isDark(activity)) 0xFF3E3E3E.toInt() else if (rounded) 0xFFEBEBEB.toInt() else 0xFFD9D9D9.toInt()
            setBackground(makePressedBackground(rounded, normal, pressed))
        }
        if (icon) {
            row.addView(FingerprintGlyph(activity, colors.primary), LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)).apply {
                leftMargin = activity.dp(12)
            })
        }
        row.addView(TextView(activity).apply {
            text = label
            textSize = (styleAnchor as? TextView)?.textSize?.let { it.toSp(activity) } ?: 16f
            setTextColor((styleAnchor as? TextView)?.currentTextColor ?: colors.onSurface)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(if (icon) 12 else horizontalPadding), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(TextView(activity).apply {
            text = BuildConfig.VERSION_NAME
            textSize = 12f
            setTextColor(if (Theme.isDark(activity)) 0xFF888888.toInt() else 0xFF999999.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, activity.dp(rightPadding), 0)
        })
        return row
    }

    private fun makePressedBackground(rounded: Boolean, normal: Int, pressed: Int): Drawable {
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            // Match the rounded surface used by Alipay's native settings rows.
            cornerRadius = if (rounded) 32f else 0f
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressed))
            addState(intArrayOf(), shape(normal))
        }
    }

    private class FingerprintGlyph(activity: Activity, color: Int) : View(activity) {
        private val density = activity.resources.displayMetrics.density
        private val paths = Array(4) { Path() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1f
            this.color = color
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val cx = w / density / 2f
            val cy = h / density / 2f
            paths.forEach(Path::rewind)
            paths[0].apply { moveTo(cx - 6, cy + 6); cubicTo(cx - 8, cy - 7, cx + 8, cy - 7, cx + 6, cy + 6) }
            paths[1].apply { moveTo(cx - 3, cy + 8); cubicTo(cx - 5, cy - 2, cx + 5, cy - 2, cx + 3, cy + 8) }
            paths[2].apply { moveTo(cx, cy - 4); cubicTo(cx + 2, cy - 1, cx + 1, cy + 2, cx + 2, cy + 5) }
            paths[3].apply { moveTo(cx - 9, cy + 1); cubicTo(cx - 8, cy - 11, cx + 8, cy - 11, cx + 9, cy + 1) }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.scale(density, density)
            paths.forEach { canvas.drawPath(it, paint) }
            canvas.restore()
        }
    }

}

internal fun Float.toSp(activity: Activity): Float {
    val scale = activity.resources.displayMetrics.density * activity.resources.configuration.fontScale
    return if (scale > 0f) this / scale else this
}
