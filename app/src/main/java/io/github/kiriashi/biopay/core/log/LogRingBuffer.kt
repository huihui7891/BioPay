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

package io.github.kiriashi.biopay.core.log

/**
 * Rolling log history. Callers must synchronize access externally.
 */
class LogRingBuffer(
    private val maxBufferSize: Int = 64 * 1024,
    private val flushThreshold: Int = maxBufferSize * 3 / 4
) {

    private val buffer = StringBuilder()

    fun append(line: String) {
        if (buffer.length > maxBufferSize) {
            val keepFrom = buffer.indexOf("\n", flushThreshold)
            if (keepFrom > 0) {
                buffer.delete(0, keepFrom + 1)
            } else {
                buffer.clear()
            }
        }
        buffer.append(line).append("\n")
    }

    fun snapshot(): String = buffer.toString()

    fun clear() {
        buffer.clear()
    }
}
