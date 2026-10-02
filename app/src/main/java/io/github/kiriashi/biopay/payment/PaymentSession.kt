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

package io.github.kiriashi.biopay.payment

import android.app.Activity
import android.content.Context
import android.os.CancellationSignal
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.MainTasks
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.storage.PaymentConfig
import java.lang.ref.WeakReference

class PaymentSession(private val onDestroy: () -> Unit = {}) {
    private val sessionToken = SessionToken()
    private val authenticationToken = SessionToken()
    private val tasks = MainTasks()
    private val signalLock = Any()

    data class AuthenticationAttempt(val id: Long, val signal: CancellationSignal)
    private data class InputPolicy(val input: WeakReference<EditText>, val showOnFocus: Boolean)

    @Volatile private var cancelSignal: CancellationSignal? = null
    @Volatile private var config: PaymentConfig? = null
    @Volatile private var currentKeyboardViewRef: WeakReference<ViewGroup>? = null
    @Volatile private var inputEditTextRef: WeakReference<EditText>? = null
    @Volatile private var confirmButtonRef: WeakReference<View>? = null
    @Volatile private var hostActivityRef: WeakReference<Activity>? = null
    private var inputPolicy: InputPolicy? = null
    private var usesSystemIme = false

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (currentSessionId() == 0L) return
            cleanupExpiredReferences()
            if (currentSessionId() != 0L) tasks.post(this, CLEANUP_INTERVAL_MS)
        }
    }

    fun isInPaymentMode(): Boolean = currentSessionId() != 0L && config != null

    fun beginSession(): Long {
        reset(clearBindings = false)
        val id = sessionToken.begin()
        tasks.post(cleanupRunnable, CLEANUP_INTERVAL_MS)
        return id
    }

    internal fun bindKeyboard(
        keyboard: ViewGroup, activity: Activity?, settings: PaymentConfig, usesSystemIme: Boolean = false
    ) {
        currentKeyboardViewRef = WeakReference(keyboard)
        hostActivityRef = activity?.let(::WeakReference)
        config = settings
        this.usesSystemIme = usesSystemIme
    }

    internal fun bindConfig(settings: PaymentConfig) {
        config = settings
    }

    internal fun currentConfig(): PaymentConfig? = config

    fun isCurrentSession(id: Long): Boolean = sessionToken.isCurrent(id)
    fun currentSessionId(): Long = sessionToken.current()

    fun endSession(id: Long) {
        if (isCurrentSession(id)) destroy()
    }

    fun endSessionForActivity(activity: Activity) {
        if (getCurrentKeyboardView()?.context?.findActivity() === activity || getHostActivity() === activity) destroy()
    }

    fun getHostActivity(): Activity? = hostActivityRef?.get()
    fun getCurrentKeyboardView(): ViewGroup? = currentKeyboardViewRef?.get()
    fun getCurrentEncodedPassword(): String? = config?.encryptedPassword

    fun setInputEditText(editText: EditText?) {
        if (inputEditTextRef?.get() !== editText) restoreInputPolicy()
        inputEditTextRef = editText?.let(::WeakReference)
        if (isAuthenticationInProgress()) suppressInputMethod()
    }

    fun suppressInputMethod() {
        if (!isAuthenticationInProgress()) return
        val keyboard = getCurrentKeyboardView() ?: return
        getInputEditText()?.let { input ->
            if (!input.isAttachedToWindow || input.rootView !== keyboard.rootView) return@let
            if (inputPolicy?.input?.get() !== input) {
                restoreInputPolicy()
                inputPolicy = InputPolicy(WeakReference(input), input.showSoftInputOnFocus)
            }
            input.showSoftInputOnFocus = false
        }
        val manager = keyboard.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(keyboard.windowToken, 0)
    }

    private fun restoreInputPolicy() {
        val policy = inputPolicy ?: return
        inputPolicy = null
        policy.input.get()?.let { input ->
            tasks.onMain { input.showSoftInputOnFocus = policy.showOnFocus }
        }
    }

    fun getInputEditText(): EditText? = inputEditTextRef?.get()

    fun setConfirmButton(button: View?) {
        confirmButtonRef = button?.let(::WeakReference)
    }

    fun getConfirmButton(): View? = confirmButtonRef?.get()
    fun isCurrentAuthentication(id: Long): Boolean = authenticationToken.isCurrent(id)
    fun isAuthenticationInProgress(): Boolean = authenticationToken.current() != 0L

    fun beginAuthentication(): AuthenticationAttempt? = synchronized(signalLock) {
        if (!isInPaymentMode() || isAuthenticationInProgress()) return null
        val signal = CancellationSignal()
        cancelSignal = signal
        AuthenticationAttempt(authenticationToken.begin(), signal)
    }

    fun finishAuthentication(id: Long): Boolean = synchronized(signalLock) {
        if (!authenticationToken.finish(id)) return false
        cancelSignal = null
        true
    }

    fun cancelAuthentication() {
        cancelCurrentSignal()
        restoreKeyboard(currentSessionId())
    }

    private fun cancelCurrentSignal() {
        val signal = synchronized(signalLock) {
            authenticationToken.invalidate()
            cancelSignal.also { cancelSignal = null }
        }
        try {
            signal?.cancel()
        } catch (error: Exception) {
            ModuleLog.w(error) { "payment authentication cancellation failed" }
        }
    }

    fun restoreKeyboard(id: Long) {
        tasks.onMain {
            if (!isCurrentSession(id) || isAuthenticationInProgress()) return@onMain
            InputMask.reset()
            restoreInputPolicy()
            val keyboard = getCurrentKeyboardView() ?: return@onMain
            keyboard.visibility = View.VISIBLE
            val input = getInputEditText() ?: return@onMain
            val activity = getHostActivity()
            if (!canRestoreInput(keyboard, input, activity)) return@onMain
            val needsInputMethod = usesSystemIme && input.showSoftInputOnFocus
            val showOnFocus = input.showSoftInputOnFocus
            try {
                if (!needsInputMethod) input.showSoftInputOnFocus = false
                input.requestFocus()
            } finally {
                input.showSoftInputOnFocus = showOnFocus
            }
            if (!needsInputMethod) return@onMain
            tasks.post(Runnable {
                if (isCurrentSession(id) && !isAuthenticationInProgress() &&
                    getCurrentKeyboardView() === keyboard && getInputEditText() === input &&
                    usesSystemIme && input.hasFocus() && input.showSoftInputOnFocus &&
                    canRestoreInput(keyboard, input, activity)) {
                    val manager = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            })
        }
    }

    private fun canRestoreInput(keyboard: ViewGroup, input: EditText, activity: Activity?): Boolean =
        keyboard.isAttachedToWindow && keyboard.isShown && keyboard.windowVisibility == View.VISIBLE &&
            input.isAttachedToWindow && input.isShown && input.hasWindowFocus() &&
            input.rootView === keyboard.rootView && getHostActivity() === activity &&
            activity?.isFinishing != true && activity?.isDestroyed != true

    private fun cleanupExpiredReferences() {
        val keyboard = getCurrentKeyboardView()
        val host = getHostActivity()
        if (!isAuthenticationInProgress() &&
            (keyboard == null || !keyboard.isAttachedToWindow || host?.isFinishing == true || host?.isDestroyed == true)
        ) {
            destroy()
        }
    }

    fun destroy() = reset(clearBindings = true)

    private fun reset(clearBindings: Boolean) {
        val hiddenKeyboard = if (isAuthenticationInProgress()) getCurrentKeyboardView() else null
        // Invalidate callbacks before canceling the prompt or restoring any UI.
        sessionToken.invalidate()
        cancelCurrentSignal()
        tasks.clear()
        restoreInputPolicy()
        usesSystemIme = false
        if (clearBindings) {
            config = null
            currentKeyboardViewRef = null
            inputEditTextRef = null
            confirmButtonRef = null
            hostActivityRef = null
        }
        hiddenKeyboard?.let { keyboard -> tasks.onMain { keyboard.visibility = View.VISIBLE } }
        onDestroy()
    }

    private companion object {
        const val CLEANUP_INTERVAL_MS = 60_000L
    }
}
