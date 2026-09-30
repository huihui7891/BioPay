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

import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.core.log.LogCapture
import io.github.kiriashi.biopay.apps.PaymentApp
import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import io.github.libxposed.api.XposedInterface

object VolumeKeyHook {

    const val HOOK_ID = "bp_volume_key"
    const val DECOR_HOOK_ID = "bp_payment_decor_volume_key"
    fun register(xposed: XposedInterface, state: AppRuntime) {
        registerActivity(xposed, state)
        if (state.adapter.app != PaymentApp.WECHAT) registerPaymentWindow(xposed, state)
    }

    fun registerActivity(xposed: XposedInterface, state: AppRuntime) {
        try {
            val method = Activity::class.java.getDeclaredMethod(
                "dispatchKeyEvent", KeyEvent::class.java
            )
            xposed.hook(method).setId(HOOK_ID).intercept(makeInterceptor(state))
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "register failed", e)
        }
    }

    fun registerPaymentWindow(xposed: XposedInterface, state: AppRuntime) {
        try {
            val decor = Class.forName("com.android.internal.policy.DecorView")
            val method = decor.getDeclaredMethod("dispatchKeyEvent", KeyEvent::class.java)
            xposed.hook(method).setId(DECOR_HOOK_ID).intercept(makeInterceptor(state))
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "payment window volume key hook failed", e)
        }
    }
    fun makeInterceptor(state: AppRuntime): XposedInterface.Hooker {
        return XposedInterface.Hooker { chain ->
            try {
                val keyboard = state.session.getCurrentKeyboardView()
                val activity = state.session.getHostActivity()
                if (state.session.isInPaymentMode() && keyboard?.isAttachedToWindow == true &&
                    (keyboard.isShown || state.session.isAuthenticationInProgress()) &&
                    activity?.isFinishing != true && activity?.isDestroyed != true) {
                    val event = chain.args[0] as? KeyEvent
                    if (event != null) {
                        val keyCode = event.keyCode
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                            Log.d(LOG_TAG, "volume key intercepted: $keyCode, triggering toggle")
                            LogCapture.log("volume key: $keyCode")
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                state.flow.toggleBetweenBiometricAndKeyboard()
                            }
                            return@Hooker true
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "volumeKey interceptor failed", e)
            }
            chain.proceed()
        }
    }
}
