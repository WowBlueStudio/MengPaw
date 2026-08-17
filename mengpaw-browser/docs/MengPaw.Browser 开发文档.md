# MengPaw.Browser 开发文档

> 独立 APK (Android 浏览器应用), 内置 Playwright 语义命令面, 供 AI Agent 半自动控制。
> 文档版本: v0.8.x · 2026-08-17 · 许可: AGPL-3.0-or-later OR LicenseRef-Commercial
> 本文件是浏览器模块的开发/维护手册; Agent 控制手册见同目录 `MengPaw_Browser_skills.md`。

## 1. 概述

MengPaw.Browser(包名 `com.mengpaw.browser`)是独立于 MengPaw Shell 的浏览器 APK,
核心价值是**可编程控制**:内置 `page.*`(22 条,Playwright 语义)+ `browser.*`(23 条)
命令面,经两条通道暴露:

- **9880 HTTP 桥**(`McpHttpServer`, 127.0.0.1:9880): 任意本机进程可调;
  默认 Bearer token 认证(仅同签名 Shell 可拿 token), 可选开放模式免认证。
- **am 桥**(`RunCommandService`): Termux 式 `am startservice`, signature 权限
  (仅同签名应用可调), 输出落盘公共目录。

与 Playwright 的关系: 命令名/参数语义对齐 Playwright(LLM 零学习成本), 但运行在
Android WebView 内, 以截图坐标 + CSS 选择器驱动, 非 CDP。

## 2. 模块与构建

- 模块: `mengpaw-browser`(Gradle Android application)
- 依赖: `mengpaw-kernel`(CLI 执行/端口表/错误码)、`mengpaw-core`(DataPaths/Logger)、
  `mengpaw-design-system`(ArcoTheme)
- 构建: `.\gradlew.bat :mengpaw-browser:assembleDebug`
- 测试: `.\gradlew.bat :mengpaw-browser:testDebugUnitTest`(纯 JVM 单测)
- 产物: `mengpaw-browser/build/outputs/apk/...`

环境铁律: JDK 17; Gradle wrapper 8.12; 新建 `.kt` 必须带 SPDX 双许可头;
单文件 ≤400 行; 禁 `!!` 强制解包; 文件 IO 必须 try/catch。

## 3. 架构总览(文件地图)

```
com.mengpaw.browser
├── BrowserActivity.kt         # APK 入口: 启动 9880 桥/token/开放模式/权限引导
├── BrowserApp.kt              # Compose 主 UI (标签页/地址栏/对话框状态)
├── BrowserAppDialogs.kt       # 对话框渲染层 (状态提升, 参数显式传入)
├── BrowserContentArea.kt      # 内容区 (NewTabPage / 多标签 WebView)
├── BrowserDarkMode.kt         # 暗色模式注入
├── BrowserMcpTools.kt         # 9880 桥工具执行 (双路径分流)
├── bridge/                    # BrowserBridge / BrowserScripts / FullPageScreenshotter
├── data/                      # BrowserPrefs / BrowserTypes / HistoryStore
├── mcp/                       # McpHttpServer (9880 桥) / McpAuthPolicy (认证策略)
├── plugin/                    # BuiltinBrowserPlugin + 4 命令组 + BrowserCommandContext
├── service/                   # RunCommandService (am 桥) / GoogleTranslate
├── ui/                        # TopBar / 设置 / 书签 / 历史 / 标签页等 Compose UI
├── util/                      # AdBlocker / BrowserStorage / DownloadUtil / SmartNavigate
└── web/                       # WebViewFactory / MdViewer*
```

关键生命周期:

- `BrowserActivity.onCreate`: 启动 `McpHttpServer`(9880)→ 生成 32 字节 token 并注入
  Shell(经签名级 ContentProvider)→ 读取 `BrowserPrefs.mcpOpenMode` 设置开放模式 →
  绑定 Quick Click/截图设置到 `BuiltinBrowserPlugin` → `BuiltinBrowserPlugin.shared`
  供 am 桥复用 → 首启申请「所有文件访问」。
- `BrowserActivity.onDestroy`: 停止 9880 桥, token 失效。

## 4. 命令面

注册源头: `BuiltinBrowserPlugin.commands` =
`BrowserTabCommands` + `BrowserPageCommands` + `BrowserQueryCommands` + `BrowserPlaywrightCommands`。
共 45 条(page.* 22 + browser.* 23)。

### 4.1 page.*(22 条, Playwright 语义)

| 命令 | 语义 |
|------|------|
| `page.goto <url> [--wait domcontentloaded\|networkidle]` | 导航 + 精确等待 |
| `page.load <url> [--max-height N]` | 半自动合体: 导航 + 全页分段截图 + 坐标系统 |
| `page.screenshot [--full] [--view]` | 全页/视口截图, 只回路径 + 尺寸/坐标 |
| `page.click <seg> <x> <y>` / `page.click <css>` | 坐标点击(段图)或选择器点击 |
| `page.fill <css> <text>` | 输入 |
| `page.content [--grep P] [--regex] [-i] [--head N] [--tail N]` | 提取正文 + 内置过滤 |
| `page.text <css>` / `page.attr <css> <name>` | 元素文本 / 属性 |
| `page.wait_selector <css> [--timeout N]` | 等待元素出现 |
| `page.scroll <x> <y>` / `page.scroll_by <dy>` | 绝对/相对滚动 |
| `page.eval <js>` | 执行 JS |
| `page.url` / `page.title` | 当前页信息 |
| `page.back` / `page.forward` | 历史导航 |
| `page.select <css> <value>` / `page.submit <css>` | 下拉选值 / 提交表单 |
| `page.check` / `page.uncheck` | 勾选/取消 |
| `page.screenshot.element <css>` | 元素截图 |
| `page.key <key>` | 按键 (Enter/Tab/ArrowDown/单字符) |

