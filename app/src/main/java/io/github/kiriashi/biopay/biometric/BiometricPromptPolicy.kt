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
package io.github.kiriashi.biopay.biometric

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build

/** Keep settings verification and payment verification on the same authentication policy. */
internal object BiometricPromptPolicy {
    fun configure(builder: BiometricPrompt.Builder, biometricType: Int): BiometricPrompt.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                if (biometricType == BiometricType.FINGERPRINT) {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                } else {
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
                }
            )
        }
        if (biometricType == BiometricType.FACE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setConfirmationRequired(false)
        }
        return builder
    }
}
