# MengPaw 插件开发指南

> 版本: 0.1.0 | 面向: Agent 开发者 & 人类开发者

---

## 1. 概述

MengPaw 插件是一个包含 `commands`（命令映射）和 `Plugin` 接口实现的 **Kotlin 库模块**。Agent 可以通过 CLI 命令创建、测试、发布插件。

### 插件类型

| 类型 | 复杂度 | 适用场景 |
|------|--------|---------|
| **SCRIPT** | 低 | 纯 shell 命令封装，JSON 声明即可，Agent 可自建 |
| **JAR** | 中 | 需要 Kotlin 逻辑，有状态，需要编译 |
| **AAR** | 高 | 完整 Android 库，含资源/UI，需 Android Studio |

---

## 2. SCRIPT 插件（零代码）

Agent 创建 JSON 文件即可生成插件，无需编译：

```json
{
  "id": "my-weather-plugin",
  "name": "天气查询",
  "version": "0.1.0",
  "type": "SCRIPT",
  "author": "Agent-MengPaw",
  "description": "通过 wttr.in 查询天气",
  "commands": {
    "weather.now": {
      "shell": "curl -s 'wttr.in/{city}?format=3'",
      "params": ["city"],
      "description": "查询指定城市的当前天气"
    },
    "weather.forecast": {
      "shell": "curl -s 'wttr.in/{city}'",
      "params": ["city"],
      "description": "查询指定城市的天气预报"
    }
  }
}
```

Agent 创建命令：
```
plugin.create --type script --name "天气查询"
```

---

## 3. JAR 插件（Kotlin）

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
    implementation(project(":mengpaw-kernel"))
}
```

### 3.3 Plugin 实现

```kotlin
package com.mengpaw.plugin.myplugin

import com.mengpaw.kernel.plugin.*

class MyPlugin : Plugin {
    override val metadata = PluginMeta(
        id = "my-plugin",
        name = "我的插件",
        version = "0.1.0",
        description = "插件描述",
        type = PluginType.JAR
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "myns.hello" to { args, ctx ->
            val name = args["name"] ?: "World"
            ExecutionResult.ok("Hello, $name!")
        }
    )
}
```

---

## 4. 命名规范

```
<插件ID> := <命名空间>-plugin
<命令>   := <命名空间>.<动作>
<命名空间> := 小写字母+数字，2-20 字符

✅ fs-plugin, net-plugin, weather-plugin
❌ FileSystemPlugin, net_plugin, my-Plugin
```

| 命名空间 | 含义 | 已占用 |
|---------|------|--------|
| `self` | Agent 内省 | ✅ 内置 |
| `plugin` | 插件管理 | ✅ 内置 |
| `agent` | 文档管理 | ✅ 内置 |
| `sys` | 系统信息 | ✅ 内置 |
| `fs`, `net`, `memory`, `skill`, `ui`, `proc`, `clipboard`, `notification`, `pad` | 官方插件 | ✅ 已占用 |
| `tavily`, `hermes`, `comfy`, `render`, `translate`, `workflow`, `incubator`, `self-ext` | 社区插件 | ✅ 已占用 |

---

## 5. API 参考

### 5.1 Plugin 接口

```kotlin
interface Plugin {
    val metadata: PluginMeta
    val commands: Map<String, CommandHandler>
}

data class PluginMeta(
    val id: String,        // 全局唯一 ID
    val name: String,      // 显示名
    val version: String,   // 语义化版本
    val description: String,
    val author: String = "",
    val type: PluginType,
    val dependencies: List<String> = emptyList()
)

enum class PluginType { SCRIPT, JAR, AAR }

typealias CommandHandler = suspend (args: Map<String, String>, ctx: ExecutionContext) -> ExecutionResult
```

### 5.2 CLI 命令参考

| 命令 | 用途 |
|------|------|
| `plugin.create --type script\|jar --name <name>` | 创建插件骨架 |
| `plugin.build <plugin-id>` | 编译插件 |
| `plugin.test <plugin-id>` | 测试插件 |
| `plugin.publish <plugin-id>` | 发布插件到市场 |
| `plugin.share <plugin-id> --to <framework>` | 分享给指定框架 |

### 5.3 市场发布

发布到公共市场需满足：
1. 版本号遵循语义化版本
2. 包含 SHA256 校验和
3. 发布地址：GitHub Pages `https://<你的用户名>.github.io/mengpaw-plugins/plugins.json`

