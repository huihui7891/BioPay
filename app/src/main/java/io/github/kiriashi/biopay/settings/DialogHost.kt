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
package io.github.kiriashi.biopay.settings

import io.github.kiriashi.biopay.core.log.ModuleLog
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import io.github.kiriashi.biopay.core.util.isValidActivity
import android.view.View
import android.os.CancellationSignal
import io.github.kiriashi.biopay.payment.SessionToken

class DialogHost(context: Context) {
    private val contextRef = java.lang.ref.WeakReference(context)
    private var dialog: AlertDialog? = null
    private val authentication = SessionToken()
    private var signal: CancellationSignal? = null
    private var authenticationCleanup: (() -> Unit)? = null
    @Volatile private var closed = false
    private var working = false
    val isOpen: Boolean get() = !closed
    val isBusy: Boolean get() = working || signal != null

    fun beginWork(): Boolean {
        if (closed || isBusy) return false
        working = true
        return true
    }

    fun finishWork(): Boolean {
        working = false
        return !closed
    }
    fun beginAuthentication(newSignal: CancellationSignal, cleanup: () -> Unit = {}): Long {
        cancelAuthentication()
        signal = newSignal
        authenticationCleanup = cleanup
        return authentication.begin()
    }
    fun finishAuthentication(id: Long): Boolean {
        if (!authentication.finish(id)) return false
        signal = null
        authenticationCleanup = null
        return true
    }
    private fun cancelAuthentication() {
        authentication.invalidate()
        val previous = signal
        signal = null
        val cleanup = authenticationCleanup
        authenticationCleanup = null
        try {
            previous?.cancel()
        } catch (error: Exception) {
            ModuleLog.w(error) { "settings authentication cancellation failed" }
        } finally { cleanup?.invoke() }
    }
    var onDismiss: (() -> Unit)? = null
    private val ctx get() = contextRef.get()
    fun show(content: View): Boolean {
        val c = ctx ?: return false
        if (!c.isValidActivity()) return false
        // Use the platform floating alert window so Android can pan it naturally to keep
        // the focused password field visible. The explicit platform theme avoids inheriting
        // payment-app alert styling behind the rounded module content.
        dialog = AlertDialog.Builder(c, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setView(content)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            }
        dialog?.setOnDismissListener {
            closed = true
            cancelAuthentication()
            dialog = null
            onDismiss?.invoke()
            onDismiss = null
        }
        dialog?.show()
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.decorView.background = ColorDrawable(Color.TRANSPARENT)
            window.setDimAmount(0.32f)
            window.setGravity(Gravity.CENTER)
            val metrics = c.resources.displayMetrics
            val maxWidth = metrics.widthPixels - (48f * metrics.density).toInt()
            window.setLayout(maxWidth.coerceAtLeast(1), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        return dialog?.isShowing == true
    }
    fun dismiss() {
        closed = true
        cancelAuthentication()
        try {
            dialog?.dismiss()
        } catch (error: Exception) {
            ModuleLog.w(error) { "settings dialog dismissal failed" }
        } finally {
            dialog = null
            onDismiss?.invoke()
            onDismiss = null
        }
    }
}
