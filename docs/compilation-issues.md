---
name: compilation-issues
description: v0.6.0 开发期间编译问题总结 — 预防性参考
metadata:
  type: reference
---

# 编译问题总结 — v0.6.0 开发期间

> 每个问题包含：现象、根因、修复方式、预防建议

## 1. RowScope / ColumnScope 与 AnimatedVisibility 冲突

**现象**:
```
'fun RowScope.AnimatedVisibility(...)' cannot be called in this context with implicit receiver
@Composable invocations can only happen from the context of a @Composable function
```

**根因**: `Box(Modifier.weight(1f))` 在 `Row { }` 内部时，`.weight()` 是 `RowScope` 扩展，导致 `Box` 的 lambda 体同时继承 `BoxScope` 和 `RowScope`。当 `AnimatedVisibility` 被调用时，编译器优先选择 `RowScope.AnimatedVisibility`（Material 3 扩展），但其签名与标准版本不同，且 lambda 上下文不是纯 RowScope，导致隐式接收者失败。

同理适用于 `ColumnScope.AnimatedVisibility`。

**修复**: 将 `AnimatedVisibility` 调用提取到独立的文件级 `@Composable` 函数中，脱离 Row/Column 作用域：

```kotlin
// 错误 — 在 Row/Column 内的 weight() Box 中
Box(Modifier.weight(1f).fillMaxWidth()) {  // 继承 RowScope
    AnimatedVisibility(...) { ... }         // 冲突！
}

// 正确 — 外层 Box + 独立函数
Box(Modifier.weight(1f).fillMaxWidth()) {
    Row(Modifier.fillMaxSize()) { ... }
    SidebarOverlay(...)  // 文件级 @Composable，无 scope 继承
}
```

**预防**: 
- 任何在 `Row { }` / `Column { }` 内使用 `Modifier.weight()` 的 `Box`，其内部的 `AnimatedVisibility` / `Crossfade` 等动画 composable 都可能触发此问题
- 优先将覆盖层（overlay）放在外层 Box 中，与 Row/Column 同级，而非嵌套
- 或提取为独立 Composable 函数

---

## 2. Private 成员跨方法访问

**现象**:
```
Cannot access 'val sessions: MutableMap<String, AgentSession>': it is private
```

**根因**: `MainScreen.kt` 直接访问了 `agentViewModel.sessions`，而 `sessions` 在 `AgentViewModel` 中是 `private val`。Compose 重组期间通过 lambdas 捕获 private 成员的引用会触发编译器访问检查。

**修复**: 使用已有的公开方法 `agentViewModel.frameworkFor(name)` 替代直接 map 访问：

```kotlin
// 错误
agentViewModel?.sessions?.get(displayAgentName)?.framework

// 正确 — 使用公开 API
agentViewModel?.frameworkFor(displayAgentName)
```

同时新增了 `frameworkFor()` 和 `agentConfig()` 两个公开查询方法到 `AgentViewModel`。

**预防**:
- ViewModel 的内部状态 (`sessions`, `_sessionHistory` 等) 必须通过公开方法暴露
- Compose 的 `remember` / `LaunchedEffect` lambda 会触发访问检查
- 原则：UI 层永远不直接访问 ViewModel 的 private 字段

---

## 3. 变量声明顺序与 LaunchedEffect 捕获

**现象**:
```
Unresolved reference 'displayAgentName'
```

**根因**: `LaunchedEffect(settingsState?.value)` 在第 78 行引用了 `displayAgentName`，但该变量在第 95 行才声明。虽然 Kotlin 支持局部变量提升（hoisting），但在 Compose 编译器插件处理 `LaunchedEffect` 的 key 和 body lambda 时，对声明顺序的容忍度更低。

**修复**: 将 `displayAgentName` 的定义移到 `LaunchedEffect` 之前：

```kotlin
// 错误顺序
LaunchedEffect(settingsState?.value) {
    viewModel.configureLlm(..., agentName = displayAgentName, ...)  // 未定义
}
val displayAgentName = activeAgentState?.value ?: "MengPaw"

// 正确顺序
val displayAgentName = activeAgentState?.value ?: "MengPaw"
LaunchedEffect(settingsState?.value) {
    viewModel.configureLlm(..., agentName = displayAgentName, ...)
}
```

**预防**:
- 所有被 `LaunchedEffect` / `remember` / `derivedStateOf` 引用的变量，必须在其使用之前声明
- 不要依赖 Kotlin hoisting 对 Compose lambda 的作用

---

## 4. Kotlin 字符串插值 `$$` 陷阱

**现象**: 编译错误（语法解析失败）

**根因**: Kotlin 字符串中 `"$${"%.2f".format(...)}"` — `$$` 被解析为转义 `$` 字面量，随后的 `{` 被解析为模板表达式开始，导致语法混乱。

**修复**:
```kotlin
// 错误
"$${"%.2f".format(value)}"

// 正确
"$" + "%.2f".format(value)
```

**预防**:
- `$` 后紧跟 `{` 永远被解析为模板表达式
- 需要字面量 `$` + 数字时，用字符串拼接代替

---

## 5. 函数参数名不匹配

**现象**:
```
Unresolved reference 'MarkdownText'
```

**根因**: `MarkdownText` 的第一个参数是 `content: String`，但调用时使用了 `markdown = item.docMarkdown`（不存在的命名参数）。

**修复**: 查阅组件签名后使用正确参数名：
```kotlin
// 错误
MarkdownText(markdown = item.docMarkdown, modifier = ...)

// 正确
MarkdownText(content = item.docMarkdown, modifier = ...)
```

**预防**:
- 调用第三方/项目内组件前，确认参数名与签名一致
- IDE 自动补全可避免此问题；纯文本编辑时需查源文件

