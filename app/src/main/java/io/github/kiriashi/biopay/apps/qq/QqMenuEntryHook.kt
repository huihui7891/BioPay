/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.qq

import io.github.kiriashi.biopay.core.log.ModuleLog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.core.util.MainTasks
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.kiriashi.biopay.runtime.PaymentWindowHook
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Watches QQ-owned popup windows so its entry can live in the chat action menu. */
internal object QqMenuEntryHook {
    const val HOOK_ID = "bp_qq_popup_add_view"
    const val SHOW_DROPDOWN_ID = "bp_qq_popup_show_dropdown"
    const val SHOW_LOCATION_ID = "bp_qq_popup_show_location"
    const val DISMISS_ID = "bp_qq_popup_dismiss"
    private val tasks = MainTasks()
    private val popups = WeakHashMap<View, WeakReference<PopupWindow>>()
    private val sizeAdjustments = WeakHashMap<View, SizeAdjustment>()
    private val pendingRetries = WeakHashMap<View, MutableList<Runnable>>()

    private class SizeAdjustment(
        val extraHeight: Int,
        var originalPopupHeight: Int?,
        var originalWindowHeight: Int?,
        var baseHeight: Int,
        var popupRef: WeakReference<PopupWindow>? = null
    )

    fun registerAddView(xposed: XposedInterface, state: AppRuntime): XposedInterface.HookHandle? {
        if (state.adapter.app != PaymentApp.QQ) return null
        return try {
            val method = PaymentWindowHook.addViewMethod()
            xposed.hook(method).setId(HOOK_ID).intercept(makeInterceptor(state))
        } catch (e: Throwable) {
            ModuleLog.w(e) { "QQ popup entry hook registration failed" }
            null
        }
    }

    fun registerShowDropdown(xposed: XposedInterface, state: AppRuntime) = registerPopupMethod(
        xposed,
        state,
        SHOW_DROPDOWN_ID,
        "showAsDropDown",
        View::class.java,
        Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!
    )

    fun registerShowLocation(xposed: XposedInterface, state: AppRuntime) = registerPopupMethod(
        xposed,
        state,
        SHOW_LOCATION_ID,
        "showAtLocation",
        View::class.java,
        Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!
    )

