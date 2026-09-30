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

import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.apps.VisualPaymentAdapter

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.kiriashi.biopay.core.util.MainTasks
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.kiriashi.biopay.storage.PaymentConfig
import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom

object PasswordAutoInput {
    private val tasks = MainTasks()
    private val silentInput = ThreadLocal<Boolean>()

    /** True only on the thread currently dispatching a module-generated key. */
    internal fun suppressesFeedback(): Boolean = silentInput.get() == true

    private fun <T> withoutFeedback(app: PaymentApp, action: () -> T): T {
        if (app != PaymentApp.ALIPAY && app != PaymentApp.TAOBAO) return action()
        val previous = silentInput.get()
        silentInput.set(true)
        return try {
            action()
        } finally {
            if (previous == null) silentInput.remove() else silentInput.set(previous)
        }
    }

    @Volatile
    private var activeRun: InputRun? = null

    fun isInProgress(sessionId: Long): Boolean = activeRun?.sessionId == sessionId

    internal fun autoInputPassword(keyboardView: ViewGroup, passwordChars: CharArray,
                          state: AppRuntime, sessionId: Long, config: PaymentConfig): Boolean {
        try {
            if (passwordChars.size != 6 || passwordChars.any { it !in '0'..'9' } ||
                state.isClosed || !state.session.isCurrentSession(sessionId) ||
                state.session.getCurrentKeyboardView() !== keyboardView ||
                state.session.currentConfig() != config || !state.prefs.isCurrent(config) ||
                !keyboardView.isAttachedToWindow || !keyboardView.isShown) return false
            // Authentication takes time. The original payment window may have
            // disappeared or been replaced while the system prompt was open.
            val screen = if (state.adapter is VisualPaymentAdapter) {
                val activity = state.session.getHostActivity() ?: return false
                val window = keyboardView.rootView as? ViewGroup ?: return false
                if (activity.isFinishing || activity.isDestroyed ||
                    window.context.findActivity()?.let { it !== activity } == true) return false
                state.adapter.observe(window, activity)?.takeIf {
                    it.keyboard === keyboardView ||
                        (keyboardView === window && it.keyboard.rootView === window)
                }
                    ?: return false
            } else null
            if (state.adapter.app == PaymentApp.QQ) {
                val input = state.session.getInputEditText()
                if (input != null && screen?.passwordInput === input &&
                    input.rootView === keyboardView.rootView && input.isAttachedToWindow &&
                    input.isShown && input.isEnabled &&
                    input.contentDescription?.toString() in QQ_INPUT_DESCRIPTIONS &&
                    input.text.isNullOrEmpty()
                ) {
                    input.setText(String(passwordChars))
                    KeyboardCloak.uncloakKeyboardViews(keyboardView)
                    KeyboardCloak.restoreConcealedInputViews(animated = true)
                    state.session.setInputEditText(null)
                    return true
                }
            }
            if (state.adapter.app == PaymentApp.ALIPAY || state.adapter.app == PaymentApp.TAOBAO) {
                val input = state.session.getInputEditText()
                val confirm = state.session.getConfirmButton()
                if (input != null && confirm != null && screen?.passwordInput === input &&
                    screen.confirmButton === confirm &&
                    input.rootView === keyboardView.rootView &&
                    confirm.rootView === keyboardView.rootView &&
                    input.isAttachedToWindow && confirm.isAttachedToWindow &&
                    input.isShown && input.isEnabled &&
                    input.text.isNullOrEmpty() && confirm.isShown &&
                    runCatching { input.resources.getResourceEntryName(input.id) }.getOrNull() == "input_et_password" &&
                    runCatching { confirm.resources.getResourceEntryName(confirm.id) }.getOrNull() == "button_ok"
                ) {
                    val submitted = withoutFeedback(state.adapter.app) {
                        input.setText(String(passwordChars))
                        confirm.isAttachedToWindow && confirm.isShown && confirm.isEnabled &&
                            confirm.rootView === keyboardView.rootView && confirm.performClick()
                    }
                    if (submitted) {
                        KeyboardCloak.uncloakKeyboardViews(keyboardView)
                        KeyboardCloak.restoreConcealedInputViews(animated = true)
                        state.session.setInputEditText(null)
                        state.session.setConfirmButton(null)
                    }
                    return submitted
                }
            }
            val digits = state.adapter.digitKeys(keyboardView) ?: return false
            if (digits.size != 10) return false
            val keys = passwordChars.map { digit ->
                val key = digits[digit - '0'] ?: return false
                if (!key.isAttachedToWindow || !key.isShown || !key.isEnabled ||
                    key.width <= 0 || key.height <= 0 || key.rootView !== keyboardView.rootView) return false
                WeakReference(key)
            }
            cancelPendingRunnables()
            val run = InputRun(WeakReference(keyboardView), keys, state, sessionId, config)
            activeRun = run
            run.scheduleNext()
            return true
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    fun cancelPendingRunnables() {
        val run = activeRun ?: return
        activeRun = null
        tasks.cancel(run)
        KeyboardCloak.reset()
    }

    private class InputRun(val keyboard: WeakReference<ViewGroup>, val keys: List<WeakReference<View>>,
                           val state: AppRuntime, val sessionId: Long, val config: PaymentConfig) : Runnable {
        private var index = 0
        fun scheduleNext() {
            val delay = if (index == 0) 0L else if (index == keys.size) 250L else
                AutoInputTiming.gaussianDelay(ThreadLocalRandom.current().nextGaussian())
            tasks.post(this, delay)
        }
        override fun run() {
            if (activeRun !== this) return
            val view = keyboard.get()
            if (state.isClosed || !state.session.isCurrentSession(sessionId) || view == null ||
                state.session.getCurrentKeyboardView() !== view || !view.isAttachedToWindow ||
                state.session.currentConfig() != config || !state.prefs.isCurrent(config)) {
                cancelPendingRunnables()
                return
            }
            try {
                if (index == keys.size) {
                    activeRun = null
                    KeyboardCloak.uncloakKeyboardViews(view)
                    KeyboardCloak.restoreConcealedInputViews(animated = true)
                    state.session.setInputEditText(null)
                    return
                }
                val key = keys[index].get()
                if (key == null || !key.isAttachedToWindow || !key.isShown || !key.isEnabled ||
                    key.width <= 0 || key.height <= 0 || key.rootView !== view.rootView) {
                    cancelPendingRunnables()
                    return
                }
                withoutFeedback(state.adapter.app) { dispatchFakeTouch(key) }
                index++
                if (activeRun === this) scheduleNext()
            } catch (e: Throwable) {
                cancelPendingRunnables()
                ModuleLog.w(e) { "autoInput failed" }
            }
        }
    }

    private fun dispatchFakeTouch(view: View) {
        val random = ThreadLocalRandom.current()
        val x = random.nextInt(view.width.coerceAtLeast(1)).toFloat()
        val y = random.nextInt(view.height.coerceAtLeast(1)).toFloat()
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        try { view.dispatchTouchEvent(down) } finally { down.recycle() }
        val up = MotionEvent.obtain(time, time + random.nextLong(2, 6), MotionEvent.ACTION_UP, x, y, 0)
        try { view.dispatchTouchEvent(up) } finally { up.recycle() }
    }

    private val QQ_INPUT_DESCRIPTIONS = setOf("支付密码", "支付密码输入框")
}
