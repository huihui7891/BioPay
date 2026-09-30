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
package io.github.kiriashi.biopay.apps.wechat

import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.libxposed.api.XposedInterface

object FingerprintTipHook {
    const val HOOK_ID = "bp_fingerprint_error"

    fun register(cl: ClassLoader, xposed: XposedInterface, state: AppRuntime): XposedInterface.HookHandle? {
        return try {
            val dialog = cl.loadClass(HookTargets.AlertDialogImpl)
            val callback = cl.loadClass(HookTargets.VoidCallback)
            val method = dialog.getDeclaredMethod(
                "showTipsImpl", String::class.java, String::class.java, String::class.java, callback
            )
            TopActivityProvider.resolve(cl)
            xposed.hook(method).setId(HOOK_ID).intercept(makeInterceptor(state))
        } catch (e: Throwable) {
            ModuleLog.e(e) { "Fingerprint tip hook unavailable" }
            null
        }
    }

    fun makeInterceptor(state: AppRuntime): XposedInterface.Hooker {
        val recovery = FingerprintTipRecovery { failure ->
            ModuleLog.w(failure) { "Fingerprint tip: page continuation failed" }
        }
        return XposedInterface.Hooker { chain ->
            val suppress = recovery.suppressIfMatched(
                enabled = state.prefs.isBioPayEnabled(),
                message = chain.args.getOrNull(0),
                currentPage = {
                    TopActivityProvider.getTopActivity()
                        ?.takeUnless { it.isFinishing || it.isDestroyed }
                        ?.let(WeChatPaymentContinuation::currentPage)
                },
                resolveAction = { page ->
                    WeChatPaymentContinuation.resolvePage(page)?.let { action ->
                        {
                            action()
                            ModuleLog.d { "Fingerprint tip: continued WeChat page" }
                        }
                    }
                }
            )
            if (suppress) {
                ModuleLog.d { "Fingerprint tip: suppressed known WeChat tip" }
                null
            } else {
                chain.proceed()
            }
        }
    }
}
