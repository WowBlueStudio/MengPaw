// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.dev

/**
 * 插件开发工具能力边界文档 — 随 dev-plugin 分发.
 *
 * 阅读方式:
 * - Agent: `dev.plugin.guide`
 * - 用户:  文件管理器打开 {DataPaths.BASE}/插件文档/plugin-dev-guide.md
 *
 * 注意: raw string 中禁止出现 `$` 与 `"""` (Kotlin 语法限制).
 */
object PluginDevGuide {

    const val FILE_NAME = "plugin-dev-guide.md"

    /** 落盘路径 (用户可读). */
    val targetFile: java.io.File
        get() = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "插件文档/$FILE_NAME")

    val CONTENT: String = """
# MengPaw 插件开发工具 — 能力边界

> 本文件由内置插件 dev-plugin 提供，随 MengPaw APK 分发。
> 完整开发规范见仓库文档 `PLUGIN_DEV_GUIDE.md`（本文档为能力边界速览）。

## 这是什么

dev-plugin（插件开发工具）是随 APK 内置的插件，让 **Agent 与用户都能在设备上创建、审计、分享插件**，
无需电脑、无需编译。命令统一以 `dev.plugin.*` 前缀注册。

## 命令清单

| 命令 | 用途 | 参数 |
|------|------|------|
| dev.plugin.create | 创建插件骨架 | --type script\|native --name <名称> [--author <作者>] [--desc <描述>] |
| dev.plugin.audit | 静态安全审计 | --target <插件ID>（必填） |
| dev.plugin.share | 分享插件给其他框架 | --plugin <插件ID> --to <框架> |
| dev.plugin.examples | 查看内嵌参考示例 | 无 |
| dev.plugin.keywords | 查看/管理 BM25 检索关键词 | --target <插件ID> |
| dev.plugin.guide | 查看本能力边界文档 | 无 |

## 插件类型

| 类型 | 说明 | 是否需要编译 |
|------|------|:--:|
| SCRIPT | JSON 声明 + shell 命令，零代码 | 否 |
| NATIVE | Kotlin 逻辑（产物 JAR/AAR），有状态 | 是（Android Studio/Gradle） |

## 开发流程

1. **创建**: `dev.plugin.create --type script|native --name <名称>`
   - 生成位置: 插件缓存目录（DataPaths.PLUGIN_CACHE）/<插件ID>/
2. **开发**: 编辑 plugin.json（SCRIPT）或用 Android Studio 打开（NATIVE）
3. **审计**: `dev.plugin.audit --target <插件ID>` — 🔴 阻断项必须修复
4. **关键词**: `dev.plugin.keywords --target <插件ID>` — 提升 Agent 检索发现性
5. **分享**: `dev.plugin.share --plugin <插件ID> --to <框架>` — 审计不过拒绝

## 命名规范

- 插件 ID: `<命名空间>-plugin`（如 fs-plugin）
- 命令注册: `<命名空间>.<短名>` — 短名由插件声明，命名空间按 ID 自动派生：
  - `fs-plugin` → `fs`；`memory-twin-plugin` → `twin`；`dev-plugin` → `dev`
- 官方已占用命名空间: self / plugin / agent / sys（内核）+ framework / fs / net / memory / skill / clipboard / notification / dev / root / twin / tools / browser / tribe 等

## 审计规则（dev.plugin.audit）

**🔴 阻断（必须修复才能分享）**:
- SCRIPT: 缺 id/version/author(非空)/description(非空)/commands、type 非 SCRIPT、无 shell 命令、危险命令（rm -rf /、mkfs、dd、chmod 777、sudo 等）、命令注入（分号分隔、shell 子命令替换、管道符）、HTTP 明文、file:// 或 localhost、定义超 50KB
- NATIVE: 缺 Plugin 类/metadata/commands/permissions/minCoreVersion、type 非 NATIVE、`!!` 强制解包、阻塞调用（Thread.sleep/while(true)/runBlocking）、文件 IO 无 try/catch、网络无超时/无截断、API Key 硬编码、路径穿越、**声明内核保留端口 9876（ACP）**

**🟡 建议（不阻断）**: 缺 commandKeywords、端口越界（非 1-65535）

## 端口与插件

- 插件可在 metadata 声明占用端口（`ports = listOf(...)`），安装时冲突检测：与已装插件端口冲突 → 拒绝安装
- 内核保留端口 **9876（ACP）** 不可声明
- 本机全部端口一览: `self.ports`（本机监听 + 外部服务默认端口表）

## 能力边界（不能做什么）

- **不能在设备上编译 Kotlin** — NATIVE 插件需电脑端 Android Studio/Gradle 构建（`scripts/build-plugins.ps1` 批量构建）
- **audit 是静态检查，不是运行测试** — 不执行插件代码，无法发现运行时问题；建议分享前用 `plugin.install` 本地验证
- **share 仅打包传输** — 接收方需用户同意才会安装；插件列表不会主动暴露
- **不能声明未实现的权限** — metadata 声明的权限与实际行为不符会被安装方拒绝
- **不提供 root 能力** — root.* 属内置插件（最高权限，审计日志），自建插件不会被授予
- **API Key 严禁硬编码** — 必须使用 Sanitizer 过滤，审计会拦截

## 发布链路（电脑端仓库工具）

1. `scripts/build-plugins.ps1` — 批量构建 26 插件模块 AAR → `releases/plugins/`，自动回写 checksum/size/changelog
2. `scripts/validate-plugins.ps1` — plugins.json 全绿校验（含 checksum 与 AAR 实际比对）
3. `gh release create plugins-vX.Y.Z releases/plugins/*.aar` — 独立 tag 发布
4. plugins.json 随 git commit 自动发布（GitHub Pages / Gitee raw 双源）

## 更多资源

- 仓库 `PLUGIN_DEV_GUIDE.md` — 完整开发指南（权威）
- `dev.plugin.examples` — 内嵌文件/网络插件参考模板
- `plugin.marketplace` / `plugin.search` — 浏览与安装插件
- `self.ports` — 端口一览
- `.claude/skills/plugin-dev.md` — 发布流程 skill（开发机侧）
""".trimIndent()

    /** 确保文档已写入用户可读路径, 返回文件路径. */
    fun ensureWritten(): String {
        val f = targetFile
        return try {
            f.parentFile?.mkdirs()
            if (!f.exists() || f.readText() != CONTENT) f.writeText(CONTENT)
            f.absolutePath
        } catch (e: Exception) {
            com.mengpaw.kernel.error.ErrorCollector.report(e, "PluginDevGuide.ensureWritten")
            ""
        }
    }
}
