/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.alipay

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import io.github.kiriashi.biopay.apps.shared.ENTRY_TAG
import io.github.kiriashi.biopay.apps.shared.EntryInstaller
import io.github.kiriashi.biopay.apps.shared.SettingsEntry
import io.github.kiriashi.biopay.apps.shared.ancestor
import io.github.kiriashi.biopay.apps.shared.findByEntryName
import io.github.kiriashi.biopay.apps.shared.findText
import io.github.kiriashi.biopay.apps.shared.versionCode
import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.core.util.dp
import java.lang.ref.WeakReference

private const val ALIPAY_NEW_SETTINGS_VERSION = 773L
private const val ALIPAY_ICON_ENTRY_MIN_VERSION = 661L

internal object AlipayEntry : SettingsEntry {
    override fun install(host: EntryInstaller, activity: Activity, root: ViewGroup): Boolean =
        host.installAlipay(activity, root)
}

internal fun EntryInstaller.installAlipay(activity: Activity, root: ViewGroup): Boolean {
    val name = activity.javaClass.name
    val version = versionCode(activity)
    if (name.endsWith(".FBAppWindowActivity") && version >= ALIPAY_NEW_SETTINGS_VERSION) {
        val title = findText(root, "支付密码", "支付密碼", "Payment Password") ?: return false
        val target = ancestor(title, 4) as? FrameLayout ?: return false
        return insertAlipayPaymentSettings(activity, target, title)
    }
    if (name.endsWith(".UserSettingActivity")) {
        val logout = findByEntryName(root, "logout") ?: return false
        val parent = logout.parent as? LinearLayout ?: return false
        return insertAtTop(activity, parent, "指纹设置", 50, leftPadding = 15)
    }
    if (!name.endsWith(".MySettingActivity")) return false
    if (version >= ALIPAY_NEW_SETTINGS_VERSION) {
        val title = findText(root, "支付密码", "支付密碼", "Payment Password")
        val target = title?.let { ancestor(it, 4) as? FrameLayout }
        if (target != null) return insertAlipayPaymentSettings(activity, target, title!!)
        return false
    }
    val listId = activity.resources.getIdentifier(
        "setting_list", "id", "com.alipay.android.phone.openplatform"
    )
    val list = if (listId != 0) activity.findViewById<View>(listId) as? ListView else null
    if (list != null) {
        val modernStyle = version >= ALIPAY_ICON_ENTRY_MIN_VERSION
        return insertListHeader(
            activity, list, "指纹设置", if (modernStyle) 50 else 45, 0,
            icon = version in ALIPAY_ICON_ENTRY_MIN_VERSION until ALIPAY_NEW_SETTINGS_VERSION,
            rounded = modernStyle,
            separatorsVisible = !modernStyle,
            bottomGap = if (modernStyle) 8 else 20
        )
    }
    return false
}

private fun EntryInstaller.insertAlipayPaymentSettings(activity: Activity, target: FrameLayout, styleAnchor: View): Boolean {
    val oldPadding = intArrayOf(target.paddingLeft, target.paddingTop, target.paddingRight, target.paddingBottom)
    val oldClip = target.clipToPadding
    val oldClipChildren = target.clipChildren
    target.setPadding(0, activity.dp(62), 0, 0)
    target.clipToPadding = false
    target.clipChildren = false
    val section = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(activity).apply { visibility = View.INVISIBLE }, LinearLayout.LayoutParams(-1, activity.dp(1)))
        addView(createRow(activity, "生物支付", 50, 12, true, styleAnchor, rightPadding = 18), LinearLayout.LayoutParams(-1, activity.dp(50)))
        addView(View(activity).apply { visibility = View.INVISIBLE }, LinearLayout.LayoutParams(-1, activity.dp(1)).apply {
            bottomMargin = activity.dp(8)
        })
    }
    val params = FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = -activity.dp(50)
        leftMargin = activity.dp(12)
        rightMargin = activity.dp(12)
    }
    target.addView(section, params)
    val targetRef = WeakReference(target)
    val sectionRef = WeakReference(section)
    record(activity) {
        sectionRef.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        targetRef.get()?.apply {
            setPadding(oldPadding[0], oldPadding[1], oldPadding[2], oldPadding[3])
            clipToPadding = oldClip
            clipChildren = oldClipChildren
        }
    }
    return true
}

private fun EntryInstaller.insertAtTop(activity: Activity, parent: LinearLayout, label: String, height: Int, leftPadding: Int): Boolean {
    val oldPadding = intArrayOf(parent.paddingLeft, parent.paddingTop, parent.paddingRight, parent.paddingBottom)
    parent.setPadding(0, 0, 0, 0)
    val index = 0
    val top = View(activity).apply { setBackgroundColor(0xFFDFDFDF.toInt()) }
    val row = createRow(activity, label, height, leftPadding, false)
    val bottom = View(activity).apply { setBackgroundColor(0xFFDFDFDF.toInt()) }
    parent.addView(top, index, LinearLayout.LayoutParams(-1, activity.dp(1)))
    parent.addView(row, index + 1, LinearLayout.LayoutParams(-1, activity.dp(height)))
    parent.addView(bottom, index + 2, LinearLayout.LayoutParams(-1, activity.dp(1)).apply { bottomMargin = activity.dp(20) })
    val parentRef = WeakReference(parent)
    val topRef = WeakReference(top)
    val rowRef = WeakReference(row)
    val bottomRef = WeakReference(bottom)
    record(activity) {
        parentRef.get()?.let { currentParent ->
            topRef.get()?.let(currentParent::removeView)
            rowRef.get()?.let(currentParent::removeView)
            bottomRef.get()?.let(currentParent::removeView)
            currentParent.setPadding(oldPadding[0], oldPadding[1], oldPadding[2], oldPadding[3])
        }
    }
    return true
}

private fun EntryInstaller.insertListHeader(
    activity: Activity,
    list: ListView,
    label: String,
    height: Int,
    leftPadding: Int,
    icon: Boolean = false,
    rounded: Boolean = false,
    separatorsVisible: Boolean = true,
    bottomGap: Int = 8
): Boolean {
    val section = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(activity).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            if (!separatorsVisible) visibility = View.INVISIBLE
        }, LinearLayout.LayoutParams(-1, activity.dp(1)))
        addView(createRow(activity, label, height, leftPadding, rounded, icon = icon), LinearLayout.LayoutParams(-1, activity.dp(height)))
        addView(View(activity).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            if (!separatorsVisible) visibility = View.INVISIBLE
        }, LinearLayout.LayoutParams(-1, activity.dp(1)).apply {
            bottomMargin = activity.dp(bottomGap)
        })
        tag = ENTRY_TAG
    }
    return try {
        list.addHeaderView(section)
        val listRef = WeakReference(list)
        val sectionRef = WeakReference(section)
        record(activity) {
            val currentList = listRef.get()
            val currentSection = sectionRef.get()
            if (currentList != null && currentSection != null) currentList.removeHeaderView(currentSection)
        }
        true
    } catch (e: Throwable) {
        Log.d(LOG_TAG, "settings list cannot accept a header", e)
        false
    }
}
