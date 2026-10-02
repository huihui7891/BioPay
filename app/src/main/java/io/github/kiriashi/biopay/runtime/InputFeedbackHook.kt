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
        // Keep each service's concrete dispatch boundary, rather than its forwarding overloads.
        for (service in listOf(Context.VIBRATOR_SERVICE, "vibrator_manager")) {
            runCatching {
                val type = state.app.getSystemService(service)?.javaClass ?: continue
                methods += dispatchMethods(type)
            }.onFailure { ModuleLog.w(it) { "input feedback target lookup failed: $service" } }
        }
        return methods.distinct()
    }

    private fun dispatchMethods(type: Class<*>): List<Method> {
        val implementations = LinkedHashMap<List<Class<*>>, Method>()
        val boundaries = HashSet<List<Class<*>>>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (method.name != "vibrate" || method.returnType != Void.TYPE ||
                    Modifier.isStatic(method.modifiers)) continue
                val signature = method.parameterTypes.toList()
                if (Modifier.isAbstract(method.modifiers)) boundaries += signature
                else implementations.putIfAbsent(signature, method)
            }
            current = current.superclass
        }
        val dispatch = implementations.filterKeys { it in boundaries }.values.toList()
        // Preserve vendor implementations that expose no abstract dispatch boundary.
        return dispatch.ifEmpty { implementations.values.toList() }
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
