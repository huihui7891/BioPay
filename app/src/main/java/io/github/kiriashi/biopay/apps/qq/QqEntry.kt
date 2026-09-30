/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.qq

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kiriashi.biopay.apps.shared.ENTRY_TAG
import io.github.kiriashi.biopay.apps.shared.EntryInstaller
import io.github.kiriashi.biopay.apps.shared.SettingsEntry
import io.github.kiriashi.biopay.apps.shared.findVisibleText
import io.github.kiriashi.biopay.apps.shared.visitViews
import io.github.kiriashi.biopay.apps.shared.toSp
import io.github.kiriashi.biopay.core.util.dp
import io.github.kiriashi.biopay.core.log.LOG_TAG
import android.util.Log
import java.lang.ref.WeakReference

private const val QQ_ICON_PERSON_SCALE = 0.81f

internal object QqEntry : SettingsEntry {
    override fun install(host: EntryInstaller, activity: Activity, root: ViewGroup): Boolean =
        host.installQqMenu(activity, root)

    override fun onExisting(host: EntryInstaller, activity: Activity, root: ViewGroup, existing: View) {
        if (activity.packageName != "com.tencent.mobileqq") return
        val paymentLabel = findVisibleText(root, "收付款", "Receive and Pay", "Payments") ?: return
        val paymentRow = findQqMenuRow(paymentLabel, root) ?: return
        QqMenuEntryHook.expandForEntry(root, menuRowHeight(paymentRow, activity))
    }
}

internal fun EntryInstaller.installQqMenu(activity: Activity, root: ViewGroup): Boolean {
    if (activity.packageName != "com.tencent.mobileqq") return false
    val paymentLabel = findVisibleText(root, "收付款", "Receive and Pay", "Payments") ?: return false
    val paymentRow = findQqMenuRow(paymentLabel, root) ?: return false
    val container = paymentRow.parent as? ViewGroup ?: return false
    val paymentIndex = container.indexOfChild(paymentRow)
    if (paymentIndex < 0) return false
    if ((paymentIndex + 1 until container.childCount).any { container.getChildAt(it).tag == ENTRY_TAG }) return true

    val labelStyle = paymentLabel as? TextView ?: return false
    val nativeIcon = findLeadingIcon(paymentRow, paymentLabel)
    val rowHeight = menuRowHeight(paymentRow, activity)
    val iconWidth = nativeIcon?.let(::viewWidth)?.takeIf { it > 0 } ?: activity.dp(22)
    val iconHeight = nativeIcon?.let(::viewHeight)?.takeIf { it > 0 } ?: activity.dp(22)
    val iconLeft = nativeIcon?.let { relativeLeft(it, paymentRow) }?.takeIf { it >= 0 } ?: activity.dp(12)
    val labelLeft = relativeLeft(paymentLabel, paymentRow)?.takeIf { it > iconLeft + iconWidth }
        ?: (iconLeft + iconWidth + activity.dp(16))
    val iconColor = nativeIcon?.let(::iconTint) ?: labelStyle.currentTextColor
    val context = paymentLabel.context

    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = ENTRY_TAG
        isClickable = true
        isFocusable = true
        minimumHeight = rowHeight
        setPadding(0, 0, 0, 0)
        val backgroundCopy = copyDrawable(paymentRow.background, context)
        val foregroundCopy = copyDrawable(paymentRow.foreground, context)
        if (backgroundCopy != null) setBackground(backgroundCopy)
        else if (foregroundCopy == null) selectableBackground(context)?.let(::setForeground)
        foregroundCopy?.let(::setForeground)
        stateListAnimator = paymentRow.stateListAnimator
        setOnClickListener {
            QqMenuEntryHook.dismiss(root)
            openSettings(activity)
        }
    }
    row.addView(QqShieldIcon(context, iconColor), LinearLayout.LayoutParams(iconWidth, iconHeight).apply {
        leftMargin = iconLeft
    })
    row.addView(TextView(context).apply {
        text = "生物支付"
        textSize = labelStyle.textSize.toSp(activity)
        setTextColor(labelStyle.currentTextColor)
        typeface = labelStyle.typeface
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = labelStyle.includeFontPadding
        isSingleLine = true
        letterSpacing = labelStyle.letterSpacing
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        leftMargin = labelLeft - iconLeft - iconWidth
    })

    val params = copyLayoutParams(paymentRow.layoutParams, rowHeight)
    container.addView(row, paymentIndex + 1, params)
    val restorePopupSize = QqMenuEntryHook.expandForEntry(root, rowHeight)
    val rowRef = WeakReference<View>(row)
    record(activity) {
        rowRef.get()?.let { inserted -> (inserted.parent as? ViewGroup)?.removeView(inserted) }
        restorePopupSize()
    }
    Log.d(LOG_TAG, "QQ chat action menu entry inserted below 收付款")
    return true
}

