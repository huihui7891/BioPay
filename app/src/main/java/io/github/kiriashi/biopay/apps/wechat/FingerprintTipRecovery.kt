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

import java.util.WeakHashMap

/** Continues a known payment tip once per short burst on the active page. */
internal class FingerprintTipRecovery(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onFailure: (Throwable) -> Unit
) {
    private val continuedPages = WeakHashMap<Any, Long>()

    fun suppressIfMatched(
        enabled: Boolean,
        message: Any?,
        currentPage: () -> Any?,
        resolveAction: (Any) -> (() -> Unit)?
    ): Boolean {
        if (!enabled || message !is String || message !in MESSAGES) return false
        var owner: Any? = null
        var claimedAt: Long? = null
        try {
            owner = currentPage() ?: return false
            val now = nowMillis()
            synchronized(continuedPages) {
                val previous = continuedPages[owner]
                if (previous != null && now >= previous && now - previous < REPEAT_WINDOW_MS) return true
            }
            val action = resolveAction(owner) ?: return false
            // Mark before invoking: the callback can synchronously show the same tip again.
            synchronized(continuedPages) {
                val previous = continuedPages[owner]
                if (previous != null && now >= previous && now - previous < REPEAT_WINDOW_MS) return true
                continuedPages[owner] = now
                claimedAt = now
            }
            action()
            return true
        } catch (e: Throwable) {
            if (claimedAt != null) {
                synchronized(continuedPages) {
                    if (continuedPages[owner] == claimedAt) continuedPages.remove(owner)
                }
            }
            onFailure(e)
            return false
        }
    }

    companion object {
        private const val REPEAT_WINDOW_MS = 2_000L
        private val MESSAGES = setOf(
            "系统错误，可删除系统指纹，重新录入后再试。如未解决，请咨询手机厂商。",
            "系統錯誤，可以刪除裝置上的指紋，重新加入後再度嘗試。如仍無法解決，請洽詢手機製造商。",
            "系統錯誤，可以刪除裝置的指紋資訊，重新加入後再嘗試。如仍無法解決，請洽手機製造商。",
            "System error. Try deleting the fingerprint data in your device settings and then re-add your fingerprint. If problems persist, contact your device manufacturer."
        )
    }
}
