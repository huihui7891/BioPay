/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.core.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Owns main-thread callbacks; each Runnable has at most one queued execution. */
internal class MainTasks {
    private val handler = Handler(Looper.getMainLooper())
    private val owner = Any()
    private val lock = Any()
    private var closed = false

    fun post(task: Runnable, delay: Long = 0L) = synchronized(lock) {
        if (closed) return@synchronized
        handler.removeCallbacks(task)
        handler.postAtTime(task, owner, SystemClock.uptimeMillis() + delay.coerceAtLeast(0L))
        Unit
    }

    fun cancel(task: Runnable) = synchronized(lock) {
        handler.removeCallbacks(task)
    }

    fun close() = synchronized(lock) {
        closed = true
        handler.removeCallbacksAndMessages(owner)
    }

    fun clear() = synchronized(lock) {
        handler.removeCallbacksAndMessages(owner)
    }

    /** Cleanup must still run after queued observation work has been canceled. */
    fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else handler.post { action() }
    }
}
