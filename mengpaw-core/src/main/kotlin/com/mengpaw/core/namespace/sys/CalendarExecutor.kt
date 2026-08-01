// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.content.ContentUris
import android.os.Build
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.core.namespace.parseTime
import com.mengpaw.core.namespace.formatTime
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import java.util.TimeZone

/** Calendar listing, event creation, deletion, and calendar ID resolution. */
internal object CalendarExecutor {

    /** Cached writable calendar ID. Refreshed when add fails or on explicit refresh. */
    private var cachedCalendarId: Long? = null

    private fun resolveCalendarId(): Long? {
        val app = SysExecutor.appContext ?: return null
        cachedCalendarId?.let { return it }
        return try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
            )
            val cursor = app.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection,
                null, null, null
            ) ?: return null
            var id: Long? = null
            while (cursor.moveToNext()) {
                val access = cursor.getInt(1)
                val visible = cursor.getInt(2) != 0
                if (visible && access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    id = cursor.getLong(0)
                    if (access >= CalendarContract.Calendars.CAL_ACCESS_OWNER) break
                }
            }
            cursor.close()
            cachedCalendarId = id
            id
        } catch (_: Exception) { null }
    }

    suspend fun calendarCalendars(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.READ_CALENDAR)) {
            return ExecutionResult.fail("需要日历读取权限。请执行: sys.permission.request READ_CALENDAR")
        }
        return try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
            )
            val cursor = app.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection,
                null, null, null
            ) ?: return ExecutionResult.fail("无法读取日历列表")
            if (cursor.count == 0) { cursor.close(); return ExecutionResult.ok("(无可用日历账户)") }
            val sb = StringBuilder("## 可用日历\n\n| ID | 名称 | 账户 | 权限 |\n|----|------|------|------|\n")
            val accessLabels = mapOf(0 to "无", 100 to "只读", 500 to "贡献", 600 to "编辑", 700 to "拥有者")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "?"
                val account = cursor.getString(2) ?: "本地"
                val access = accessLabels[cursor.getInt(3)] ?: "?"
                val visible = if (cursor.getInt(4) != 0) "" else " (隐藏)"
                sb.appendLine("| $id | $name | $account | $access$visible |")
            }
            cursor.close()
            cachedCalendarId = null
            val resolved = resolveCalendarId()
            if (resolved != null) sb.appendLine("\n默认写入日历: ID=$resolved")
            ExecutionResult.ok(sb.toString().trimEnd())
        } catch (e: Exception) {
            ExecutionResult.fail("日历查询异常: ${e.message}")
        }
    }

    suspend fun calendarAdd(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.WRITE_CALENDAR)) {
            return ExecutionResult.fail("需要日历写入权限。请执行: sys.permission.request WRITE_CALENDAR")
        }
        if (args.isEmpty()) return ExecutionResult.fail(
            "用法: sys.calendar.add <标题> <开始时间> [--end <结束时间>] [--desc <描述>] [--cal <日历ID>]\n" +
            "时间格式: yyyy-MM-dd HH:mm 或 Unix毫秒。查看日历列表: sys.calendar.calendars"
        )
        val parts = args.takeWhile { !it.startsWith("--") }
        val title = parts.getOrNull(0) ?: return ExecutionResult.fail("缺少标题")
        val startStr = parts.getOrNull(1) ?: return ExecutionResult.fail("缺少开始时间")
        val flags = args.dropWhile { !it.startsWith("--") }
        val endStr = flags.find { it.startsWith("--end") }?.substringAfter("--end")?.trim()
        val desc = flags.find { it.startsWith("--desc") }?.substringAfter("--desc")?.trim()
        val calId = flags.find { it.startsWith("--cal") }?.substringAfter("--cal")?.trim()?.toLongOrNull()

        val cal = calId ?: resolveCalendarId()
            ?: return ExecutionResult.fail("未找到可写入的日历。请先执行 sys.calendar.calendars 查看可用日历，然后用 --cal <ID> 指定。")
        val startMillis = parseTime(startStr) ?: return ExecutionResult.fail("时间格式无效: $startStr。使用 yyyy-MM-dd HH:mm 或 Unix毫秒")
        val endMillis = endStr?.let { parseTime(it) } ?: (startMillis + 3600_000L)

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, cal)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                if (desc != null) put(CalendarContract.Events.DESCRIPTION, desc)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val id = uri.lastPathSegment ?: "?"
                ExecutionResult.ok(buildString {
                    appendLine("日历事件已创建 ✅")
                    appendLine("- 标题: $title")
                    appendLine("- ID: $id (用于 sys.calendar.delete $id)")
                    appendLine("- 时间: ${formatTime(startMillis)} → ${formatTime(endMillis)}")
                    appendLine("- 日历: $cal")
                    appendLine()
                    appendLine("查看: sys.calendar.list --days 7")
                    appendLine("删除: sys.calendar.delete $id")
                })
            } else ExecutionResult.fail("日历写入失败。尝试 sys.calendar.calendars 查看可用日历，用 --cal <ID> 指定。")
        } catch (e: SecurityException) {
            ExecutionResult.fail("日历权限被拒绝: ${e.message}")
        } catch (e: Exception) {
            ExecutionResult.fail("日历异常: ${e.message}")
        }
    }

    suspend fun calendarList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.READ_CALENDAR)) {
            return ExecutionResult.fail("需要日历读取权限。请执行: sys.permission.request READ_CALENDAR")
        }
        val days = args.find { it.startsWith("--days") }?.substringAfter("--days")?.trim()?.toIntOrNull() ?: 7
        val start = System.currentTimeMillis()
        val end = start + days * 86_400_000L

        return try {
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.CALENDAR_ID
            )
            val cursor = app.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(start.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )
            if (cursor == null || cursor.count == 0) {
                cursor?.close()
                return ExecutionResult.ok("(未来 $days 天无日历事件)\n\n添加: sys.calendar.add \"标题\" \"yyyy-MM-dd HH:mm\"")
            }
            val sb = StringBuilder("## 未来 $days 天日历事件 (${cursor.count}个)\n\n| 时间 | 标题 | ID | 日历 |\n|------|------|----|------|\n")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1) ?: "(无标题)"
                val dtStart = cursor.getLong(2)
                val calId = cursor.getLong(4)
                sb.appendLine("| ${formatTime(dtStart)} | ${title.take(30)} | $id | $calId |")
            }
            cursor.close()
            sb.appendLine("\n删除: sys.calendar.delete <ID>")
            ExecutionResult.ok(sb.toString().trimEnd())
        } catch (e: Exception) {
            ExecutionResult.fail("日历查询异常: ${e.message}")
        }
    }

    suspend fun calendarDelete(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.WRITE_CALENDAR)) {
            return ExecutionResult.fail("需要日历写入权限。请执行: sys.permission.request WRITE_CALENDAR")
        }
        val idStr = args.firstOrNull() ?: return ExecutionResult.fail(
            "用法: sys.calendar.delete <ID>\n先用 sys.calendar.list 查看事件ID。"
        )
        val id = idStr.toLongOrNull() ?: return ExecutionResult.fail("无效的事件ID: $idStr")
        return try {
            val uri = ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, id
            )
            val deleted = app.contentResolver.delete(uri, null, null)
            if (deleted > 0) ExecutionResult.ok("日历事件已删除: ID=$id")
            else ExecutionResult.fail("未找到事件 ID=$id。用 sys.calendar.list 查看有效ID。")
        } catch (e: SecurityException) {
            ExecutionResult.fail("权限被拒绝: ${e.message}")
        } catch (e: Exception) {
            ExecutionResult.fail("删除异常: ${e.message}")
        }
    }
}
