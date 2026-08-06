// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.mcp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 设备内 MCP 桥 token 存储（P0 修复 — 桥认证通道）。
 *
 * ## 设计
 * 浏览器进程 (com.mengpaw.browser) 与 Shell 进程 (com.mengpaw) 是两个 APK, 类加载器隔离。
 * 浏览器启动时生成随机 token, 经本 provider (signature 级权限, 仅同签名 app 可访问)
 * 写入 Shell 进程内存; BrowserMcpPlugin (Shell 进程, 反射加载) 通过反射同步静态字段。
 * 之后 Shell → 127.0.0.1:9880 的所有 /mcp 请求携带 `Authorization: Bearer <token>`。
 *
 * 任意第三方 app 既无法调用本 provider (签名权限), 也无法猜出 token (32 字节 SecureRandom)。
 */
class BridgeTokenProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val matrix = MatrixCursor(arrayOf("token"))
        matrix.addRow(arrayOf(BridgeTokenStore.token))
        return matrix
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int {
        val token = values?.getAsString("token") ?: return 0
        BridgeTokenStore.token = token
        // 反射同步到 BrowserMcpPlugin 静态字段 (Shell 编译期不依赖插件模块, 插件反射加载)
        try {
            val cls = Class.forName("com.mengpaw.plugin.browsermcp.BrowserMcpPlugin")
            cls.getField("bridgeToken").set(null, token)
        } catch (_: Exception) { /* 插件类未加载 — 插件加载时会读 BridgeTokenStore 兜底 */ }
        return 1
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "text/plain"
}

/** 本进程内 token 持有者 — provider 写入, 插件加载处读取兜底。 */
object BridgeTokenStore {
    @Volatile
    var token: String = ""
}
