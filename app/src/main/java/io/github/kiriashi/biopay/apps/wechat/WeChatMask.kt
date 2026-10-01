/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.wechat

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.apps.shared.PaymentViewTree
import io.github.kiriashi.biopay.core.util.findActivity

internal object WeChatMask {
    // Reserved password area above the native keypad; Flutter provides no node bounds.
    private const val FLUTTER_PASSWORD_HEIGHT_DP = 75f

    fun passwordPanel(input: View?): View? {
        if (input == null || !input.isAttachedToWindow || !input.isShown) return null
        var current: View? = input
        while (current != null && current !== input.rootView) {
            if (current.javaClass.name.endsWith(".EditHintPasswdView")) return current
            current = current.parent as? View
        }
        return input
    }

    fun flutterRegion(keyboard: ViewGroup): ((Rect) -> Boolean)? {
        val activity = keyboard.context.findActivity() ?: return null
        val name = activity.javaClass.name
        if (!name.endsWith(".WxaLiteAppPayTransparentLiteUI") &&
            !name.endsWith(".WxaLiteAppTransparentLiteUI")) return null
        val root = keyboard.rootView as? ViewGroup ?: return null
        val views = PaymentViewTree(root)
        if (!views.complete) return null
        val flutter = views.all.firstOrNull {
            it.javaClass.name == "io.flutter.embedding.android.FlutterView"
        } ?: return null
        val key = views.all.firstOrNull {
            views.resourceName(it) == "tenpay_keyboard_0" && it.isShown &&
                it.width > 0 && it.height > 0
        } ?: return null
        // The host can be a larger container. Anchor to MyKeyboardWindow,
        // including obfuscated subclasses, rather than that container's top.
        val keypad = generateSequence(key) { it.parent as? View }.firstOrNull { view ->
            generateSequence<Class<*>>(view.javaClass) { it.superclass }.any {
                it.name.endsWith(".MyKeyboardWindow")
            }
        } ?: keyboard
        val stripHeight = (FLUTTER_PASSWORD_HEIGHT_DP * keyboard.resources.displayMetrics.density).toInt()
        val flutterBounds = Rect()
        return { region ->
            if (!key.isAttachedToWindow || !key.isShown || !keypad.getGlobalVisibleRect(region) ||
                !flutter.getGlobalVisibleRect(flutterBounds)) false
            else {
                region.bottom = region.top
                region.top = (region.top - stripHeight).coerceAtLeast(flutterBounds.top)
                !region.isEmpty && region.intersect(flutterBounds)
            }
        }
    }
}
