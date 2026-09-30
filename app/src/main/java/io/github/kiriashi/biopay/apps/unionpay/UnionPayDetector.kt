/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.unionpay

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.apps.shared.PaymentDetector
import io.github.kiriashi.biopay.apps.shared.PaymentKeypad
import io.github.kiriashi.biopay.apps.shared.PaymentScreen
import io.github.kiriashi.biopay.apps.shared.PaymentViewTree

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
        return PaymentKeypad.group(root, keys)?.let(::PaymentScreen)
    }

    override fun digitKeys(keyboard: ViewGroup): List<View>? =
        PaymentKeypad.textKeys(PaymentViewTree(keyboard))
}
