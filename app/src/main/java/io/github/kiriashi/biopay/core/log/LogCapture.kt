/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.core.log

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.kiriashi.biopay.BuildConfig
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Debug history stays in memory; only explicit exports write to Downloads. */
object LogCapture {
    private val lock = Any()
    private val history = LogRingBuffer()
    private var session: Session? = null
    private val formatter by lazy {
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    }
    private val startTime = timestamp()

    private class Session(val context: Context) {
        val worker = lazy { Executors.newSingleThreadExecutor { Thread(it, "BioPayDiagnostics") } }
        var exporting = false
        var bridge: LogExport? = null
    }

    fun start(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            if (session != null) return
            val next = Session(context.applicationContext)
            session = next
            next.bridge = LogExport(next.context, ::enqueue)
        }
    }

    fun close() {
        if (!BuildConfig.DEBUG) return
        val old = synchronized(lock) { session.also { session = null } } ?: return
        old.bridge?.close()
        if (old.worker.isInitialized()) old.worker.value.shutdown()
    }

    fun export(context: Context, onSaved: (List<String>) -> Unit) {
        if (!BuildConfig.DEBUG) { onSaved(emptyList()); return }
        start(context)
        val bridge = synchronized(lock) { session?.bridge }
        if (bridge != null) bridge.export(onSaved) else exportLocal(onSaved = { onSaved(listOfNotNull(it)) })
    }

    internal fun exportLocal(onSaved: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val current = synchronized(lock) {
            session?.takeUnless { it.exporting }?.also { it.exporting = true }
        }
        if (current == null) { main.post { onSaved(null) }; return }
        val content = buildString {
            append(LogFormat.header(
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT, startTime
            ))
            appendLine("Module: ${BuildConfig.VERSION_NAME}; process: ${Application.getProcessName()}; pid: ${Process.myPid()}")
            append(synchronized(lock) { history.snapshot() })
            append(LogFormat.footer(timestamp()))
        }
        enqueue {
            var location: String? = null
            try {
                val report = LogReport(current.context)
                report.write(content)
                location = report.location
                runCatching { report.prune() }.onFailure { ModuleLog.w(it) { "log report cleanup failed" } }
            } catch (error: Exception) {
                ModuleLog.w(error) { "diagnostic export failed" }
            } finally {
                synchronized(lock) { current.exporting = false }
                main.post { onSaved(location) }
            }
        }.also { queued ->
            if (!queued) {
                synchronized(lock) { current.exporting = false }
                main.post { onSaved(null) }
            }
        }
    }

    private fun enqueue(work: () -> Unit): Boolean = synchronized(lock) {
        val current = session ?: return false
        try {
            current.worker.value.execute(work)
            true
        } catch (_: RejectedExecutionException) { false }
    }

    internal fun log(message: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) { history.append("${timestamp()} ${message.take(4096)}") }
    }

    private fun timestamp(): String = formatter.get()!!.format(System.currentTimeMillis())
}
