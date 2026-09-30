/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.core.log

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import io.github.kiriashi.biopay.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** Each process and module generation owns its capture session and writer. */
object LogCapture {
    private val lock = Any()
    private var session: Session? = null
    private val formatter by lazy {
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    }
    private const val FLUSH_INTERVAL = 5000L
    private const val MAX_REPORTS = 10
    private const val MAX_DISK_BYTES = 2 * 1024 * 1024L

    private class Session(context: Context) {
        val ring = LogRingBuffer()
        val thread = HandlerThread("BioPayLog").apply { start() }
        val writer = Handler(thread.looper)
        val file = File(context.filesDir, "BioPay/biopay_log_${Process.myPid()}_${UUID.randomUUID()}.txt")
        var dirty = true
        var writeFailed = false
        val flush = object : Runnable {
            override fun run() {
                val content = synchronized(lock) {
                    if (session !== this@Session) return
                    if (dirty) { dirty = false; ring.snapshot() } else null
                }
                if (content != null) save(this@Session, content)
                synchronized(lock) {
                    if (session === this@Session) writer.postDelayed(this, FLUSH_INTERVAL)
                }
            }
        }
    }

    fun start(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            if (session != null) return
            val next = Session(context)
            next.ring.appendRaw(LogFormat.header(
                device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                androidRelease = android.os.Build.VERSION.RELEASE,
                apiLevel = android.os.Build.VERSION.SDK_INT,
                startTime = timestamp()
            ))
            next.ring.append("Module: ${BuildConfig.VERSION_NAME}; process: ${Application.getProcessName()}; pid: ${Process.myPid()}")
            session = next
            next.writer.postDelayed(next.flush, FLUSH_INTERVAL)
        }
    }

    fun stop(@Suppress("UNUSED_PARAMETER") context: Context, onSaved: (String?) -> Unit) {
        if (!BuildConfig.DEBUG) { onSaved(null); return }
        val main = Handler(Looper.getMainLooper())
        synchronized(lock) {
            val current = session
            if (current == null) { main.post { onSaved(null) }; return }
            session = null
            current.writer.removeCallbacks(current.flush)
            current.ring.appendRaw(LogFormat.footer(timestamp()))
            val content = current.ring.snapshot()
            if (!current.writer.post {
                val saved = save(current, content)
                main.post { onSaved(if (saved) current.file.absolutePath else null) }
            }) main.post { onSaved(null) }
            current.thread.quitSafely()
        }
    }

    internal fun log(message: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            val current = session ?: return
            current.ring.append("${timestamp()} ${message.take(4096)}")
            current.dirty = true
        }
    }

    private fun timestamp(): String =
        formatter.get()!!.format(System.currentTimeMillis())

    private fun save(current: Session, content: String): Boolean = try {
        current.file.parentFile?.mkdirs()
        LogFileWriter.writeAtomically(current.file, content)
        current.writeFailed = false
        prune(current.file)
        true
    } catch (error: Exception) {
        if (!current.writeFailed) {
            current.writeFailed = true
            ModuleLog.w(error) { "log capture write failed" }
        }
        false
    }

    private fun prune(active: File) {
        val files = active.parentFile?.listFiles { file ->
            file.isFile && file.name.startsWith("biopay_log_") && file.extension == "txt"
        }?.sortedByDescending(File::lastModified) ?: return
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (file != active && (index >= MAX_REPORTS || bytes > MAX_DISK_BYTES)) file.delete()
        }
    }
}
