/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.unionpay

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.apps.shared.PaymentDetector
import io.github.kiriashi.biopay.apps.shared.PaymentKeypad
import io.github.kiriashi.biopay.apps.shared.PaymentScreen
import io.github.kiriashi.biopay.apps.shared.PaymentViewTree
import io.github.kiriashi.biopay.apps.shared.PaymentMasks

/** UnionPay identifies the visible payment title, while excluding password settings. */
internal object UnionPayDetector : PaymentDetector {
    private val paymentHosts = listOf(
        "UPActivityReactNative", "PayWalletActivity"
    )

    override fun supports(activity: Activity): Boolean = paymentHosts.any { activity.javaClass.name.contains(it) }

    override fun find(root: ViewGroup, activity: Activity): PaymentScreen? {
        if (!supports(activity)) return null
        val views = PaymentViewTree(root)
        if (!views.complete) return null
        if (views.hasText("设置支付密码", "修改支付密码", "重置支付密码", "Payment password settings")) return null
        if (!views.hasText("请输入支付密码", "請輸入支付密碼", "Input payment password")) return null
        val keys = PaymentKeypad.textKeys(views) ?: return null
        return PaymentKeypad.group(root, keys)?.let { PaymentScreen(it, digitKeys = keys) }
    }

    /** Native wallet pages name their custom field; React Native uses five dividers. */
    fun passwordPanel(root: ViewGroup, keyboard: ViewGroup): View? {
        val views = PaymentViewTree(root)
        if (!views.complete) return null
        val keypadBounds = Rect()
        if (!keyboard.getGlobalVisibleRect(keypadBounds)) return null
        val bounds = Rect()
        val named = views.all.filter { field ->
            views.resourceName(field) == "edit_pay_pwd" &&
                field.getGlobalVisibleRect(bounds) && bounds.height() > 0 &&
                bounds.bottom <= keypadBounds.top && bounds.width() >= keypadBounds.width() * 0.7f
        }
        PaymentMasks.nearestPassword(named, keyboard)?.let { return it }
        val rows = views.all.filterIsInstance<ViewGroup>().filter { row ->
            if (row.childCount != 5 || !row.getGlobalVisibleRect(bounds) ||
                bounds.bottom > keypadBounds.top || bounds.width() < keypadBounds.width() * 0.7f ||
                bounds.height() <= 0) return@filter false
            val rowBounds = Rect(bounds)
            (0 until 5).all { index ->
                val divider = row.getChildAt(index)
                if (divider is ViewGroup && divider.childCount != 0) return@all false
                if (!divider.getGlobalVisibleRect(bounds)) return@all false
                val position = (bounds.exactCenterX() - rowBounds.left) / rowBounds.width()
                bounds.width() <= rowBounds.width() * 0.015f &&
                    bounds.height() >= rowBounds.height() * 0.8f &&
                    kotlin.math.abs(position - (index + 1) / 6f) < 0.04f
            }
        }
        return PaymentMasks.nearestPassword(rows, keyboard)
    }

    override fun digitKeys(keyboard: ViewGroup): List<View>? =
        PaymentKeypad.textKeys(PaymentViewTree(keyboard))
}