    fun makeInterceptor(state: AppRuntime): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        try {
            val root = chain.args.firstOrNull() as? ViewGroup
            if (root != null) {
                root.post {
                    if (state.isClosed || !root.isAttachedToWindow) return@post
                    runCatching { state.visualMonitor?.watchWindow(root) }
                        .onFailure { ModuleLog.w(it) { "QQ payment window inspection failed" } }
                    val activity = root.context.findActivity()
                    if (activity?.packageName != state.app.packageName) return@post
                    runCatching {
                        state.installPopupEntry(activity, root)
                        // QQ can finalize popup height after addView returns.
                        scheduleRetries(root, activity, state)
                    }.onFailure { ModuleLog.w(it) { "QQ popup inspection failed" } }
                }
            }
        } catch (e: Throwable) {
            ModuleLog.w(e) { "QQ popup inspection failed" }
        }
        result
    }

    fun makePopupInterceptor(state: AppRuntime): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        try {
            val popup = chain.thisObject as? PopupWindow
            val content = popup?.contentView
            val root = content?.rootView
            val activity = content?.context?.findActivity()
            if (popup?.isShowing == true && content != null && root != null && activity?.packageName == state.app.packageName) {
                synchronized(popups) {
                    val reference = WeakReference(popup)
                    popups[root] = reference
                    popups[content] = reference
                }
                synchronized(sizeAdjustments) {
                    val adjustment = sizeAdjustments[root] ?: sizeAdjustments[content]
                    if (adjustment != null) {
                        adjustment.popupRef = WeakReference(popup)
                        if (adjustment.originalPopupHeight == null) {
                            adjustment.originalPopupHeight = popup.height.takeIf { it != 0 }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            ModuleLog.d(e) { "QQ popup reference capture skipped" }
        }
        result
    }

    fun dismiss(root: View) {
        cancelRetries(root)
        val popup = popupFor(root) ?: return
        runCatching { if (popup.isShowing) popup.dismiss() }
            .onFailure { ModuleLog.w(it) { "QQ action menu dismissal failed" } }
        restorePopup(popup)
    }

    fun makeDismissInterceptor(): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        try {
            (chain.thisObject as? PopupWindow)?.let(::restorePopup)
        } catch (e: Throwable) {
            ModuleLog.d(e) { "QQ popup size restoration skipped" }
        }
        result
    }

    /** Expands the fixed-height QQ popup to include the injected row. */
    fun expandForEntry(root: View, extraHeight: Int): () -> Unit {
        val adjustment = synchronized(sizeAdjustments) {
            sizeAdjustments[root] ?: run {
                val popup = popupFor(root)
                val shared = popup?.let { currentPopup ->
                    sizeAdjustments.values.firstOrNull { it.popupRef?.get() === currentPopup }
                }
                shared?.let {
                    sizeAdjustments[root] = it
                    it.popupRef = WeakReference(popup)
                    return@synchronized it
                }
                val popupHeight = popup?.height
                val params = root.layoutParams as? WindowManager.LayoutParams
                val windowHeight = params?.height
                val baseHeight = popupHeight?.takeIf { it > 0 }
                    ?: windowHeight?.takeIf { it > 0 }
                    ?: root.height
                SizeAdjustment(extraHeight, popupHeight, windowHeight, baseHeight, popup?.let(::WeakReference)).also {
                    sizeAdjustments[root] = it
                }
            }
        }
        applyExpansion(root, adjustment)
        return { restoreAdjustment(adjustment) }
    }

    private fun applyExpansion(root: View, adjustment: SizeAdjustment) {
        if (adjustment.baseHeight <= 0) {
            val popupHeight = popupFor(root)?.height
            adjustment.baseHeight = popupHeight?.takeIf { it > 0 } ?: root.height
        }
        val baseHeight = adjustment.baseHeight
        if (baseHeight <= 0) return

        val popup = popupFor(root) ?: adjustment.popupRef?.get()
        if (popup?.isShowing == true) {
            runCatching {
                val popupBase = adjustment.originalPopupHeight?.takeIf { it > 0 } ?: baseHeight
                popup.height = popupBase + adjustment.extraHeight
                popup.update()
            }.onFailure { ModuleLog.w(it) { "QQ popup expansion retry failed" } }
        }

        val params = root.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            runCatching {
                val windowBase = adjustment.originalWindowHeight?.takeIf { it > 0 } ?: baseHeight
                val targetHeight = windowBase + adjustment.extraHeight
                if (params.height != targetHeight) {
                    params.height = targetHeight
                    (root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .updateViewLayout(root, params)
                }
            }.onFailure { ModuleLog.w(it) { "QQ action menu window resize failed" } }
        }
        root.requestLayout()
    }

    private fun registerPopupMethod(
        xposed: XposedInterface,
        state: AppRuntime,
        id: String,
        name: String,
        vararg parameters: Class<*>
    ): XposedInterface.HookHandle? {
        if (state.adapter.app != PaymentApp.QQ) return null
        return try {
            val method = PopupWindow::class.java.getDeclaredMethod(name, *parameters)
            xposed.hook(method).setId(id).intercept(makePopupInterceptor(state))
        } catch (e: Throwable) {
            ModuleLog.w(e) { "QQ popup lifecycle hook $name registration failed" }
            null
        }
    }

    fun registerDismiss(xposed: XposedInterface, state: AppRuntime): XposedInterface.HookHandle? {
        if (state.adapter.app != PaymentApp.QQ) return null
        return try {
            val method = PopupWindow::class.java.getDeclaredMethod("dismiss")
            xposed.hook(method).setId(DISMISS_ID).intercept(makeDismissInterceptor())
        } catch (e: Throwable) {
            ModuleLog.w(e) { "QQ popup dismissal hook registration failed" }
            null
        }
    }

    private fun popupFor(root: View): PopupWindow? = synchronized(popups) {
        popups[root]?.get() ?: popups[root.rootView]?.get()
    }

    private fun restorePopup(popup: PopupWindow) {
        synchronized(pendingRetries) {
            pendingRetries.keys.filter { popupFor(it) === popup }.toList()
        }.forEach(::cancelRetries)
        val adjustments = synchronized(sizeAdjustments) {
            sizeAdjustments.values
                .filter { it.popupRef?.get() === popup }
                .distinct()
        }
        adjustments.forEach(::restoreAdjustment)
        synchronized(popups) {
            popups.entries.removeAll { it.value.get() === popup }
        }
    }

    private fun restoreAdjustment(adjustment: SizeAdjustment) {
        val roots = synchronized(sizeAdjustments) {
            val matching = sizeAdjustments.entries
                .filter { it.value === adjustment }
                .map { it.key }
            matching.forEach(sizeAdjustments::remove)
            matching
        }
        if (roots.isEmpty()) return

        runCatching {
            adjustment.popupRef?.get()?.let { popup ->
                adjustment.originalPopupHeight?.let { originalHeight ->
                    popup.height = originalHeight
                    if (popup.isShowing) popup.update()
                }
            }
            roots.forEach { root ->
                val params = root.layoutParams as? WindowManager.LayoutParams ?: return@forEach
                val originalHeight = adjustment.originalWindowHeight ?: return@forEach
                params.height = originalHeight
                if (root.isAttachedToWindow) {
                    (root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .updateViewLayout(root, params)
                }
            }
        }.onFailure { ModuleLog.w(it) { "QQ popup size restoration failed" } }
    }

    private val EXPANSION_RETRIES = longArrayOf(80L, 200L, 500L, 900L)

    private fun scheduleRetries(root: ViewGroup, activity: android.app.Activity, state: AppRuntime) {
        synchronized(pendingRetries) {
            if (pendingRetries.containsKey(root)) return
            val jobs = mutableListOf<Runnable>()
            val rootRef = WeakReference(root)
            val activityRef = WeakReference(activity)
            for (delay in EXPANSION_RETRIES) {
                val retry = Runnable {
                    val currentRoot = rootRef.get()
                    val currentActivity = activityRef.get()
                    if (!state.isClosed && currentRoot?.isAttachedToWindow == true &&
                        currentActivity != null && !currentActivity.isDestroyed) {
                        state.installPopupEntry(currentActivity, currentRoot)
                    }
                }
                jobs += retry
                tasks.post(retry, delay)
            }
            pendingRetries[root] = jobs
        }
    }

    private fun cancelRetries(root: View) {
        synchronized(pendingRetries) { pendingRetries.remove(root) }
            ?.forEach(tasks::cancel)
    }

    fun cancelPendingRetries() {
        tasks.close()
        synchronized(pendingRetries) { pendingRetries.keys.toList() }.forEach(::cancelRetries)
    }
}
