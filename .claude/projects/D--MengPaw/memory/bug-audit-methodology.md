---
name: bug-audit-methodology
description: 完整的 MengPaw 项目 Bug 审计方法论 — 从安全/UI/Agent/按钮四个维度系统化审查代码
metadata:
  type: reference
---

# MengPaw Bug 审计方法论

> 固化自 2026-07-20 全项目深度审计经验，覆盖 ~188 个 Bug 的发现过程。

---

## 一、审计维度

每次审计从 4 个维度并行切入：

| 维度 | 关注点 | 关键检查项 |
|------|--------|-----------|
| **安全层** | 加密/认证/注入/权限 | Vault/Sanitizer/WebView/API Key/路径遍历/SSRF |
| **UI 层** | 状态/布局/主题/按钮 | Compose state/remember key/暗色模式/LazyColumn key/onClick |
| **Agent 层** | 引擎/会话/LLM/插件 | ReAct loop/并发/内存泄漏/Android 兼容性/协程泄漏 |
| **按钮层** | onClick/映射/占位符 | 空操作/Mock数据/硬编码/功能缺陷/安全风险 |

---

## 二、安全审计检查清单

### 2.1 WebView 安全 (7 项)
- [ ] `mixedContentMode` ≠ `ALWAYS_ALLOW`
- [ ] 有 `onReceivedSslError` 处理 → `handler.cancel()`
- [ ] `@JavascriptInterface` 不暴露 `eval()` 等危险方法
- [ ] `allowFileAccess` = `false`
- [ ] `allowContentAccess` = `false`
- [ ] 有 `shouldOverrideUrlLoading` 阻止 `javascript:`/`file:` scheme
- [ ] 第三方 Cookie 禁用

### 2.2 网络安全 (6 项)
- [ ] HTTP 请求有 URL scheme 白名单 (仅 http/https)
- [ ] 阻止 `file://` / `jar://` scheme
- [ ] 阻止私有 IP / localhost / 云元数据端点 (SSRF)
- [ ] 禁用自动跟随重定向 / 重定向目标验证
- [ ] 无 HTTP 明文请求（插件市场/API 调用）
- [ ] 下载有读取超时

### 2.3 文件系统安全 (5 项)
- [ ] 路径操作限制在 `workDir` 子目录
- [ ] 使用 `canonicalFile` 解析 `..` 和符号链接
- [ ] 输入路径中过滤 `../` / `..\\` 路径遍历
- [ ] 文件读取有大小上限（防 OOM）
- [ ] `suffix`/`filename` 参数净化（防注入）

### 2.4 API 密钥保护 (5 项)
- [ ] API Key 使用 `Vault` (EncryptedSharedPreferences) 存储
- [ ] 不在 data class 中存储密钥（`toString()` 泄露）
- [ ] 不在日志中打印密钥
- [ ] `sanitize()` 脱敏时不完全展示前缀
- [ ] `allowBackup` = `false`

### 2.5 Android 兼容性 (8 项)
- [ ] `minSdk` 26 → `targetSdk` 35 的 API 变更
- [ ] Android 10+ Scoped Storage → 不用 `/sdcard/` 默认路径
- [ ] Android 12+ `SCHEDULE_EXACT_ALARM` 权限
- [ ] Android 14+ `registerReceiver()` 需要 `RECEIVER_NOT_EXPORTED`
- [ ] Android 14+ 前台服务 `foregroundServiceType`
- [ ] Hidden API 限制 (反射访问 Build.SERIAL / AlarmManager)
- [ ] R8 混淆后 `Class.forName(...)` 崩溃
- [ ] `context.filesDir` 可能 NPE

---

## 三、UI 审计检查清单

### 3.1 Compose 状态管理 (8 项)
- [ ] `remember` 有正确的 key 参数
- [ ] 不在 composable body 中直接修改状态（用 `LaunchedEffect`）
- [ ] `StateFlow.collectAsState()` 响应式绑定（不是一次快照）
- [ ] `derivedStateOf` 用于派生计算防不必要重组
- [ ] `LazyColumn.items` 有 `key` 参数
- [ ] `Modifier.clickable` onClick 不是空 `{}`
- [ ] `@Composable` getter 不滥用（ThemeColors 模式）
- [ ] 切换/开关使用 `onCheckedChange` 的 Boolean 参数

### 3.2 暗色模式 (3 项)
- [ ] 不使用硬编码 `ArcoColors.xxx`（应使用 `ThemeColors.xxx`）
- [ ] 不用硬编码 `Color(0xFFxxxxxx)`
- [ ] `darkTheme` state 实际影响 UI（不是只收集不使用）

### 3.3 协程泄漏 (3 项)
- [ ] 不用 `CoroutineScope(Dispatchers.IO).launch`（用 `rememberCoroutineScope`）
- [ ] 不用 `MainScope()`（用 `lifecycleScope` 或 `viewModelScope`）
- [ ] Dialog dismiss 后协程自动取消

---

## 四、Agent 层审计检查清单

