/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.core.util

import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** Serializes shared key initialization across host processes and module generations. */
internal inline fun <T> withFileLock(
    path: File, timeoutMillis: Long = 5_000L, action: (RandomAccessFile) -> T
): T = RandomAccessFile(path, "rw").use { file ->
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    var acquired: java.nio.channels.FileLock? = null
    while (acquired == null) {
        acquired = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (acquired == null) {
            check(SystemClock.elapsedRealtime() < deadline) { "BioPay file lock timed out" }
            Thread.sleep(10)
        }
    }
    acquired.use { action(file) }
}