private fun findQqMenuRow(label: View, root: ViewGroup): ViewGroup? {
    var nearestLinear: ViewGroup? = null
    var current = label.parent as? View
    while (current != null && current !== root) {
        if (current is ViewGroup) {
            if (current.isClickable || current.hasOnClickListeners()) return current
            if (nearestLinear == null && current is LinearLayout) nearestLinear = current
        }
        current = current.parent as? View
    }
    return nearestLinear
}

private fun menuRowHeight(row: View, activity: Activity): Int = row.height.takeIf { it > 0 }
    ?: row.measuredHeight.takeIf { it > 0 }
    ?: activity.dp(50)

private fun findLeadingIcon(row: ViewGroup, label: View): View? {
    val labelLeft = relativeLeft(label, row) ?: Int.MAX_VALUE
    var best: View? = null
    var bestLeft = Int.MAX_VALUE
    visitViews(row) { candidate ->
        if (candidate !== row && candidate !== label && candidate !is ViewGroup && candidate !is TextView &&
            candidate.visibility == View.VISIBLE
        ) {
            val left = relativeLeft(candidate, row) ?: Int.MAX_VALUE
            val width = viewWidth(candidate)
            val height = viewHeight(candidate)
            if (left < labelLeft && left < bestLeft && width > 0 && height > 0 && width <= row.height.coerceAtLeast(height)) {
                best = candidate
                bestLeft = left
            }
        }
        false
    }
    return best
}

private fun relativeLeft(view: View, row: View): Int? {
    if (view.width <= 0 || row.width <= 0) return null
    val viewPosition = IntArray(2)
    val rowPosition = IntArray(2)
    view.getLocationOnScreen(viewPosition)
    row.getLocationOnScreen(rowPosition)
    return viewPosition[0] - rowPosition[0]
}

private fun viewWidth(view: View): Int = view.width.takeIf { it > 0 }
    ?: view.layoutParams?.width?.takeIf { it > 0 } ?: 0

private fun viewHeight(view: View): Int = view.height.takeIf { it > 0 }
    ?: view.layoutParams?.height?.takeIf { it > 0 } ?: 0

private fun iconTint(view: View): Int? = when (view) {
    is android.widget.ImageView -> view.imageTintList?.defaultColor
    else -> null
}

private fun copyDrawable(drawable: Drawable?, context: Context): Drawable? = drawable?.constantState
    ?.newDrawable(context.resources, context.theme)?.mutate()

private fun selectableBackground(context: Context): Drawable? {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) return null
    return runCatching { context.getDrawable(value.resourceId) }.getOrNull()
}

private fun copyLayoutParams(source: ViewGroup.LayoutParams?, rowHeight: Int): ViewGroup.LayoutParams {
    val copy = source?.let { params ->
        runCatching {
            params.javaClass.getConstructor(ViewGroup.LayoutParams::class.java)
                .newInstance(params) as ViewGroup.LayoutParams
        }.getOrNull()
    } ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
    copy.height = rowHeight
    return copy
}