---

## 6. Missing import（ArcoColors / clipToBounds / MarkdownText）

**现象**: `Unresolved reference`

**根因**: 多个文件引用了 `ArcoColors`、`clipToBounds()`、`MarkdownText` 但缺少对应 import。

涉及文件：
| 文件 | 缺失 import |
|------|------------|
| `SplashScreen.kt` | `ArcoColors` |
| `SidebarContent.kt` | `ArcoColors` |
| `HistorySidebar.kt` | `clipToBounds`, `ArcoColors` |
| `SettingsScreen.kt` | `MarkdownText`, `TokenLineChart`, `TokenStatsCollector`, `formatTokenCount` |
| `MissionMonitorOverlay.kt` | `ArcoColors` |

**修复**: 逐个添加缺失的 import。

**预防**:
- 新增色值引用时同步检查 import
- 使用 `ArcoColors` token 的文件必须显式 import

---

## 7. `Modifier.padding(vertical = ...)` 不存在

**现象**:
```
None of the following candidates is applicable: fun Modifier.padding(...)
```

**根因**: `Modifier.padding()` 没有 `vertical` 命名参数。正确写法是用两个 `padding()` 链式调用或使用 `horizontal` + `vertical` 拆分。

**修复**:
```kotlin
// 错误
Modifier.padding(start = 56.dp, end = ArcoSpacing.lg, vertical = ArcoSpacing.xs)

// 正确
Modifier.padding(start = 56.dp, end = ArcoSpacing.lg).padding(vertical = ArcoSpacing.xs)
```

**预防**: `padding(all)`, `padding(horizontal, vertical)`, `padding(start, top, end, bottom)` 是四个合法重载。不存在 `padding(start, end, vertical)` 这种组合。

---

## 8. `Modifier.weight()` 在非 Scope 上下文中

**现象**:
```
Expression 'weight' of type 'Float' cannot be invoked as a function
```

**根因**: `Modifier.weight(1f)` 是 `RowScope` / `ColumnScope` 扩展属性，只能在 `Row {}` 或 `Column {}` 的 lambda 体内使用。在独立 `@Composable` 函数（如 `StatCard`）中使用会报错。

**修复**:
```kotlin
// 错误 — StatCard 是独立 Composable，无 scope
Surface(modifier = Modifier.weight(1f)) { ... }

// 正确 — weight 由调用方在 Row 中控制
// 调用处: Row { StatCard(modifier = Modifier.weight(1f)) }
// 定义处: Surface(modifier = modifier) { ... }
```

**预防**: `Modifier.weight()` 只能作为 `Row` / `Column` 直接子级的 modifier 使用。提取 Composable 时，weight 应在调用方传入。

---

## 9. 类型推断失败（chartData 联合类型）

**现象**:
```
Argument type mismatch: List<Pair<String, List<Pair<String, Any>>>> vs List<Pair<String, List<Pair<String, Long>>>>
```

**根因**: `chartData` 由 `when (statRange)` 返回两种不同类型（`DayRecord` / `WeeklySummary`），Kotlin 推断公共父类型为 `Any`。后续 `.modelTokens[model]` 访问时无法解析。

**修复**: 在消费处使用 `when (statRange)` 替代 `is` 检查，让编译器根据已知的 `statRange` 确定具体类型：

```kotlin
// 错误 — 类型擦除为 Any
label to (if (record is DayRecord) record.modelTokens[model] : 0L
          else (record as WeeklySummary).modelTokens[model] ?: 0L)

// 正确 — 用 statRange 区分
val tokens = when (statRange) {
    0 -> (record as DayRecord).modelTokens[model] ?: 0L
    else -> (record as WeeklySummary).modelTokens[model] ?: 0L
}
```

**预防**: `when` 返回联合类型时，优先在消费处再次使用相同的 `when` 分支而非 `is` 类型检查。

---

## 10. `Write` 工具误覆盖完整文件

**现象**: 使用 `Write` 工具追加函数到 `MainScreen.kt` 末尾时，意外将整个文件内容替换为仅追加的代码段（31 行）。

**根因**: `Write` 工具是**全量覆盖**而非追加。本意是追加文件末尾，但工具语义导致文件被截断。

**修复**: `git checkout HEAD -- <file>` 从最新提交恢复完整文件，然后重新应用增量修改。

**预防**:
- **永远不要对已有文件使用 `Write`** — 除非意图是完整替换
- 对已有文件的修改使用 `Edit` 工具（增量替换）
- 仅对新建文件使用 `Write`
- 对文件末尾追加使用 `Bash` 的 `>>` 重定向

---

## 编译问题速查表

| 编号 | 错误特征 | 根因 | 修复方向 |
|------|---------|------|---------|
| 1 | `RowScope.AnimatedVisibility` cannot be called | weight() 引发 scope 继承 | 提取独立 Composable |
| 2 | `Cannot access private` | 跨方法访问 private 字段 | 添加公开方法 |
| 3 | `Unresolved reference` (变量) | LaunchedEffect 捕获后声明变量 | 移动声明到使用前 |
| 4 | 字符串插值编译失败 | `$${...}` 语法 | 改用拼接 |
| 5 | `Unresolved reference` (函数参数) | 参数名与签名不匹配 | 核对源文件签名 |
| 6 | `Unresolved reference` (类/函数) | 缺少 import | 添加 import |
| 7 | `padding` candidate 不匹配 | 不存在的参数组合 | 拆分为两次调用 |
| 8 | `weight` cannot be invoked | 非 scope 内使用 | 由调用方传入 |
| 9 | 联合类型推断 `Any` | when 返回不同具体类型 | 消费处用 when 区分 |
| 10 | 文件被截断 | Write 工具语义误解 | 用 Edit 代替 Write |
