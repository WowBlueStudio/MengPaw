# MengPaw 插件开发指南

> 版本: 0.20.0 | 面向: Agent 开发者 & 人类开发者 | 更新: 2026-07-31

---

## 1. 概述

MengPaw 插件是一个包含 `commands`（命令映射）和 `Plugin` 接口实现的 **Kotlin 库模块**。Agent 可以通过 CLI 命令创建、审计、分享插件（`dev.plugin.*` 命令集，由内置 dev-plugin 提供）。

### 插件类型

| 类型 | 复杂度 | 适用场景 |
|------|--------|---------|
| **NATIVE** | 中 | 编译型（产物 JAR/AAR），需要 Kotlin 逻辑、有状态，需 Android Studio/Gradle 编译 |
| **SCRIPT** | 低 | 纯 shell 命令封装，JSON 声明即可，Agent 可零代码自建 |

> **JAR/AAR 统一归为 NATIVE**。早期文档中的 `PluginType.JAR` / `PluginType.AAR` 已合并为 `PluginType.NATIVE`（`mengpaw-kernel/.../plugin/Plugin.kt` 权威枚举）。

---

## 2. SCRIPT 插件（零代码）

Agent 创建 JSON 文件即可生成插件，无需编译。JSON 含 **`commands`** 与 **`keywords`** 两个顶层字段；命令键为**短名**（不含命名空间前缀——运行时由 PluginManager 按插件 id 自动拼前缀）：

```json
{
  "id": "my-weather-plugin",
  "name": "天气查询",
  "version": "0.1.0",
  "type": "SCRIPT",
  "author": "Agent-MengPaw",
  "description": "通过 wttr.in 查询天气",
  "commands": {
    "now": {
      "shell": "curl -s 'wttr.in/{city}?format=3'",
      "params": ["city"],
      "description": "查询指定城市的当前天气"
    },
    "forecast": {
      "shell": "curl -s 'wttr.in/{city}'",
      "params": ["city"],
      "description": "查询指定城市的天气预报"
    }
  },
  "keywords": {
    "now": { "zh": ["天气", "气温"], "en": ["weather", "temperature"] },
    "forecast": { "zh": ["预报"], "en": ["forecast"] }
  }
}
```

Agent 创建命令（生成骨架后编辑 `plugin.json`）：
```
dev.plugin.create --type script --name "天气查询" [--author 作者] [--desc 描述]
dev.plugin.audit --target my-weather-plugin   # 发布前审计 (--target 必填)
dev.plugin.keywords --target my-weather-plugin  # 查看/编辑检索关键词
```

> **命名空间前缀说明**：DevPlugin 的命令键为 `plugin.create` 等，但经 `PluginManager.namespaceFor("dev-plugin")` 派生后，实际注册命令为 **`dev.plugin.create`**（插件 id `dev-plugin` → 命名空间 `dev`）。本指南中所有 `dev.plugin.*` 命令均指此。

---

## 3. NATIVE 插件（编译型 Kotlin）

### 3.1 项目结构

```
my-plugin/
├── build.gradle.kts
└── src/main/kotlin/com/mengpaw/plugin/myplugin/
    └── MyPlugin.kt
```

### 3.2 build.gradle.kts 模板

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mengpaw.plugin.myplugin"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":mengpaw-core"))
}
```

> 模板由 `dev.plugin.create --type native` 自动生成（含 `:mengpaw-core` 依赖、PluginMetadata 全字段、commandKeywords 示例、ports 声明）。生成后用 Android Studio 打开开发。

### 3.3 Plugin 实现

```kotlin
package com.mengpaw.plugin.myplugin

import com.mengpaw.kernel.cli.*
import com.mengpaw.kernel.plugin.*

class MyPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "my-plugin",
        name = "我的插件",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "",                     // 必填，不允许匿名
        description = "插件描述",
        permissions = emptyList(),       // 列出所有需要的 Android 权限
        minCoreVersion = "0.2.0",        // 必须 ≥ 0.2.0
        ports = emptyList(),             // 声明占用的端口（与内核保留端口冲突会被拒绝）
        commands = listOf("example"),
        commandKeywords = mapOf(
            "example" to CommandKeywords(
                zh = listOf("示例"), en = listOf("example")
            )
        )
    )

    // 命令键为短名 — 运行时注册为 <命名空间>.<短名>（命名空间由插件 id 派生）
    override val commands: Map<String, CommandHandler> = mapOf(
        "example" to ::example
    )

    private suspend fun example(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("我的插件已就绪!")
    }
}
```

### 3.4 端口声明

插件如需监听或占用端口，在 `PluginMetadata.ports` 声明（如 `ports = listOf(8765)`）：

- 安装时 `PluginManager` 做**冲突检测**：与已安装/激活插件声明的端口冲突 → 拒绝安装
- **不可声明内核保留端口 9876（ACP）**——`dev.plugin.audit` 会直接标记 🔴
- 未声明端口 = 不占用，零冲突

---

## 4. 命名规范

```
<插件ID> := <命名空间>-plugin
<命令>   := <命名空间>.<动作>
<命名空间> := 小写字母+数字，2-20 字符

✅ fs-plugin, net-plugin, weather-plugin
❌ FileSystemPlugin, net_plugin, my-Plugin
```

| 命名空间 | 来源 | 状态 |
|---------|------|--------|
| `self`, `plugin`, `agent`, `sys` | 内核内置 | ✅ 始终可用 |
| `framework`, `fs`, `net`, `memory`, `skill`, `self`, `clipboard`, `notification`, `dev`, `root`, `twin`, `tools` | 内置插件（随 APK 预装） | ✅ 已占用 |
| `browser`, `tavily`, `tribe`(hermes 兼容), `comfy`, `render`, `translate`, `workflow`, `incubator`, `error-report`, `update` | 远程插件（按需安装） | ✅ 已占用 |
| `agent-mission`, `agent-loop` | 嵌入插件（UI 绑定） | ✅ 已占用 |

> 插件 id → 命名空间派生规则（`PluginManager.namespaceFor`）：
> - `xxx-plugin` → `xxx`（如 `fs-plugin` → `fs`）
> - `memory-twin-plugin` → `twin`（memory-* 取后缀）
> - 因此 `dev-plugin` → `dev`，其命令注册为 `dev.plugin.*`

---

## 5. API 参考

### 5.1 Plugin 接口（权威: `mengpaw-kernel/.../plugin/Plugin.kt`）

```kotlin
interface Plugin {
    val metadata: PluginMetadata
    val commands: Map<String, CommandHandler>   // 键为短名, 注册时拼命名空间前缀
    val uiButtons: List<PluginUiButton> = emptyList()
    suspend fun onInstall(context: PluginContext) {}
    suspend fun onUninstall() {}
    suspend fun onUpgrade(newVersion: String) {}
}

data class PluginMetadata(
    val id: String,                    // 全局唯一 ID, 形如 xxx-plugin
    val name: String,                  // 显示名
    val version: String,               // 语义化版本
    val type: PluginType = NATIVE,
    val author: String = "",           // 必填, 不允许匿名
    val description: String = "",
    val permissions: List<String> = emptyList(),  // Android 权限, 不声明则无
    val minCoreVersion: String = "0.1.0",         // 必须 ≥ 0.2.0
    val maxCoreVersion: String = "99.99.99",
    val dependencies: List<String> = emptyList(),
    val commands: List<String> = emptyList(),     // 命令清单 (marketplace 索引用)
    val ports: List<Int> = emptyList(),           // 声明占用端口, 安装时冲突检测
    val downloadUrl: String = "",
    val checksum: String = "",
    val sizeBytes: Long = 0,
    val commandKeywords: Map<String, CommandKeywords> = emptyMap()  // BM25 检索同义词
)

