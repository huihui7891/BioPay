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

import org.junit.Assert.*
import org.junit.Test

class FingerprintTipRecoveryTest {
    private val chinese = "系统错误，可删除系统指纹，重新录入后再试。如未解决，请咨询手机厂商。"

    @Test
    fun moduleOffAndUnrelatedTipsDoNotResolveOrContinueAPage() {
        val recovery = FingerprintTipRecovery { throw AssertionError(it) }
        val neverResolve = { throw AssertionError("unexpected page lookup") }
        val neverAct: (Any) -> (() -> Unit)? = { throw AssertionError("unexpected continuation") }
        assertFalse(recovery.suppressIfMatched(false, chinese, neverResolve, neverAct))
        assertFalse(recovery.suppressIfMatched(true, "支付失败，请重试", neverResolve, neverAct))
        assertFalse(recovery.suppressIfMatched(true, chinese + "其他错误", neverResolve, neverAct))
        assertFalse(recovery.suppressIfMatched(true, null, neverResolve, neverAct))
    }

    @Test
    fun allFourKnownMessagesContinueTheirOwnActivity() {
        val messages = listOf(
            chinese,
            "系統錯誤，可以刪除裝置上的指紋，重新加入後再度嘗試。如仍無法解決，請洽詢手機製造商。",
            "系統錯誤，可以刪除裝置的指紋資訊，重新加入後再嘗試。如仍無法解決，請洽手機製造商。",
            "System error. Try deleting the fingerprint data in your device settings and then re-add your fingerprint. If problems persist, contact your device manufacturer."
        )
        var calls = 0
        val recovery = FingerprintTipRecovery { throw AssertionError(it) }
        for (message in messages) {
            val owner = Any()
            assertTrue(recovery.suppressIfMatched(true, message, { owner }) { { calls++ } })
        }
        assertEquals(4, calls)
    }

    @Test
    fun reentrantTipsAreDeduplicatedAndLaterPaymentsCanContinue() {
        val owner = Any()
        var calls = 0
        var now = 10_000L
        val recovery = FingerprintTipRecovery({ now }) { throw AssertionError(it) }
        fun repeat(): Boolean = recovery.suppressIfMatched(true, chinese, { owner }) {
            { calls++; assertTrue(recovery.suppressIfMatched(true, chinese, { owner }) {
                throw AssertionError("reentrant callback resolution")
            }) }
        }
        assertTrue(repeat())
        assertTrue(repeat())
        assertEquals(1, calls)
        now += 2_001L
        assertTrue(repeat())
        assertEquals(2, calls)
    }

    @Test
    fun missingActivityOrCallbackLeavesTheOriginalTipVisible() {
        val recovery = FingerprintTipRecovery { throw AssertionError(it) }
        assertFalse(recovery.suppressIfMatched(true, chinese, { null }) {
            throw AssertionError("no Activity")
        })
        assertFalse(recovery.suppressIfMatched(true, chinese, { Any() }) { null })
    }

    @Test
    fun failedContinuationIsReportedAndCanRetryOnTheSameActivity() {
        val owner = Any()
        val failure = IllegalStateException("WeChat callback changed")
        val failures = mutableListOf<Throwable>()
        val recovery = FingerprintTipRecovery { failures.add(it) }
        assertFalse(recovery.suppressIfMatched(true, chinese, { owner }) { { throw failure } })
        var calls = 0
        assertTrue(recovery.suppressIfMatched(true, chinese, { owner }) { { calls++ } })
        assertEquals(listOf(failure), failures)
        assertEquals(1, calls)
    }

    @Test
    fun lookupFailureDoesNotCrashOrHideUnrelatedMessages() {
        var failures = 0
        val recovery = FingerprintTipRecovery { failures++ }
        assertFalse(recovery.suppressIfMatched(true, chinese, { throw NoSuchMethodException() }) { null })
        assertFalse(recovery.suppressIfMatched(true, "余额不足", { throw NoSuchMethodException() }) { null })
        assertEquals(1, failures)
    }
}
