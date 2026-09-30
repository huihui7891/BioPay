/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.runtime

import io.github.kiriashi.biopay.core.log.ModuleLog
import android.content.Context
import android.view.View
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.payment.PasswordAutoInput
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Silences feedback initiated synchronously by automatic Alipay/Taobao keys. */
internal object InputFeedbackHook {
    @Suppress("DEPRECATION") // The legacy vibrator is still needed on Android 10–11.
    fun targets(state: AppRuntime): List<Method> {
        if (state.adapter.app !in setOf(PaymentApp.ALIPAY, PaymentApp.TAOBAO)) return emptyList()
        val methods = View::class.java.declaredMethods.filter {
            it.name == "performHapticFeedback" && it.returnType == Boolean::class.javaPrimitiveType
        }.toMutableList()
        // The concrete vibrator and manager cover both legacy and modern APIs.
        // Resolve implementations at runtime because Android versions differ.
        for (service in listOf(Context.VIBRATOR_SERVICE, "vibrator_manager")) {
            runCatching {
                var type: Class<*>? = state.app.getSystemService(service)?.javaClass
                while (type != null && type != Any::class.java) {
                    methods += type.declaredMethods.filter {
                        it.name == "vibrate" && it.returnType == Void.TYPE &&
                            !Modifier.isAbstract(it.modifiers)
                    }
                    type = type.superclass
                }
            }.onFailure { ModuleLog.w(it) { "input feedback target lookup failed: $service" } }
        }
        return methods.distinct()
    }

    fun id(method: Method): String = "bp_input_feedback:${method.declaringClass.name}:" +
        method.name + method.parameterTypes.joinToString(",", "(", ")") { it.name }

    fun interceptor(): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        if (!PasswordAutoInput.suppressesFeedback()) {
            chain.proceed()
        } else {
            // Report haptic feedback as handled so callers do not try a fallback.
            if (chain.thisObject is View) true else null
        }
    }

    fun register(xposed: XposedInterface, method: Method): XposedInterface.HookHandle? =
        runCatching { xposed.hook(method).setId(id(method)).intercept(interceptor()) }
            .onFailure { ModuleLog.w(it) { "input feedback hook failed: ${id(method)}" } }
            .getOrNull()
}
