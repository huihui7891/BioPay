/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.kiriashi.biopay.apps.shared.MaskLayout
import io.github.kiriashi.biopay.apps.shared.PaymentViewTree

/** Covers only password controls; the overlay never changes the host view hierarchy. */
internal class PaymentMask(
    private val layout: MaskLayout,
    private val isCurrent: () -> Boolean,
    private val onClose: (PaymentMask) -> Unit
) : Drawable(), ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val host = layout.host
    private val keyboard = layout.keyboard
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 15f, host.resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
    }
    private var backgroundColor = fallbackColor()
    private var colorLeaves: List<View> = emptyList()
    private var lastColorTime = 0L
    private var lastNightMode = -1
    private var samplePixel: Bitmap? = null
    private var sampleCanvas: Canvas? = null
    private val panel = Rect()
    private val previousPanel = Rect()
    private val targetBounds = Rect()
    private val handler = Handler(Looper.getMainLooper())
    private val observer = host.viewTreeObserver
    private var drawableAlpha = 255
    private var closed = false
    private var submitted = false
    private val timeout = Runnable { close() }
    private val position = IntArray(2)

    fun attach() {
        if (closed) return
        setBounds(0, 0, host.width, host.height)
        updateRegions()
        if (panel.isEmpty) {
            close()
            return
        }
        refreshColor(rebuild = true)
        host.overlay.add(this)
        observer.addOnPreDrawListener(this)
        host.addOnAttachStateChangeListener(this)
        keyboard.addOnAttachStateChangeListener(this)
        // Bound retention even when an app keeps its old payment views alive.
        handler.postDelayed(timeout, MAX_DURATION_MS)
    }

    fun finish() {
        submitted = true
        if (!keyboard.isAttachedToWindow || !keyboard.isShown) close()
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
        host.overlay.remove(this)
        host.removeOnAttachStateChangeListener(this)
        keyboard.removeOnAttachStateChangeListener(this)
        panel.setEmpty()
        colorLeaves = emptyList()
        samplePixel?.recycle()
        samplePixel = null
        sampleCanvas = null
        onClose(this)
    }

    override fun onViewAttachedToWindow(view: View) {}

    override fun onViewDetachedFromWindow(view: View) = close()

    fun blocksTouch(root: View, x: Float, y: Float): Boolean {
        if (closed || root !== host.rootView || !isCurrent()) return false
        host.getLocationOnScreen(position)
        if (!panel.contains((x - position[0]).toInt(), (y - position[1]).toInt())) return false
        if (submitted) {
            // A manual retry must reveal the controls before accepting its touch.
            close()
            return false
        }
        return true
    }

    override fun onPreDraw(): Boolean {
        if (closed) return true
        val passwordGone = layout.password?.let { !it.isAttachedToWindow || !it.isShown } == true
        if (!isCurrent() || !host.isAttachedToWindow || !keyboard.isAttachedToWindow ||
            (submitted && (!keyboard.isShown || passwordGone))) {
            close()
            return true
        }
        setBounds(0, 0, host.width, host.height)
        previousPanel.set(panel)
        updateRegions()
        if (panel.isEmpty) close()
        else {
            val moved = previousPanel != panel
            val recolored = refreshColor(rebuild = moved)
            if (moved || recolored) invalidateSelf()
        }
        return true
    }

    private fun updateRegions() {
        panel.setEmpty()
        host.getLocationOnScreen(position)
        for (view in layout.targets) {
            if (!view.isAttachedToWindow || !view.getGlobalVisibleRect(targetBounds)) continue
            targetBounds.offset(-position[0], -position[1])
            if (targetBounds.intersect(0, 0, host.width, host.height)) panel.union(targetBounds)
        }
        val extraVisible = layout.extraRegion?.invoke(targetBounds)
        if (submitted && extraVisible == false) {
            panel.setEmpty()
            return
        }
        if (extraVisible == true) {
            targetBounds.offset(-position[0], -position[1])
            if (targetBounds.intersect(0, 0, host.width, host.height)) panel.union(targetBounds)
        }
        if (!panel.isEmpty && layout.fillWidth) {
            panel.left = 0
            panel.right = host.width
        }
    }

    private fun fallbackColor(): Int = if (nightMode() == Configuration.UI_MODE_NIGHT_YES) {
        Color.rgb(25, 25, 25)
    } else if (layout.extraRegion != null) Color.WHITE else Color.rgb(245, 245, 245)

    private fun nightMode(): Int = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    private fun refreshColor(rebuild: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val mode = nightMode()
        if (rebuild || mode != lastNightMode || colorLeaves.any { !it.isAttachedToWindow || !it.isShown }) {
            colorLeaves = emptyList()
        }
        if (mode == lastNightMode && now - lastColorTime < COLOR_INTERVAL_MS) return false
        lastColorTime = now
        lastNightMode = mode
        val color = surroundingColor() ?: keyboardColor() ?: fallbackColor()
        if (color == backgroundColor) return false
        backgroundColor = color
        return true
    }

    private fun surroundingColor(): Int? {
        val source = layout.colorSource
        if (source != null) {
            if (source.isShown && source.getGlobalVisibleRect(targetBounds)) {
                sampleBackground(source, targetBounds.centerX(), targetBounds.centerY())?.let { return it }
            }
            if (keyboard.getGlobalVisibleRect(targetBounds)) {
                sampleBackground(keyboard, targetBounds.centerX(), targetBounds.centerY())?.let { return it }
            }
            return null
        }
        val field = layout.password ?: return hostColor()
        val fieldRect = Rect()
        if (!field.getGlobalVisibleRect(fieldRect)) return hostColor()
        host.getLocationOnScreen(position)
        // Sample margins outside the password cells to preserve the panel palette.
        val points = listOf(
            position[0] + ((fieldRect.left - position[0]) / 2).coerceAtLeast(1) to fieldRect.centerY(),
            position[0] + host.width -
                ((position[0] + host.width - fieldRect.right) / 2).coerceAtLeast(1) - 1 to fieldRect.centerY(),
            position[0] + panel.centerX() to position[1] + panel.top - 1
        )
        if (colorLeaves.isEmpty()) {
            val tree = PaymentViewTree(host)
            if (!tree.complete) return hostColor()
            colorLeaves = points.map { (x, y) ->
                val bounds = Rect()
                var leaf: View = host
                var deepest = -1
                for (view in tree.all) {
                    if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) continue
                    var depth = 0
                    var current: View? = view
                    while (current != null) {
                        depth++
                        current = current.parent as? View
                    }
                    if (depth > deepest) {
                        leaf = view
                        deepest = depth
                    }
                }
                leaf
            }
        }
        val colors = colorLeaves.zip(points).mapNotNull { (leaf, point) ->
            sampleBackground(leaf, point.first, point.second)
        }
        return colors.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: hostColor()
    }

    private fun hostColor(): Int? {
        if (!host.getGlobalVisibleRect(targetBounds)) return null
        return sampleBackground(host, targetBounds.centerX(), targetBounds.centerY())
    }

    private fun keyboardColor(): Int? {
        if (!keyboard.getGlobalVisibleRect(targetBounds)) return null
        return sampleBackground(keyboard, targetBounds.centerX(), targetBounds.centerY())
    }

    private fun sampleBackground(leaf: View, x: Int, y: Int): Int? {
        val pixel = samplePixel ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also {
            samplePixel = it
            sampleCanvas = Canvas(it)
        }
        val canvas = requireNotNull(sampleCanvas)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val location = IntArray(2)
        try {
            val ancestry = generateSequence(leaf) { it.parent as? View }.toList().asReversed()
            var opacity = 1f
            for (view in ancestry) {
                opacity *= view.alpha
                val background = view.background ?: continue
                view.getLocationOnScreen(location)
                val save = canvas.save()
                try {
                    canvas.translate((location[0] - x).toFloat(), (location[1] - y).toFloat())
                    val layer = canvas.saveLayerAlpha(
                        background.bounds.left.toFloat(), background.bounds.top.toFloat(),
                        background.bounds.right.toFloat(), background.bounds.bottom.toFloat(),
                        (opacity * 255).toInt().coerceIn(0, 255)
                    )
                    background.draw(canvas)
                    canvas.restoreToCount(layer)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
            val color = pixel.getPixel(0, 0)
            return color.takeIf { Color.alpha(it) == 255 }
        } catch (_: RuntimeException) {
            return null
        }
    }

    override fun draw(canvas: Canvas) {
        paint.color = backgroundColor
        paint.alpha = drawableAlpha
        if (panel.isEmpty) return
        canvas.drawRect(panel, paint)
        val luminance = (Color.red(backgroundColor) * 299 +
            Color.green(backgroundColor) * 587 + Color.blue(backgroundColor) * 114) / 1000
        paint.color = if (luminance < 128) Color.LTGRAY else Color.rgb(110, 110, 110)
        paint.alpha = drawableAlpha
        canvas.drawText("正在处理…", panel.exactCenterX(),
            panel.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val MAX_DURATION_MS = 5_000L
        const val COLOR_INTERVAL_MS = 250L
    }
}
