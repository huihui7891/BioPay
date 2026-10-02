/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.runtime

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Keeps Keystore calls off the UI thread and disposes results from an old runtime. */
internal class PaymentWorker(private val mainExecutor: Executor) {
    private val lock = Any()
    private val pending = mutableSetOf<Delivery<*>>()
    @Volatile private var closed = false
    private val worker = lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "BioPayCrypto") }
    }

    fun <T> submit(
        work: () -> T,
        cleanup: () -> Unit = {},
        discard: (T) -> Unit = {},
        complete: (Result<T>) -> Unit
    ): Boolean = synchronized(lock) {
        if (closed) { cleanup(); return@synchronized false }
        try {
            worker.value.execute {
                if (closed) { cleanup(); return@execute }
                val result = try {
                    Result.success(work())
                } catch (error: Exception) {
                    Result.failure(error)
                } finally { cleanup() }
                if (closed) {
                    result.onSuccess(discard)
                } else {
                    val delivery = Delivery(result, discard, complete)
                    val accepted = synchronized(lock) {
                        if (closed) false else pending.add(delivery)
                    }
                    if (!accepted) { delivery.discard(); return@execute }
                    try {
                        mainExecutor.execute(delivery)
                    } catch (_: RejectedExecutionException) { delivery.discard() }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            cleanup()
            false
        }
    }

    fun close() {
        val discarded = synchronized(lock) {
            closed = true
            // Queued work drains through its cleanup path; running Binder calls are not interrupted.
            if (worker.isInitialized()) worker.value.shutdown()
            pending.toList().also { pending.clear() }
        }
        discarded.forEach { it.discard() }
    }

    private inner class Delivery<T>(
        result: Result<T>, private val dispose: (T) -> Unit,
        private val complete: (Result<T>) -> Unit
    ) : Runnable {
        private var result: Result<T>? = result

        override fun run() {
            val delivered = synchronized(lock) {
                pending.remove(this)
                if (closed) null else result.also { result = null }
            }
            if (delivered == null) discard() else complete(delivered)
        }

        fun discard() {
            val discarded = synchronized(lock) {
                pending.remove(this)
                result.also { result = null }
            }
            discarded?.onSuccess(dispose)
        }
    }
}
