/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.apps.shared.PaymentDetector
import io.github.kiriashi.biopay.apps.shared.PaymentScreen

/** Resolves an app's ten digit keys for automatic password entry. */
interface PaymentAdapter {
    val app: PaymentApp
    fun digitKeys(keyboard: ViewGroup): List<View?>?
}

/** Keeps the shared payment flow separate from each app's page recognizer. */
class VisualPaymentAdapter internal constructor(
    override val app: PaymentApp,
    private val recognizer: PaymentDetector
) : PaymentAdapter {
    init { require(app != PaymentApp.WECHAT) }

    internal fun supports(activity: Activity): Boolean = recognizer.supports(activity)

    internal fun observe(root: ViewGroup, activity: Activity): PaymentScreen? = recognizer.find(root, activity)

    override fun digitKeys(keyboard: ViewGroup): List<View>? = recognizer.digitKeys(keyboard)
}
