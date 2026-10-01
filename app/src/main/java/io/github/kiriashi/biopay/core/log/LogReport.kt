/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.core.log

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.IOException
import java.util.UUID

/** Writes only this process's reports to its owned Downloads entries. */
internal class LogReport(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val directory = "${Environment.DIRECTORY_DOWNLOADS}/BioPay/"
    private val prefix = "biopay_log_${Application.getProcessName().replace(':', '_')}_"
    private val name = "$prefix${Process.myPid()}_${UUID.randomUUID()}.txt"
    val location: String get() = "下载/BioPay/$name"
    private var uri: Uri? = null

    fun write(content: String) {
        val fresh = uri == null
        val target = uri ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })?.also { uri = it } ?: throw IOException("could not create log report in Downloads")
        try {
            val stream = resolver.openOutputStream(target, "rwt")
                ?: throw IOException("could not open log report in Downloads")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            if (fresh && resolver.update(target, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 0) throw IOException("could not publish log report in Downloads")
        } catch (error: Exception) {
            if (fresh) {
                // Remove an unpublished partial entry; the next flush may retry.
                runCatching { resolver.delete(target, null, null) }
                uri = null
            }
            throw error
        }
    }

    fun prune() {
        val active = uri ?: return
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        var count = 0
        var bytes = 0L
        resolver.query(collection, columns, selection, arrayOf(directory),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val fileName = cursor.getString(1) ?: continue
                if (!fileName.startsWith(prefix) || !fileName.endsWith(".txt")) continue
                val target = ContentUris.withAppendedId(collection, cursor.getLong(0))
                count++
                bytes += cursor.getLong(2)
                if (target != active && (count > MAX_REPORTS || bytes > MAX_DISK_BYTES)) {
                    resolver.delete(target, null, null)
                }
            }
        }
    }

    private companion object {
        const val MAX_REPORTS = 10
        const val MAX_DISK_BYTES = 2 * 1024 * 1024L
    }
}
