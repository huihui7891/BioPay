/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.core.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.IdentityHashMap

/** Owns main-thread callbacks; each Runnable has at most one queued execution. */
internal class MainTasks {
    private val handler = Handler(Looper.getMainLooper())
    private val owner = Any()
    private val lock = Any()
    private val pending = IdentityHashMap<Runnable, Scheduled>()
    private var closed = false

    fun post(task: Runnable, delay: Long = 0L) = synchronized(lock) {
        if (closed) return@synchronized
        pending.remove(task)?.let(handler::removeCallbacks)
        val next = Scheduled(task)
        pending[task] = next
        if (!handler.postAtTime(next, owner, SystemClock.uptimeMillis() + delay.coerceAtLeast(0L))) {
            pending.remove(task)
        }
        Unit
    }

    fun cancel(task: Runnable) = synchronized(lock) {
        pending.remove(task)?.let(handler::removeCallbacks)
        Unit
    }

    fun close() = synchronized(lock) {
        closed = true
        clearPending()
    }

    fun clear() = synchronized(lock) {
        clearPending()
    }

    private fun clearPending() {
        if (pending.isEmpty()) return
        pending.clear()
        handler.removeCallbacksAndMessages(owner)
    }

    private inner class Scheduled(private val task: Runnable) : Runnable {
        override fun run() {
            val current = synchronized(lock) {
                if (closed || pending[task] !== this) false else {
                    pending.remove(task)
                    true
                }
            }
            if (current) task.run()
        }
    }

    /** Cleanup must still run after queued observation work has been canceled. */
    fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else handler.post { action() }
    }
}
