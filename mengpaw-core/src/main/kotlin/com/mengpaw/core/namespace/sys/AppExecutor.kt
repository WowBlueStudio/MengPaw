// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App listing, launch, uninstall, and info queries. */
internal object AppExecutor {

    suspend fun apps(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.QUERY_ALL_PACKAGES) && Build.VERSION.SDK_INT >= 30) {
            return ExecutionResult.fail("需要 QUERY_ALL_PACKAGES 权限", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        val pm = app.packageManager
        val query = args.firstOrNull()
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { query == null || it.packageName.contains(query, ignoreCase = true) || it.loadLabel(pm).contains(query, ignoreCase = true) }
            .take(30)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        return ExecutionResult.ok(buildString {
            appendLine("Installed apps${if (query != null) " matching '$query'" else ""} (showing ${apps.size}):")
            apps.forEach { appendLine("  ${it.loadLabel(pm)} — ${it.packageName}") }
            if (apps.size >= 30) appendLine("  ... use sys.apps <keyword> to filter")
        })
    }

    suspend fun appLaunch(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.app.launch <package>")
        return try {
            val intent = app.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ExecutionResult.fail("App not found or not launchable: $pkg", errorCode = ErrorCodes.ERR_NOT_FOUND)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ExecutionResult.ok("Launched: $pkg")
        } catch (e: Exception) {
            ExecutionResult.fail("Launch failed: ${e.message}")
        }
    }

    suspend fun appUninstall(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.app.uninstall <package>")
        if (pkg == app.packageName) {
            return ExecutionResult.fail("Cannot uninstall MengPaw itself", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ExecutionResult.ok("Uninstall dialog opened for: $pkg (user confirmation required)")
        } catch (e: Exception) {
            ExecutionResult.fail("Uninstall failed: ${e.message}")
        }
    }

    suspend fun appInfo(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.app.info <package>")
        return try {
            val pm = app.packageManager
            val ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            val pi = pm.getPackageInfo(pkg, 0)
            ExecutionResult.ok(buildString {
                appendLine("Package: $pkg")
                appendLine("Name: ${pm.getApplicationLabel(ai)}")
                appendLine("Version: ${pi.versionName} (${pi.versionCode})")
                appendLine("Target SDK: ${ai.targetSdkVersion}")
                appendLine("System: ${(ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0}")
                appendLine("Installed: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(pi.firstInstallTime))}")
            })
        } catch (e: PackageManager.NameNotFoundException) {
            ExecutionResult.fail("App not found: $pkg", errorCode = ErrorCodes.ERR_NOT_FOUND)
        } catch (e: Exception) {
            ExecutionResult.fail("Error: ${e.message}")
        }
    }
}
