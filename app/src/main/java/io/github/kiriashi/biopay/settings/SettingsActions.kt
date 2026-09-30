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
package io.github.kiriashi.biopay.settings

import io.github.kiriashi.biopay.biometric.BioPayPrompt

import io.github.kiriashi.biopay.core.log.LogCapture
import io.github.kiriashi.biopay.core.util.isValidActivity
import io.github.kiriashi.biopay.storage.PasswordCipher
import io.github.kiriashi.biopay.storage.PasswordVersionPolicy
import io.github.kiriashi.biopay.runtime.FieldStore
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.kiriashi.biopay.biometric.BiometricType
import io.github.kiriashi.biopay.biometric.BiometricPromptPolicy
import io.github.kiriashi.biopay.settings.ui.M3Field
import android.content.Context
import android.os.CancellationSignal
import android.hardware.biometrics.BiometricPrompt
import android.widget.Toast

object SettingsActions {

    fun loadSavedPassword(pwdInput: M3Field, state: AppRuntime) {
        val raw = state.prefs.getEncodedPassword()
        if (!raw.isNullOrEmpty()) {
            pwdInput.hint = "密码已设置"
        } else if (state.prefs.needsPasswordReentry()) {
            pwdInput.hint = "请重新录入该应用的支付密码"
        }
    }

    fun handleSave(context: Context, dialogHost: DialogHost, pwdInput: M3Field, state: AppRuntime, selectedType: Int) {
        if (state.isClosed) return
        if (selectedType == BiometricType.DISABLED) {
            if (state.prefs.setBiometricMode(BiometricType.DISABLED)) {
                dismissDialog(context, dialogHost, state)
            } else {
                showToast(context, "保存失败，请重试")
            }
            return
        }
        val pwd = pwdInput.text.toString().trim()
        val expectedPasswordVersion = PasswordVersionPolicy.current
        if (pwd.isEmpty()) {
            if (state.prefs.needsPasswordReentry()) {
                showToast(context, "请重新录入该应用的支付密码")
                return
            }
            if (!state.prefs.getEncodedPassword().isNullOrEmpty()) {
                if (PasswordVersionPolicy.requiresReentry(state.prefs.getEncodedPassword(), state.prefs.getPasswordVersion())) {
                    if (state.prefs.clearPassword()) {
                        showToast(context, "安全存储已升级，请重新输入支付密码")
                    } else {
                        showToast(context, "保存失败，请重试")
                    }
                    return
                }
                authenticateWithBiometric(context, dialogHost, state, biometricType = selectedType) {
                    state.prefs.setBiometricMode(selectedType).also { saved ->
                        if (!saved) showToast(context, "保存失败，请重试")
                    }
                }
                return
            }
            showToast(context, "请输入支付密码")
            return
        }
        if (pwd.length != PasswordCipher.PASSWORD_LENGTH || pwd.any { it !in '0'..'9' }) {
            showToast(context, "密码必须是6位数字")
            return
        }
        val encryptionCipher = try {
            PasswordCipher.createEncryptionCipher(state.adapter.app.packageName)
        } catch (e: Throwable) {
            showToast(context, "无法初始化安全存储，请重试")
            return
        }
        authenticateWithBiometric(
            context,
            dialogHost,
            state,
            biometricType = selectedType
        ) {
            if (state.prefs.savePassword(pwd, encryptionCipher, expectedPasswordVersion, selectedType).isFailure) {
                showToast(context, "密码加密失败，请重试")
                return@authenticateWithBiometric false
            }
            true
        }
    }

    fun handleClearPassword(state: AppRuntime): Boolean = state.prefs.clearPassword()

    fun dismissDialog(context: Context, dialogHost: DialogHost, state: AppRuntime) {
        state.fields.removeField(context, FieldStore.SETTINGS_DIALOG)
        dialogHost.dismiss()
    }

    fun authenticateWithBiometric(
        context: Context,
        dialogHost: DialogHost,
        state: AppRuntime,
        successMsg: String = "生物支付已启用",
        biometricType: Int = state.prefs.getBiometricType(),
        onSuccess: () -> Boolean
    ) {
        if (state.isClosed || !context.isValidActivity()) return
        val signal = CancellationSignal()
        val attemptId = dialogHost.beginAuthentication(signal)
        try {
            val builder = BiometricPrompt.Builder(context)
                .setTitle(io.github.kiriashi.biopay.biometric.BioPayPrompt.TITLE)
                .setNegativeButton("取消", context.mainExecutor) { _, _ ->
                    dialogHost.finishAuthentication(attemptId)
                }
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult?) {
                    if (!dialogHost.finishAuthentication(attemptId) || state.isClosed || !context.isValidActivity()) return
                    try {
                        if (onSuccess()) {
                            showToast(context, successMsg)
                            dismissDialog(context, dialogHost, state)
                        }
                    } catch (e: Throwable) {
                        showToast(context, "保存失败，请重试")
                    }
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                    if (!dialogHost.finishAuthentication(attemptId)) return
                    if (msg != null && context.isValidActivity()) showToast(context, msg.toString())
                }
            }
            BiometricPromptPolicy.configure(builder, biometricType).build()
                .authenticate(signal, context.mainExecutor, callback)
        } catch (e: Throwable) {
            if (dialogHost.finishAuthentication(attemptId)) showToast(context, "无法启动身份验证，请重试")
        }
    }

    fun showToast(context: Context, msg: String) {
        if (msg.isNotEmpty()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun setLogCaptureEnabled(context: Context, state: AppRuntime, enabled: Boolean): String? {
        if (state.isClosed) return null
        if (!state.prefs.setLogCaptureEnabled(enabled)) return null
        if (enabled) {
            LogCapture.start(context)
            return "日志捕获已开启"
        }
        LogCapture.stop(context) { path ->
            if (context.isValidActivity()) {
                showToast(context, if (path != null) "日志已保存到: $path" else "日志保存未完成")
            }
        }
        return "日志捕获已关闭"
    }
}