enum class PluginType { NATIVE, SCRIPT }

typealias CommandHandler = suspend (List<String>, ExecutionContext) -> ExecutionResult
```

### 5.2 CLI 命令参考

**内核 `plugin.*`（PluginExecutor — 插件管理，始终可用）**：

| 命令 | 用途 |
|------|------|
| `plugin.marketplace [--refresh]` | 浏览插件市场 |
| `plugin.search <关键词>` | 搜索插件 |
| `plugin.install <插件ID>` | 下载+验证+安装+激活 |
| `plugin.uninstall <插件ID>` | 卸载插件 |
| `plugin.list [--ports]` | 已安装列表（--ports 显示端口声明） |
| `plugin.info <插件ID>` | 插件详情 |
| `plugin.enable / plugin.disable <插件ID>` | 启用/停用 |
| `plugin.update <插件ID>` / `plugin.upgrade --all` | 更新/全部升级 |
| `plugin.auto <on\|off>` | 自动更新开关 |
| `plugin.verify <插件ID>` | 校验插件完整性 |

**dev-plugin `dev.plugin.*`（插件开发工具 — 内置 dev-plugin 提供）**：

| 命令 | 用途 |
|------|------|
| `dev.plugin.create --type script\|native --name <名称> [--author <作者>] [--desc <描述>]` | 创建插件骨架 |
| `dev.plugin.audit --target <插件ID>` | 静态安全审计（**--target 必填**，🔴 阻断分享） |
| `dev.plugin.share --plugin <插件ID> --to <框架>` | 分享给指定框架（审计不过拒绝） |
| `dev.plugin.examples` | 查看内嵌参考示例（文件/网络插件） |
| `dev.plugin.keywords --target <插件ID>` | 查看命令 BM25 检索关键词 |

> 命令键 `plugin.create` 注册为 `dev.plugin.create` 的机制：DevPlugin 的 commands map 键为 `plugin.create`，PluginManager 按插件 id `dev-plugin` 派生命名空间 `dev`，注册时拼接为 `dev.plugin.create`。

### 5.3 市场发布

发布到公共市场需满足：
1. 版本号遵循语义化版本（SemVer `X.Y.Z`）
2. 包含 SHA256 校验和（plugins.json `checksum: "sha256:..."` 字段）
3. 官方索引：仓库根 `plugins.json` 随 git commit 自动发布（GitHub Pages / Gitee raw 双源）
4. 发布流程（仓库工具链）：
   - `scripts/build-plugins.ps1` — 批量构建插件 AAR，自动回写 checksum/size/changelog 到 plugins.json
   - `scripts/validate-plugins.ps1` — 校验 plugins.json（字段/命名空间/checksum 与 AAR 一致性）
   - 产物上传 GitHub Release：独立 tag `plugins-vX.Y.Z`
   - 详见 `.claude/skills/plugin-dev.md`（插件开发/发布 skill）

---

## 6. 信任链分享

### 6.1 框架间分享

```
Agent A（框架：我的平板，已信任）
  → dev.plugin.share --plugin weather-plugin --to "同事的工作站"
  → 对方 Agent 收到请求："平板 Agent 想分享插件 '天气查询 v0.1.0'，是否安装？"
  → 用户同意 → 安装
  → 用户拒绝 → 丢弃
```

### 6.2 规则

- 插件列表**不主动暴露**——只有明确分享时对方才知道
- 分享**需要用户同意**——Agent 可以问，但用户决定
- 和 ACP 调度权限**完全分离**——插件分享 ≠ 远程调度
- 签名校验：接收方验证插件来源框架的签名

---

## 7. 开发者自发布

在公网上独立发布：

```
标题：基于MengPaw的天气查询插件，版本号0.1.0
描述：通过 wttr.in API 查询全球城市天气
安装：plugin.install --from https://xxx.github.io/mengpaw-plugins/plugins.json
```

发布者维护自己的 `plugins.json` 索引，格式与官方索引一致（字段见 §5.3）。用户手动添加信任源后即可安装。

发布者维护自己的 `plugins.json` 索引，格式与官方索引一致。用户手动添加信任源后即可安装。

---

## 8. UI 设计规范

插件如包含 UI 界面（对话框、设置页、浮动窗等），必须遵循 MengPaw 设计系统。

### 8.1 主题颜色

```kotlin
// 始终从 ThemeColors 取值，不硬编码颜色
import com.mengpaw.design.theme.ThemeColors

