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

package io.github.kiriashi.biopay.apps.wechat

import io.github.kiriashi.biopay.core.codec.XorCodec

/** Obfuscated WeChat class names touched by hooks. */
object HookTargets {

    private val KindaContextE = intArrayOf(57, 83, 19, 51, 235, 46, 204, 11, 63, 82, 10, 51, 244, 34, 204, 12, 59, 18, 24, 111, 254, 38, 199, 31, 53, 78, 21, 51, 232, 34, 198, 15, 63, 72, 80, 105, 240, 36, 206, 27, 116, 119, 23, 115, 251, 42, 225, 7, 52, 72, 27, 101, 235)
    private val MyKeyboardWindowE = intArrayOf(57, 83, 19, 51, 235, 46, 204, 24, 59, 69, 80, 124, 241, 47, 208, 7, 51, 88, 80, 106, 250, 40, 202, 9, 46, 18, 51, 100, 212, 46, 219, 10, 53, 93, 12, 121, 200, 34, 204, 12, 53, 75)
    private val tenpayKeyboardE = intArrayOf(46, 89, 16, 109, 254, 50, 253, 3, 63, 69, 28, 114, 254, 57, 198, 55)

    private val AlertDialogImplE = intArrayOf(57, 83, 19, 51, 235, 46, 204, 11, 63, 82, 10, 51, 244, 34, 204, 12, 59, 18, 24, 111, 254, 38, 199, 31, 53, 78, 21, 51, 232, 34, 198, 15, 63, 72, 80, 127, 254, 56, 199, 70, 27, 80, 27, 111, 235, 15, 203, 9, 54, 83, 25, 84, 242, 59, 206)
    val AlertDialogImpl by lazy { XorCodec.decode(AlertDialogImplE) }

    private val VoidCallbackE = intArrayOf(57, 83, 19, 51, 235, 46, 204, 11, 63, 82, 10, 51, 244, 34, 204, 12, 59, 18, 25, 120, 241, 101, 244, 7, 51, 88, 61, 124, 243, 39, 192, 9, 57, 87)
    val VoidCallback by lazy { XorCodec.decode(VoidCallbackE) }

    val KindaContext by lazy { XorCodec.decode(KindaContextE) }
    val MyKeyboardWindow by lazy { XorCodec.decode(MyKeyboardWindowE) }
    val tenpayKeyboard by lazy { XorCodec.decode(tenpayKeyboardE) }
}
