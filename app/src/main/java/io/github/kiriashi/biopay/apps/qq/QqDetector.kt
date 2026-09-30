/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.qq

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import io.github.kiriashi.biopay.apps.shared.PaymentDetector
import io.github.kiriashi.biopay.apps.shared.PaymentKeypad
import io.github.kiriashi.biopay.apps.shared.PaymentScreen
import io.github.kiriashi.biopay.apps.shared.PaymentViewTree

/** QQ's short payment password field and MyKeyboardWindow identify its payment dialog. */
internal object QqDetector : PaymentDetector {
    private val paymentHosts = listOf(
        "QWalletPluginProxyActivity", "QWalletToolFragmentActivity"
    )
    private val keyPrefixes = arrayOf("tenpay_keyboard_", "qqpay_keyboard_", "keyboard_num_", "key_num_")

    override fun supports(activity: Activity): Boolean = paymentHosts.any { activity.javaClass.name.contains(it) }

    override fun find(root: ViewGroup, activity: Activity): PaymentScreen? {
        if (!supports(activity)) return null
        val views = PaymentViewTree(root)
        if (!views.complete) return null
        val input = views.all.firstOrNull {
            it is EditText && it.contentDescription?.toString() in QQ_PASSWORD_DESCRIPTIONS && it.text.isNullOrEmpty()
        } as? EditText ?: return null
        val keyboard = views.all.firstOrNull {
            it is ViewGroup && it.javaClass.name.endsWith(".MyKeyboardWindow") && it.isAttachedToWindow
        } as? ViewGroup ?: return null
        return PaymentScreen(keyboard, input)
    }

    override fun digitKeys(keyboard: ViewGroup): List<View>? = PaymentKeypad.namedKeys(
        PaymentViewTree(keyboard),
        keyPrefixes
    )

    private val QQ_PASSWORD_DESCRIPTIONS = setOf("支付密码", "支付密码输入框")
}
