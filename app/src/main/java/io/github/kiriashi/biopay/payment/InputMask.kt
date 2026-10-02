/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.kiriashi.biopay.BuildConfig
import io.github.kiriashi.biopay.apps.shared.PaymentMasks
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.runtime.AppRuntime
import java.lang.ref.WeakReference

/** Owns overlays and touch protection for the current payment session. */
internal object InputMask {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val masks = mutableListOf<PaymentMask>()
    private var blockedRoot: WeakReference<View>? = null

    fun show(state: AppRuntime, digitKeys: List<View>? = null) {
        reset()
        val keyboard = state.session.getCurrentKeyboardView() ?: return
        val started = if (BuildConfig.DEBUG) SystemClock.uptimeMillis() else 0L
        val sessionId = state.session.currentSessionId()
        val config = state.session.currentConfig()
        for (layout in PaymentMasks.resolve(
            state.adapter.app, keyboard, state.session.getInputEditText(), state.session.getConfirmButton(),
            digitKeys
        )) {
            val mask = PaymentMask(layout,
                isCurrent = { !state.isClosed && state.session.isCurrentSession(sessionId) &&
                    state.session.getCurrentKeyboardView() === keyboard &&
                    state.session.currentConfig() == config },
                onClose = { closed -> masks.remove(closed) }
            )
            masks.add(mask)
            mask.attach()
        }
        ModuleLog.d { "payment mask: app=${state.adapter.app}, count=${masks.size}, duration=${SystemClock.uptimeMillis() - started}ms" }
    }

    fun blocksTouch(root: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            blockedRoot = null
            // A submitted mask may remove itself while checking the touch.
            for (index in masks.lastIndex downTo 0) {
                if (masks[index].blocksTouch(root, event.rawX, event.rawY)) {
                    blockedRoot = WeakReference(root)
                    break
                }
            }
        }
        val blocked = blockedRoot?.get() === root
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            blockedRoot = null
        }
        return blocked
    }

    fun finish() = masks.toList().forEach { it.finish() }

    fun reset() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::reset)
            return
        }
        masks.toList().forEach { it.close() }
        masks.clear()
        // Keep consuming the rest of an intercepted gesture until UP/CANCEL.
    }
}
