/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.shared

import android.app.Activity
import android.view.View
import android.view.ViewGroup

/** App-specific placement rules used by the shared entry lifecycle. */
internal interface SettingsEntry {
    val tryImmediately: Boolean get() = true

    fun findExisting(root: ViewGroup): View? = root.findViewWithTag(ENTRY_TAG)
    fun prepare(activity: Activity) = Unit
    fun install(host: EntryInstaller, activity: Activity, root: ViewGroup): Boolean
    fun onExisting(host: EntryInstaller, activity: Activity, root: ViewGroup, existing: View) = Unit
    fun accepts(existing: View): Boolean = true
    fun replaceHidden(root: ViewGroup, existing: View): Boolean = false
    fun remove(activity: Activity) = Unit
    fun close() = Unit
}
