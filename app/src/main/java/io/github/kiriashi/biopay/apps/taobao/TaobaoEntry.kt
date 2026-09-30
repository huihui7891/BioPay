/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.taobao

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kiriashi.biopay.BuildConfig
import io.github.kiriashi.biopay.settings.ui.Theme
import io.github.kiriashi.biopay.apps.shared.EntryInstaller
import io.github.kiriashi.biopay.apps.shared.SettingsEntry
import io.github.kiriashi.biopay.apps.shared.visitViews
import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.core.util.dp
import java.util.WeakHashMap

private const val TAOBAO_SETTINGS_ACTIVITY_SUFFIX = ".DxSettingCommonActivity"
private const val ACCOUNT_SECTION_LABEL = "账号与安全"
private const val FIRST_ACCOUNT_ROW_LABEL = "收货地址"
private const val BIOPAY_ENTRY_LABEL = "生物支付"
private const val TAOBAO_NATIVE_ROW_CLASS = "com.taobao.android.dinamicx.view.DXNativeLinearLayout"

private data class TaobaoInsertionPoint(
    val label: View,
    val row: LinearLayout,
    val parent: LinearLayout
)

private data class TaobaoViewFrame(
    val view: View,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

private data class TaobaoGeometry(
    val siblings: List<TaobaoViewFrame>,
    val heights: List<Pair<View, Int>>,
    val containers: List<TaobaoViewFrame>
) {
    fun isApplied(amount: Int): Boolean =
        containers.all { it.isAtHeightOffset(amount) } &&
            siblings.all { it.isAtOffset(amount) }

    fun apply(amount: Int) {
        containers.forEach { it.layoutWithHeightOffset(amount) }
        siblings.forEach { it.layoutAtOffset(amount) }
    }

    fun restore() {
        heights.forEach { (view, height) ->
            view.layoutParams?.let { params ->
                params.height = height
                view.layoutParams = params
            }
        }
        apply(0)
    }
}

private fun View.toTaobaoFrame() = TaobaoViewFrame(this, left, top, right, bottom)

private fun TaobaoViewFrame.isAtOffset(yOffset: Int): Boolean =
    view.left == left && view.top == top + yOffset &&
        view.right == right && view.bottom == bottom + yOffset

private fun TaobaoViewFrame.isAtHeightOffset(heightOffset: Int): Boolean =
    view.left == left && view.top == top &&
        view.right == right && view.bottom == bottom + heightOffset

private fun TaobaoViewFrame.layoutAtOffset(yOffset: Int = 0) {
    val targetTop = top + yOffset
    val targetBottom = bottom + yOffset
    if (view.left != left || view.top != targetTop || view.right != right || view.bottom != targetBottom) {
        view.layout(left, targetTop, right, targetBottom)
    }
}

private fun TaobaoViewFrame.layoutWithHeightOffset(heightOffset: Int = 0) {
    val targetBottom = bottom + heightOffset
    if (view.left != left || view.top != top || view.right != right || view.bottom != targetBottom) {
        view.layout(left, top, right, targetBottom)
    }
}

internal class TaobaoEntry : SettingsEntry {
    private val insertionCleanups = WeakHashMap<Activity, () -> Unit>()
    override val tryImmediately = false

    override fun findExisting(root: ViewGroup): View? {
        var found: View? = null
        visitViews(root) { view ->
            if (view.javaClass.name == TAOBAO_NATIVE_ROW_CLASS &&
                view.contentDescription?.toString() == BIOPAY_ENTRY_LABEL
            ) {
                found = view
                true
            } else false
        }
        return found
    }

    override fun install(host: EntryInstaller, activity: Activity, root: ViewGroup): Boolean {
        insertionCleanups.remove(activity)?.invoke()
        return host.installTaobao(activity, root) { cleanup ->
            insertionCleanups[activity] = cleanup
        }
    }

    override fun accepts(existing: View): Boolean {
        if (!existing.isShown || existing.width <= 0 || existing.height <= 0) return false
        if (findTaobaoEntryLabel(existing)?.let { it.width > 0 && it.height > 0 } != true) return false
        val parent = existing.parent as? ViewGroup ?: return false
        val index = parent.indexOfChild(existing)
        val addressRow = (index + 1 until parent.childCount)
            .asSequence()
            .map(parent::getChildAt)
            .firstOrNull { containsTaobaoLabel(it, FIRST_ACCOUNT_ROW_LABEL) }
            ?: return false
        return addressRow.top >= existing.bottom && addressRow.translationY == 0f
    }

    override fun replaceHidden(root: ViewGroup, existing: View): Boolean {
        // The registered insertion cleanup restores exact bounds and heights.
        (existing.parent as? ViewGroup)?.removeView(existing)
        return true
    }

    override fun remove(activity: Activity) {
        insertionCleanups.remove(activity)?.invoke()
    }

    override fun close() {
        insertionCleanups.values.toList().forEach { cleanup -> runCatching(cleanup) }
        insertionCleanups.clear()
    }
}

private fun EntryInstaller.installTaobao(
    activity: Activity,
    root: ViewGroup,
    onCleanup: (() -> Unit) -> Unit
): Boolean {
    if (!activity.javaClass.name.endsWith(TAOBAO_SETTINGS_ACTIVITY_SUFFIX)) {
        return false
    }
    val point = findTaobaoInsertionPoint(root)
    if (point == null) {
        watchForEntry(activity, root)
        return false
    }
    if (!point.label.isAttachedToWindow || !point.row.isAttachedToWindow || !point.parent.isAttachedToWindow) {
        watchForEntry(activity, root)
        return false
    }
    if (!point.label.isShown || !point.row.isShown || !point.parent.isShown ||
        point.row.width <= 0 || point.row.height <= 0 || point.parent.width <= 0
    ) {
        watchForEntry(activity, root)
        return false
    }

    val inserted = insertTaobaoRow(activity, point, onCleanup)
    if (!inserted) {
        watchForEntry(activity, root)
    }
    return inserted
}

/** Finds the first row beneath the visible “账号与安全” section heading. */
private fun findTaobaoInsertionPoint(root: View): TaobaoInsertionPoint? {
    if (findTaobaoLabel(root, ACCOUNT_SECTION_LABEL) == null) return null

    var point: TaobaoInsertionPoint? = null
    visitViews(root) { label ->
        if (!label.isShown || !label.matchesTaobaoLabel(FIRST_ACCOUNT_ROW_LABEL)) return@visitViews false

        var current: View? = label
        while (current != null) {
            if (current is LinearLayout && current.isClickable) {
                val parent = current.parent as? LinearLayout
                if (parent != null) {
                    point = TaobaoInsertionPoint(label, current, parent)
                    return@visitViews true
                }
            }
            current = current.parent as? View
        }
        false
    }
    return point
}

private fun findTaobaoLabel(root: View, label: String): View? {
    var found: View? = null
    visitViews(root) { view ->
        if (view.isShown && view.matchesTaobaoLabel(label)) {
            found = view
            true
        } else false
    }
    return found
}

private fun View.matchesTaobaoLabel(label: String): Boolean =
    contentDescription?.toString()?.trim() == label ||
        (this is TextView && text?.toString()?.trim() == label)

private fun containsTaobaoLabel(root: View, label: String): Boolean {
    var found = false
    visitViews(root) { view ->
        if (view.matchesTaobaoLabel(label)) {
            found = true
            true
        } else false
    }
    return found
}

private fun EntryInstaller.insertTaobaoRow(
    activity: Activity,
    point: TaobaoInsertionPoint,
    onCleanup: (() -> Unit) -> Unit
): Boolean {
    val anchor = point.row
    val parent = point.parent
    val index = parent.indexOfChild(anchor)
    val anchorParams = anchor.layoutParams as? LinearLayout.LayoutParams ?: return false
    if (index < 0) return false

    val location = IntArray(2)
    anchor.getLocationOnScreen(location)
    val labelLocation = IntArray(2)
    point.label.getLocationOnScreen(labelLocation)
    val labelStart = (labelLocation[0] - location[0] - anchor.paddingLeft).coerceAtLeast(0)

    val chevronTemplate = findTaobaoChevron(anchor)
    val chevronLocation = IntArray(2)
    chevronTemplate?.getLocationOnScreen(chevronLocation)
    val chevronWidth = chevronTemplate?.width?.takeIf { it > 0 } ?: activity.dp(11)
    val rightInset = if (chevronTemplate != null && anchor.width > 0) {
        (location[0] + anchor.width - chevronLocation[0] - chevronWidth).coerceAtLeast(anchor.paddingRight)
    } else activity.dp(12)
    val trailingMarginPx = (rightInset - anchor.paddingRight).coerceAtLeast(0)

    val row = (createTaobaoNativeRow(activity) ?: return false).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = BIOPAY_ENTRY_LABEL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isFocusable = anchor.isFocusable
        minimumHeight = anchor.minimumHeight.takeIf { it > 0 } ?: anchor.height
        setPadding(anchor.paddingLeft, anchor.paddingTop, anchor.paddingRight, anchor.paddingBottom)
        background = cloneDrawable(anchor.background, activity)
        foreground = cloneDrawable(anchor.foreground, activity)
        elevation = anchor.elevation
        setOnClickListener { openSettings(activity) }
    }

    val labelColors = resolveNativeTextColors(activity)
    val title = TextView(activity).apply {
        text = BIOPAY_ENTRY_LABEL
        textSize = 16f
        setTextColor(labelColors)
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        leftMargin = labelStart
    })

    val version = TextView(activity).apply {
        text = BuildConfig.VERSION_NAME
        textSize = 12f
        setTextColor(if (Theme.isDark(activity)) 0xFF888888.toInt() else 0xFF999999.toInt())
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        isSingleLine = true
    }
    row.addView(version, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    ).apply {
        rightMargin = trailingMarginPx
    })

    val rowParams = LinearLayout.LayoutParams(anchorParams).apply {
        if (height == 0 && weight == 0f) height = anchor.height.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        if (width == 0 && weight == 0f && parent.orientation == LinearLayout.HORIZONTAL) {
            width = anchor.width.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }
    val rowHeight = anchor.height
    if (rowHeight <= 0 || anchor.width <= 0) return false
    val anchorFrame = anchor.toTaobaoFrame()
    val existingRowFrames = (index until parent.childCount).map { childIndex ->
        parent.getChildAt(childIndex).toTaobaoFrame()
    }
    parent.addView(row, index, rowParams)
    val geometry = growTaobaoAncestors(parent, rowHeight)
    row.measure(
        View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(rowHeight, View.MeasureSpec.EXACTLY)
    )
    fun positionEntry() {
        if (row.parent === parent && anchor.parent === parent) {
            if (geometry.isApplied(rowHeight) && existingRowFrames.all { it.isAtOffset(rowHeight) } &&
                row.left == anchorFrame.left && row.top == anchorFrame.top &&
                row.right == anchorFrame.right && row.bottom == anchorFrame.top + rowHeight) return
            // Laying out a DX wrapper can reset its descendants. Expand from
            // the outside in, then place the rows and their content last.
            geometry.apply(rowHeight)
            existingRowFrames.forEach { it.layoutAtOffset(rowHeight) }
            if (row.left != anchorFrame.left || row.top != anchorFrame.top ||
                row.right != anchorFrame.right || row.bottom != anchorFrame.top + rowHeight) {
                row.layout(anchorFrame.left, anchorFrame.top, anchorFrame.right, anchorFrame.top + rowHeight)
            }
            val left = labelStart.coerceIn(0, row.width)
            val versionRight = (row.width - rightInset).coerceAtLeast(left)
            version.measure(
                View.MeasureSpec.makeMeasureSpec(versionRight - left, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(rowHeight, View.MeasureSpec.EXACTLY)
            )
            val versionLeft = versionRight - version.measuredWidth
            val titleRight = (versionLeft - activity.dp(12)).coerceAtLeast(left)
            title.measure(
                View.MeasureSpec.makeMeasureSpec(titleRight - left, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rowHeight, View.MeasureSpec.EXACTLY)
            )
            title.layout(left, 0, titleRight, rowHeight)
            version.layout(versionLeft, 0, versionRight, rowHeight)
        }
    }
    val decorRoot = activity.window?.decorView
    val treeObserver = decorRoot?.viewTreeObserver
    val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { positionEntry() }
    if (treeObserver?.isAlive == true) treeObserver.addOnGlobalLayoutListener(layoutListener)
    positionEntry()
    parent.requestLayout()
    parent.invalidate()
    var cleanedUp = false
    val cleanup = cleanup@{
        if (cleanedUp) return@cleanup
        cleanedUp = true
        if (treeObserver?.isAlive == true) treeObserver.removeOnGlobalLayoutListener(layoutListener)
        decorRoot?.viewTreeObserver?.takeIf { it.isAlive && it !== treeObserver }
            ?.removeOnGlobalLayoutListener(layoutListener)
        (row.parent as? ViewGroup)?.removeView(row)
        geometry.restore()
        existingRowFrames.forEach { it.layoutAtOffset() }
        decorRoot?.requestLayout()
    }
    onCleanup(cleanup)
    return true
}

/** Expands fixed-height DinamicX wrappers and shifts following sections down one row. */
private fun growTaobaoAncestors(
    target: ViewGroup,
    amount: Int
): TaobaoGeometry {
    val moved = mutableListOf<TaobaoViewFrame>()
    val resized = mutableListOf<Pair<View, Int>>()
    val expanded = mutableListOf<TaobaoViewFrame>()

    // DX keeps the native row list at its original measured height even after
    // a child is inserted. Its last row then falls outside this view's clip.
    // Give the row list one additional native row of space before expanding
    // its section wrappers.
    target.layoutParams?.let { params ->
        resized += target to params.height
        params.height = target.height + amount
        target.layoutParams = params
    }

    var child: View = target
    while (true) {
        expanded += child.toTaobaoFrame()
        child.layoutParams?.let { params ->
            if (params.height > 0 && params.height == child.height && child !== target) {
                resized += child to params.height
                params.height += amount
                child.layoutParams = params
            }
        }
        val host = child.parent as? ViewGroup ?: break
        val childIndex = host.indexOfChild(child)
        if (childIndex >= 0) {
            for (index in childIndex + 1 until host.childCount) {
                val sibling = host.getChildAt(index)
                if (moved.none { it.view === sibling }) {
                    val frame = sibling.toTaobaoFrame()
                    moved += frame
                }
            }
        }
        if (host.javaClass.name.contains("RecyclerView")) break
        child = host
    }
    return TaobaoGeometry(moved.asReversed(), resized, expanded.asReversed())
}

/** DXNativeLinearLayout is the row type the settings page's custom parent lays out. */
private fun createTaobaoNativeRow(activity: Activity): LinearLayout? {
    return runCatching {
        val type = activity.classLoader.loadClass(TAOBAO_NATIVE_ROW_CLASS).asSubclass(LinearLayout::class.java)
        val constructor = type.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 1 && candidate.parameterTypes[0].isAssignableFrom(activity.javaClass)
        } ?: type.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes[0].isAssignableFrom(activity.javaClass) &&
                candidate.parameterTypes[1].name == "android.util.AttributeSet"
        } ?: error("No compatible DXNativeLinearLayout constructor")
        constructor.isAccessible = true
        val args = if (constructor.parameterTypes.size == 1) arrayOf(activity) else arrayOf(activity, null)
        constructor.newInstance(*args) as LinearLayout
    }.getOrElse { error ->
        Log.w(LOG_TAG, "Could not create Taobao native settings row", error)
        null
    }
}

private fun findTaobaoChevron(row: View): TextView? {
    var found: TextView? = null
    visitViews(row) { view ->
        if (view is TextView && view.text?.toString()?.trim() == "큚") {
            found = view
            true
        } else false
    }
    return found
}

private fun findTaobaoEntryLabel(row: View): TextView? {
    var found: TextView? = null
    visitViews(row) { view ->
        if (view is TextView && view.text?.toString() == BIOPAY_ENTRY_LABEL) {
            found = view
            true
        } else false
    }
    return found
}

private fun resolveNativeTextColors(activity: Activity): ColorStateList {
    val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
    return try {
        attributes.getColorStateList(0) ?: ColorStateList.valueOf(Color.BLACK)
    } finally {
        attributes.recycle()
    }
}

private fun cloneDrawable(source: Drawable?, activity: Activity): Drawable? =
    source?.constantState?.newDrawable(activity.resources)?.mutate()
