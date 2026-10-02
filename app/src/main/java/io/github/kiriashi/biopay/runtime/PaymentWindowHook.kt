/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.runtime

import io.github.kiriashi.biopay.core.log.ModuleLog
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.kiriashi.biopay.apps.VisualPaymentAdapter
import io.github.kiriashi.biopay.core.util.findActivity
import java.lang.reflect.Method

/** Observes payment popups created outside Dialog.show. */
internal object PaymentWindowHook {
    const val HOOK_ID = "bp_payment_window_add_view"

    fun addViewMethod(): Method = Class.forName("android.view.WindowManagerGlobal")
        .declaredMethods
        .filter { method ->
            val args = method.parameterTypes
            method.name == "addView" && args.size >= 2 &&
                View::class.java.isAssignableFrom(args[0]) &&
                ViewGroup.LayoutParams::class.java.isAssignableFrom(args[1])
        }
        .maxByOrNull { it.parameterTypes.size }
        ?: error("WindowManagerGlobal.addView was not found")

    fun register(xposed: XposedInterface, state: AppRuntime): XposedInterface.HookHandle? {
        return try {
            xposed.hook(addViewMethod()).setId(HOOK_ID).intercept(interceptor(state))
        } catch (e: Throwable) {
            ModuleLog.w(e) { "payment window hook registration failed" }
            null
        }
    }

    fun interceptor(state: AppRuntime): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        try {
            (chain.args.firstOrNull() as? ViewGroup)?.let { root ->
                if (!shouldInspect(state, root)) return@let
                root.post {
                    if (state.isClosed || !root.isAttachedToWindow) return@post
                    runCatching { state.visualMonitor?.watchWindow(root) }
                        .onFailure { ModuleLog.w(it) { "payment window inspection failed" } }
                }
            }
        } catch (e: Throwable) {
            ModuleLog.w(e) { "payment window inspection setup failed" }
        }
        result
    }

    internal fun shouldInspect(state: AppRuntime, root: ViewGroup): Boolean {
        if (state.isClosed || !state.prefs.isBioPayEnabled()) return false
        val adapter = state.adapter as? VisualPaymentAdapter ?: return false
        // Some plugin windows have no Activity context; the monitor resolves their current host.
        val activity = root.context.findActivity() ?: return true
        return activity.packageName == state.app.packageName &&
            !activity.isFinishing && !activity.isDestroyed && adapter.supports(activity)
    }
}
