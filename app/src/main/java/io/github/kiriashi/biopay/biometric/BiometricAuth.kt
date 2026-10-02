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
package io.github.kiriashi.biopay.biometric

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.BuildConfig
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.payment.PasswordAutoInput
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.kiriashi.biopay.storage.PasswordCipher
import io.github.kiriashi.biopay.storage.PaymentConfig

object BiometricAuth {
    fun triggerBiometricAuth(
        keyboardView: ViewGroup,
        encodedPassword: String,
        state: AppRuntime,
        sessionId: Long
    ): Boolean {
        val config = state.session.currentConfig() ?: return false
        if (state.isClosed || !state.session.isCurrentSession(sessionId) ||
            config.encryptedPassword != encodedPassword || !state.prefs.isCurrent(config)) {
            ModuleLog.d { "biometric auth skipped: payment session expired or settings changed, app=${state.adapter.app}" }
            return false
        }
        // Plugin payment views can carry a different Activity from their host window.
        val activity = state.session.getHostActivity() ?: keyboardView.context.findActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            ModuleLog.w { "biometric auth skipped: keypad context has no live Activity, app=${state.adapter.app}, context=${keyboardView.context.javaClass.name}" }
            return false
        }
        if (PasswordAutoInput.isInProgress(sessionId)) return false
        val biometricType = config.biometricType
        if (biometricType !in BiometricType.BOTH..BiometricType.FACE) {
            ModuleLog.d { "biometric auth skipped: no biometric method enabled, app=${state.adapter.app}" }
            return false
        }
        val attempt = state.session.beginAuthentication() ?: return false
        try {
            state.session.suppressInputMethod()
            return state.paymentWorker.submit(
                work = {
                    if (!state.isClosed && state.session.isCurrentSession(sessionId) &&
                        state.session.isCurrentAuthentication(attempt.id)) {
                        measureCrypto("prepare") {
                            PasswordCipher.createDecryptOperation(encodedPassword, state.adapter.app.packageName)
                        }
                    } else null
                },
                discard = { it?.ciphertext?.fill(0) }
            ) { result ->
                val operation = result.getOrNull()
                if (!isCurrent(state, config, sessionId, attempt.id, keyboardView, activity) ||
                    !keyboardView.isShown) {
                    ModuleLog.d { "authentication preparation discarded: app=${state.adapter.app}, session=$sessionId, attempt=${attempt.id}" }
                    operation?.ciphertext?.fill(0)
                    restoreKeyboard(state, sessionId, attempt.id)
                    return@submit
                }
                if (operation == null) {
                    result.exceptionOrNull()?.let { ModuleLog.w(it) { "password decryption preparation failed" } }
                    restoreKeyboard(state, sessionId, attempt.id)
                    return@submit
                }
                val callback = BiometricAuthCallback(
                    activity, operation, config, state, sessionId, attempt.id
                )
                try {
                    val executor = activity.mainExecutor
                    val builder = BiometricPrompt.Builder(activity)
                        .setTitle(BioPayPrompt.TITLE)
                        .setNegativeButton("取消", executor) { _, _ -> callback.cancel() }
                    BiometricPromptPolicy.configure(builder, biometricType).build()
                        .authenticate(attempt.signal, executor, callback)
                    if (state.session.isCurrentAuthentication(attempt.id)) {
                        // INVISIBLE preserves external payment keyboard attachment and the active prompt.
                        keyboardView.visibility = View.INVISIBLE
                        ModuleLog.d { "trigger: type=$biometricType, session=$sessionId, attempt=${attempt.id}" }
                    }
                } catch (error: Exception) {
                    callback.cancel()
                    ModuleLog.w(error) { "biometric auth failed" }
                }
            }.also { submitted ->
                if (!submitted) restoreKeyboard(state, sessionId, attempt.id)
            }
        } catch (e: Throwable) {
            restoreKeyboard(state, sessionId, attempt.id)
            ModuleLog.w(e) { "biometric auth failed" }
            return false
        }
    }

    private fun <T> measureCrypto(phase: String, work: () -> T): T {
        val start = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L
        return try { work() } finally {
            if (BuildConfig.DEBUG) ModuleLog.d { "payment crypto: phase=$phase, duration=${SystemClock.elapsedRealtime() - start}ms" }
        }
    }

    private fun isCurrent(
        state: AppRuntime, config: PaymentConfig, sessionId: Long, attemptId: Long,
        keyboard: ViewGroup, activity: Activity
    ): Boolean = !state.isClosed && state.session.isCurrentSession(sessionId) &&
        state.session.isCurrentAuthentication(attemptId) &&
        state.session.currentConfig() == config && state.prefs.isCurrent(config) &&
        state.session.getCurrentKeyboardView() === keyboard &&
        state.session.getHostActivity() === activity &&
        // Plugin hosts may expose a separate Application instance for the same package.
        !activity.isFinishing && !activity.isDestroyed && activity.packageName == state.app.packageName &&
        keyboard.isAttachedToWindow && keyboard.windowVisibility == View.VISIBLE &&
        keyboard.rootView.context.findActivity()?.let { it === activity } != false

    private fun restoreKeyboard(state: AppRuntime, sessionId: Long, attemptId: Long) {
        if (state.session.isCurrentSession(sessionId) && state.session.finishAuthentication(attemptId)) {
            state.session.restoreKeyboard(sessionId)
        }
    }

    private class BiometricAuthCallback(
        private val activity: Activity,
        operation: PasswordCipher.DecryptOperation,
        private val config: PaymentConfig,
        private val state: AppRuntime,
        private val sessionId: Long,
        private val attemptId: Long
    ) : BiometricPrompt.AuthenticationCallback() {
        private var operation: PasswordCipher.DecryptOperation? = operation
        private var decrypting = false

        fun cancel() {
            if (decrypting) return
            operation?.ciphertext?.fill(0)
            operation = null
            restoreKeyboard(state, sessionId, attemptId)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            if (decrypting) return
            cancel()
            ModuleLog.d { "onAuthError: code=$errorCode, attempt=$attemptId" }
        }

        override fun onAuthenticationFailed() {
            if (state.session.isCurrentSession(sessionId) && state.session.isCurrentAuthentication(attemptId)) {
                ModuleLog.d { "onAuthFailed: attempt=$attemptId" }
            }
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            val keyboard = state.session.getCurrentKeyboardView()
            if (keyboard == null || !isCurrent(state, config, sessionId, attemptId, keyboard, activity)) {
                cancel()
                return
            }
            val pending = operation ?: return
            operation = null
            decrypting = true
            ModuleLog.d { "onAuthSucceeded: attempt=$attemptId" }
            // The authentication attempt remains active until decryption completes.
            // Layout callbacks and volume keys cannot start another prompt in between.
            val submitted = state.paymentWorker.submit(
                work = {
                    if (!state.isClosed && state.session.isCurrentSession(sessionId) &&
                        state.session.isCurrentAuthentication(attemptId)) {
                        measureCrypto("decrypt") { PasswordCipher.decryptToCharArray(pending) }
                    } else null
                },
                cleanup = { pending.ciphertext.fill(0) },
                discard = { it?.fill('\u0000') }
            ) { result -> finishInput(result) }
            if (!submitted) restoreKeyboard(state, sessionId, attemptId)
        }

        private fun finishInput(result: Result<CharArray?>) {
            val password = result.getOrNull()
            try {
                // WeChat may replace its keyboard while the prompt is open. Only
                // the live binding for this session and Activity may receive input.
                val keyboard = state.session.getCurrentKeyboardView()
                if (keyboard == null || !isCurrent(state, config, sessionId, attemptId, keyboard, activity)) {
                    restoreKeyboard(state, sessionId, attemptId)
                    return
                }
                if (password == null) {
                    result.exceptionOrNull()?.let { ModuleLog.w(it) { "password decryption failed" } }
                    restoreKeyboard(state, sessionId, attemptId)
                    return
                }
                if (!state.session.finishAuthentication(attemptId)) return
                keyboard.visibility = View.VISIBLE
                PasswordAutoInput.cancelPendingRunnables()
                if (!PasswordAutoInput.autoInputPassword(keyboard, password, state, sessionId, config)) {
                    state.session.restoreKeyboard(sessionId)
                }
            } catch (e: Throwable) {
                ModuleLog.w(e) { "post-authentication input failed" }
                state.session.finishAuthentication(attemptId)
                state.session.restoreKeyboard(sessionId)
            } finally {
                password?.fill('\u0000')
            }
        }
    }
}
