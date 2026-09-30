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

import io.github.kiriashi.biopay.core.log.ModuleLog
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.TextView
import io.github.kiriashi.biopay.core.util.isValidActivity
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.libxposed.api.XposedInterface

object PullDownHook {

    const val HOOK_ID = "bp_pull_down"

    private val SETTINGS_TEXTS = arrayOf("设置", "設定", "Settings")
    fun register(cl: ClassLoader, xposed: XposedInterface, state: AppRuntime): XposedInterface.HookHandle? {
        return try {
            val clazz = cl.loadClass(HookTargets.PullDownListView)
            val method = clazz.getDeclaredMethod(
                "onItemLongClick",
                AdapterView::class.java, View::class.java,
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            xposed.hook(method).setId(HOOK_ID).intercept(makeInterceptor(state))
        } catch (e: Throwable) {
            ModuleLog.w(e) { "register failed" }
            null
        }
    }
    fun makeInterceptor(state: AppRuntime): XposedInterface.Hooker {
        return XposedInterface.Hooker { chain ->
            try {
                val view = chain.args[1] as? View
                if (view != null && view.context.isValidActivity() && containsSettingsText(view)) {
                    if (state.showSettings(view.context)) return@Hooker true
                }
            } catch (e: Throwable) {
                ModuleLog.w(e) { "pullDown interceptor failed" }
            }
            chain.proceed()
        }
    }
    private fun containsSettingsText(view: View, depth: Int = 0): Boolean {
        if (depth > 8) return false
        if (view is TextView) {
            val t = view.text?.toString() ?: ""
            if (t in SETTINGS_TEXTS) return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsSettingsText(view.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }
}