private class QqShieldIcon(context: Context, color: Int) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val shieldPath = Path()
    private val personPath = Path()
    private var centerX = 0f
    private var personCenterY = 0f
    private val shieldLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.5f
        this.color = color
    }
    private val personLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.5f / QQ_ICON_PERSON_SCALE
        this.color = color
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / density / 2f
        val cy = h / density / 2f
        val personCy = cy - 1.5f
        centerX = cx
        personCenterY = personCy
        shieldPath.rewind()
        personPath.rewind()

        shieldPath.apply {
            moveTo(cx, cy - 9f)
            cubicTo(cx + 2.4f, cy - 9f, cx + 5.1f, cy - 8.3f, cx + 7.2f, cy - 7.4f)
            cubicTo(cx + 7.7f, cy - 7.2f, cx + 8f, cy - 6.8f, cx + 8f, cy - 6.2f)
            lineTo(cx + 8f, cy - 1f)
            cubicTo(cx + 8f, cy + 3.2f, cx + 4.7f, cy + 5.5f, cx + 1f, cy + 7.8f)
            cubicTo(cx + 0.4f, cy + 8.2f, cx - 0.4f, cy + 8.2f, cx - 1f, cy + 7.8f)
            cubicTo(cx - 4.7f, cy + 5.5f, cx - 8f, cy + 3.2f, cx - 8f, cy - 1f)
            lineTo(cx - 8f, cy - 6.2f)
            cubicTo(cx - 8f, cy - 6.8f, cx - 7.7f, cy - 7.2f, cx - 7.2f, cy - 7.4f)
            cubicTo(cx - 5.1f, cy - 8.3f, cx - 2.4f, cy - 9f, cx, cy - 9f)
            close()
        }

        personPath.apply {
            moveTo(cx - 4.8f, personCy + 5.1f)
            lineTo(cx - 4.8f, personCy + 3.7f)
            cubicTo(cx - 4.8f, personCy + 2.7f, cx - 3.7f, personCy + 2f, cx - 2.4f, personCy + 2f)
            cubicTo(cx - 1.9f, personCy + 2f, cx - 1.6f, personCy + 1.7f, cx - 1.6f, personCy + 1.2f)
            cubicTo(cx - 2.5f, personCy + 0.5f, cx - 3f, personCy - 0.4f, cx - 3f, personCy - 1.4f)
            cubicTo(cx - 3f, personCy - 3f, cx - 1.65f, personCy - 4.2f, cx, personCy - 4.2f)
            cubicTo(cx + 1.65f, personCy - 4.2f, cx + 3f, personCy - 3f, cx + 3f, personCy - 1.4f)
            cubicTo(cx + 3f, personCy - 0.4f, cx + 2.5f, personCy + 0.5f, cx + 1.6f, personCy + 1.2f)
            cubicTo(cx + 1.6f, personCy + 1.7f, cx + 1.9f, personCy + 2f, cx + 2.4f, personCy + 2f)
            cubicTo(cx + 3.7f, personCy + 2f, cx + 4.8f, personCy + 2.7f, cx + 4.8f, personCy + 3.7f)
            lineTo(cx + 4.8f, personCy + 5.1f)
            cubicTo(cx + 4.8f, personCy + 5.5f, cx + 4.5f, personCy + 5.8f, cx + 4.1f, personCy + 5.8f)
            lineTo(cx - 4.1f, personCy + 5.8f)
            cubicTo(cx - 4.5f, personCy + 5.8f, cx - 4.8f, personCy + 5.5f, cx - 4.8f, personCy + 5.1f)
            close()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        canvas.drawPath(shieldPath, shieldLine)
        canvas.save()
        canvas.scale(QQ_ICON_PERSON_SCALE, QQ_ICON_PERSON_SCALE, centerX, personCenterY + 0.4f)
        canvas.drawPath(personPath, personLine)
        canvas.restore()

        canvas.restore()
    }
}
