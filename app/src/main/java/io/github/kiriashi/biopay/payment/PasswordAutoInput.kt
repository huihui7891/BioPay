/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import io.github.kiriashi.biopay.BuildConfig
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.apps.VisualPaymentAdapter
import io.github.kiriashi.biopay.apps.shared.KeyboardMode
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.MainTasks
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.kiriashi.biopay.storage.PaymentConfig
import io.github.kiriashi.biopay.storage.PasswordCipher
import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom

object PasswordAutoInput {
    private val tasks = MainTasks()
    private val silentInput = ThreadLocal<Boolean>()

    /** True during BioPay-generated password input on this thread. */
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

    internal sealed interface Preparation {
        data class Ready(val input: PreparedInput) : Preparation
        data class Waiting(val reason: String) : Preparation
        data class Rejected(val reason: String) : Preparation
    }

    internal class PreparedInput internal constructor(
        internal val boundKeyboard: ViewGroup,
        internal val keyboard: ViewGroup,
        internal val root: ViewGroup,
        internal val activity: Activity,
        internal val sessionId: Long,
        internal val config: PaymentConfig,
        internal val input: EditText?,
        internal val confirm: View?,
        internal val digits: List<View>?,
        internal val keyboardMode: KeyboardMode?
    )

    /** Resolves live controls without decrypting or touching the password field. */
    internal fun prepareInput(keyboard: ViewGroup, state: AppRuntime): Preparation {
        val sessionId = state.session.currentSessionId()
        val config = state.session.currentConfig() ?: return Preparation.Rejected("settings unavailable")
        val activity = state.session.getHostActivity() ?: return Preparation.Rejected("host unavailable")
        val root = keyboard.rootView as? ViewGroup ?: return Preparation.Waiting("window not ready")
        bindingRejection(keyboard, root, activity, state, sessionId, config)?.let {
            return Preparation.Rejected(it)
        }
        if (!controlReady(keyboard, root, requireEnabled = false)) return Preparation.Waiting("keyboard not ready")
        state.session.getInputEditText()?.let { input ->
            if (input.isAttachedToWindow && input.rootView === root && !input.text.isNullOrEmpty()) {
                return Preparation.Rejected("password field already contains input")
            }
        }

        val started = if (BuildConfig.DEBUG) SystemClock.uptimeMillis() else 0L
        val screen = try {
            if (state.adapter is VisualPaymentAdapter) {
                state.adapter.observe(root, activity) ?: return Preparation.Waiting("payment page not ready")
            } else null
        } finally {
            ModuleLog.d { "payment input recognition: app=${state.adapter.app}, duration=${SystemClock.uptimeMillis() - started}ms" }
        }
        val liveKeyboard = screen?.keyboard ?: keyboard
        if (liveKeyboard !== keyboard && (keyboard !== root || liveKeyboard.rootView !== root)) {
            return Preparation.Rejected("payment keyboard replaced")
        }
        val input = screen?.passwordInput ?: if (screen == null) {
            state.session.getInputEditText()?.takeIf { it.isAttachedToWindow && it.rootView === root }
        } else null
        val confirm = screen?.confirmButton
        if (input != null && !input.text.isNullOrEmpty()) {
            return Preparation.Rejected("password field already contains input")
        }
        val directInput = when (state.adapter.app) {
            PaymentApp.QQ -> input != null && input.contentDescription?.toString() in QQ_INPUT_DESCRIPTIONS
            PaymentApp.ALIPAY, PaymentApp.TAOBAO -> input != null && confirm != null &&
                resourceName(input) == "input_et_password" && resourceName(confirm) == "button_ok"
            else -> false
        }
        val digits = if (directInput) null else {
            val found = if (screen != null) screen.digitKeys else state.adapter.digitKeys(liveKeyboard)
            if (found == null || found.size != 10 || found.any { it == null }) {
                return Preparation.Waiting("digit controls unavailable")
            }
            found.filterNotNull()
        }
        val prepared = PreparedInput(keyboard, liveKeyboard, root, activity, sessionId, config,
            input, if (directInput) confirm else null, digits, screen?.keyboardMode)
        return validatePrepared(prepared, state)
    }

    private fun bindingRejection(
        keyboard: ViewGroup, root: ViewGroup, activity: Activity, state: AppRuntime,
        sessionId: Long, config: PaymentConfig
    ): String? = when {
        state.isClosed -> "runtime closed"
        !state.session.isCurrentSession(sessionId) -> "payment session expired"
        state.session.getCurrentKeyboardView() !== keyboard -> "keyboard binding changed"
        state.session.currentConfig() != config || !state.prefs.isCurrent(config) -> "settings changed"
        config.app != state.adapter.app || activity.packageName != state.adapter.app.packageName ||
            state.app.packageName != state.adapter.app.packageName -> "payment owner mismatch"
        state.session.getHostActivity() !== activity || activity.isFinishing || activity.isDestroyed -> "host expired"
        keyboard.rootView !== root || root.context.findActivity()?.let { it !== activity } == true -> "window owner changed"
        else -> null
    }

