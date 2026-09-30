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

package io.github.kiriashi.biopay.core.log

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import io.github.kiriashi.biopay.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object LogCapture {

    private val ring = LogRingBuffer()
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val flushInterval = 5000L
    @Volatile private var running = false
    @Volatile private var outputDir: File? = null
    private val timeFormatter = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val headerFormatter = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    private val flushRunnable = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (!running) return
                val dir = outputDir ?: return
                val file = File(dir, "biopay_log.txt")
                val content = ring.snapshot()
                bgHandler?.post {
                    try {
                        file.parentFile?.mkdirs()
                        LogFileWriter.writeAtomically(file, content)
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to flush log to disk", e)
                    }
                }
                handler.postDelayed(this, flushInterval)
            }
        }
    }

    fun start(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            if (running) return
            if (bgThread == null) {
                bgThread = HandlerThread("LogCapture").apply { start() }
                bgHandler = Handler(bgThread!!.looper)
            }
            ring.clear()
            outputDir = File(context.filesDir, "BioPay")
            ring.appendRaw(
                LogFormat.header(
                    device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    androidRelease = android.os.Build.VERSION.RELEASE,
                    apiLevel = android.os.Build.VERSION.SDK_INT,
                    startTime = headerFormatter.get()!!.format(System.currentTimeMillis())
                )
            )
            running = true
            handler.postDelayed(flushRunnable, flushInterval)
            Log.d(LOG_TAG, "LogCapture started")
        }
    }

    fun stop(context: Context, onSaved: (String?) -> Unit) {
        if (!BuildConfig.DEBUG) {
            onSaved(null)
            return
        }
        synchronized(lock) {
            if (!running) {
                onSaved(null)
                return
            }
            running = false
            handler.removeCallbacks(flushRunnable)
            ring.appendRaw(LogFormat.footer(headerFormatter.get()!!.format(System.currentTimeMillis())))
            val content = ring.snapshot()
            val file = File(context.filesDir, "BioPay/" +
                LogFormat.reportFileName(headerFormatter.get()!!.format(System.currentTimeMillis())))
            val writer = bgHandler
            val thread = bgThread
            ring.clear()
            bgHandler = null
            bgThread = null
            outputDir = null
            if (writer == null || !writer.post {
                    val path = try {
                        file.parentFile?.mkdirs()
                        LogFileWriter.writeAtomically(file, content)
                        file.absolutePath
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to save log", e)
                        null
                    }
                    handler.post { onSaved(path) }
                }) {
                handler.post { onSaved(null) }
            }
            thread?.quitSafely()
        }
    }

    internal fun log(msg: String) {
        if (!BuildConfig.DEBUG || !running) return
        val time = timeFormatter.get()!!.format(System.currentTimeMillis())
        synchronized(lock) {
            if (!running) return
            ring.append("$time $msg")
        }
    }

}
