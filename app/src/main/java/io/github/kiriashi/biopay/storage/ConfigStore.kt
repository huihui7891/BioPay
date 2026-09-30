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

package io.github.kiriashi.biopay.storage

import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.biometric.BiometricType
import io.github.kiriashi.biopay.apps.PaymentApp
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log

private const val KEY_LOG_CAPTURE = "log_capture"

class ConfigStore(
    context: Context,
    private val pref: SharedPreferences,
    private val app: PaymentApp
) {

    private val sync = ConfigSync(context, pref, app)

    fun close() = sync.close()

    internal fun saveState(): Bundle? = sync.saveState(::localSettings)

    internal fun restoreState(saved: Bundle?) = sync.restoreState(saved)

    private fun localSettings(): PaymentSettings {
        // Read one coherent preference snapshot instead of locking each field separately.
        val values = pref.all
        return PaymentSettings(
            values[PrefKeys.prefKeyPwd] as? String,
            values[KEY_PASSWORD_OWNER] as? String,
            values[PrefKeys.prefKeyOn] as? Boolean ?: false,
            values[KEY_PASSWORD_VERSION] as? Int ?: 0,
            values[KEY_BIOMETRIC_TYPE] as? Int ?: BiometricType.DISABLED,
            values[KEY_SETTINGS_REVISION] as? Long ?: 0L
        )
    }

    private fun settings(): PaymentSettings = sync.current(::localSettings)

    private fun writeSettings(change: SharedPreferences.Editor.() -> Unit): Boolean {
        val base = settings()
        val editor = pref.edit()
        if (app == PaymentApp.QQ && base.revision > localSettings().revision) {
            // This process received a newer snapshot while its framework cache stayed stale.
            editor.putString(PrefKeys.prefKeyPwd, base.encryptedPassword)
                .putString(KEY_PASSWORD_OWNER, base.owner)
                .putBoolean(PrefKeys.prefKeyOn, base.enabled)
                .putInt(KEY_PASSWORD_VERSION, base.passwordVersion)
                .putInt(KEY_BIOMETRIC_TYPE, base.biometricType)
        }
        change(editor)
        editor.putLong(KEY_SETTINGS_REVISION, base.revision + 1L)
        if (!editor.commit()) return false
        sync.afterWrite(localSettings())
        return true
    }

    fun isBioPayEnabled(): Boolean = activeConfig() != null

    internal fun activeConfig(): PaymentConfig? = settings().activeFor(app)

    internal fun isCurrent(config: PaymentConfig): Boolean = activeConfig() == config

    /** Returns a password only when it belongs to this app and payment is enabled. */
    fun activePassword(): String? = activeConfig()?.encryptedPassword

    fun isLogCaptureEnabled(): Boolean {
        return pref.getBoolean(KEY_LOG_CAPTURE, false)
    }

    fun setLogCaptureEnabled(enabled: Boolean): Boolean =
        pref.edit().putBoolean(KEY_LOG_CAPTURE, enabled).commit()

    fun getEncodedPassword(): String? = encodedPassword(settings())

    private fun encodedPassword(current: PaymentSettings): String? = current.encodedPasswordFor(app)

    fun needsPasswordReentry(): Boolean = settings().let {
        !it.encryptedPassword.isNullOrEmpty() && encodedPassword(it) == null
    }

    fun savePassword(password: String, cipher: javax.crypto.Cipher, passwordVersion: Int, biometricType: Int = getBiometricType()): Result<Unit> {
        return try {
            val encrypted = PasswordCipher.encrypt(password, cipher)
            if (!writeSettings {
                putString(PrefKeys.prefKeyPwd, encrypted)
                putBoolean(PrefKeys.prefKeyOn, true)
                putInt(KEY_PASSWORD_VERSION, passwordVersion)
                putInt(KEY_BIOMETRIC_TYPE, biometricType)
                putString(KEY_PASSWORD_OWNER, app.packageName)
            }) error("payment settings could not be saved")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "savePassword failed", e)
            Result.failure(e)
        }
    }

    fun clearPassword(): Boolean {
        return writeSettings {
            putBoolean(PrefKeys.prefKeyOn, false)
            putString(PrefKeys.prefKeyPwd, "")
            putInt(KEY_PASSWORD_VERSION, 0)
            putInt(KEY_BIOMETRIC_TYPE, BiometricType.DISABLED)
            remove(KEY_PASSWORD_OWNER)
        }
    }

    fun setBiometricMode(type: Int): Boolean {
        require(type in BiometricType.DISABLED..BiometricType.FACE)
        return writeSettings {
            putBoolean(PrefKeys.prefKeyOn, type != BiometricType.DISABLED)
            putInt(KEY_BIOMETRIC_TYPE, type)
        }
    }

    fun getBiometricType(): Int = settings().let {
        if (encodedPassword(it) == null) BiometricType.DISABLED else it.biometricType
    }

    fun getPasswordVersion(): Int = settings().let {
        if (encodedPassword(it) == null) 0 else it.passwordVersion
    }
}
