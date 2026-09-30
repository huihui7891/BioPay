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

import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.payment.KeyboardCloak
import io.github.kiriashi.biopay.payment.PasswordAutoInput

import android.hardware.biometrics.BiometricPrompt
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.storage.PasswordCipher
import io.github.kiriashi.biopay.storage.PaymentConfig
import io.github.kiriashi.biopay.runtime.AppRuntime

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
        val activity = keyboardView.context.findActivity() ?: state.session.getHostActivity()
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
        val operation = PasswordCipher.createDecryptOperation(encodedPassword, state.adapter.app.packageName)
        if (operation == null) {
            state.session.finishAuthentication(attempt.id)
            state.session.restoreKeyboard(sessionId)
            return false
        }
        try {
            val executor = activity.mainExecutor
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(BioPayPrompt.TITLE)
                .setNegativeButton("取消", executor) { _, _ ->
                    operation.ciphertext.fill(0)
                    if (state.session.isCurrentSession(sessionId) && state.session.finishAuthentication(attempt.id)) {
                        state.session.restoreKeyboard(sessionId)
                    }
                }
            val callback = BiometricAuthCallback(
                keyboardView, operation, config, state, sessionId, attempt.id
            )
            BiometricPromptPolicy.configure(builder, biometricType).build()
                .authenticate(attempt.signal, executor, callback)
            if (!state.session.isCurrentAuthentication(attempt.id)) return false
            // INVISIBLE preserves external payment keyboard attachment and the active prompt.
            keyboardView.visibility = View.INVISIBLE
            ModuleLog.d { "trigger: type=$biometricType, session=$sessionId, attempt=${attempt.id}" }
            return true
        } catch (e: Throwable) {
            operation.ciphertext.fill(0)
            if (state.session.isCurrentSession(sessionId) && state.session.finishAuthentication(attempt.id)) {
                state.session.restoreKeyboard(sessionId)
            }
            ModuleLog.w(e) { "biometric auth failed" }
            return false
        }
    }

    private class BiometricAuthCallback(
        private val keyboardView: ViewGroup,
        private val operation: PasswordCipher.DecryptOperation,
        private val config: PaymentConfig,
        private val state: AppRuntime,
        private val sessionId: Long,
        private val attemptId: Long
    ) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            operation.ciphertext.fill(0)
            if (!state.session.isCurrentSession(sessionId) || !state.session.finishAuthentication(attemptId)) return
            ModuleLog.d { "onAuthError: code=$errorCode, attempt=$attemptId" }
            state.session.restoreKeyboard(sessionId)
        }

        override fun onAuthenticationFailed() {
            if (state.session.isCurrentSession(sessionId) && state.session.isCurrentAuthentication(attemptId)) {
                ModuleLog.d { "onAuthFailed: attempt=$attemptId" }
            }
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            if (!state.session.isCurrentSession(sessionId) || !state.session.finishAuthentication(attemptId)) {
                operation.ciphertext.fill(0)
                return
            }
            ModuleLog.d { "onAuthSucceeded: attempt=$attemptId" }

            finishInput()
        }

        private fun finishInput() {
            var password: CharArray? = null
            try {
                if (state.isClosed || state.session.currentConfig() != config || !state.prefs.isCurrent(config)) {
                    state.session.restoreKeyboard(sessionId)
                    return
                }
                password = PasswordCipher.decryptToCharArray(operation)
                if (password == null) {
                    state.session.restoreKeyboard(sessionId)
                    return
                }
                val currentView = state.session.getCurrentKeyboardView() ?: keyboardView
                currentView.visibility = View.VISIBLE
                PasswordAutoInput.cancelPendingRunnables()
                KeyboardCloak.concealActivityWindow(state)
                if (PasswordAutoInput.autoInputPassword(currentView, password, state, sessionId, config)) {
                    // Resolve visible digit keys before concealing them. QQ writes to its
                    // password field synchronously, so only queued keypad input needs cloaking.
                    if (PasswordAutoInput.isInProgress(sessionId)) {
                        KeyboardCloak.cloakKeyboardViews(currentView)
                    }
                } else {
                    state.session.restoreKeyboard(sessionId)
                }
            } catch (e: Throwable) {
                ModuleLog.w(e) { "post-authentication input failed" }
                state.session.restoreKeyboard(sessionId)
            } finally {
                password?.fill('\u0000')
                operation.ciphertext.fill(0)
            }
        }
    }
}
