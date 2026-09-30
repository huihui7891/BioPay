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
import android.view.ViewGroup
import android.widget.EditText
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.libxposed.api.XposedInterface

object KeyboardWindowHook {

    const val HOOK_ID = "bp_keyboard_window"
    fun register(cl: ClassLoader, xposed: XposedInterface, state: AppRuntime): XposedInterface.HookHandle? {
        return try {
            val clazz = cl.loadClass(HookTargets.MyKeyboardWindow)
            val method = clazz.getDeclaredMethod("setInputEditText", EditText::class.java)
            xposed.hook(method).setId(HOOK_ID).intercept(makeInterceptor(state))
        } catch (e: Throwable) {
            ModuleLog.w(e) { "register failed" }
            null
        }
    }
    fun makeInterceptor(state: AppRuntime): XposedInterface.Hooker {
        return XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            try {
                val encodedPassword = state.prefs.activePassword() ?: return@Hooker result

                val inputEditText = chain.args[0] as? EditText
                val keyboardView = chain.thisObject as? ViewGroup
                val samePayment = inputEditText != null && keyboardView != null &&
                    keyboardView.isAttachedToWindow && state.session.isInPaymentMode() &&
                    state.session.getCurrentKeyboardView() === keyboardView &&
                    state.session.getInputEditText() === inputEditText &&
                    state.session.getCurrentEncodedPassword() == encodedPassword
                if (inputEditText != null) {
                    state.session.setInputEditText(inputEditText)
                }

                if (keyboardView != null) {
                    if (samePayment) return@Hooker result
                    ModuleLog.d { "setInputEditText intercepted, view=${keyboardView.hashCode()}, biometricInProgress=${state.session.isAuthenticationInProgress()}" }
                    state.flow.setupBiometricAuth(keyboardView, encodedPassword)
                }
            } catch (e: Throwable) {
                ModuleLog.w(e) { "keyboardWindow interceptor failed" }
            }
            result
        }
    }
}
