/*
 * BioPay - biometric payment assistance for supported payment apps.
 *
 * Copyright (C) 2026 kiriashi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.kiriashi.biopay.runtime

import android.app.Application
import io.github.kiriashi.biopay.apps.wechat.FingerprintTipHook
import io.github.kiriashi.biopay.apps.wechat.KeyboardWindowHook
import io.github.kiriashi.biopay.apps.wechat.PullDownHook
import io.github.kiriashi.biopay.apps.wechat.TopActivityProvider
import io.github.kiriashi.biopay.apps.qq.QqMenuEntryHook

import io.github.kiriashi.biopay.core.log.LOG_TAG
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.kiriashi.biopay.apps.PaymentApp

object HookManager {


    fun init(classLoader: ClassLoader, xposed: XposedInterface, state: AppRuntime) {
        Log.d(LOG_TAG, "HookManager.init classLoader=${classLoader.javaClass.name}@${Integer.toHexString(classLoader.hashCode())}")
        if (state.adapter.app == PaymentApp.WECHAT) {
            PullDownHook.register(classLoader, xposed, state)
            KeyboardWindowHook.register(classLoader, xposed, state)
            FingerprintTipHook.register(classLoader, xposed, state)
        } else {
            DialogShowHook.register(xposed, state)
            PaymentActivityLifecycleHook.register(xposed, state)
            if (usesQqMenuHook(state)) QqMenuEntryHook.register(xposed, state)
            else PaymentWindowHook.register(xposed, state)
        }
        VolumeKeyHook.register(xposed, state)
        InputFeedbackHook.targets(state).forEach { InputFeedbackHook.register(xposed, it) }
    }

    fun replaceHooksFromOldGeneration(
        oldHandles: List<XposedInterface.HookHandle>,
        xposed: XposedInterface,
        state: AppRuntime
    ) {
        val app = state.adapter.app
        if (app == PaymentApp.WECHAT) TopActivityProvider.resolveFromHandles(oldHandles, state.app.classLoader)
        else TopActivityProvider.reset()

        val handled = HashSet<XposedInterface.HookHandle>()

        fun bind(id: String, interceptor: XposedInterface.Hooker, register: () -> Unit) {
            val old = oldHandles.firstOrNull { it.id == id && it !in handled }
            if (old != null) {
                try {
                    old.replaceHook(interceptor)
                    handled += old
                    return
                } catch (e: Throwable) {
                    Log.w(LOG_TAG, "hot reload: replacing $id failed; reinstalling", e)
                    val removed = runCatching { old.unhook() }.isSuccess
                    if (removed) handled += old
                }
            }
            register()
        }

        fun discard(id: String) {
            oldHandles.filter { it.id == id && it !in handled }.forEach { old ->
                runCatching { old.unhook() }
                    .onFailure { Log.w(LOG_TAG, "hot reload: removing obsolete $id failed", it) }
                handled += old
            }
        }

        // These bootstrap hooks are only needed before Application.onCreate. The
        // current Application already exists in this rebind path.
        discard("bp_app_oncreate")
        discard("bp_instrumentation_app_oncreate")

        bind(VolumeKeyHook.HOOK_ID, VolumeKeyHook.makeInterceptor(state)) {
            VolumeKeyHook.registerActivity(xposed, state)
        }

        if (app == PaymentApp.WECHAT) {
            bind(PullDownHook.HOOK_ID, PullDownHook.makeInterceptor(state)) {
                state.app.classLoader?.let { PullDownHook.register(it, xposed, state) }
            }
            bind(KeyboardWindowHook.HOOK_ID, KeyboardWindowHook.makeInterceptor(state)) {
                state.app.classLoader?.let { KeyboardWindowHook.register(it, xposed, state) }
            }
            bind(FingerprintTipHook.HOOK_ID, FingerprintTipHook.makeInterceptor(xposed, state)) {
                state.app.classLoader?.let { FingerprintTipHook.register(it, xposed, state) }
            }
        } else {
            bind(DialogShowHook.HOOK_ID, DialogShowHook.makeInterceptor(state)) {
                DialogShowHook.register(xposed, state)
            }
            if (usesQqMenuHook(state)) {
                bind(QqMenuEntryHook.HOOK_ID, QqMenuEntryHook.makeInterceptor(state)) {
                    QqMenuEntryHook.registerAddView(xposed, state)
                }
                bind(QqMenuEntryHook.SHOW_DROPDOWN_ID, QqMenuEntryHook.makePopupInterceptor(state)) {
                    QqMenuEntryHook.registerShowDropdown(xposed, state)
                }
                bind(QqMenuEntryHook.SHOW_LOCATION_ID, QqMenuEntryHook.makePopupInterceptor(state)) {
                    QqMenuEntryHook.registerShowLocation(xposed, state)
                }
                bind(QqMenuEntryHook.DISMISS_ID, QqMenuEntryHook.makeDismissInterceptor()) {
                    QqMenuEntryHook.registerDismiss(xposed, state)
                }
            } else {
                bind(PaymentWindowHook.HOOK_ID, PaymentWindowHook.interceptor(state)) {
                    PaymentWindowHook.register(xposed, state)
                }
            }
            bind(VolumeKeyHook.DECOR_HOOK_ID, VolumeKeyHook.makeInterceptor(state)) {
                VolumeKeyHook.registerPaymentWindow(xposed, state)
            }
            val resumeId = when (app) {
                PaymentApp.ALIPAY -> PaymentActivityLifecycleHook.ALIPAY_RESUME_ID
                PaymentApp.TAOBAO -> PaymentActivityLifecycleHook.TAOBAO_RESUME_ID
                else -> null
            }
            if (resumeId != null) {
                bind(resumeId, PaymentActivityLifecycleHook.makeInterceptor(state)) {
                    PaymentActivityLifecycleHook.register(xposed, state)
                }
            }
        }

        InputFeedbackHook.targets(state).forEach { method ->
            bind(InputFeedbackHook.id(method), InputFeedbackHook.interceptor()) {
                InputFeedbackHook.register(xposed, method)
            }
        }

        // Remove handles from retired or unrecognized generations. Leaving one
        // attached would keep its old module classloader and runtime alive.
        oldHandles.filterNot { it in handled }.forEach { old ->
            runCatching { old.unhook() }
                .onFailure { Log.w(LOG_TAG, "hot reload: removing stale hook ${old.id} failed", it) }
        }
        Log.d(LOG_TAG, "hot reload: rebound hooks for ${app.displayName}; ${oldHandles.size} old handles")
    }

    private fun usesQqMenuHook(state: AppRuntime): Boolean =
        state.adapter.app == PaymentApp.QQ && Application.getProcessName() == state.app.packageName
}
