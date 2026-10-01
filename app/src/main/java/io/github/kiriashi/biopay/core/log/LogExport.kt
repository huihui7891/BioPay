/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.core.log

import io.github.kiriashi.biopay.core.util.withFileLock
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.UUID

/** A signed request lets QQ's existing payment process export its own history. */
internal class LogExport(
    private val context: Context,
    private val dispatch: (() -> Unit) -> Boolean
) {
    private val main = Handler(Looper.getMainLooper())
    private val primary = Application.getProcessName() == context.packageName
    private val owner = "BioPay:diagnostic-export:${context.packageName}"
    @Volatile private var closed = false
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || primary || intent.action != ACTION) return
            val request = intent.getStringExtra(REQUEST)?.takeIf { it.length == 36 } ?: return
            val signature = intent.getStringExtra(SIGNATURE)
            val reply = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(REPLY, ResultReceiver::class.java)
                else intent.getParcelableExtra<ResultReceiver>(REPLY)
            if (reply == null) return
            val pending = goAsync()
            if (!dispatch {
                var queued = false
                try {
                    if (!closed && Auth.verifies(context, owner, request, signature)) {
                        queued = main.post {
                            if (closed) pending.finish()
                            else LogCapture.exportLocal { location ->
                                try {
                                    reply.send(if (location == null) 0 else 1, Bundle().apply {
                                        putString(LOCATION, location)
                                        putString(REQUEST, request)
                                    })
                                } finally { pending.finish() }
                            }
                        }
                    }
                } catch (error: Exception) {
                    ModuleLog.w(error) { "diagnostic export request rejected" }
                } finally { if (!queued) pending.finish() }
            }) pending.finish()
        }
    }

    init {
        if (context.packageName == "com.tencent.mobileqq" && !primary) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerLegacyReceiver()
                }
                registered = true
            }.onFailure { ModuleLog.w(it) { "diagnostic export receiver failed" } }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLegacyReceiver() {
        // Android 10–12 lack RECEIVER_NOT_EXPORTED; signed requests authenticate the sender.
        check(Build.VERSION.SDK_INT < 33)
        context.registerReceiver(receiver, IntentFilter(ACTION))
    }

    fun export(onSaved: (List<String>) -> Unit) {
        val paths = linkedSetOf<String>()
        val request = UUID.randomUUID().toString()
        val remote = primary && context.packageName == "com.tencent.mobileqq"
        var localDone = false
        var remoteDone = !remote
        var finished = false
        fun complete() {
            if (finished || !localDone || !remoteDone) return
            finished = true
            onSaved(paths.toList())
        }
        val reply = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (finished || resultData?.getString(REQUEST) != request) return
                resultData.getString(LOCATION)?.takeIf {
                    resultCode == 1 && it.startsWith("下载/BioPay/biopay_log_") && it.length < 512
                }?.let(paths::add)
                remoteDone = true
                complete()
            }
        }
        if (remote) {
            main.postDelayed({ remoteDone = true; complete() }, 3_000L)
            if (!dispatch {
                try {
                    if (!closed) context.sendBroadcast(Intent(ACTION).setPackage(context.packageName).apply {
                        putExtra(REQUEST, request)
                        putExtra(SIGNATURE, Auth.sign(context, owner, request))
                        putExtra(REPLY, reply)
                    })
                } catch (error: Exception) {
                    ModuleLog.w(error) { "payment process diagnostic request failed" }
                    main.post { remoteDone = true; complete() }
                }
            }) remoteDone = true
        }
        LogCapture.exportLocal { location ->
            location?.let(paths::add)
            localDone = true
            complete()
        }
    }

    fun close() {
        closed = true
        if (registered) {
            registered = false
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { ModuleLog.d(it) { "diagnostic export receiver cleanup failed" } }
        }
    }

    // Export authentication must work even when Android Keystore is the failing service.
    // This app-private random key authenticates diagnostics requests only.
    private object Auth {
        @Volatile private var cachedKey: ByteArray? = null

        fun sign(context: Context, owner: String, request: String): String =
            Base64.encodeToString(mac(context, owner, request), Base64.NO_WRAP)

        fun verifies(context: Context, owner: String, request: String, signature: String?): Boolean {
            if (signature.isNullOrEmpty() || signature.length > 128) return false
            val bytes = runCatching { Base64.decode(signature, Base64.NO_WRAP) }.getOrNull() ?: return false
            return MessageDigest.isEqual(bytes, mac(context, owner, request))
        }

        private fun mac(context: Context, owner: String, request: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(key(context), "HmacSHA256"))
            }.doFinal("$owner\n$request".toByteArray(Charsets.UTF_8))

        @Synchronized
        private fun key(context: Context): ByteArray {
            cachedKey?.let { return it }
            return withFileLock(File(context.noBackupFilesDir, "biopay_diagnostics.key"), timeoutMillis = 2_000L) { file ->
                if (file.length() == 0L) {
                    val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    file.write(generated)
                    file.fd.sync()
                }
                check(file.length() == 32L) { "Invalid diagnostic key file" }
                file.seek(0)
                ByteArray(32).also { file.readFully(it); cachedKey = it }
            }
        }
    }

    private companion object {
        const val ACTION = "io.github.kiriashi.biopay.DIAGNOSTIC_EXPORT"
        const val REQUEST = "request"
        const val SIGNATURE = "signature"
        const val REPLY = "reply"
        const val LOCATION = "location"
    }
}