// ✅ 正确
Surface(color = ThemeColors.bgPrimary) { ... }
Text(color = ThemeColors.textPrimary)

// ❌ 错误
Text(color = Color(0xFF000000))
```

### 8.2 间距与圆角

```kotlin
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.design.tokens.ArcoRadius

// 间距：xs=4, sm=8, md=16, lg=24, xl=32
Modifier.padding(ArcoSpacing.lg)
// 圆角：sm=4, md=8, lg=12, xl=16
RoundedCornerShape(ArcoRadius.md)
```

### 8.3 组件优先使用设计系统

| 需求 | 使用 |
|------|------|
| 分割线 | `ArcoDivider()` |
| 卡片 | `ArcoCard(title, subtitle) { }` |
| 空状态 | `ArcoEmpty(message)` |
| 徽章 | `ArcoBadge(text)` |
| 按钮 | `Button` + `OutlinedButton` + `FilledTonalButton`（Material3） |

### 8.4 图标

- Material Icons Extended 已集成，使用 `Icons.Outlined.*` 和 `Icons.Filled.*`
- 无自定义图标文件（减少 APK 体积）

### 8.5 暗色模式

所有 UI 必须支持暗色模式。主题自动跟随 `ArcoTheme(darkTheme)` 切换。
测试：`ThemeColors.bgPrimary` 在暗色下自动变为深灰。

### 8.6 平板适配

使用 `isCompact()` / `isWide()` 判断屏幕尺寸：
- Compact（手机竖屏）：单列布局
- Medium/Expanded（平板/横屏）：双列布局

### 8.7 输入法适配

底部输入区域必须加 `Modifier.imePadding()`。

### 8.8 规范更新

本规范随 MengPaw 和 MP 浏览器的 UI 调整持续更新。

---

## 9. 安全规则 ⚠️

> **所有插件（含自建）必须遵守 MengPaw 安全规则。这是框架级别的强制要求，不可绕过。**
>
> 违反安全规则的插件将被 `dev.plugin.audit` 标记 🔴，无法通过 `dev.plugin.share` 分享。

### 9.1 命令安全（Shell 注入防护 — SCRIPT 插件）

`dev.plugin.audit` 对 SCRIPT 插件的 JSON 实际检测：

| 检测项 | 违例示例 | 级别 |
|------|---------|------|
| 危险命令 | `rm -rf /`、`rm -rf ~`、`mkfs.`、`dd if=`、`> /dev/sda`、`> /dev/null;` | 🔴 |
| 提权操作 | `sudo`、`su -`、`chmod 777` | 🔴 |
| Fork 炸弹 | `:(){ :|:& };:` | 🔴 |
| 命令注入 | `;` 分隔符、`$(...)` 命令替换、未确认的 `\|` 管道符 | 🔴 |
| 参数白名单 | `shell: "rm {path}"` 无路径校验 | 🔴 |
| 元数据 | 缺少 id/version/author(非空)/description(非空)/commands、type 非 SCRIPT、无 shell 命令 | 🔴 |
| 大小限制 | 插件定义 > 50KB | 🔴 |

### 9.2 网络安全（SCRIPT + NATIVE）

| 检测项 | 说明 | 级别 |
|------|------|------|
| **HTTPS 优先** | 含 `http://` 且无 `https://` — 明文 HTTP | 🔴 |
| **URL 校验** | 含 `file://`、`localhost`、`127.0.0.1` — 可能内网攻击/SSRF | 🔴 |
| **响应截断** | `bodyAsText()` 无 `.take(N)` — 内存溢出风险 | 🔴 |
| **超时设置** | 有 `connectTimeout` 无 `readTimeout` — 可能无限等待 | 🔴 |

