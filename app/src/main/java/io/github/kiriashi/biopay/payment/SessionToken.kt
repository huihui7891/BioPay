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

package io.github.kiriashi.biopay.payment

import java.util.concurrent.atomic.AtomicLong

internal class SessionToken {
    private val nextId = AtomicLong(0)
    private val currentId = AtomicLong(0)

    fun begin(): Long {
        val id = nextId.incrementAndGet()
        currentId.set(id)
        return id
    }

    fun current(): Long = currentId.get()

    fun isCurrent(id: Long): Boolean = id != 0L && currentId.get() == id

    fun finish(id: Long): Boolean = id != 0L && currentId.compareAndSet(id, 0L)

    fun invalidate() {
        currentId.set(0L)
    }
}
