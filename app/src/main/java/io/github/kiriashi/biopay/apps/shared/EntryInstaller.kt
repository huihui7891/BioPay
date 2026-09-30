/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.shared

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.core.util.MainTasks
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.kiriashi.biopay.runtime.FieldStore
import io.github.kiriashi.biopay.settings.SettingsDialog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal const val ENTRY_TAG = "io.github.kiriashi.biopay.native_entry"

/** Adds independently built, app-native settings rows at each payment app's own insertion point. */
internal class EntryInstaller(
    private val state: AppRuntime,
    private val entry: SettingsEntry
) {
    private val app = state.adapter.app
    private val cleanups = WeakHashMap<Activity, MutableList<() -> Unit>>()
    private val lastAttempt = WeakHashMap<Activity, Long>()
    private val retryJobs = WeakHashMap<Activity, Runnable>()
    private class LayoutWatch(
        val root: WeakReference<ViewGroup>,
        val listener: ViewTreeObserver.OnGlobalLayoutListener,
        val retry: Runnable
    )
    private val layoutWatchers = WeakHashMap<Activity, LayoutWatch>()
    private val installedRows = WeakHashMap<ViewGroup, WeakReference<View>>()
    private val tasks = MainTasks()
    @Volatile private var closed = false
    private var cleanedUp = false

    /** Retry while an app is still creating its settings fragment after Activity resume. */
    fun installWhenReady(activity: Activity) {
        if (closed) return
        cancelRetry(activity)
        entry.prepare(activity)
        if (entry.tryImmediately) install(activity)
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (entryIsInstalled(root)) return
        if (activity.isFinishing || activity.isDestroyed) return

        val activityRef = WeakReference(activity)
        val rootRef = WeakReference(root)
        var nextDelay = 0
        lateinit var retry: Runnable
        retry = Runnable {
            if (closed) return@Runnable
            val currentActivity = activityRef.get()
            val currentRoot = rootRef.get()
            if (currentActivity != null && retryJobs[currentActivity] !== retry) return@Runnable
            if (currentActivity == null || currentRoot == null || currentActivity.isFinishing || currentActivity.isDestroyed) {
                if (currentActivity != null) retryJobs.remove(currentActivity)
                return@Runnable
            }
            if (entryIsInstalled(currentRoot)) {
                retryJobs.remove(currentActivity)
                return@Runnable
            }
            install(currentActivity)
            if (entryIsInstalled(currentRoot) || nextDelay >= RETRY_DELAYS.size) {
                retryJobs.remove(currentActivity)
            } else {
                tasks.post(retry, RETRY_DELAYS[nextDelay++])
            }
        }
        retryJobs[activity] = retry
        tasks.post(retry, RETRY_DELAYS[nextDelay++])
    }

    fun install(activity: Activity) {
        if (closed) return
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.window?.decorView as? ViewGroup ?: return
        existingEntry(root)?.let { existing ->
            if (entry.accepts(existing)) {
                cancelRetry(activity)
                return
            }
            if (!entry.replaceHidden(root, existing)) return
            installedRows.remove(root)
            cleanups.remove(activity)?.asReversed()?.forEach { cleanup -> runCatching(cleanup) }
            entry.remove(activity)
        }
        val now = SystemClock.uptimeMillis()
        if (now - (lastAttempt[activity] ?: 0L) < 150L) return
        lastAttempt[activity] = now
        try {
            val installed = entry.install(this, activity, root)
            if (installed) {
                findTagged(root)?.let { installedRows[root] = WeakReference(it) }
                Log.i(LOG_TAG, "${app.displayName} native BioPay entry installed")
                diagnostic(Log.INFO, "${app.displayName} native settings entry installed")
            }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "${app.displayName} native entry insertion failed", e)
        }
    }

    /** Install into a transient app-owned window such as QQ's chat action menu. */
    fun installInRoot(activity: Activity, root: ViewGroup) {
        if (closed || activity.isFinishing || activity.isDestroyed || !root.isAttachedToWindow) return
        val existing = findTagged(root)
        if (existing != null && entry.accepts(existing)) {
            try {
                entry.onExisting(this, activity, root, existing)
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "${app.displayName} transient entry refresh failed", e)
            }
            return
        }
        if (existing != null && !entry.replaceHidden(root, existing)) return
        try {
            if (entry.install(this, activity, root)) {
                Log.i(LOG_TAG, "${app.displayName} transient native entry installed")
            }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "${app.displayName} transient entry insertion failed", e)
        }
    }

    /** Keep checking when an app populates or rebuilds its settings page asynchronously. */
    internal fun watchForEntry(activity: Activity, root: ViewGroup) {
        if (closed || layoutWatchers.containsKey(activity) || activity.isFinishing || activity.isDestroyed) return

        val activityRef = WeakReference(activity)
        val rootRef = WeakReference(root)
        var retryPending = false
        var timedRetries = 0
        lateinit var watcher: ViewTreeObserver.OnGlobalLayoutListener
        lateinit var retry: Runnable
        retry = Runnable {
            retryPending = false
            val currentActivity = activityRef.get()
            val currentRoot = rootRef.get()
            if (closed || currentActivity == null || currentRoot == null || currentActivity.isFinishing ||
                currentActivity.isDestroyed || layoutWatchers[currentActivity]?.listener !== watcher
            ) return@Runnable

            install(currentActivity)
            if (!entryIsInstalled(currentRoot)) {
                // A tagged row means the target was found and injected. If the
                // host currently refuses to lay it out, wait for the next real
                // layout event instead of forcing a periodic retry loop.
                if (findTagged(currentRoot) != null) return@Runnable
                if (timedRetries < MAX_LAYOUT_RETRIES) {
                    timedRetries++
                    retryPending = true
                    tasks.post(retry, LAYOUT_RETRY_MS)
                }
            }
        }
        watcher = ViewTreeObserver.OnGlobalLayoutListener {
            if (!closed && !retryPending) {
                retryPending = true
                tasks.post(retry)
            }
        }
        layoutWatchers[activity] = LayoutWatch(rootRef, watcher, retry)
        root.viewTreeObserver.addOnGlobalLayoutListener(watcher)
        retryPending = true
        tasks.post(retry)
    }

    fun removeActivity(activity: Activity) {
        stopWatching(activity)
        (activity.window?.decorView as? ViewGroup)?.let(installedRows::remove)
        cleanups.remove(activity)?.asReversed()?.forEach { cleanup ->
            runCatching(cleanup).onFailure { Log.d(LOG_TAG, "entry cleanup failed", it) }
        }
        lastAttempt.remove(activity)
        entry.remove(activity)
    }

    fun close() {
        if (closed) return
        closed = true
        tasks.close()
        tasks.onMain(::cleanup)
    }

    private fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        layoutWatchers.keys.toList().forEach(::stopWatching)
        retryJobs.keys.toList().forEach(::cancelRetry)
        cleanups.keys.toList().forEach(::removeActivity)
        lastAttempt.clear()
        installedRows.clear()
        entry.close()
    }

    private fun cancelRetry(activity: Activity) {
        retryJobs.remove(activity)?.let(tasks::cancel)
    }

    internal fun stopWatching(activity: Activity) {
        cancelRetry(activity)
        layoutWatchers.remove(activity)?.let { watcher ->
            tasks.cancel(watcher.retry)
            watcher.root.get()?.viewTreeObserver?.takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(watcher.listener)
        }
    }

    private val rows = EntryRowFactory(::openSettings)

    internal fun createRow(
        activity: Activity,
        label: String,
        height: Int,
        horizontalPadding: Int,
        rounded: Boolean,
        styleAnchor: View? = null,
        icon: Boolean = false,
        rightPadding: Int = if (horizontalPadding == 0) 18 else horizontalPadding
    ): LinearLayout = rows.createRow(
        activity, label, height, horizontalPadding, rounded, styleAnchor, icon, rightPadding
    )

    internal fun openSettings(activity: Activity) {
        if (closed) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (!state.fields.compareAndSetField(activity, FieldStore.SETTINGS_DIALOG, false, true)) return
        try {
            if (!SettingsDialog.show(activity, state)) {
                state.fields.removeField(activity, FieldStore.SETTINGS_DIALOG)
                Log.w(LOG_TAG, "BioPay settings dialog could not be shown")
            }
        } catch (e: Throwable) {
            state.fields.removeField(activity, FieldStore.SETTINGS_DIALOG)
            Log.w(LOG_TAG, "BioPay settings dialog failed", e)
        }
    }

    internal fun record(activity: Activity, cleanup: () -> Unit) {
        cleanups.getOrPut(activity) { mutableListOf() }.add(cleanup)
    }

    internal fun diagnostic(priority: Int, message: String) {
        state.diagnostic(priority, message)
    }

    private fun findTagged(root: ViewGroup): View? = entry.findExisting(root)

    private fun existingEntry(root: ViewGroup): View? {
        val cached = installedRows[root]?.get()
        if (cached != null && cached.isAttachedToWindow && cached.rootView === root.rootView) return cached
        installedRows.remove(root)
        return findTagged(root)?.also { installedRows[root] = WeakReference(it) }
    }

    private fun entryIsInstalled(root: ViewGroup): Boolean {
        val existing = existingEntry(root) ?: return false
        return entry.accepts(existing)
    }

    private companion object {
        val RETRY_DELAYS = longArrayOf(150L, 250L, 350L, 500L, 750L, 1_000L, 1_500L, 2_000L, 3_000L, 4_000L)
        const val LAYOUT_RETRY_MS = 500L
        const val MAX_LAYOUT_RETRIES = 10
    }
}
