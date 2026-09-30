/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.os.Looper
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import io.github.kiriashi.biopay.core.log.ModuleLog

/** Includes Activity, Dialog and popup roots without reading framework internals. */
internal object PaymentWindowRoots {
    fun attached(): List<ViewGroup> {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Window inspection must run on the main thread" }
        return runCatching {
            WindowInspector.getGlobalWindowViews().filterIsInstance<ViewGroup>()
                .filter { it.isAttachedToWindow }
        }.onFailure { ModuleLog.w(it) { "payment window enumeration failed" } }
            .getOrDefault(emptyList())
    }
}
