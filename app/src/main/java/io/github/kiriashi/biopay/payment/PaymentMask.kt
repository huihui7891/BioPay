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
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.kiriashi.biopay.apps.shared.MaskLayout

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
    private val colorLeaves = arrayOfNulls<View>(3)
    private val colorDepths = IntArray(3)
    private val colorPoints = IntArray(6)
    private val sampledColors = IntArray(3)
    private val sampleAncestry = ArrayList<View>()
    private val sampleLocation = IntArray(2)
    private val fieldBounds = Rect()
    private var colorInitialized = false
    private var colorRefreshReady = false
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
    private val colorRefresh = Runnable {
        if (!closed) {
            colorRefreshReady = true
            invalidateSelf()
        }
    }
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
        handler.removeCallbacks(colorRefresh)
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
        host.overlay.remove(this)
        host.removeOnAttachStateChangeListener(this)
        keyboard.removeOnAttachStateChangeListener(this)
        panel.setEmpty()
        colorLeaves.fill(null)
        sampleAncestry.clear()
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
        val mode = nightMode()
        val themeChanged = mode != lastNightMode
        var leafInvalid = false
        for (leaf in colorLeaves) {
            if (leaf != null && (!leaf.isAttachedToWindow || !leaf.isShown)) {
                leafInvalid = true
                break
            }
        }
        if (rebuild || themeChanged || leafInvalid) {
            colorLeaves.fill(null)
            // Sample once more when layout/theme transitions have settled.
            handler.removeCallbacks(colorRefresh)
            handler.postDelayed(colorRefresh, COLOR_SETTLE_MS)
        }
        if (colorInitialized && !themeChanged && !colorRefreshReady) return false
        colorInitialized = true
        colorRefreshReady = false
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
        if (!field.getGlobalVisibleRect(fieldBounds)) return hostColor()
        host.getLocationOnScreen(position)
        // Sample margins outside the password cells to preserve the panel palette.
        colorPoints[0] = position[0] + ((fieldBounds.left - position[0]) / 2).coerceAtLeast(1)
        colorPoints[1] = fieldBounds.centerY()
        colorPoints[2] = position[0] + host.width -
            ((position[0] + host.width - fieldBounds.right) / 2).coerceAtLeast(1) - 1
        colorPoints[3] = fieldBounds.centerY()
        colorPoints[4] = position[0] + panel.centerX()
        colorPoints[5] = position[1] + panel.top - 1
        if (colorLeaves[0] == null && !findColorLeaves()) return hostColor()
        var count = 0
        for (index in colorLeaves.indices) {
            val leaf = colorLeaves[index] ?: continue
            sampleBackground(leaf, colorPoints[index * 2], colorPoints[index * 2 + 1])?.let {
                sampledColors[count++] = it
            }
        }
        var bestColor: Int? = null
        var mostVotes = 0
        for (index in 0 until count) {
            val candidate = sampledColors[index]
            var votes = 0
            for (other in 0 until count) if (sampledColors[other] == candidate) votes++
            if (votes > mostVotes) {
                mostVotes = votes
                bestColor = candidate
            }
        }
        return bestColor ?: hostColor()
    }

    private fun findColorLeaves(): Boolean {
        colorLeaves.fill(host)
        colorDepths.fill(-1)
        if (!host.isShown) return true
        val pendingViews = ArrayDeque<View>()
        val pendingDepths = ArrayDeque<Int>()
        pendingViews.add(host)
        pendingDepths.add(0)
        var visited = 0
        while (pendingViews.isNotEmpty()) {
            if (visited >= MAX_COLOR_VIEWS) {
                colorLeaves.fill(null)
                return false
            }
            val view = pendingViews.removeLast()
            val depth = pendingDepths.removeLast()
            if (view.visibility != View.VISIBLE) continue
            visited++
            var containsPoint = false
            if (view.getGlobalVisibleRect(targetBounds)) {
                for (index in colorLeaves.indices) {
                    if (!targetBounds.contains(colorPoints[index * 2], colorPoints[index * 2 + 1])) continue
                    containsPoint = true
                    if (depth > colorDepths[index]) {
                        colorLeaves[index] = view
                        colorDepths[index] = depth
                    }
                }
            }
            // Descendants can extend outside a parent with clipChildren=false.
            if (view is ViewGroup && (containsPoint || !view.clipChildren)) {
                for (index in 0 until view.childCount) {
                    pendingViews.add(view.getChildAt(index))
                    pendingDepths.add(depth + 1)
                }
            }
        }
        return true
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
        try {
            var current: View? = leaf
            while (current != null) {
                sampleAncestry.add(current)
                current = current.parent as? View
            }
            var opacity = 1f
            for (index in sampleAncestry.lastIndex downTo 0) {
                val view = sampleAncestry[index]
                opacity *= view.alpha
                val background = view.background ?: continue
                view.getLocationOnScreen(sampleLocation)
                val save = canvas.save()
                try {
                    canvas.translate((sampleLocation[0] - x).toFloat(), (sampleLocation[1] - y).toFloat())
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
        } finally {
            sampleAncestry.clear()
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
        const val COLOR_SETTLE_MS = 250L
        const val MAX_COLOR_VIEWS = 8_192
    }
}
