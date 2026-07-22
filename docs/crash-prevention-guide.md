# MengPaw 闪退防治手册

> 基于 v0.1.0 ~ v0.7.0 开发过程中的真实闪退案例，覆盖 30+ 个崩溃根因与修复模式。
> 最后更新: 2026-07-22 (v0.7.0)

---

## 目录

1. [进程保活](#1-进程保活)
2. [空安全](#2-空安全)
3. [路径与文件 IO](#3-路径与文件-io)
4. [Android 生命周期](#4-android-生命周期)
5. [Compose 陷阱](#5-compose-陷阱)
6. [模块分层](#6-模块分层)
7. [编译期陷阱](#7-编译期陷阱)
8. [预览清单](#8-发布前预览清单)

---

## 1. 进程保活

### 1.1 前台服务被系统杀死

**现象**: App 打开后几分钟自动退出，回到桌面。

**根因**: Android 12+ 对后台进程限制更严。仅靠前台通知不足以保活，CPU 休眠后进程被 LMK (Low Memory Killer) 回收。

**修复** (`ShellService.kt` — v0.7.0):
```kotlin
// 获取 WakeLock 防止 CPU 深度休眠
val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mengpaw:shell-service")
wakeLock?.acquire(60 * 60 * 1000L) // 1 小时超时

// onStartCommand 中检测并重新获取
if (wakeLock?.isHeld != true) {
    wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
}
```

**关键点**:
- `setReferenceCounted(false)` — 防止引用计数泄漏
- 超时自动释放 — 避免电池耗尽
- `onDestroy` 必须释放 — `if (wakeLock?.isHeld == true) wakeLock?.release()`

### 1.2 AlarmManager 唤醒缺失

**现象**: 定时任务不触发，Agent 在后台不响应。

**根因**: `registerCronAlarm(null)` 传入 null Context，立即 return 不注册闹钟。

**修复** (`TriggerEngine.kt` — v0.7.0):
```kotlin
// 存储 Context
fun setContext(context: Context) { appContext = context.applicationContext }

// 使用存储的 Context 注册闹钟
fun registerCronAlarm() {
    val ctx = appContext ?: return  // 而非传入的 null
    // ... AlarmManager.setExact(...)
}
```

### 1.3 OEM 厂商额外限制

**设备**: 小米 (MIUI)、华为 (EMUI)、OPPO (ColorOS)、vivo (OriginOS)

| 厂商 | 额外限制 | 用户操作 |
|------|---------|---------|
| 小米 | 后台自启动管理 | 设置 → 应用 → MengPaw → 自启动：允许 |
| 华为 | 电池优化 + 关联启动 | 手机管家 → 启动管理 → 手动管理 ✓ |
| OPPO | 自动冻结 | 设置 → 电池 → 应用速冻 → MengPaw → 关闭 |
| vivo | 后台高耗电 | 设置 → 电池 → 后台高耗电 → MengPaw → 允许 |

---

## 2. 空安全

### 2.1 `!!` 强制解包

**现象**: `NullPointerException` — 最常见闪退类型。

**规则**: **全项目禁止 `!!`**。用 `?.let {}` 或 `?: return` 替代。

```kotlin
// ❌ 崩溃
val session = sessions[agentName]!!
session.messages.value = ...

// ✅ 安全
val session = sessions[agentName] ?: return
session.messages.value = ...
```

### 2.2 文件读取空值

**现象**: 读取工作区文件返回 null → 后续操作崩溃。

```kotlin
// ❌ 崩溃
val text = File(path).readText()
val firstLine = text.lines().first()  // 空文件返回空列表 → NoSuchElementException

// ✅ 安全
val text = try { File(path).readText() } catch (_: Exception) { "" }
val firstLine = text.lines().firstOrNull() ?: ""
```

### 2.3 Intents / Bundle 空值

**现象**: `intent.getStringExtra("key")!!` → 其他 App 发来的 Intent 没有这个 key。

```kotlin
// ✅ 安全
val url = intent.getStringExtra("url")
if (url != null) handleUrl(url)  // 静默忽略不认识的 Intent
```

---

## 3. 路径与文件 IO

### 3.1 硬编码路径

**现象** (`v0.2.2`): `/sdcard/MengPaw` 在 Android 11+ 无权限访问 → 所有文件操作失败 → 首次启动闪退。

**规则**: **永远不要硬编码文件路径**。用 `DataPaths` 统一管理，`Context.filesDir` 初始化。

```kotlin
// 启动时初始化
DataPathsInitializer.initialize(context)  // → DataPaths.BASE = context.filesDir

// 所有路径通过 DataPaths 访问
val dir = File(DataPaths.AGENTS, agentName)
```

### 3.2 主线程 IO

**现象**: UI 在主线程读取大文件 → ANR (Application Not Responding)。

```kotlin
// ❌ 在主线程
val content = File(path).readText()  // > 50MB 文件 → ANR

// ✅ 在 IO 线程
viewModelScope.launch(Dispatchers.IO) {
    val content = try { File(path).readText() } catch (_: Exception) { "" }
    withContext(Dispatchers.Main) { /* 更新 UI */ }
}
```

### 3.3 目录不存在

```kotlin
// ✅ 写文件前确保目录存在
file.parentFile?.mkdirs()
file.writeText(content)
```

---

## 4. Android 生命周期

### 4.1 WebView 未销毁

**现象** (`BrowserActivity`): 切换标签页后内存持续增长 → OOM 崩溃。

**修复** (`v0.3.0`):
```kotlin
// DisposableEffect 确保离开组合时销毁 WebView
DisposableEffect(activeTabId) {
    onDispose {
        wv.stopLoading()
        wv.destroy()
        webViewMap.remove(activeTabId)
    }
}
```

### 4.2 ViewModel 资源泄漏

```kotlin
override fun onCleared() {
    // 关闭所有 HTTP 客户端
    sessions.values.forEach { session ->
        try { (session.provider as? Closeable)?.close() } catch (_: Exception) {}
    }
    // 取消所有协程
    stateObserverJob?.cancel()
    messageBindingJob?.cancel()
}
```

### 4.3 onNewIntent 未处理

**现象**: `singleTask` launchMode 下重复打开 URL 不触发导航。

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleOpenUrl(intent)  // 必须手动处理
}
```

---

## 5. Compose 陷阱

### 5.1 Scope 冲突

**现象**: `RowScope.AnimatedVisibility cannot be called in this context`

**根因**: `Box(Modifier.weight(1f))` 在 `Row {}` 内，weight 是 RowScope 扩展，导致内部的 Composable 继承多个 Scope。

**修复**: 将覆盖层提取为独立的文件级 `@Composable` 函数，脱离 Row/Column scope。
```kotlin
// ❌ 在 Row 内的 weight() Box 中直接调用 AnimatedVisibility
// ✅ 提取为独立 Composable
@Composable
private fun SidebarOverlay(...) { ... }
```

### 5.2 声明顺序

**现象**: `Unresolved reference` — 变量在 `LaunchedEffect` 之后声明。

**规则**: `LaunchedEffect` / `remember` / `derivedStateOf` 引用的变量必须在使用前声明。
```kotlin
// ✅ 正确顺序
val displayAgentName = activeAgentState?.value ?: "MengPaw"
LaunchedEffect(settingsState?.value) {
    viewModel.configureLlm(..., agentName = displayAgentName, ...)
}
```

### 5.3 `Modifier.padding(vertical = ...)` 不存在

```kotlin
// ❌ 编译错误
Modifier.padding(start = 56.dp, end = 8.dp, vertical = 4.dp)

// ✅ 两次调用
Modifier.padding(start = 56.dp, end = 8.dp).padding(vertical = 4.dp)
```

### 5.4 `Modifier.weight()` 在非 Scope 中

`weight()` 是 `RowScope`/`ColumnScope` 扩展，只能在这些 scope 内使用。提取 Composable 时 weight 应由调用方传入。

---

## 6. 模块分层

### 6.1 不要在 kernel 层使用 Android API

**现象**: `org.json.JSONObject` 在 `mengpaw-kernel` 中编译失败。

**根因**: kernel 是纯 JVM 模块 (`kotlin("jvm")`)，没有 Android SDK。

**规则**:

| 层 | 可用序列化 | 不可用 |
|----|----------|--------|
| kernel (JVM) | `kotlinx.serialization` | `org.json`, `android.*` |
| core (Android Library) | `kotlinx.serialization` + `org.json` | — |
| shell (APK) | 全部 | — |

### 6.2 不要在 kernel 层硬编码 Android Context

`ExecutionContext` 是纯数据类，不含 Android 依赖。Android Context 通过 `SysExecutor.init(context)` 注入 core 层。

---

## 7. 编译期陷阱

### 7.1 bulk replace 副作用

**教训** (`v0.7.0`): `replace_all: true` 把 `ctx.context` → `ctx` 破坏所有 Android Context 引用。

**原则**: 
- 优先用精确匹配的 `replace_all: false`
- 必须用 `replace_all: true` 时，匹配字符串要足够独特
- 替换后立即编译验证

### 7.2 Kotlin `_` 参数名限制

Kotlin 2.0+ 中 `_` 在 suspend 函数参数中不被允许。用 `ec` 或 `ignored` 代替。

### 7.3 Write 工具语义

`Write` 是**全量覆盖**，不是追加。对已有文件的修改用 `Edit`。

### 7.4 字符串插值 `$$` 陷阱

```kotlin
// ❌ 编译错误
"$${"%.2f".format(value)}"

// ✅ 正确
"$" + "%.2f".format(value)
```

### 7.5 联合类型推断失败

```kotlin
// ❌ when 返回不同类型 → Kotlin 推断为 Any
val result = when (mode) { A -> TypeA(); B -> TypeB() }

// ✅ 在消费处再次用 when 分支
val tokens = when (statRange) { 0 -> (r as Day).tokens; else -> (r as Week).tokens }
```

---

## 8. 发布前预览清单

每次发布前逐项检查：

```
□ 1. clean build 通过 (./gradlew clean assembleRelease)
□ 2. kernel 测试通过 (./gradlew :mengpaw-kernel:test)
□ 3. 没有 "unresolved reference" 编译警告
□ 4. 版本号统一 (build.gradle.kts + AgentEngine.CORE_VERSION + Settings About)
□ 5. ProGuard keep 规则已检查
□ 6. git status 干净
□ 7. git tag + gh release create + APK 上传
□ 8. 开发文档 + LESSONS.md 已更新
□ 9. API Key 没有硬编码在源码中
□ 10. `!!` 断言已全部检查
□ 11. 所有 File IO 有 try/catch
□ 12. 前台服务 + WakeLock 在 manifest 中声明
□ 13. 模拟器验证不闪退 (至少 5 分钟运行)
```

---

## 附录：案例索引

| 版本 | 闪退原因 | 根因 | 修复 |
|------|---------|------|------|
| 0.2.2 | 首次启动 | 硬编码 /sdcard/MengPaw 路径 | DataPaths 动态初始化 |
| 0.2.2 | 新建智能体 | 同上 + 文件写入无 try/catch | AgentDocs.bootstrap 安全写入 |
| 0.3.0 | WebView OOM | WebView.destroy() 未调用 | DisposableEffect 清理 |
| 0.4.0 | ProGuard 缺失 | R8 混淆了反射调用的类 | ProGuard keep 规则 |
| 0.5.0 | `!!` NPE | 多处 force unwrap | 替换为 `?.let` / `?: return` |
| 0.6.0 | 编译失败 ×10 | scope 冲突 + import 缺失 + padding 错误 | 逐项修复见 compilation-issues.md |
| 0.7.0 | 进程被杀 | 无 WakeLock | ShellService 加 PARTIAL_WAKE_LOCK |
| 0.7.0 | 触发器不触发 | start() 未调用 + onFire null | 接入 MainActivity + AgentViewModel |

---

*这份文档应与 `LESSONS.md` 和 `docs/compilation-issues.md` 配合阅读。每次发现新闪退模式后更新本文。*
