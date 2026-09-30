/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.runtime

import android.app.Application
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.apps.qq.QqMenuEntryHook
import io.github.kiriashi.biopay.apps.wechat.FingerprintTipHook
import io.github.kiriashi.biopay.apps.wechat.KeyboardWindowHook
import io.github.kiriashi.biopay.apps.wechat.PullDownHook
import io.github.kiriashi.biopay.apps.wechat.TopActivityProvider
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.libxposed.api.XposedInterface

/** Uses the same installation plan for process startup and module replacement. */
object HookManager {
    internal class Result(
        val handles: List<XposedInterface.HookHandle>,
        val missingRequired: List<String>,
        val missingOptional: List<String>,
        val staleHooks: Int
    ) {
        val ready: Boolean get() = missingRequired.isEmpty() && staleHooks == 0

        fun report(reloading: Boolean) {
            val operation = if (reloading) "hot reload" else "initialization"
            when {
                !ready -> ModuleLog.e {
                    "$operation failed: required=$missingRequired; stale=$staleHooks"
                }
                missingOptional.isNotEmpty() -> ModuleLog.w {
                    "$operation degraded: optional=$missingOptional; installed=${handles.size}"
                }
                reloading -> ModuleLog.summary { "hot reload: ${handles.size} hooks restored" }
                else -> ModuleLog.d { "initialization: ${handles.size} hooks installed" }
            }
        }

        fun rollback() {
            handles.forEach { handle ->
                runCatching { handle.unhook() }.onFailure {
                    ModuleLog.w(it) { "failed to roll back hook ${handle.id}" }
                }
            }
        }
    }

    internal fun install(
        xposed: XposedInterface,
        state: AppRuntime,
        oldHandles: List<XposedInterface.HookHandle> = emptyList()
    ): Result {
        val app = state.adapter.app
        val cl = state.app.classLoader
        if (app == PaymentApp.WECHAT && oldHandles.isNotEmpty()) {
            TopActivityProvider.resolveFromHandles(oldHandles, cl)
        } else if (app != PaymentApp.WECHAT) TopActivityProvider.reset()

        val handled = HashSet<XposedInterface.HookHandle>()
        val installed = mutableListOf<XposedInterface.HookHandle>()
        val required = mutableListOf<String>()
        val optional = mutableListOf<String>()

        fun bind(
            id: String,
            necessary: Boolean = false,
            interceptor: XposedInterface.Hooker,
            register: () -> XposedInterface.HookHandle?
        ) {
            val old = oldHandles.firstOrNull { it.id == id && it !in handled }
            if (old != null) {
                try {
                    old.replaceHook(interceptor)
                    handled += old
                    installed += old
                    return
                } catch (error: Throwable) {
                    ModuleLog.w(error) { "hot reload: replacing $id failed; reinstalling" }
                    if (runCatching { old.unhook() }.isSuccess) {
                        handled += old
                    } else {
                        // Do not add a duplicate interceptor while its predecessor remains attached.
                        if (necessary) required += id else optional += id
                        return
                    }
                }
            }
            val handle = runCatching(register).onFailure {
                ModuleLog.w(it) { "hook installation failed: $id" }
            }.getOrNull()
            if (handle != null) installed += handle
            else if (necessary) required += id else optional += id
        }

        bind(VolumeKeyHook.HOOK_ID, interceptor = VolumeKeyHook.makeInterceptor(state)) {
            VolumeKeyHook.registerActivity(xposed, state)
        }
        if (app == PaymentApp.WECHAT) {
            bind(PullDownHook.HOOK_ID, interceptor = PullDownHook.makeInterceptor(state)) {
                PullDownHook.register(cl, xposed, state)
            }
            bind(KeyboardWindowHook.HOOK_ID, true, KeyboardWindowHook.makeInterceptor(state)) {
                KeyboardWindowHook.register(cl, xposed, state)
            }
            bind(FingerprintTipHook.HOOK_ID, interceptor = FingerprintTipHook.makeInterceptor(state)) {
                FingerprintTipHook.register(cl, xposed, state)
            }
        } else {
            bind(DialogShowHook.HOOK_ID, interceptor = DialogShowHook.makeInterceptor(state)) {
                DialogShowHook.register(xposed, state)
            }
            if (app == PaymentApp.QQ && Application.getProcessName() == state.app.packageName) {
                bind(QqMenuEntryHook.HOOK_ID, true, QqMenuEntryHook.makeInterceptor(state)) {
                    QqMenuEntryHook.registerAddView(xposed, state)
                }
                bind(QqMenuEntryHook.SHOW_DROPDOWN_ID, interceptor = QqMenuEntryHook.makePopupInterceptor(state)) {
                    QqMenuEntryHook.registerShowDropdown(xposed, state)
                }
                bind(QqMenuEntryHook.SHOW_LOCATION_ID, interceptor = QqMenuEntryHook.makePopupInterceptor(state)) {
                    QqMenuEntryHook.registerShowLocation(xposed, state)
                }
                bind(QqMenuEntryHook.DISMISS_ID, interceptor = QqMenuEntryHook.makeDismissInterceptor()) {
                    QqMenuEntryHook.registerDismiss(xposed, state)
                }
            } else {
                bind(PaymentWindowHook.HOOK_ID, true, PaymentWindowHook.interceptor(state)) {
                    PaymentWindowHook.register(xposed, state)
                }
            }
            bind(VolumeKeyHook.DECOR_HOOK_ID, interceptor = VolumeKeyHook.makeInterceptor(state)) {
                VolumeKeyHook.registerPaymentWindow(xposed, state)
            }
            val resumeId = when (app) {
                PaymentApp.ALIPAY -> PaymentActivityLifecycleHook.ALIPAY_RESUME_ID
                PaymentApp.TAOBAO -> PaymentActivityLifecycleHook.TAOBAO_RESUME_ID
                else -> null
            }
            if (resumeId != null) {
                bind(resumeId, true, PaymentActivityLifecycleHook.makeInterceptor(state)) {
                    PaymentActivityLifecycleHook.register(xposed, state)
                }
            }
        }
        InputFeedbackHook.targets(state).forEach { method ->
            bind(InputFeedbackHook.id(method), interceptor = InputFeedbackHook.interceptor()) {
                InputFeedbackHook.register(xposed, method)
            }
        }

        // Includes retired bootstrap hooks and duplicate IDs from older generations.
        var stale = 0
        oldHandles.filterNot { it in handled }.forEach { old ->
            runCatching { old.unhook() }.onFailure {
                stale++
                ModuleLog.w(it) { "hot reload: removing stale hook ${old.id} failed" }
            }
        }
        return Result(installed, required, optional, stale)
    }
}
