// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

/**
 * 命令安全分级 (v0.34.3 分级系统, 替代"全部写操作一刀切 reason"):
 *
 * - LOW 普通: 新建/写入文件、普通表达 (通知/悬浮窗/打开链接) — 默认放行
 * - MID 中危: 删除/修改文件、剪贴板读写、截图录屏等隐私读取、插件/技能状态变更 —
 *   默认拒绝, Agent 权限等级提升为 TRUSTED 后放行 (智能体设置)
 * - HIGH 高危: 清空/卸载/系统级 (root/proc)、整片记忆删除、拍照 — 弹窗询问用户,
 *   用户拒绝即阻挡 (worker/无 UI 环境默认拒绝)
 *
 * reason 门禁 (HighRiskCommandGate) 只对 MID/HIGH 生效 — LOW 命令维持纯文本调用。
 */
enum class RiskLevel(val label: String) {
    LOW("普通"),
    MID("中危"),
    HIGH("高危")
}

/** 命令 → 风险等级表 — 精确命令名优先, root./proc. 家族前缀兜底, 未登记默认 LOW。 */
object CommandRiskLevels {

    val LEVELS: Map<String, RiskLevel> = mapOf(
        // ── 普通: 新建/写入/普通表达 (默认放行; agent.write/mkdir/fs.cp 已随 Linux 通道移除) ──
        "agent.memory.keep" to RiskLevel.LOW,
        "agent.memory.write" to RiskLevel.LOW,
        "agent.memory.record" to RiskLevel.LOW,
        "agent.memory.project.save" to RiskLevel.LOW,
        "self.notify.message" to RiskLevel.LOW,
        "self.notify.banner" to RiskLevel.LOW,
        "sys.notification.send" to RiskLevel.LOW,
        "sys.alarm.set" to RiskLevel.LOW,
        "sys.overlay.show" to RiskLevel.LOW,
        "sys.overlay.hide" to RiskLevel.LOW,
        "sys.overlay.update" to RiskLevel.LOW,
        "sys.intent.open" to RiskLevel.LOW,
        "sys.intent.view" to RiskLevel.LOW,
        "sys.app.launch" to RiskLevel.LOW,
        "sys.browser.open" to RiskLevel.LOW,
        "sys.calendar.add" to RiskLevel.LOW,
        "sys.toast" to RiskLevel.LOW,
        "sys.torch.on" to RiskLevel.LOW,
        "sys.torch.off" to RiskLevel.LOW,
        "sys.tts.speak" to RiskLevel.LOW,
        "sys.tts.engines" to RiskLevel.LOW,
        "sys.wakelock.acquire" to RiskLevel.LOW,
        "sys.wakelock.release" to RiskLevel.LOW,
        "sys.ir.transmit" to RiskLevel.LOW,
        "sys.usb.list" to RiskLevel.LOW,
        "sys.download" to RiskLevel.LOW,
        "sys.wallpaper.set" to RiskLevel.LOW,
        "sys.accessibility.status" to RiskLevel.LOW,
        // ── 中危: 删除/修改/隐私读取 (默认拒绝, TRUSTED 放行) ──
        // agent.rm/fs.mv 已随 Linux 通道移除 — Linux rm/mv 由 CommandMonitor CONFIRM 弹窗承接
        "agent.memory.rm" to RiskLevel.MID,
        "agent.memory.edit" to RiskLevel.MID,
        "agent.memory.mid.rm" to RiskLevel.MID,
        "agent.memory.mid.edit" to RiskLevel.MID,
        "agent.memory.project.rm" to RiskLevel.MID,
        "agent.memory.project.edit" to RiskLevel.MID,
        "clipboard.copy" to RiskLevel.MID,
        "clipboard.paste" to RiskLevel.MID,
        "sys.clipboard" to RiskLevel.MID,
        "sys.clipboard.set" to RiskLevel.MID,
        "sys.screenshot" to RiskLevel.MID,
        "sys.screenrecord.start" to RiskLevel.MID,
        "sys.notification.cancel" to RiskLevel.MID,
        "sys.calendar.delete" to RiskLevel.MID,
        "sys.intent.share" to RiskLevel.MID,
        // v0.36.x 敏感命令组: 用户交互/录音/隐私读取/短信/联系人/通话记录/拨号/USB 授权/WiFi 扫描
        "sys.dialog.confirm" to RiskLevel.MID,
        "sys.dialog.text" to RiskLevel.MID,
        "sys.dialog.radio" to RiskLevel.MID,
        "sys.dialog.checkbox" to RiskLevel.MID,
        "sys.dialog.spinner" to RiskLevel.MID,
        "sys.dialog.sheet" to RiskLevel.MID,
        "sys.dialog.date" to RiskLevel.MID,
        "sys.dialog.time" to RiskLevel.MID,
        "sys.dialog.counter" to RiskLevel.MID,
        "sys.dialog.color" to RiskLevel.MID,
        "sys.dialog.speech" to RiskLevel.MID,
        "sys.stt.listen" to RiskLevel.MID,
        "sys.mic.record" to RiskLevel.MID,
        "sys.mic.stop" to RiskLevel.MID,
        "sys.notification.list" to RiskLevel.MID,
        "sys.contacts.list" to RiskLevel.MID,
        "sys.sms.send" to RiskLevel.MID,
        "sys.sms.list" to RiskLevel.MID,
        "sys.calllog.list" to RiskLevel.MID,
        "sys.phone.call" to RiskLevel.MID,
        "sys.usb.request" to RiskLevel.MID,
        "sys.wifi.scan" to RiskLevel.MID,
        "sys.accessibility.dump" to RiskLevel.MID,
        "plugin.install" to RiskLevel.MID,
        "plugin.enable" to RiskLevel.MID,
        "plugin.disable" to RiskLevel.MID,
        "plugin.update" to RiskLevel.MID,
        "skill.enable" to RiskLevel.MID,
        "skill.disable" to RiskLevel.MID,
        "skill.from.project" to RiskLevel.MID,
        "skill.request" to RiskLevel.MID,
        "skill.import" to RiskLevel.MID,
        // ── 高危: 清空/卸载/系统级/整片删除/拍照 (弹窗确认) ──
        "clipboard.clear" to RiskLevel.HIGH,
        "sys.app.uninstall" to RiskLevel.HIGH,
        // 无障碍模拟操作 (v0.42.2): 点击/滑动/输入/全局导航 = 模拟用户操作任意应用, 高危
        "sys.accessibility.click" to RiskLevel.HIGH,
        "sys.accessibility.swipe" to RiskLevel.HIGH,
        "sys.accessibility.input" to RiskLevel.HIGH,
        "sys.accessibility.back" to RiskLevel.HIGH,
        "sys.accessibility.home" to RiskLevel.HIGH,
        "sys.accessibility.recents" to RiskLevel.HIGH,
        "plugin.uninstall" to RiskLevel.HIGH,
        "agent.memory.mid.delete" to RiskLevel.HIGH,
        "agent.memory.project.delete" to RiskLevel.HIGH,
        "proc.exec" to RiskLevel.HIGH,
        "proc.system" to RiskLevel.HIGH,
        "proc.kill" to RiskLevel.HIGH,
        "root.exec" to RiskLevel.HIGH,
        "root.shell" to RiskLevel.HIGH,
        "root.fs.write" to RiskLevel.HIGH,
        "root.system.setprop" to RiskLevel.HIGH,
        "root.system.hosts" to RiskLevel.HIGH,
        "root.backup.restore" to RiskLevel.HIGH,
        "root.apps.uninstall" to RiskLevel.HIGH,
        "root.apps.freeze" to RiskLevel.HIGH,
        "root.apps.unfreeze" to RiskLevel.HIGH,
        "sys.camera.photo" to RiskLevel.HIGH
    )

    /** 命令全名 (不含参数) → 风险等级。精确表优先; root./proc. 家族前缀兜底; 未登记 = LOW。 */
    fun levelOf(command: String): RiskLevel {
        val name = command.trim().split(" ").firstOrNull() ?: return RiskLevel.LOW
        LEVELS[name]?.let { return it }
        return when {
            name.startsWith("root.") -> RiskLevel.HIGH
            name.startsWith("proc.") -> RiskLevel.HIGH
            else -> RiskLevel.LOW
        }
    }
}
