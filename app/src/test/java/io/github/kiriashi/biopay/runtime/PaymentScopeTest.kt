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

import io.github.kiriashi.biopay.apps.PaymentApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScopeTest {
    @Test
    fun qqWalletProcessIsIncludedWithoutOtherQqChildren() {
        assertTrue(PaymentApp.QQ.handlesProcess("com.tencent.mobileqq"))
        assertTrue(PaymentApp.QQ.handlesProcess("com.tencent.mobileqq:tool"))
        assertFalse(PaymentApp.QQ.handlesProcess("com.tencent.mobileqq:MSF"))
        assertFalse(PaymentApp.WECHAT.handlesProcess("com.tencent.mm:tool"))
    }

    @Test
    fun packagedScopeTargetsPaymentApps() {
        val resource = javaClass.classLoader!!.getResourceAsStream("META-INF/xposed/scope.list")
        assertNotNull("Module scope resource must be packaged", resource)
        val scopes = resource!!.bufferedReader().use { reader ->
            reader.readLines().filter { it.isNotBlank() }.toSet()
        }
        assertEquals(setOf(
            "com.tencent.mm", "com.eg.android.AlipayGphone", "com.taobao.taobao",
            "com.tencent.mobileqq", "com.unionpay"
        ), scopes)
    }
}
