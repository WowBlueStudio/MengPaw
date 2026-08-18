// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 敏感数据命令组 — 短信/联系人/通话记录/拨号
 * (对齐 Termux:API termux-sms-* / termux-contact-list / termux-call-log / termux-telephony-call)。
 *
 * 全部需要运行时危险权限 (Manifest 已声明), 命令风险等级 MID — 默认拒绝, 仅 TRUSTED 放行。
 */
internal object ContactsSmsExecutor {

    private fun permissionFail(permission: String, guide: String): ExecutionResult =
        ExecutionResult.fail(
            "需要 $permission 权限。请先执行 sys.permission.request $permission 弹出系统授权框" +
                "（用户允许后再重试本命令）；不要尝试绕路或谎报已执行。$guide",
            errorCode = ErrorCodes.ERR_PERMISSION_DENIED
        )

    suspend fun contactsList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        if (!app.checkSelf(Manifest.permission.READ_CONTACTS)) {
            return permissionFail(Manifest.permission.READ_CONTACTS, "")
        }
        val limit = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val lines = mutableListOf<String>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    lines += "${cursor.getString(nameIdx)} | ${cursor.getString(numIdx)}"
                    count++
                }
            }
        } catch (e: Exception) {
            return ExecutionResult.fail("读取联系人失败: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
        val text = lines.joinToString("\n")
        return ExecutionResult.ok(
            if (lines.isEmpty()) "(无联系人)" else text +
                (if (lines.size >= limit) "\n...(仅显示前 $limit 条, 可用参数调整)" else "")
        )
    }

    suspend fun smsSend(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val to = args.firstOrNull()
            ?: return ExecutionResult.fail(
                "Usage: sys.sms.send <号码> <短信内容>",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        val body = args.drop(1).joinToString(" ").trim()
        if (body.isEmpty()) {
            return ExecutionResult.fail(
                "Usage: sys.sms.send <号码> <短信内容>",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        if (!app.checkSelf(Manifest.permission.SEND_SMS)) {
            return permissionFail(Manifest.permission.SEND_SMS, "")
        }
        return try {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(to, null, body, null, null)
            ExecutionResult.ok("短信已发送至 $to")
        } catch (e: Exception) {
            ExecutionResult.fail("短信发送失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    suspend fun smsList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        if (!app.checkSelf(Manifest.permission.READ_SMS)) {
            return permissionFail(Manifest.permission.READ_SMS, "")
        }
        val limit = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val lines = mutableListOf<String>()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        try {
            val projection = arrayOf(
                Telephony.Sms.Inbox.ADDRESS,
                Telephony.Sms.Inbox.DATE,
                Telephony.Sms.Inbox.BODY
            )
            app.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.Inbox.DATE} DESC LIMIT $limit"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY)
                while (cursor.moveToNext()) {
                    val time = fmt.format(Date(cursor.getLong(dateIdx)))
                    val bodyText = cursor.getString(bodyIdx)?.take(120) ?: ""
                    lines += "[$time] ${cursor.getString(addrIdx)}: $bodyText"
                }
            }
        } catch (e: Exception) {
            return ExecutionResult.fail("读取短信失败: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
        return ExecutionResult.ok(if (lines.isEmpty()) "(收件箱为空)" else lines.joinToString("\n"))
    }

    suspend fun callLogList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        if (!app.checkSelf(Manifest.permission.READ_CALL_LOG)) {
            return permissionFail(Manifest.permission.READ_CALL_LOG, "")
        }
        val limit = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val lines = mutableListOf<String>()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            app.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (cursor.moveToNext()) {
                    val type = when (cursor.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "来电"
                        CallLog.Calls.OUTGOING_TYPE -> "去电"
                        CallLog.Calls.MISSED_TYPE -> "未接"
                        else -> "其他"
                    }
                    val time = fmt.format(Date(cursor.getLong(dateIdx)))
                    lines += "[$time] $type ${cursor.getString(numIdx)} (${cursor.getLong(durIdx)}s)"
                }
            }
        } catch (e: Exception) {
            return ExecutionResult.fail("读取通话记录失败: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
        return ExecutionResult.ok(if (lines.isEmpty()) "(无通话记录)" else lines.joinToString("\n"))
    }

    suspend fun phoneCall(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val number = args.firstOrNull()
            ?: return ExecutionResult.fail(
                "Usage: sys.phone.call <号码>",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        if (!app.checkSelf(Manifest.permission.CALL_PHONE)) {
            return permissionFail(Manifest.permission.CALL_PHONE, "")
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            ExecutionResult.ok("正在拨号: $number")
        } catch (e: Exception) {
            ExecutionResult.fail("拨号失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
