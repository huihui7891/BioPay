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

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Locates the current WeChat page action without retaining its Activity. */
internal object WeChatPaymentContinuation {
    fun currentPage(activity: Any): Any? =
        method(activity.javaClass, "topShowFragment").invoke(activity)

    fun resolve(activity: Any): (() -> Unit)? = currentPage(activity)?.let(::resolvePage)

    fun resolvePage(fragment: Any): (() -> Unit)? {
        val delegate = field(fragment.javaClass, "pagePlatformFuncDelegate").get(fragment) ?: return null
        val callback = field(delegate.javaClass, "topRightBtnCallback").get(delegate) ?: return null
        val call = method(callback.javaClass, "call")
        return { call.invoke(callback); Unit }
    }

    private fun method(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        // Also support a public method inherited from an interface.
        return type.getMethod(name).apply { isAccessible = true }
    }

    private fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }
}
