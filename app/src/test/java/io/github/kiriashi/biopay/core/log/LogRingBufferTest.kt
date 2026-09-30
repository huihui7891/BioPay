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

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRingBufferTest {

    @Test
    fun snapshotPreservesAppendedContent() {
        val ring = LogRingBuffer()
        ring.append("a")
        ring.append("b")

        val content = ring.snapshot()

        assertEquals("a\nb\n", content)
        ring.append("c")
        assertEquals("a\nb\nc\n", ring.snapshot())
    }

    @Test
    fun emptyBufferHasEmptySnapshot() {
        val ring = LogRingBuffer()

        val content = ring.snapshot()

        assertEquals("", content)
    }

    @Test
    fun overCapacityTrimsOldestLinesKeepingLineBoundary() {
        val ring = LogRingBuffer(maxBufferSize = 17, flushThreshold = 10)
        for (i in 0..5) ring.append("l$i")

        ring.append("l6")

        assertEquals("l4\nl5\nl6\n", ring.snapshot())
    }

    @Test
    fun trimWithoutNewlineClearsBuffer() {
        val ring = LogRingBuffer(maxBufferSize = 10, flushThreshold = 5)
        ring.append("0123456789abcdef")

        ring.append("next")

        assertEquals("next\n", ring.snapshot())
    }

    @Test
    fun clearResetsToEmpty() {
        val ring = LogRingBuffer()
        ring.append("a")

        ring.clear()

        assertEquals("", ring.snapshot())
    }
}