### 4.1 AgentEngine 核心 (6 项)
- [ ] `stop()` 真正取消协程（不是只设标志）
- [ ] ReAct loop 中有 `ensureActive()` 检查
- [ ] `buildPipeline()` 不每次都创建新实例
- [ ] `snipStaleToolResults` 参数正确（步数 vs hash）
- [ ] `parse()` Final Answer 只在最后位置返回
- [ ] `compactStuck` 不跨 session 泄漏

### 4.2 Session/History (4 项)
- [ ] `compressIfNeeded` 在 LLM 调用前快照消息
- [ ] `messages` MutableList 并发安全
- [ ] 压缩后保留并发添加的消息
- [ ] 会话有最大消息数上限

### 4.3 LLM Providers (5 项)
- [ ] `buildRequest` 传递 `_cache_control` 和 `_image` 字段
- [ ] `RemoteApi` 检查 HTTP 状态码
- [ ] `completeStreaming` 实际调用 `onToken` 回调
- [ ] Fallback 链不丢弃 streaming/onToken 参数
- [ ] `testConnection` 做真实 API 调用（不是硬编码 mock）

### 4.4 Plugin 系统 (5 项)
- [ ] `install/uninstall` 调用 `onInstall/onUninstall` 回调
- [ ] `install` 允许覆盖安装（更新）
- [ ] `loadPluginJar` 有错误日志（不是静默吞没）
- [ ] `namespaceFor` 不碰撞不同插件 ID
- [ ] 并发 install/activate/deactivate 有同步保护

### 4.5 Dream/Trigger/Mission (5 项)
- [ ] 路径不固化（用 lazy getter 而非 `object` 初始化时计算）
- [ ] `AlarmManager` 不用反射（用直接 API）
- [ ] Trigger 引擎有 scope 生命周期（不永远运行）
- [ ] MissionMonitor 有线程安全（`@Synchronized`）
- [ ] `formatBytes` 单位正确（MB 用 1024² 而非 1024³）

---

## 五、按钮审计检查清单

### 5.1 完整性问题 (5 种模式)
- [ ] **空操作**: `onClick = {}` — 无任何功能
- [ ] **注释占位**: `/* toggle monitor overlay */` — 意图未实现
- [ ] **只关菜单**: `{ menuExpanded = false }` — 无实际操作
- [ ] **Mock 数据**: 硬编码 `balance = "1.23"` — 假数据
- [ ] **半成品**: 只检测不执行 (`update` 只报告有更新但不升级)

### 5.2 功能缺陷 (5 种模式)
- [ ] 开关不影响现有实例（`factory` 只调用一次）
- [ ] 功能名不副实（"翻译页面"只翻译标题）
- [ ] 限制不一致（菜单不检查 `maxTabs`，标签栏检查）
- [ ] 回调参数被忽略（`onCheckedChange = { if (oldValue) ... }`）
- [ ] 不同源的状态不同步（菜单开关不持久化）

### 5.3 安全风险 (3 种模式)
- [ ] 分享敏感数据到第三方应用
- [ ] 跨 APK Intent 无 URL 验证
- [ ] `evaluateJavascript` 注入未转义内容

---

## 六、审计方法

### 6.1 并行 Agent 策略

```javascript
// 每轮 4-5 个 Agent 并行覆盖不同维度
const agents = [
  { name: "安全层", files: "security/ + WebView + manifest" },
  { name: "UI 层", files: "screens/ + components/ + theme/" },
  { name: "Agent 层", files: "AgentEngine + Session + LLM + Plugin" },
  { name: "系统层", files: "ACP/MCP/Trigger/Dream/Mission/service/" },
  { name: "数据层", files: "DataPaths + AgentDocs + DreamEngine" },
]
```

### 6.2 搜索模式字典

| 用途 | Grep 模式 |
|------|----------|
| 空 onClick | `onClick\s*=\s*\{\s*\}` |
| 硬编码密钥 | `apiKey.*=.*"sk-|apiKey.*=.*"AIza` |
| 协程泄漏 | `CoroutineScope\(Dispatchers|MainScope\(\)` |
| R8 不安全反射 | `Class.forName\(` |
| 阻塞 I/O | `File\(.*\)\.readText|\.writeText` |
| 无 key 的 remember | `remember\s*\{` (检查有无 key 参数) |
| 硬编码颜色 | `Color\(0x[0-9A-F]{8}\)` |
| 私有 IP 暴露 | `127\.0\.0\.1|192\.168\.|10\.\d+\.` |

### 6.3 修复优先级

| 级别 | 标准 | 示例 |
|------|------|------|
| **P0** | 功能完全崩溃 | 消息不显示/标签页切换失效/Service 未在 Manifest |
| **P1** | 数据丢失/安全漏洞 | API Key 明文/路径遍历/SSRF |
| **P2** | 用户体验降级 | 空操作按钮/Mock 数据/暗色模式失效 |
| **P3** | 代码质量 | 硬编码字符串/无 key 的 remember/重复代码 |

---

## 七、与本项目 MEMORY.md 的关系

审计中发现的问题通过以下记忆文件追踪：
- [[no-broken-code-push]] — 不推送损坏的代码
- [[release-checklist]] — 发布前检查清单
- [[dev-guide-as-single-source]] — 修复后同步更新开发文档
