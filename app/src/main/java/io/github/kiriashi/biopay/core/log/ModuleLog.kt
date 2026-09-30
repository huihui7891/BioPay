/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.core.log

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.kiriashi.biopay.BuildConfig

/** Process-local logging policy. Messages must never contain payment or account data. */
object ModuleLog {
    @Volatile private var sink: ((Int, String) -> Unit)? = null
    @Volatile private var process = "pid=${Process.myPid()}"
    private val recent = LinkedHashMap<String, Long>()

    fun bind(processName: String, report: (Int, String) -> Unit) {
        process = "$processName/${Process.myPid()}"
        sink = report
    }

    inline fun d(error: Throwable? = null, message: () -> String) {
        if (BuildConfig.DEBUG) emit(Log.DEBUG, message(), error)
    }

    fun w(error: Throwable? = null, message: () -> String) = report(Log.WARN, error, message)
    fun e(error: Throwable? = null, message: () -> String) = report(Log.ERROR, error, message)
    fun summary(message: () -> String) = emit(Log.INFO, message(), null)

    fun report(priority: Int, error: Throwable? = null, message: () -> String) {
        if (priority < Log.WARN) {
            d(error, message)
            return
        }
        val text = message()
        if (!BuildConfig.DEBUG) synchronized(recent) {
            val key = "$priority:$text:${error?.javaClass?.name}"
            val now = SystemClock.elapsedRealtime()
            if (recent[key]?.let { now - it < 30_000 } == true) return
            if (recent.size >= 128) recent.remove(recent.keys.first())
            recent[key] = now
        }
        emit(priority, text, error)
    }

    @PublishedApi
    internal fun emit(priority: Int, message: String, error: Throwable?) {
        // Throwable messages and causes may contain host data. Keep only type and frames.
        val text = buildString {
            append("[${BuildConfig.VERSION_NAME} $process] ")
            append(message.take(2048).replace('\n', ' ').replace('\r', ' '))
            if (error != null) {
                append(" (").append(error.javaClass.name).append(')')
                if (BuildConfig.DEBUG) error.stackTrace.take(16).forEach {
                    append("\n  at ").append(it)
                }
            }
        }
        if (BuildConfig.DEBUG) {
            Log.println(priority, LOG_TAG, text)
            LogCapture.log(text)
        }
        if (priority >= Log.INFO) {
            val target = sink
            if (target != null) {
                try { target(priority, text) } catch (_: Throwable) {
                    if (!BuildConfig.DEBUG) Log.println(priority, LOG_TAG, text)
                }
            } else if (!BuildConfig.DEBUG) Log.println(priority, LOG_TAG, text)
        }
    }
}

private const val LOG_TAG = "bp"
