/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.shared

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.apps.unionpay.UnionPayDetector
import io.github.kiriashi.biopay.apps.wechat.WeChatMask

internal data class MaskLayout(
    val host: ViewGroup,
    val targets: List<View>,
    val password: View?,
    val keyboard: ViewGroup,
    val fillWidth: Boolean,
    val colorSource: View? = null,
    val extraRegion: ((Rect) -> Boolean)? = null
)

/** Resolves app-specific controls once for each input operation. */
internal object PaymentMasks {
    private val passwordNames = setOf("simplePwdLayout", "mini_linSimplePwdComponent", "input_et_password")

    fun resolve(
        app: PaymentApp, keyboard: ViewGroup, input: View?, confirm: View?, keys: List<View>?
    ): List<MaskLayout> {
        val root = keyboard.rootView as? ViewGroup ?: return emptyList()
        val keypad = if (keyboard === root) {
            keys?.takeIf { it.size == 10 }?.let { PaymentKeypad.group(root, it) }
        } else keyboard
        val password = when (app) {
            PaymentApp.WECHAT -> WeChatMask.passwordPanel(input)
            PaymentApp.UNIONPAY -> keypad?.let { UnionPayDetector.passwordPanel(root, it) }
            else -> input
        }
        val flutter = if (app == PaymentApp.WECHAT && password == null) WeChatMask.flutterRegion(keyboard) else null
        val windows = linkedMapOf<ViewGroup, MutableList<View>>()
        for (view in listOfNotNull(password, keypad, input, confirm).distinct()) {
            if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) continue
            val window = view.rootView as? ViewGroup ?: continue
            if (view !== window) windows.getOrPut(window) { mutableListOf() }.add(view)
        }
        return windows.mapNotNull { (window, controls) ->
            val tree = PaymentViewTree(window)
            if (!tree.complete) return@mapNotNull null
            val flybird = app == PaymentApp.ALIPAY || app == PaymentApp.TAOBAO
            val host = if (flybird) tree.all.firstOrNull {
                it is ViewGroup && tree.resourceName(it) == "flybird_main_layout" &&
                    controls.all { control -> contains(it, control) }
            } as? ViewGroup ?: window else window
            val field = if (flybird && keypad != null) nearestPassword(tree.all.filter {
                contains(host, it) && !contains(keypad, it) &&
                    (tree.resourceName(it) in passwordNames || it.contentDescription?.toString()?.let { label ->
                        label.startsWith("密码共6位") || label == "支付密码" || label == "支付密码输入框"
                    } == true)
            }, keypad) else password?.takeIf { it.rootView === window }
            val targets = (listOfNotNull(field) + controls).distinct().filter { contains(host, it) && it !== host }
            if (targets.isEmpty()) return@mapNotNull null
            MaskLayout(
                host, targets, field, keypad ?: keyboard,
                app != PaymentApp.QQ,
                colorSource = if (flutter != null && window === root) tree.all.firstOrNull {
                    contains(keyboard, it) && tree.resourceName(it) == "tenpay_keyboard_1"
                } ?: keyboard else null,
                extraRegion = flutter.takeIf { window === root }
            )
        }
    }

    fun nearestPassword(candidates: List<View>, keyboard: View): View? {
        val keypad = Rect()
        if (!keyboard.getGlobalVisibleRect(keypad)) return null
        val bounds = Rect()
        var nearest: View? = null
        var smallestGap = Int.MAX_VALUE
        for (view in candidates) {
            if (!view.isAttachedToWindow || !view.isShown || !view.getGlobalVisibleRect(bounds) ||
                bounds.isEmpty || bounds.bottom > keypad.top ||
                minOf(bounds.right, keypad.right) - maxOf(bounds.left, keypad.left) < bounds.width() / 2
            ) continue
            val gap = keypad.top - bounds.bottom
            if (gap < smallestGap) {
                nearest = view
                smallestGap = gap
            }
        }
        return nearest
    }

    private fun contains(parent: View, child: View): Boolean =
        generateSequence(child) { it.parent as? View }.any { it === parent }
}
