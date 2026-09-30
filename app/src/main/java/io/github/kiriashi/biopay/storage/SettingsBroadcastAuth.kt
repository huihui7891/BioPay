/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Authenticates QQ settings broadcasts on Android versions without private runtime receivers. */
internal object SettingsBroadcastAuth {
    private const val KEY_ALIAS = "biopay_qq_settings_sync_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALGORITHM = "HmacSHA256"
    private val lock = Any()
    @Volatile private var cachedKey: SecretKey? = null

    fun sign(settings: PaymentSettings): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(key()) }
        return Base64.encodeToString(mac.doFinal(payload(settings)), Base64.NO_WRAP)
    }

    fun verifies(settings: PaymentSettings, signature: String?): Boolean {
        if (signature.isNullOrEmpty()) return false
        val actual = runCatching { Base64.decode(signature, Base64.NO_WRAP) }.getOrNull() ?: return false
        val mac = Mac.getInstance(ALGORITHM).apply { init(key()) }
        return MessageDigest.isEqual(mac.doFinal(payload(settings)), actual)
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(lock) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (!store.containsAlias(KEY_ALIAS)) {
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).setDigests(KeyProperties.DIGEST_SHA256).build()
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
                    .apply { init(spec) }.generateKey()
            }
            return (store.getKey(KEY_ALIAS, null) as SecretKey).also { cachedKey = it }
        }
    }

    private fun payload(settings: PaymentSettings): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeNullable(settings.encryptedPassword)
            data.writeNullable(settings.owner)
            data.writeBoolean(settings.enabled)
            data.writeInt(settings.passwordVersion)
            data.writeInt(settings.biometricType)
            data.writeLong(settings.revision)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeNullable(value: String?) {
        if (value == null) {
            writeInt(-1)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }
    }
}
