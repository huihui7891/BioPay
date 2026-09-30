/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.biometric.BiometricType
import io.github.kiriashi.biopay.core.log.LOG_TAG
import java.util.concurrent.atomic.AtomicBoolean

internal const val KEY_PASSWORD_VERSION = "password_version"
internal const val KEY_BIOMETRIC_TYPE = "bt"
internal const val KEY_PASSWORD_OWNER = "password_owner_package"
internal const val KEY_SETTINGS_REVISION = "payment_settings_revision"
private const val ACTION_SETTINGS_CHANGED = "io.github.kiriashi.biopay.PAYMENT_SETTINGS_CHANGED"
private const val EXTRA_SIGNATURE = "io.github.kiriashi.biopay.SETTINGS_SIGNATURE"

/** Delivers QQ setting changes to its separate payment process. */
internal class ConfigSync(
    private val context: Context,
    private val pref: SharedPreferences,
    private val app: PaymentApp
) {
    @Volatile private var remote: PaymentSettings? = null
    private val receiver: BroadcastReceiver? = if (app == PaymentApp.QQ) {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_SETTINGS_CHANGED) return
                val incoming = intent.extras?.readSettings() ?: return
                if (incoming.owner != null && incoming.owner != app.packageName) return
                val currentRevision = maxOf(remote?.revision ?: 0L, pref.getLong(KEY_SETTINGS_REVISION, 0L))
                if (incoming.revision <= currentRevision) return
                val verified = runCatching {
                    SettingsBroadcastAuth.verifies(incoming, intent.getStringExtra(EXTRA_SIGNATURE))
                }.onFailure { Log.w(LOG_TAG, "QQ settings update could not be verified", it) }
                    .getOrDefault(false)
                if (!verified) return
                remote = incoming
            }
        }.also { registered ->
            val filter = IntentFilter(ACTION_SETTINGS_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(registered, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(registered, filter)
            }
        }
    } else null
    private val receiverRegistered = AtomicBoolean(receiver != null)

    fun current(local: () -> PaymentSettings): PaymentSettings {
        val localSettings = local()
        return remote?.latest(localSettings) ?: localSettings
    }

    fun saveState(local: () -> PaymentSettings): Bundle? =
        if (app == PaymentApp.QQ) current(local).toBundle() else null

    fun restoreState(saved: Bundle?) {
        if (app != PaymentApp.QQ || saved == null) return
        val incoming = saved.readSettings()
        if (incoming.owner != null && incoming.owner != app.packageName) return
        if (incoming.revision > maxOf(remote?.revision ?: 0L, pref.getLong(KEY_SETTINGS_REVISION, 0L))) {
            remote = incoming
        }
    }

    fun afterWrite(updated: PaymentSettings) {
        if (app != PaymentApp.QQ) return
        remote = updated
        val signature = runCatching { SettingsBroadcastAuth.sign(updated) }
            .onFailure { Log.w(LOG_TAG, "QQ settings update could not be signed", it) }
            .getOrNull() ?: return
        val intent = Intent(ACTION_SETTINGS_CHANGED).setPackage(app.packageName).apply {
            putExtras(updated.toBundle())
            putExtra(EXTRA_SIGNATURE, signature)
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(LOG_TAG, "QQ payment settings update could not be broadcast", it) }
    }

    fun close() {
        val registered = receiver ?: return
        if (!receiverRegistered.compareAndSet(true, false)) return
        try {
            context.unregisterReceiver(registered)
        } catch (e: IllegalArgumentException) {
            // Android may already have removed the receiver while replacing the
            // module generation. Treat that state as an idempotent cleanup.
            Log.w(LOG_TAG, "QQ settings receiver was already unregistered", e)
        }
    }
}

private fun PaymentSettings.toBundle(): Bundle = Bundle().apply {
    putString(PrefKeys.prefKeyPwd, encryptedPassword)
    putString(KEY_PASSWORD_OWNER, owner)
    putBoolean(PrefKeys.prefKeyOn, enabled)
    putInt(KEY_PASSWORD_VERSION, passwordVersion)
    putInt(KEY_BIOMETRIC_TYPE, biometricType)
    putLong(KEY_SETTINGS_REVISION, revision)
}

private fun Bundle.readSettings(): PaymentSettings = PaymentSettings(
    getString(PrefKeys.prefKeyPwd),
    getString(KEY_PASSWORD_OWNER),
    getBoolean(PrefKeys.prefKeyOn, false),
    getInt(KEY_PASSWORD_VERSION, 0),
    getInt(KEY_BIOMETRIC_TYPE, BiometricType.DISABLED),
    getLong(KEY_SETTINGS_REVISION, 0L)
)
