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

package io.github.kiriashi.biopay.runtime

import android.app.Activity
import java.util.WeakHashMap

class FieldStore {

    companion object {
        const val SETTINGS_DIALOG = "a"
    }

    private val fields: MutableMap<Any, HashMap<String, Any>> = WeakHashMap()

    fun compareAndSetField(obj: Any, name: String, expected: Boolean, newValue: Any): Boolean {
        synchronized(fields) {
            val exists = fields[obj]?.containsKey(name) == true
            if (exists == expected) {
                fields.getOrPut(obj) { HashMap() }[name] = newValue
                return true
            }
            return false
        }
    }

    fun removeField(obj: Any, name: String) {
        synchronized(fields) {
            val map = fields[obj] ?: return
            map.remove(name)
            if (map.isEmpty()) fields.remove(obj)
        }
    }

    fun cleanupActivity(activity: Activity) {
        synchronized(fields) {
            fields.remove(activity)
        }
    }
}
