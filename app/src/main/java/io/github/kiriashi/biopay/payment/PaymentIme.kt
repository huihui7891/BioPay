/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import io.github.kiriashi.biopay.BuildConfig
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.MainTasks
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/** Owns IME suppression and input-field policy for one payment window. */
internal class PaymentIme : View.OnAttachStateChangeListener,
    ViewTreeObserver.OnWindowFocusChangeListener, ViewTreeObserver.OnGlobalFocusChangeListener,
    ViewTreeObserver.OnGlobalLayoutListener {

    private class Policy(val input: WeakReference<EditText>, val owner: Any, val showOnFocus: Boolean)

    private val tasks = MainTasks()
    private val revision = AtomicLong()
    private var keyboardRef: WeakReference<ViewGroup>? = null
    private var inputRef: WeakReference<EditText>? = null
    private var rootRef: WeakReference<View>? = null
    private var observerRef: WeakReference<ViewTreeObserver>? = null
    private var policy: Policy? = null
    @Volatile private var blocked = false
    private var authenticating = false
    private var lastHide = 0L
    private var lastCheck = 0L
    private var lastLog: String? = null
    private var layoutPending = false
    private var recheckAt = 0L
    private val checkLayout = Runnable {
        layoutPending = false
        enforce("layout", force = false)
    }
    private val recheck = Runnable {
        recheckAt = 0L
        enforce("recheck", force = Build.VERSION.SDK_INT < 30)
    }

    fun update(keyboard: ViewGroup?, input: EditText?, block: Boolean, authentication: Boolean, force: Boolean = false) {
        val request = revision.incrementAndGet()
        tasks.onMain {
            if (revision.get() != request) return@onMain
            blocked = block && keyboard != null
            authenticating = authentication
            if (!blocked) {
                release()
                return@onMain
            }
            val previous = keyboardRef?.get()
            if (previous !== keyboard) {
                previous?.removeOnAttachStateChangeListener(this)
                keyboardRef = keyboard?.let(::WeakReference)
                keyboard?.addOnAttachStateChangeListener(this)
            }
            if (input == null || inputRef?.get() !== input) inputRef = input?.let(::WeakReference)
            enforce("binding", force)
            if (force) scheduleRecheck(RECHECK_DELAY_MS)
        }
    }

    fun clear() {
        blocked = false
        val request = revision.incrementAndGet()
        tasks.onMain {
            if (revision.get() != request) return@onMain
            release()
        }
    }

    fun show(input: EditText) {
        if (blocked) return
        try {
            requestVisibility(input, visible = true)
        } catch (error: Exception) {
            ModuleLog.w(error) { "payment IME restoration failed" }
        }
    }

    private fun release() {
        if (rootRef != null || policy != null) ModuleLog.d { "payment IME guard released" }
        keyboardRef?.get()?.removeOnAttachStateChangeListener(this)
        keyboardRef = null
        inputRef = null
        stopWatching()
        restorePolicy()
        lastLog = null
    }

    override fun onViewAttachedToWindow(view: View) {
        if (!blocked || keyboardRef?.get() !== view) return
        enforce("attached", force = true)
        scheduleRecheck(RECHECK_DELAY_MS)
    }

    override fun onViewDetachedFromWindow(view: View) {
        if (keyboardRef?.get() !== view) return
        stopWatching()
        restorePolicy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!blocked) return
        enforce(if (hasFocus) "window focused" else "window unfocused", force = Build.VERSION.SDK_INT < 30)
        scheduleRecheck(RECHECK_DELAY_MS)
    }

    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
        if (!blocked) return
        protectInput()
        scheduleRecheck(0L)
    }

    override fun onGlobalLayout() {
        if (!blocked) return
        protectInput()
        scheduleLayoutCheck()
    }

    private fun scheduleRecheck(delay: Long) {
        val due = SystemClock.uptimeMillis() + delay
        if (recheckAt != 0L && recheckAt <= due) return
        recheckAt = due
        tasks.post(recheck, delay)
    }

    private fun scheduleLayoutCheck() {
        if (layoutPending) return
        layoutPending = true
        tasks.post(checkLayout, maxOf(lastCheck, lastHide) + HIDE_INTERVAL_MS - SystemClock.uptimeMillis())
    }

    private fun watchRoot() {
        val keyboard = keyboardRef?.get() ?: return
        val root = keyboard.rootView
        val observer = root.viewTreeObserver
        if (rootRef?.get() === root && observerRef?.get() === observer && observer.isAlive) return
        stopWatching()
        rootRef = WeakReference(root)
        observerRef = WeakReference(observer)
        if (observer.isAlive) {
            observer.addOnWindowFocusChangeListener(this)
            observer.addOnGlobalFocusChangeListener(this)
            observer.addOnGlobalLayoutListener(this)
        }
    }

    private fun stopWatching() {
        tasks.clear()
        layoutPending = false
        recheckAt = 0L
        val root = rootRef?.get()
        val observer = observerRef?.get()?.takeIf { it.isAlive } ?: root?.viewTreeObserver
        if (observer?.isAlive == true) {
            observer.removeOnWindowFocusChangeListener(this)
            observer.removeOnGlobalFocusChangeListener(this)
            observer.removeOnGlobalLayoutListener(this)
        }
        rootRef = null
        observerRef = null
        lastHide = 0L
        lastCheck = 0L
    }

    private fun protectInput() {
        val root = rootRef?.get() ?: return
        val input = inputRef?.get()
        val previous = policy?.input?.get()
        // A transient missing binding must not re-enable a submitted password field.
        val target = input ?: previous?.takeIf { it.isAttachedToWindow && it.rootView === root }
        if (target == null || (target.isAttachedToWindow && target.rootView !== root)) {
            restorePolicy()
            return
        }
        if (previous !== target) {
            restorePolicy()
            val inherited = target.getTag(POLICY_TAG) as? Array<*>
            val original = inherited?.getOrNull(1) as? Boolean ?: target.showSoftInputOnFocus
            val owner = Any()
            target.setTag(POLICY_TAG, arrayOf(owner, original))
            policy = Policy(WeakReference(target), owner, original)
        }
        if (target.showSoftInputOnFocus) {
            target.showSoftInputOnFocus = false
            ModuleLog.d { "payment IME input focus policy suppressed" }
        }
    }

    private fun restorePolicy() {
        val saved = policy ?: return
        policy = null
        saved.input.get()?.let { input ->
            val marker = input.getTag(POLICY_TAG) as? Array<*>
            // A new module instance may already own this field after hot reload.
            if (marker?.getOrNull(0) === saved.owner) {
                input.setTag(POLICY_TAG, null)
                input.showSoftInputOnFocus = saved.showOnFocus
            }
        }
    }

    private fun enforce(event: String, force: Boolean) {
        if (!blocked) return
        val keyboard = keyboardRef?.get() ?: return
        watchRoot()
        protectInput()
        val root = rootRef?.get() ?: return
        if (!keyboard.isAttachedToWindow || !root.isAttachedToWindow || root.windowVisibility != View.VISIBLE) return
        lastCheck = SystemClock.uptimeMillis()
        val visible = if (Build.VERSION.SDK_INT >= 30) root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) else null
        if (!force) {
            if (visible != true) return
            if (lastCheck < lastHide + HIDE_INTERVAL_MS) {
                scheduleLayoutCheck()
                return
            }
        }
        val focused = root.findFocus()
        val foreignInput = focused?.onCheckIsTextEditor() == true && focused !== policy?.input?.get()
        if (BuildConfig.DEBUG) {
            val snapshot = "auth=$authenticating, focus=${root.hasWindowFocus()}, ime=$visible, input=${policy != null}, foreignInput=$foreignInput"
            if (snapshot != lastLog) {
                lastLog = snapshot
                ModuleLog.d { "payment IME guard: event=$event, $snapshot" }
            }
        }
        // A payment field may be created after the prompt starts. Outside authentication,
        // leave unbound editors alone so ordinary editing can still use the IME.
        if (foreignInput && !authenticating) return
        try {
            requestVisibility(root, visible = false)
            lastHide = SystemClock.uptimeMillis()
        } catch (error: Exception) {
            ModuleLog.w(error) { "payment IME suppression failed" }
        }
    }

    private fun requestVisibility(view: View, visible: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.windowInsetsController?.let { controller ->
                if (visible) controller.show(WindowInsets.Type.ime())
                else controller.hide(WindowInsets.Type.ime())
                return
            }
        }
        val manager = view.context.getSystemService(InputMethodManager::class.java)
        if (visible) manager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        else manager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private companion object {
        // A stable, non-resource package key preserves ownership across class-loader replacement.
        const val POLICY_TAG = 0x62690001
        const val RECHECK_DELAY_MS = 32L
        const val HIDE_INTERVAL_MS = 100L
    }
}
