/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.runtime

import android.app.Activity
import android.app.Instrumentation
import android.util.Log
import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.libxposed.api.XposedInterface

/** Receives payment Activity resumes that Alibaba's app lifecycle can bypass. */
object PaymentActivityLifecycleHook {
    const val ALIPAY_RESUME_ID = "bp_alipay_activity_resume"
    const val TAOBAO_RESUME_ID = "bp_taobao_instrumentation_resume"

    fun register(xposed: XposedInterface, state: AppRuntime) {
        val app = state.adapter.app
        if (app != PaymentApp.ALIPAY && app != PaymentApp.TAOBAO) return
        try {
            val method = if (app == PaymentApp.ALIPAY) {
                Activity::class.java.getDeclaredMethod("onResume")
            } else {
                Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java)
            }
            val id = if (app == PaymentApp.ALIPAY) ALIPAY_RESUME_ID else TAOBAO_RESUME_ID
            xposed.hook(method).setId(id).intercept(makeInterceptor(state))
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "$app payment Activity resume hook failed", e)
        }
    }

    fun makeInterceptor(state: AppRuntime): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        try {
            val activity = (chain.thisObject as? Activity) ?: (chain.args.firstOrNull() as? Activity)
            if (activity?.packageName == state.app.packageName) {
                state.watchActivity(activity)
            }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "payment Activity resume inspection failed", e)
        }
        result
    }
}
