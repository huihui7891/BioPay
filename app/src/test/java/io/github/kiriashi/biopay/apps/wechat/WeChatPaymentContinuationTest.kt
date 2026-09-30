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

import io.github.kiriashi.biopay.apps.wechat.WeChatPaymentContinuation

import org.junit.Assert.*
import org.junit.Test

class WeChatPaymentContinuationTest {
    open class ActivityBase(private val page: Page?) {
        private fun topShowFragment(): Page? = page
    }
    class Activity(page: Page?) : ActivityBase(page)
    class Page(private val pagePlatformFuncDelegate: Delegate?)
    class Delegate(private val topRightBtnCallback: Callback?)
    open class CallbackBase {
        var calls = 0
        private fun call() { calls++ }
    }
    class Callback : CallbackBase()

    @Test
    fun resolvesInheritedPrivateMembersWithoutInvokingUntilRequested() {
        val callback = Callback()
        val action = WeChatPaymentContinuation.resolve(Activity(Page(Delegate(callback))))
        assertNotNull(action)
        assertEquals(0, callback.calls)
        action!!()
        assertEquals(1, callback.calls)
    }

    @Test
    fun absentPageOrDelegateOrCallbackDoesNotInventAnAction() {
        assertNull(WeChatPaymentContinuation.resolve(Activity(null)))
        assertNull(WeChatPaymentContinuation.resolve(Activity(Page(null))))
        assertNull(WeChatPaymentContinuation.resolve(Activity(Page(Delegate(null)))))
    }

    @Test(expected = NoSuchMethodException::class)
    fun incompatibleActivityDoesNotResolveAnUnrelatedMethod() {
        WeChatPaymentContinuation.resolve(Any())
    }
}
