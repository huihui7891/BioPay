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

internal data class PaymentScreen(
    val keyboard: ViewGroup,
    val passwordInput: EditText? = null,
    val confirmButton: View? = null,
    val digitKeys: List<View>? = null
)

/** An app owns its payment-page evidence and its post-authentication keypad lookup. */
internal interface PaymentDetector {
    fun supports(activity: Activity): Boolean
    fun find(root: ViewGroup, activity: Activity): PaymentScreen?
    fun digitKeys(keyboard: ViewGroup): List<View>?
}
