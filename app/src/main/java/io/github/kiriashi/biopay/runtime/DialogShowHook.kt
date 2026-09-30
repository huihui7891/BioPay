/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.runtime

import io.github.kiriashi.biopay.core.log.ModuleLog
import android.app.Dialog
import io.github.libxposed.api.XposedInterface

/** Includes payment dialogs whose window is separate from the Activity decor. */
object DialogShowHook {
    const val HOOK_ID = "bp_dialog_show"

    fun register(xposed: XposedInterface, state: AppRuntime): XposedInterface.HookHandle? {
        return try {
            val method = Dialog::class.java.getDeclaredMethod("show")
            xposed.hook(method).setId(HOOK_ID).intercept(makeInterceptor(state))
        } catch (e: Throwable) {
            ModuleLog.w(e) { "dialog show hook failed" }
            null
        }
    }

    fun makeInterceptor(state: AppRuntime): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        try {
            (chain.thisObject as? Dialog)?.let { dialog ->
                if (!state.isClosed) state.visualMonitor?.watchDialog(dialog)
            }
        } catch (e: Throwable) {
            ModuleLog.w(e) { "dialog inspection setup failed" }
        }
        result
    }
}