### 9.3 文件与代码安全（NATIVE 插件）

| 检测项 | 说明 | 级别 |
|------|------|------|
| **try/catch 必备** | 使用 File/readText/writeText/listFiles 但无 try/catch | 🔴 |
| **路径穿越** | 代码含 `..` 与 `"path"` — 参数校验不充分 | 🔴 |
| **空安全** | 使用 `!!` 强制解包 — NPE 崩溃风险 | 🔴 |
| **阻塞调用** | `Thread.sleep`、`while (true)`、`runBlocking` — 应使用协程 suspend | 🔴 |
| **结构完整** | 缺 Plugin 类、metadata、commands、`PluginType.NATIVE`、permissions、minCoreVersion | 🔴 |
| **端口冲突** | 声明端口 9876（内核 ACP 保留） | 🔴 |
| **端口范围** | 声明端口超出 1-65535 | 🟡 |

### 9.4 隐私安全

| 检测项 | 说明 | 级别 |
|------|------|------|
| **API Key 硬编码** | 含 `"sk-"`、`apiKey`、`api_key` — 必须用 Sanitizer 过滤 | 🔴 |
| **敏感数据访问** | 引用 `ContactsContract`/`Telephony`/`CallLog` — 必须用户明确授权 | 🔴 |
| **不静默上传** | 任何网络传输必须先告知用户，不可后台静默上传文件 | 准则 |

### 9.5 插件声明安全（metadata 必填）

```kotlin
override val metadata = PluginMetadata(
    // ...
    permissions = listOf("INTERNET"),       // 必须声明
    minCoreVersion = "0.2.0",               // 必须声明最低框架版本
    ports = emptyList(),                    // 声明占用端口 (可省略)
    commandKeywords = mapOf(...),           // 建议声明 (黄牌)
    // ...
)
```

| 字段 | 要求 |
|------|------|
| `permissions` | 列出所有需要的 Android 权限，不声明则无权限 |
| `minCoreVersion` | 必须 ≥ `0.2.0`，否则安装时拒绝 |
| `author` | 必须填写，不填则拒绝安装（"anonymous" 不允许） |
| `ports` | 声明占用的端口；与已装插件冲突 → 安装拒绝；9876 为内核保留 |
| `commandKeywords` | 未声明 → 审计 🟡 黄牌（仅提示，不阻断） |

### 9.6 审计强制

`dev.plugin.audit --target <插件ID>`（**--target 必填**）按插件类型检测：

- **SCRIPT**（`auditScript`）：元数据完整性、危险 shell 命令、命令注入、URL 安全、大小限制、keywords
- **NATIVE**（`auditKotlin`）：结构完整性（class/Plugin/metadata/commands/NATIVE/permissions/minCoreVersion）、空安全、并发阻塞、文件 IO try/catch、网络超时与截断、隐私（API Key/通讯录）、路径穿越、端口冲突、commandKeywords

**发布前必须先通过审计。** 任何 🔴 项 = 阻止 `dev.plugin.share`。🟡 项为建议，不阻断。

### 9.7 信任链安全

```
本地 MengPaw
  └─ 官方插件：直接安装（用户确认）
  └─ 信任框架插件：SHA256 校验 + 用户确认
  └─ 公网插件：SHA256 + 用户确认 + 来源标记
  └─ 未验证来源：拒绝安装
```

- 所有非官方插件安装前弹窗显示：插件名、版本、作者、来源框架、权限列表
- 用户可查看插件 JSON/Kotlin 源码后再决定是否安装
- 已安装插件的来源信息保存在本地，可追溯
