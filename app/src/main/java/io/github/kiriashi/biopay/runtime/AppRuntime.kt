/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.runtime

import android.app.Application
import android.app.Activity
import android.content.Context
import io.github.kiriashi.biopay.apps.PaymentAdapter
import io.github.kiriashi.biopay.apps.VisualPaymentAdapter
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.apps.AppComponents
import io.github.kiriashi.biopay.apps.qq.QqMenuEntryHook
import io.github.kiriashi.biopay.apps.shared.EntryInstaller
import io.github.kiriashi.biopay.payment.PaymentFlow
import io.github.kiriashi.biopay.payment.PaymentWindowRoots
import io.github.kiriashi.biopay.payment.PaymentSession
import io.github.kiriashi.biopay.payment.VisualPaymentMonitor
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.storage.PrefKeys
import io.github.kiriashi.biopay.storage.ConfigStore

/** Owns the collaborators and cleanup for one payment app process. */
class AppRuntime private constructor(
    val app: Application,
    val adapter: PaymentAdapter,
    private val report: (Int, String) -> Unit
) {
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    val prefs = ConfigStore(
        app,
        app.getSharedPreferences(PrefKeys.prefName, Context.MODE_PRIVATE),
        adapter.app
    )
    val flow = PaymentFlow(this)
    val session = PaymentSession(onDestroy = flow::reset)
    val fields = FieldStore()
    private val entryInstaller = if (adapter is VisualPaymentAdapter) {
        AppComponents.entryFor(adapter.app)?.let { EntryInstaller(this, it) }
    } else null

    private val monitor = lazy {
        (adapter as? VisualPaymentAdapter)?.let { VisualPaymentMonitor(this, it) }
    }
    val visualMonitor: VisualPaymentMonitor? get() = if (closed) null else monitor.value

    fun onActivityCreated(activity: Activity) {
        val name = activity.javaClass.name
        if ((adapter.app == PaymentApp.TAOBAO && TAOBAO_SETTINGS_ACTIVITIES.any(name::endsWith)) ||
            (adapter.app == PaymentApp.UNIONPAY && name.endsWith(".UPActivityReactNative"))) {
            entryInstaller?.installWhenReady(activity)
        }
    }

    fun watchActivity(activity: Activity) {
        if (shouldInstallEntry(activity)) {
            entryInstaller?.installWhenReady(activity)
            if (adapter.app == PaymentApp.TAOBAO) {
                (activity.window?.decorView as? android.view.ViewGroup)?.let { root ->
                    entryInstaller?.watchForEntry(activity, root)
                }
            }
        }
        visualMonitor?.watchActivity(activity)
    }

    /** Reapply settings entries to Activities that survived an LSPosed hot reload. */
    fun restoreVisibleEntries() {
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Activity, Boolean>()
        )
        val roots = PaymentWindowRoots.attached()
        for (root in roots) {
            val activity = root.context.findActivity() ?: continue
            if (activity.application !== app || !visited.add(activity) ||
                activity.isFinishing || activity.isDestroyed || !shouldInstallEntry(activity)
            ) continue

            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed && shouldInstallEntry(activity)) {
                    entryInstaller?.installWhenReady(activity)
                }
            }
        }
    }

    private fun shouldInstallEntry(activity: Activity): Boolean {
        val visualAdapter = adapter as? VisualPaymentAdapter ?: return false
        val name = activity.javaClass.name
        return when (adapter.app) {
            PaymentApp.ALIPAY -> !visualAdapter.supports(activity)
            PaymentApp.TAOBAO -> TAOBAO_SETTINGS_ACTIVITIES.any(name::endsWith)
            PaymentApp.UNIONPAY -> !visualAdapter.supports(activity) || name.endsWith(".UPActivityReactNative")
            PaymentApp.QQ, PaymentApp.WECHAT -> false
        }
    }

    fun stopActivity(activity: Activity, destroyed: Boolean = false) {
        if (destroyed) {
            entryInstaller?.removeActivity(activity)
        } else if (adapter.app == PaymentApp.TAOBAO) {
            entryInstaller?.stopWatching(activity)
        }
        visualMonitor?.stopActivity(activity, destroyed)
    }

    internal fun diagnostic(priority: Int, message: String) {
        runCatching { report(priority, message) }
    }

    fun installEntry(activity: Activity) {
        if (adapter.app == PaymentApp.QQ) return
        entryInstaller?.install(activity)
    }

    fun installPopupEntry(activity: Activity, root: android.view.ViewGroup) {
        if (adapter.app != PaymentApp.QQ) return
        entryInstaller?.installInRoot(activity, root)
    }

    fun close() {
        if (closed) return
        closed = true
        if (adapter.app == PaymentApp.QQ) QqMenuEntryHook.cancelPendingRetries()
        if (monitor.isInitialized()) monitor.value?.close()
        entryInstaller?.close()
        session.destroy()
        prefs.close()
    }

    companion object {
        private val TAOBAO_SETTINGS_ACTIVITIES = listOf(
            ".DxSettingCommonActivity"
        )
        fun create(
            app: Application,
            adapter: PaymentAdapter,
            report: (Int, String) -> Unit = { _, _ -> }
        ): AppRuntime {
            require(app.packageName == adapter.app.packageName)
            return AppRuntime(app, adapter, report)
        }
    }
}
