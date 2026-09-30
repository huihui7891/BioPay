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

import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.log.LogCapture
import io.github.kiriashi.biopay.apps.PaymentApp
import io.github.kiriashi.biopay.apps.AppComponents
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class BioPayModule : XposedModule() {

    private var runtime: AppRuntime? = null
    private val initLock = Any()
    @Volatile private var initializedApplication: Application? = null
    private var applicationHooksRegistered = false
    private var lifecycleCallbacks: AppLifecycleCallbacks? = null
    private var pendingSettings: Bundle? = null
    @Volatile private var processName: String? = null
    @Volatile private var targetPackageName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModuleLog.bind(param.processName) { priority, message -> log(priority, "bp", message) }
        processName = param.processName
        targetPackageName = paymentAppForProcess(param.processName)?.packageName
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val targetApp = PaymentApp.fromPackage(param.packageName) ?: return

        if (!param.isFirstPackage && targetApp != PaymentApp.ALIPAY &&
            targetApp != PaymentApp.TAOBAO && targetApp != PaymentApp.QQ) {
            return
        }

        val loadingProcess = Application.getProcessName().takeIf(String::isNotBlank) ?: processName.orEmpty()
        if (!targetApp.handlesProcess(loadingProcess)) {
            ModuleLog.d { "skipping non-payment process: $loadingProcess" }
            return
        }
        targetPackageName = targetApp.packageName
        processName = loadingProcess
        ModuleLog.d { "package loaded, isFirstPkg=${param.isFirstPackage}, process=$processName" }

        hookApplicationOnCreate(targetApp)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val reloadProcess = processName.orEmpty()
        ModuleLog.d { "hot reload: old generation entered for $reloadProcess" }
        val packageName = listOfNotNull(
            initializedApplication?.packageName,
            targetPackageName,
            paymentAppForProcess(reloadProcess)?.packageName
        ).firstOrNull { PaymentApp.fromPackage(it) != null }
        if (packageName == null) {
            // Package state is optional for inactive child processes: the new
            // generation can derive it from its process name or discard old hooks.
            ModuleLog.d { "hot reload: package metadata unavailable in $reloadProcess; continuing cleanup" }
        } else {
            ModuleLog.d { "hot reload: saving state for $packageName in $reloadProcess" }
        }
        param.setSavedInstanceState(Bundle().apply {
            packageName?.let { putString(STATE_PACKAGE, it) }
            putString(STATE_PROCESS, processName)
            runtime?.prefs?.saveState()?.let { putBundle(STATE_SETTINGS, it) }
        })
        try {
            initializedApplication?.let { app ->
                lifecycleCallbacks?.let { callbacks ->
                    app.unregisterActivityLifecycleCallbacks(callbacks)
                    ModuleLog.d { "hot reload: lifecycle callbacks detached in $reloadProcess" }
                }
                LogCapture.stop(app) { }
            }
            lifecycleCallbacks = null
            runtime?.close()
            runtime = null
            initializedApplication = null
        } catch (e: Throwable) {
            ModuleLog.e(e) { "hot reload: old generation cleanup failed in $reloadProcess" }
            return false
        }
        ModuleLog.d { "hot reload: old generation cleanup complete in $reloadProcess" }
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val reloadProcess = param.processName
        ModuleLog.bind(reloadProcess) { priority, message -> log(priority, "bp", message) }
        ModuleLog.d { "hot reload: new generation entered for $reloadProcess; oldHooks=${param.oldHookHandles.size}" }
        val savedState = param.savedInstanceState as? Bundle
        pendingSettings = savedState?.getBundle(STATE_SETTINGS)
        processName = savedState?.getString(STATE_PROCESS)?.takeIf(String::isNotBlank)
            ?: param.processName
        val packageName = savedState?.getString(STATE_PACKAGE)
            ?: targetPackageName
            ?: paymentAppForProcess(param.processName)?.packageName
        val targetApp = packageName?.let { PaymentApp.fromPackage(it) }
        if (targetApp == null) {
            ModuleLog.d { "hot reload: no payment app for $reloadProcess; removing old hooks" }
            removeOldHooks(param.oldHookHandles)
            return
        }
        if (!targetApp.handlesProcess(processName.orEmpty())) {
            ModuleLog.d { "hot reload: $reloadProcess is not an active payment process; removing old hooks" }
            removeOldHooks(param.oldHookHandles)
            ModuleLog.summary { "hot reload: inactive process hooks removed" }
            return
        }
        targetPackageName = targetApp.packageName
        val app = try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Application
        } catch (e: Throwable) {
            ModuleLog.w(e) { "hot reload: failed to get Application via ActivityThread" }
            null
        }?.takeIf { it.packageName == targetApp.packageName }

        if (app != null) {
            ModuleLog.d { "hot reload: Application available for $reloadProcess; creating runtime" }
            val adapter = AppComponents.adapterFor(targetApp.packageName) ?: run {
                ModuleLog.e { "hot reload: no adapter for ${targetApp.packageName} in $reloadProcess" }
                return
            }
            runtime?.close()
            val state = AppRuntime.create(app, adapter).also {
                it.prefs.restoreState(pendingSettings)
                pendingSettings = null
                runtime = it
            }
            ModuleLog.d { "hot reload: replacing ${param.oldHookHandles.size} hooks in $reloadProcess" }
            val hooks = HookManager.install(this, state, param.oldHookHandles)
            if (!hooks.ready) {
                hooks.report(reloading = true)
                state.close()
                runtime = null
                hooks.rollback()
                error("Required hooks could not be restored")
            }
            initializedApplication = app
            applicationHooksRegistered = true
            lifecycleCallbacks = AppLifecycleCallbacks(state).also(app::registerActivityLifecycleCallbacks)
            // Old UI listeners can be removed by a task already queued on the
            // main thread. Restore surviving windows after that task runs.
            Handler(app.mainLooper).post {
                if (runtime === state && !state.isClosed) {
                    state.restoreVisibleEntries()
                    state.visualMonitor?.restoreVisibleWindows()
                }
            }
            if (state.prefs.isLogCaptureEnabled()) LogCapture.start(app)
            hooks.report(reloading = true)
        } else {
            // A process may reload between package loading and Application creation.
            // Rebind the bootstrap hooks so new code, rather than the old classloader,
            // initializes the app when Application.onCreate eventually runs.
            ModuleLog.d { "hot reload: Application unavailable in $reloadProcess; rebinding bootstrap hooks" }
            check(hookApplicationOnCreate(targetApp, param.oldHookHandles)) { "Bootstrap hooks could not be restored" }
            ModuleLog.summary { "hot reload: bootstrap hooks restored; awaiting Application" }
        }
    }

    private fun hookApplicationOnCreate(
        targetApp: PaymentApp,
        oldHandles: List<XposedInterface.HookHandle> = emptyList()
    ): Boolean {
        synchronized(initLock) {
            if (applicationHooksRegistered) return true
            applicationHooksRegistered = true
        }
        val handled = HashSet<XposedInterface.HookHandle>()
        var installed = false

        fun bind(id: String, method: java.lang.reflect.Executable, interceptor: XposedInterface.Hooker) {
            val old = oldHandles.firstOrNull { it.id == id && it !in handled }
            if (old != null) {
                try {
                    old.replaceHook(interceptor)
                    handled += old
                    installed = true
                    return
                } catch (e: Throwable) {
                    ModuleLog.w(e) { "hot reload: replacing $id failed; reinstalling" }
                    if (runCatching { old.unhook() }.isSuccess) handled += old
                    else return
                }
            }
            try {
                hook(method).setId(id).intercept(interceptor)
                installed = true
            } catch (e: Throwable) {
                ModuleLog.w(e) { "$id hook registration failed" }
            }
        }

        try {
            val method = Application::class.java.getDeclaredMethod("onCreate")
            bind("bp_app_oncreate", method, XposedInterface.Hooker { chain ->
                try {
                    (chain.thisObject as? Application)?.let { initializeApplication(it, targetApp) }
                } catch (e: Throwable) {
                    ModuleLog.w(e) { "init failed" }
                }
                chain.proceed()
            })
            ModuleLog.d { "hookApplicationOnCreate registered" }
        } catch (e: Throwable) {
            ModuleLog.w(e) { "hook onCreate failed" }
        }
        if (targetApp == PaymentApp.ALIPAY || targetApp == PaymentApp.TAOBAO) {
            try {
                val method = Instrumentation::class.java.getDeclaredMethod(
                    "callApplicationOnCreate", Application::class.java
                )
                bind("bp_instrumentation_app_oncreate", method, XposedInterface.Hooker { chain ->
                    try {
                        (chain.args.firstOrNull() as? Application)?.let {
                            initializeApplication(it, targetApp)
                        }
                    } catch (e: Throwable) {
                        ModuleLog.w(e) { "instrumentation init failed" }
                    }
                    chain.proceed()
                })
            } catch (e: Throwable) {
                ModuleLog.w(e) { "hook callApplicationOnCreate failed" }
            }
        }

        var stale = 0
        oldHandles.filterNot { it in handled }.forEach { old ->
            runCatching { old.unhook() }
                .onFailure { stale++; ModuleLog.w(it) { "hot reload: stale hook removal failed (${old.id})" } }
        }
        if (!installed) synchronized(initLock) { applicationHooksRegistered = false }
        return installed && stale == 0
    }

    private fun initializeApplication(application: Application, targetApp: PaymentApp) {
        val currentProcessName = Application.getProcessName()
        if (!targetApp.handlesProcess(currentProcessName) || application.packageName != targetApp.packageName) {
            ModuleLog.d { "skipping non-payment process: $currentProcessName" }
            return
        }
        synchronized(initLock) {
            // Some hosts create several Application objects in one process. Their
            // lifecycle callbacks observe the same Activities, so keep one state.
            if (initializedApplication != null) return
            val adapter = AppComponents.adapterFor(targetApp.packageName) ?: return
            val state = AppRuntime.create(application, adapter).also {
                it.prefs.restoreState(pendingSettings)
                pendingSettings = null
                runtime = it
            }
            val hooks = HookManager.install(this, state)
            if (!hooks.ready) {
                hooks.report(reloading = false)
                state.close()
                runtime = null
                hooks.rollback()
                return
            }
            lifecycleCallbacks = AppLifecycleCallbacks(state)
                .also(application::registerActivityLifecycleCallbacks)
            if (state.prefs.isLogCaptureEnabled()) LogCapture.start(application)
            initializedApplication = application
            targetPackageName = targetApp.packageName
            this.processName = this.processName ?: currentProcessName
            hooks.report(reloading = false)
        }
    }

    private fun removeOldHooks(handles: List<XposedInterface.HookHandle>) {
        var failures = 0
        handles.forEach { handle ->
            runCatching { handle.unhook() }.onFailure {
                failures++
                ModuleLog.w(it) { "hot reload: stale hook removal failed (${handle.id})" }
            }
        }
        check(failures == 0) { "Old hooks could not be removed" }
    }

    private fun paymentAppForProcess(name: String): PaymentApp? =
        PaymentApp.fromPackage(name.substringBefore(':'))

    private companion object {
        const val STATE_PACKAGE = "target_package"
        const val STATE_PROCESS = "process_name"
        const val STATE_SETTINGS = "payment_settings"
    }
}