    private fun validatePrepared(prepared: PreparedInput, state: AppRuntime): Preparation {
        bindingRejection(prepared.boundKeyboard, prepared.root, prepared.activity, state,
            prepared.sessionId, prepared.config)?.let { return Preparation.Rejected(it) }
        if (!controlReady(prepared.boundKeyboard, prepared.root, requireEnabled = false) ||
            (prepared.keyboard !== prepared.boundKeyboard &&
                !controlReady(prepared.keyboard, prepared.root, requireEnabled = false))) {
            return Preparation.Waiting("keyboard not ready")
        }
        prepared.input?.let { input ->
            if (!input.text.isNullOrEmpty()) return Preparation.Rejected("password field already contains input")
            if (input.rootView !== prepared.root) return Preparation.Rejected("password field window changed")
            if (!input.isAttachedToWindow) return Preparation.Waiting("password field not ready")
            // Native keypads can keep a hidden EditText solely as their input target.
            if (prepared.digits == null && !controlReady(input, prepared.root)) {
                return Preparation.Waiting("password field not ready")
            }
        }
        // The confirmation button may remain disabled until setText fills the field.
        prepared.confirm?.let { confirm ->
            if (!controlReady(confirm, prepared.root, requireEnabled = false)) {
                return Preparation.Waiting("confirmation control not ready")
            }
        }
        if (prepared.digits?.any { !controlReady(it, prepared.root) } == true) {
            return Preparation.Waiting("digit controls not ready")
        }
        return Preparation.Ready(prepared)
    }

    private fun controlReady(view: View, root: ViewGroup, requireEnabled: Boolean = true): Boolean =
        view.isAttachedToWindow && view.isShown && (!requireEnabled || view.isEnabled) &&
            view.width > 0 && view.height > 0 && view.rootView === root

    private fun resourceName(view: View): String? =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    internal fun autoInputPassword(
        keyboardView: ViewGroup, passwordChars: CharArray, state: AppRuntime,
        sessionId: Long, config: PaymentConfig, prepared: PreparedInput
    ): Boolean {
        try {
            if (passwordChars.size != PasswordCipher.PASSWORD_LENGTH || passwordChars.any { it !in '0'..'9' }) {
                return rejectInput(state, "decrypted password format invalid")
            }
            val plan = when (val preparation = validatePrepared(prepared, state)) {
                is Preparation.Ready -> preparation.input
                is Preparation.Waiting -> return rejectInput(state, preparation.reason)
                is Preparation.Rejected -> return rejectInput(state, preparation.reason)
            }
            if (plan.boundKeyboard !== keyboardView || plan.sessionId != sessionId || plan.config != config) {
                return rejectInput(state, "prepared input binding changed")
            }
            cancelPendingRunnables("new input started")
            // Monitoring pauses during authentication, so its old references may be stale.
            state.session.setInputEditText(plan.input)
            state.session.setConfirmButton(plan.confirm)
            val digits = plan.digits
            if (digits == null) {
                val input = plan.input ?: return rejectInput(state, "password field unavailable")
                InputMask.show(state)
                val submitted = withoutFeedback(state.adapter.app) {
                    input.setText(String(passwordChars))
                    val confirm = plan.confirm
                    confirm == null || (bindingRejection(plan.boundKeyboard, plan.root, plan.activity,
                        state, sessionId, config) == null &&
                        controlReady(confirm, plan.root) && confirm.performClick())
                }
                if (submitted) {
                    InputMask.finish()
                    if (state.session.isCurrentSession(sessionId)) {
                        state.session.setInputEditText(null)
                        state.session.setConfirmButton(null)
                    }
                } else {
                    rejectInput(state, "confirmation unavailable after input")
                }
                // The field has already been written. Never retry the full password.
                return submitted
            }
            val keys = passwordChars.map { WeakReference(digits[it - '0']) }
            InputMask.show(state, digits)
            val run = InputRun(WeakReference(keyboardView), WeakReference(plan.root),
                WeakReference(plan.activity), keys, state, sessionId, config)
            activeRun = run
            run.scheduleNext()
            return true
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun rejectInput(state: AppRuntime, reason: String): Boolean {
        ModuleLog.d { "payment input rejected: app=${state.adapter.app}, reason=$reason" }
        return false
    }

    fun cancelPendingRunnables(reason: String = "input canceled") {
        val run = activeRun ?: return
        activeRun = null
        tasks.cancel(run)
        InputMask.reset()
        ModuleLog.d { "payment input canceled: app=${run.state.adapter.app}, reason=$reason" }
    }

    private class InputRun(val keyboard: WeakReference<ViewGroup>, val root: WeakReference<ViewGroup>,
                           val activity: WeakReference<Activity>, val keys: List<WeakReference<View>>,
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
            val window = root.get()
            val host = activity.get()
            if (state.isClosed || !state.session.isCurrentSession(sessionId) || view == null ||
                state.session.getCurrentKeyboardView() !== view || !view.isAttachedToWindow ||
                state.session.currentConfig() != config || !state.prefs.isCurrent(config) ||
                window == null || view.rootView !== window || host == null ||
                state.session.getHostActivity() !== host || host.isFinishing || host.isDestroyed) {
                cancelPendingRunnables("payment binding expired")
                return
            }
            try {
                if (index == keys.size) {
                    activeRun = null
                    InputMask.finish()
                    state.session.setInputEditText(null)
                    return
                }
                val key = keys[index].get()
                if (key == null || !key.isAttachedToWindow || !key.isShown || !key.isEnabled ||
                    key.width <= 0 || key.height <= 0 || key.rootView !== window) {
                    cancelPendingRunnables("digit control unavailable")
                    return
                }
                withoutFeedback(state.adapter.app) { dispatchFakeTouch(key) }
                index++
                if (activeRun === this) scheduleNext()
            } catch (e: Throwable) {
                cancelPendingRunnables("key dispatch failed")
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
