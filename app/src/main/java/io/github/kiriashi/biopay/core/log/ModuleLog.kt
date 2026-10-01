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
import java.util.Collections
import java.util.IdentityHashMap

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
        val text = buildString {
            append("[${BuildConfig.VERSION_NAME} $process] ")
            append(message.take(2048).replace('\n', ' ').replace('\r', ' '))
            if (error != null) {
                append(" (").append(error.javaClass.name).append(')')
                if (BuildConfig.DEBUG) append(debugException(error))
            }
        }
        if (BuildConfig.DEBUG) {
            // Keep each chunk below Logcat's byte limit, including multibyte text.
            val chunks = text.chunked(900)
            chunks.forEachIndexed { index, chunk ->
                val part = if (chunks.size == 1) chunk else
                    "[$process part ${index + 1}/${chunks.size}] $chunk"
                Log.println(priority, LOG_TAG, part)
                LogCapture.log(part)
            }
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

    private fun debugException(error: Throwable): String = buildString {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun appendError(current: Throwable, label: String) {
            if (length >= 32_768 || visited.size >= 16) {
                append("\n[exception details truncated]")
                return
            }
            if (!visited.add(current)) {
                append("\n").append(label).append("[circular reference]")
                return
            }
            append("\n").append(label).append(current.javaClass.name)
            current.message?.let { append(": ").append(redact(it.take(1024))) }
            val frames = current.stackTrace
            frames.take(64).forEach { append("\n  at ").append(it) }
            if (frames.size > 64) append("\n  ... ").append(frames.size - 64).append(" more frames")
            current.cause?.let { appendError(it, "Caused by: ") }
            current.suppressed.take(8).forEach { appendError(it, "Suppressed: ") }
            if (current.suppressed.size > 8) append("\n  ... suppressed exceptions truncated")
        }
        appendError(error, "Exception: ")
    }.take(32_768)

    private fun redact(message: String): String = message
        .replace('\n', ' ').replace('\r', ' ')
        .replace(credentialValues, "$1[redacted]")
        .replace(longNumbers, "[redacted-number]")
        .replace(longTokens, "[redacted-token]")

    private val credentialValues by lazy {
        Regex("(?i)((?:password|passwd|pwd|token|secret|密码)\\s*[:=]\\s*)[^\\s,;]+")
    }
    private val longNumbers by lazy { Regex("\\d{6,}") }
    private val longTokens by lazy { Regex("[A-Za-z0-9+/=_-]{32,}") }
}

private const val LOG_TAG = "bp"