---

## 6. 信任链分享

### 6.1 框架间分享

```
Agent A（框架：我的平板，已信任）
  → plugin.share weather-plugin --to "同事的工作站"
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
> 违反安全规则的插件将被 `plugin.audit` 拒绝，无法通过 `plugin.share` 分享。

### 9.1 命令安全（Shell 注入防护）

| 规则 | 违例示例 | 正确做法 |
|------|---------|---------|
| 禁止危险命令 | `rm -rf /`, `mkfs.*`, `dd if=`, `> /dev/sda` | 限定操作路径，不暴露裸 shell |
| 禁止命令注入 | `shell: "curl {url}"` 无参数校验 | 校验 URL 格式，禁止 `;` `|` `$()` 等 shell 元字符 |
| 禁止 Fork 炸弹 | `:(){ :|:& };:` | 审计自动检测 |
| 禁止提权操作 | `sudo`, `su`, `chmod 777` | 不需要，MengPaw 不提供 root |
| 参数白名单 | `shell: "rm {path}"` | `path` 必须校验为合法路径，禁止 `..` 穿越 |

### 9.2 网络安全

| 规则 | 说明 |
|------|------|
| **HTTPS 优先** | 所有网络请求默认用 HTTPS。明文 HTTP 触发审计黄牌警告 |
| **URL 校验** | 请求前校验 URL 格式，拒绝 `file://`、`localhost:6379` 等内部协议 |
| **响应截断** | 网络响应默认截断到 10KB，避免内存溢出 |
| **超时设置** | 连接 10s + 读取 30s，不可无限等待 |

### 9.3 文件安全

| 规则 | 说明 |
|------|------|
| **路径限制** | 只能操作 `/sdcard/` 和 `DataPaths` 下的文件，禁止访问 `/data/data/`、`/system/` |
| **禁止覆盖关键文件** | 不允许写入 `.gradle`, `.ssh`, `.android` 等目录 |
| **大小限制** | 单文件写入 ≤ 10MB，防止磁盘写满 |
| **try/catch 必备** | 所有文件操作必须包裹异常处理，`plugin.audit` 检测 |

### 9.4 隐私安全

| 规则 | 说明 |
|------|------|
| **API Key 脱敏** | 日志/输出中不得出现 API Key。使用 `Sanitizer.sanitize()` 过滤 |
| **不收集隐私** | 禁止读取联系人、短信、通话记录、相册（除非用户明确授权） |
| **不静默上传** | 任何网络传输必须先告知用户，不可后台静默上传文件 |
| **输入清洗** | 用户输入在传递给 `shell` 前必须过滤特殊字符 |

### 9.5 插件声明安全（metadata 必填）

```kotlin
override val metadata = PluginMetadata(
    // ...
    permissions = listOf("INTERNET"),       // 必须声明
    minCoreVersion = "0.2.0",               // 必须声明最低框架版本
    // ...
)
```

| 字段 | 要求 |
|------|------|
| `permissions` | 列出所有需要的 Android 权限，不声明则无权限 |
| `minCoreVersion` | 必须 ≥ `0.2.0`，否则安装时拒绝 |
| `author` | 必须填写，不填则拒绝安装（"anonymous" 不允许） |

### 9.6 审计强制

`plugin.audit` 检测 7 类安全问题：

```
🔴 危险 shell 命令 (rm -rf /, mkfs, dd, > /dev/sda)
🔴 Fork 炸弹 / 无限循环
🔴 强制解包 (!!) — 会导致 NPE 崩溃
🔴 文件 IO 无 try/catch
🔴 HTTP 明文连接（建议 HTTPS）
🔴 阻塞调用 (Thread.sleep, while(true))
🔴 缺少 metadata 关键字段 (id/version/author/permissions)
```

**发布前必须先通过审计。** 任何 🔴 项 = 阻止分享。

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
