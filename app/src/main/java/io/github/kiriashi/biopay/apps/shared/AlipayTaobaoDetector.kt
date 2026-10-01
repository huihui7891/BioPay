/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.shared

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/** Alipay and Taobao share the same password UI family and keypad resource names. */
internal object AlipayTaobaoDetector : PaymentDetector {
    private val paymentHosts = listOf(
        "PayPwdDialogActivity", "MspContainerActivity", "FlyBirdWindowActivity", "PayPwdHalfActivity"
    )
    private val labels = arrayOf(
        "支付宝支付密码", "支付寶支付密碼", "Alipay Payment Password",
        "请输入长密码", "請輸入長密碼", "密码共6位，已输入0位",
        "请输入支付密码", "請輸入支付密碼", "Payment Password"
    )

    override fun supports(activity: Activity): Boolean = paymentHosts.any { activity.javaClass.name.contains(it) }

    override fun find(root: ViewGroup, activity: Activity): PaymentScreen? {
        if (!supports(activity)) return null
        val views = PaymentViewTree(root)
        if (!views.complete) return null
        val halfScreen = activity.javaClass.name.contains("PayPwdHalfActivity")
        val localPaymentUi = hasPaymentUi(views)
        val namedKey1 = views.hasAnyName(*firstKeyNames)
        // The Activity's own decor may hold the title while its payment dialog
        // holds the keys. Never combine evidence from unrelated process windows.
        val hostRoot = activity.window?.decorView as? ViewGroup
        val passwordUi = localPaymentUi || (namedKey1 && hostRoot != null && hostRoot !== root &&
            PaymentViewTree(hostRoot).let { it.complete && hasPaymentUi(it) })
        if (!passwordUi && !(halfScreen && namedKey1)) return null
        val keys = PaymentKeypad.namedKeys(views, keyPrefixes) ?: PaymentKeypad.textKeys(views)
        if (keys != null) PaymentKeypad.group(root, keys)?.let { return PaymentScreen(it, digitKeys = keys) }

        // Some verifyidentity and long-password pages use an EditText and OK button
        // instead of ten Android keypad views. Keep both controls in the same window.
        val input = views.all.firstOrNull {
            it is EditText && views.resourceName(it) == "input_et_password" && it.text.isNullOrEmpty()
        } as? EditText
        val confirm = views.all.firstOrNull { views.resourceName(it) == "button_ok" }
        if (input != null && confirm != null) return PaymentScreen(root, input, confirm)

        // Alibaba builds some keypads after the prompt has been requested. Keep
        // early recognition, but resolve the eventual keys in this window only.
        return if (namedKey1) PaymentScreen(root) else null
    }

    override fun digitKeys(keyboard: ViewGroup): List<View>? {
        val views = PaymentViewTree(keyboard)
        return PaymentKeypad.namedKeys(views, keyPrefixes) ?: PaymentKeypad.textKeys(views)
    }

    private fun hasPaymentUi(views: PaymentViewTree): Boolean = views.hasText(*labels) ||
        (views.hasName("input_et_password") && views.hasName("keyboard_container")) ||
        views.hasAnyName("ll_key_area", "simplePwdLayout", "mini_linSimplePwdComponent")

    private val keyPrefixes = arrayOf("au_num_", "key_num_", "key_", "keyboard_num_")
    private val firstKeyNames = keyPrefixes.map { "${it}1" }.toTypedArray()
}
