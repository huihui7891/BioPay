/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps

import io.github.kiriashi.biopay.apps.alipay.AlipayEntry
import io.github.kiriashi.biopay.apps.qq.QqDetector
import io.github.kiriashi.biopay.apps.qq.QqEntry
import io.github.kiriashi.biopay.apps.shared.AlipayTaobaoDetector
import io.github.kiriashi.biopay.apps.shared.PaymentDetector
import io.github.kiriashi.biopay.apps.shared.SettingsEntry
import io.github.kiriashi.biopay.apps.taobao.TaobaoEntry
import io.github.kiriashi.biopay.apps.unionpay.UnionPayDetector
import io.github.kiriashi.biopay.apps.unionpay.UnionPayEntry
import io.github.kiriashi.biopay.apps.wechat.WeChatAdapter

/** Packages whose payment screens BioPay can inspect when selected in LSPosed. */
enum class PaymentApp(val packageName: String, val displayName: String) {
    WECHAT("com.tencent.mm", "微信"),
    ALIPAY("com.eg.android.AlipayGphone", "支付宝"),
    TAOBAO("com.taobao.taobao", "淘宝"),
    QQ("com.tencent.mobileqq", "QQ"),
    UNIONPAY("com.unionpay", "云闪付");

    /** QQ Wallet runs payment UI in :tool; other child processes stay outside the payment hooks. */
    fun handlesProcess(name: String): Boolean =
        name == packageName || (this == QQ && name == "$packageName:tool")

    companion object {
        fun fromPackage(packageName: String): PaymentApp? = entries.firstOrNull { it.packageName == packageName }
    }
}

object AppComponents {
    fun adapterFor(packageName: String): PaymentAdapter? = when (val app = PaymentApp.fromPackage(packageName)) {
        null -> null
        PaymentApp.WECHAT -> WeChatAdapter
        else -> VisualPaymentAdapter(app, detectorFor(app))
    }

    internal fun entryFor(app: PaymentApp): SettingsEntry? = when (app) {
        PaymentApp.ALIPAY -> AlipayEntry
        PaymentApp.TAOBAO -> TaobaoEntry()
        PaymentApp.QQ -> QqEntry
        PaymentApp.UNIONPAY -> UnionPayEntry
        PaymentApp.WECHAT -> null
    }

    private fun detectorFor(app: PaymentApp): PaymentDetector = when (app) {
        PaymentApp.ALIPAY, PaymentApp.TAOBAO -> AlipayTaobaoDetector
        PaymentApp.QQ -> QqDetector
        PaymentApp.UNIONPAY -> UnionPayDetector
        PaymentApp.WECHAT -> error("WeChat uses its Tenpay hooks")
    }
}
