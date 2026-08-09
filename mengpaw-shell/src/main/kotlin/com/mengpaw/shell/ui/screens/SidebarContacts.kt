// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

// ── 框架通讯录加载 — 拆自 SidebarContent.kt (2026-08-06, >400 行文件拆分批次4) ──

/**
 * 加载已保存的框架联系人 (ACP_TRUSTED) + 合并 mDNS 发现的框架节点。
 * 信任目录条目为基准, mDNS 发现更新在线状态/Agent 列表/版本, 保留 remark 与 frameworkType。
 */
internal fun loadFrameworkContacts(): List<FrameworkContact> {
    val contacts = mutableListOf<FrameworkContact>()
    val trustedDir = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED)
    if (trustedDir.exists()) {
        trustedDir.listFiles()
            ?.filter { it.extension == "json" && !it.name.endsWith(".tmp.json") }
            ?.forEach { file ->
                try {
                    val contactFile = sidebarAppJson.decodeFromString<FrameworkContactFile>(file.readText())
                    contacts.add(FrameworkContact(
                        name = contactFile.name.ifBlank { file.nameWithoutExtension },
                        address = contactFile.address,
                        online = false,
                        trusted = true,
                        agents = emptyList(),
                        remark = contactFile.remark,
                        frameworkType = contactFile.frameworkType
                    ))
                } catch (e: Exception) { com.mengpaw.kernel.KernelLog.w("SidebarContent", "load framework: ${e.message}") }
            }
    }
    // 合并 mDNS 发现的框架
    val discovered = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll()
    discovered.forEach { peer ->
        val existing = contacts.indexOfFirst { it.name == peer.name }
        if (existing >= 0) {
            // 更新在线状态和 Agent 列表, 保留已有的 remark 和 frameworkType
            val old = contacts[existing]
            contacts[existing] = old.copy(
                online = peer.lastSeen > System.currentTimeMillis() - 120_000,
                address = "${peer.address}:${peer.port}",
                agents = peer.agents,
                version = peer.version,
                frameworkName = peer.frameworkName,
                remark = peer.remark.ifBlank { old.remark },
                frameworkType = peer.frameworkType.let { if (it != "mengpaw") it else old.frameworkType }
            )
        } else {
            contacts.add(FrameworkContact(
                name = peer.name,
                address = "${peer.address}:${peer.port}",
                online = peer.lastSeen > System.currentTimeMillis() - 120_000,
                trusted = peer.trusted,
                agents = peer.agents,
                version = peer.version,
                frameworkName = peer.frameworkName,
                remark = peer.remark,
                frameworkType = peer.frameworkType
            ))
        }
    }
    // v0.34.3: 合并发现结果 (未入册, discovered=true) — 发现即入册已取消, 用户确认后添加
    com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.discoveredPeers?.toList()?.forEach { peer ->
        if (contacts.none { it.name == peer.name && it.address == "${peer.address}:${peer.port}" }) {
            contacts.add(FrameworkContact(
                name = peer.name,
                address = "${peer.address}:${peer.port}",
                online = peer.lastSeen > System.currentTimeMillis() - 120_000,
                trusted = peer.trusted,
                agents = peer.agents,
                version = peer.version,
                frameworkName = peer.frameworkName,
                frameworkType = peer.frameworkType,
                fingerprint = peer.fingerprint,
                discovered = true
            ))
        }
    }
    return contacts
}
