/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.wechat

import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.apps.PaymentAdapter
import io.github.kiriashi.biopay.apps.PaymentApp

object WeChatAdapter : PaymentAdapter {
    override val app = PaymentApp.WECHAT
    private var cachedPackage: String? = null
    private var cachedIds: IntArray? = null

    override fun digitKeys(keyboard: ViewGroup): List<View?>? {
        // The original WeChat path resolves Tenpay IDs from the host Application.
        val application = keyboard.context.applicationContext ?: keyboard.context
        val resources = application.resources
        val packageName = application.packageName
        val ids = if (cachedPackage == packageName) cachedIds else null
        val resolvedIds = ids ?: IntArray(10) { digit ->
            resources.getIdentifier(HookTargets.tenpayKeyboard + digit, "id", packageName)
        }.also {
            cachedPackage = packageName
            cachedIds = it
        }
        return (0..9).map { digit ->
            val id = resolvedIds[digit]
            if (id == 0) null else keyboard.findViewById<View>(id)
        }
    }
}