### 4.2 browser.*(23 条, 保留命令)

标签页与效率(`BrowserTabCommands`): `tabs` / `tab` / `tab.open` / `tab.close` /
`tab.all` / `batch` / `q`

页面控制(`BrowserPageCommands`): `inject` / `diff` / `preload` / `wait` /
`wait.nav` / `cookies` / `cookies.set` / `cookies.clear` / `dialog.accept` /
`dialog.dismiss`

元素查询与设置(`BrowserQueryCommands`): `visible` / `enabled` / `storage` /
`viewport` / `userAgent` / `version`

### 4.3 原生 6 工具(9880 桥直连)

`browser_navigate` / `browser_screenshot` / `browser_click` / `browser_type` /
`browser_extract` / `browser_eval`(参数为 `selector`/`text`/`script`/`url`)。

## 5. 调用通道

### 5.1 9880 HTTP 桥(主通道, 任意本机进程)

监听 `127.0.0.1:9880`(Ports.BROWSER_MCP), 生命周期随 BrowserActivity。

- `GET /health`: 免认证 → `{"ok":true,"status":"online","tools":6,"openMode":bool}`
- `POST /mcp`: body `{"tool":"<命令>","args":{...}}`; 安全模式需
  `Authorization: Bearer <token>`, 开放模式免认证
- 工具名 = 45 条命令键 + 原生 6 工具名, 参数 map 按位置键序展开
  (`url/selector/text/x/y/script/value/name/width/height/...` + `--flag` 映射)

### 5.2 am 桥(同签名专属)

```text
action:  com.mengpaw.browser.RUN_COMMAND
extra:   com.mengpaw.browser.RUN_COMMAND_ARGUMENTS = "-c,<命令串>"
extra:   com.mengpaw.browser.RUN_COMMAND_OUTPUT    = <输出文件路径, 公共目录>
extra:   com.mengpaw.browser.RUN_COMMAND_BACKGROUND = true
权限:    com.mengpaw.permission.RUN_BROWSER_COMMAND (signature)
白名单:  仅 page.* / browser.* 前缀
```

`RunCommandService` 引号感知分词, 输出落盘后由调用方读取。浏览器未运行时报
「浏览器未就绪(请先打开 MP 浏览器再调用)」。

## 6. 安全模型

| 层 | 措施 |
|---|------|
| am 桥 | signature 权限 `RUN_BROWSER_COMMAND`, 仅同签名 Shell 可调 |
| 9880 桥 | Bearer token (32 字节 SecureRandom), 签名级 ContentProvider 下发, 401 fail-closed |
| 开放模式 | 用户设置显式开启 → `/mcp` 免认证 (Playwright 式, 仅回环 127.0.0.1) |
| 命令面 | am 桥 payload 白名单 `page.*`/`browser.*`, 拒绝任意 shell |
| 输出路径 | am 桥输出限制在公共目录 `MengPaw/` 下 |
| 存储 | `MANAGE_EXTERNAL_STORAGE`, 首启弹窗; 拒绝后每次 `page.load` 提示重授 |

认证策略为纯函数 `McpAuthPolicy.isAuthorized(openMode, expectedToken, providedHeader)`
(安全模式 fail-closed / 开放模式放行), 单测锁定。

## 7. 存储与截图

- 截图/输出落盘: `/storage/emulated/0/MengPaw/截图存档`(公共目录)
- 超长页分段: 每段 ≈ 视口高, 上限 30 段, 超出标注 `partial:true`
- 坐标系统: 段图坐标 → 页面坐标由浏览器自动还原 (缩放比/段偏移)
- 设置持久化: `BrowserPrefs`(SharedPreferences `mp_browser`)

## 8. 测试

`src/test/kotlin/com/mengpaw/browser/`:

- `util/SmartNavigateTest.kt` / `util/AdBlockerTest.kt`
- `mcp/McpAuthPolicyTest.kt`(认证策略 7 用例)

改命令面/桥/安全逻辑必须补对应单测; 核心链路改动跑
`:mengpaw-browser:testDebugUnitTest` 全绿。

## 9. 开发规范

- 命令核对以注册处为准(`BuiltinBrowserPlugin.commands` 聚合, 不凭 grep 印象)
- 新增命令四源同步: 命令注册 / `MengPaw_Browser_skills.md` / 开发指南 / 提示词
- 端口单一事实源: `Ports.kt`(9876 内核保留 / 9880 浏览器 MCP / 9881 MCP 网关)
- 新 `.kt` 必须带 SPDX 双许可头; 单文件 ≤400 行; 禁 `!!`
- 双许可: `AGPL-3.0-or-later OR LicenseRef-Commercial`

## 10. 版本历史

- v0.8.0 (2026-08-11): 半自动武器 Phase 1-3 — page.* 命令面 / 分段坐标 / am 桥 /
  browser.* 去重 45→23
- v0.8.x (2026-08-17): MCP 开放模式 (第三方 Agent 接入) — McpAuthPolicy /
  BrowserPrefs.mcpOpenMode / 设置开关 / /health 开放模式探测
