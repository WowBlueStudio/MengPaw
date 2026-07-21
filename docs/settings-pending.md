---
name: settings-pending-items
description: 设置页待处理项 — 后续会话解决对应条目后删除
metadata:
  type: project
---

# 设置页待处理项

> 每个条目解决后，从本文档中删除对应章节。全部解决后删除本文档。

## 1. Token 统计接入埋点

**文件**: `TokenStatsCollector.kt`
**问题**: 目前 TokenStatsCollector 只在内存中记录，无实际数据来源
**需要**: 在 `AgentEngine.run()` 或 `LlmRequestBuilder` 的每次 LLM 调用处调用：
```kotlin
TokenStatsCollector.record(
    model = modelName,
    tokens = estimatedTokens,
    cacheHit = isCacheHit,
    cacheHitTokens = cacheHitCount
)
```
**验证**: 设置页 Token 统计折线图出现数据

## 2. Loop Mission/Mission+ 插件安装检测

**文件**: `SettingsScreen.kt` → `AgentSettingsContent` → Loop 模式区
**问题**: `installed` 条件硬编码为 `mode != LoopMode.GOAL`，实际应查询 PluginManager
**需要**: 接入 `PluginManager.isInstalled("loop-mission-plugin")` 等真实安装状态
**验证**: 安装对应插件后 Mission/Mission+ 模式变为可选

## 3. 安全规则 Switch 接入 SecurityPolicy

**文件**: `SettingsScreen.kt` → `SecurityRulesSection`
**问题**: 内核/插件/文件完整性防护的 Switch 仅操作本地 `remember` 状态，未实际控制
**需要**: 
- 内核完整性 → `SecurityPolicy.setIntegrityEnabled()`
- 插件完整性 → `PluginManager.setIntegrityCheck()`
- 文件完整性 → `IntegrityGuard.setEnabled()`
**验证**: 关闭 Switch 后 Agent 可修改被保护文件

## 4. 工作区文件实时刷新

**文件**: `MainActivity.kt` → `workspaceItems` 构建逻辑
**问题**: `remember(activeAgent)` 仅在 Agent 名称变化时重新读取，文件内容变更不触发
**需要**: 监听文件变化或提供手动刷新按钮
**验证**: 修改工作区 MD 文件后设置页立即反映

## 5. Icons.Default → Icons.Filled 迁移

**文件**: `SettingsScreen.kt`（原有代码）
**位置**: `Icons.Default.Add`（2处）、`Icons.Default.Close`（1处）
**需要**: 替换为 `Icons.Filled.Add` / `Icons.Filled.Close`
**验证**: 无 deprecated warning

## 6. 框架/Agent 插件-Tools-Skills 列表动态化

**文件**: `MainActivity.kt` → `cliItems` / `pluginItems` / `toolItems` / `skillItems` 构建
**问题**: 当前为硬编码示例数据
**需要**: 接入 `CommandRegistry.list()`、`PluginManager.listAll()`、`McpServer.listTools()` 等实际数据源
**验证**: 安装新插件后对应列表自动更新
