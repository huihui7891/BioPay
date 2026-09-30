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

package io.github.kiriashi.biopay.settings.ui

import android.content.Context
import android.content.res.Configuration
import io.github.kiriashi.biopay.apps.PaymentApp

data class ThemeColors(
    val primary: Int,
    val onPrimary: Int,
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainerHighest: Int,
    val outline: Int,
    val switchOn: Int,
    val switchOffBg: Int,
    val divider: Int,
    val ripple: Int,
    val disabledRipple: Int
)

object Theme {

    private data class AppAccent(
        val light: Int,
        val dark: Int,
        val darkOnPrimary: Int
    )

    private fun accentFor(app: PaymentApp?): AppAccent = when (app) {
        PaymentApp.ALIPAY -> AppAccent(0xFF1677D2.toInt(), 0xFF80B8FF.toInt(), 0xFF00325F.toInt())
        PaymentApp.TAOBAO -> AppAccent(0xFFC74600.toInt(), 0xFFFFB59A.toInt(), 0xFF4C1A00.toInt())
        PaymentApp.QQ -> AppAccent(0xFF45484D.toInt(), 0xFFC5C8CE.toInt(), 0xFF292B30.toInt())
        PaymentApp.UNIONPAY -> AppAccent(0xFFE60012.toInt(), 0xFFFF8A95.toInt(), 0xFF5A0005.toInt())
        PaymentApp.WECHAT, null -> AppAccent(
            light = 0xFF1A6B52.toInt(),
            dark = 0xFF7DD4B5.toInt(),
            darkOnPrimary = 0xFF003828.toInt()
        )
    }

    val Light = ThemeColors(
        primary = 0xFF1A6B52.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        surface = 0xFFF6FBF6.toInt(),
        onSurface = 0xFF191C1A.toInt(),
        onSurfaceVariant = 0xFF3F4943.toInt(),
        surfaceContainerLowest = 0xFFFFFFFF.toInt(),
        surfaceContainerLow = 0xFFF0F5F0.toInt(),
        surfaceContainerHighest = 0xFFDFE4DF.toInt(),
        outline = 0xFF6F7972.toInt(),
        switchOn = 0xFF1A6B52.toInt(),
        switchOffBg = 0xFFDFE4DF.toInt(),
        divider = 0xFFDFE4DF.toInt(),
        ripple = 0xFFDFE4DF.toInt(),
        disabledRipple = 0x501A6B52.toInt()
    )

    val Dark = ThemeColors(
        primary = 0xFF7DD4B5.toInt(),
        onPrimary = 0xFF003828.toInt(),
        surface = 0xFF191C1A.toInt(),
        onSurface = 0xFFE1E3DE.toInt(),
        onSurfaceVariant = 0xFFBFC9C1.toInt(),
        surfaceContainerLowest = 0xFF0E1210.toInt(),
        surfaceContainerLow = 0xFF1E211F.toInt(),
        surfaceContainerHighest = 0xFF393D3A.toInt(),
        outline = 0xFF89938C.toInt(),
        switchOn = 0xFF7DD4B5.toInt(),
        switchOffBg = 0xFF393D3A.toInt(),
        divider = 0xFF393D3A.toInt(),
        ripple = 0xFF393D3A.toInt(),
        disabledRipple = 0x507DD4B5.toInt()
    )

    fun isDark(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun colors(context: Context, app: PaymentApp? = null): ThemeColors {
        val darkMode = isDark(context)
        val base = if (darkMode) Dark else Light
        val accent = accentFor(app)
        val primary = if (darkMode) accent.dark else accent.light
        return base.copy(
            primary = primary,
            onPrimary = if (darkMode) accent.darkOnPrimary else 0xFFFFFFFF.toInt(),
            switchOn = primary,
            disabledRipple = (primary and 0x00FFFFFF) or (0x50 shl 24)
        )
    }
}
