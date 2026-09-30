/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.util.Log
import android.view.ViewGroup
import io.github.kiriashi.biopay.core.log.LOG_TAG
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Finds dialogs and popup windows that are outside an Activity's decor tree. */
internal object PaymentWindowRoots {
    private data class Access(val getInstance: Method, val views: Field)

    private val access: Access? by lazy {
        runCatching {
            val type = Class.forName("android.view.WindowManagerGlobal")
            Access(
                type.getDeclaredMethod("getInstance").apply { isAccessible = true },
                type.getDeclaredField("mViews").apply { isAccessible = true }
            )
        }.onFailure { Log.w(LOG_TAG, "payment window enumeration unavailable", it) }.getOrNull()
    }

    fun attached(): List<ViewGroup> {
        val reflection = access ?: return emptyList()
        return runCatching {
            val manager = reflection.getInstance.invoke(null)
            val views = reflection.views.get(manager) as? Iterable<*> ?: return emptyList()
            buildList {
                for (view in views) {
                    if (view is ViewGroup && view.isAttachedToWindow) add(view)
                }
            }
        }.getOrDefault(emptyList())
    }
}
