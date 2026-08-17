# MengPaw.Browser 开发文档

> 独立 APK (Android 浏览器应用), 内置 Playwright 语义命令面, 供 AI Agent 半自动控制。
> 文档版本: v0.8.x · 2026-08-17 · 许可: AGPL-3.0-or-later OR LicenseRef-Commercial
> 本文档分两部分: **第一部分**引导 Agent 通过 MCP 连接浏览器并开始操作;
> **第二部分**面向开发者, 讲解目录结构与实现。

---

## 第一部分 快速开始 — Agent 通过 MCP 连接浏览器

### 1. 连接前提

- 设备已安装 MengPaw.Browser APK(包名 `com.mengpaw.browser`)
- **第三方 Agent(非同签名)**: 需用户在浏览器设置中开启「开放 MCP 控制」——默认
  安全模式下 9880 桥要求 Bearer token, 而 token 仅同签名 Shell 可获取, 第三方拿不到
  (详见 §3 认证)
- Agent 具备三项能力: `am` 命令执行(唤醒浏览器) / HTTP 请求(调用 9880) /
  读取公共目录文件(查看截图)

### 2. 三步连接

```bash
# ① 唤起浏览器(未运行或被杀时桥不可达, 必须先唤起)
am start -a com.mengpaw.action.OPEN_URL --es url "https://example.com"

# ② 探测桥状态
curl http://127.0.0.1:9880/health
# → {"ok":true,"status":"online","tools":6,"openMode":true}

# ③ 调用命令(开放模式下免认证)
curl -X POST http://127.0.0.1:9880/mcp \
  -d '{"tool":"page.content","args":{"head":"20"}}'
```

设备外(PC)接入: `adb forward tcp:9880 tcp:9880` 后连本机 9880。
浏览器进程被杀后桥自动停止, 重新唤起即恢复。

### 3. 认证: 安全模式与开放模式

| 模式 | /mcp 要求 | 适用 |
|------|-----------|------|
| 安全模式(默认) | `Authorization: Bearer <token>`, token 仅同签名 Shell 可拿 | MengPaw Shell |
| 开放模式 | 免认证(用户设置显式开启, 仅回环 127.0.0.1) | 第三方 Agent |

**401 处置流程**(Agent 首次调用大概率遇到):

```text
1. 收到 {"ok":false,"error":"unauthorized: ..."} 或 HTTP 401
2. 判定为安全模式: GET /health 若 openMode:false
3. 引导用户: 浏览器设置 → 开放 MCP 控制 → 开启
4. 重新 GET /health 确认 openMode:true, 再继续调用
```

开放模式仅监听回环地址, 不暴露局域网; 请仅在可信环境开启, 用完可关回。

### 4. 命令面速查

共 45 条: `page.*` 22 条(Playwright 语义, 主用)+ `browser.*` 23 条(保留能力)+
原生 6 工具(旧 MCP 工具名)。完整清单与参数见同目录
`MengPaw_Browser_skills.md` §3; 下文为高频命令。

| 命令 | 用途 |
|------|------|
| `page.load <url> [--max-height N]` | 导航 + 全页分段截图 + 坐标系统(推荐起手) |
| `page.goto <url> [--wait domcontentloaded\|networkidle]` | 导航 + 精确等待 |
| `page.screenshot [--full] [--view]` / `page.screenshot.element <css>` | 截图, 只回路径 + 尺寸/坐标 |
| `page.click <seg> <x> <y>` / `page.click <css>` | 段图坐标点击 / 选择器点击 |
| `page.fill <css> <text>` / `page.select <css> <value>` / `page.submit <css>` | 表单 |
| `page.content [--grep P] [--regex] [-i] [--head N] [--tail N]` | 提取正文 + 过滤 |
| `page.text <css>` / `page.attr <css> <name>` / `page.wait_selector <css>` | 查询/等待 |
| `page.scroll <x> <y>` / `page.scroll_by <dy>` / `page.eval <js>` | 滚动/JS |
| `page.url` / `page.title` / `page.back` / `page.forward` / `page.key <key>` | 信息/导航/按键 |
| `tabs` / `tab` / `tab.open` / `tab.close` / `tab.all` | 标签页(最多 4) |
| `cookies` / `cookies.set` / `cookies.clear` / `storage` | 存储与 Cookie |
| `browser_navigate` / `browser_extract` / `browser_eval` / ... | 原生 6 工具 |

### 5. 半自动循环(推荐工作流)

```text
page.load https://example.com        # 一次完成: 导航 + 分段截图 + 坐标系统
page.click 1 320 480                  # 看图 → 按段图坐标点击 (段 1)
page.scroll_by 800                    # 滚动后 page.screenshot --full 核对
page.content --grep "价格" --head 20  # 过滤提取, 不进上下文
```

`page.load` 返回格式(Agent 解析依据):

```text
## page.load 完成
URL: https://example.com
段数: 3 (partial: false)
段 1: /storage/emulated/0/MengPaw/截图存档/page_..._seg1.png (1080 × 2400, 缩放 0.44)
坐标系统: page.click <seg> <x> <y> — 框架自动还原页面坐标
```

超长页按段截取(段数上限 30, 超出标注 `partial:true`), 截图只回路径,
Agent 自行读取 `/storage/emulated/0/MengPaw/截图存档/` 下的图片查看。

### 6. 排障速查

| 现象 | 处理 |
|------|------|
| 9880 连不上 | 浏览器未运行 → 先唤起(§2 ①) |
| 401 unauthorized | 安全模式且无 token → 开启开放模式(§3 处置流程) |
| `WebView not available` | 无打开标签页 → 先 `OPEN_URL` 开页 |
| `page.load` 提示存储权限 | 未授予「所有文件访问」→ 浏览器首启弹窗或系统设置授权 |
| `page.click` 错位/超界 | 先 `page.screenshot --full` 刷新段图, 用返回段号 + 坐标 |
| `Selector not found` | 元素未加载/在 iframe → `page.eval` 探测 DOM, 先等加载 |
| 页面提取为空 | JS 渲染未完成 → `page.goto` 等加载后再 `page.content` |
| 分段截图 `partial:true` | 超长截断属正常 → 按已返回段操作或滚动重截 |

---

## 第二部分 从源码开始 — 目录结构与实现

### 7. 模块与构建

- 模块: `mengpaw-browser`(Gradle Android application)
- 依赖: `mengpaw-kernel`(CLI 执行/端口表/错误码)、`mengpaw-core`(DataPaths/Logger)、
  `mengpaw-design-system`(ArcoTheme)
- 构建: `.\gradlew.bat :mengpaw-browser:assembleDebug`
- 测试: `.\gradlew.bat :mengpaw-browser:testDebugUnitTest`(纯 JVM 单测)
- 产物: `mengpaw-browser/build/outputs/apk/...`

### 8. 目录结构(文件地图)

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

### 9. 命令面实现

注册源头: `BuiltinBrowserPlugin.commands` =
`BrowserTabCommands` + `BrowserPageCommands` + `BrowserQueryCommands` +
`BrowserPlaywrightCommands`。共 45 条(page.* 22 + browser.* 23)。

- `BrowserPlaywrightCommands`: `page.*` 22 条 — 参数解析支持位置参数 +
  `--flag` 值(`wait/max-height/grep/head/tail/timeout`)+ 布尔 flag(`-i` 等)
- `BrowserTabCommands`: `tabs` / `tab` / `tab.open` / `tab.close` / `tab.all` /
  `batch` / `q`
- `BrowserPageCommands`: `inject` / `diff` / `preload` / `wait` / `wait.nav` /
  `cookies` / `cookies.set` / `cookies.clear` / `dialog.accept` / `dialog.dismiss`
- `BrowserQueryCommands`: `visible` / `enabled` / `storage` / `viewport` /
  `userAgent` / `version`

命令实现统一接收 `(List<String>, ExecutionContext) -> ExecutionResult`, 经
`BrowserCommandContext` 访问 WebView 桥(`BrowserBridge`)/标签页状态/截图器。

### 10. 调用通道实现

#### 10.1 9880 HTTP 桥(`McpHttpServer`)

监听 `127.0.0.1:9880`(Ports.BROWSER_MCP), 生命周期随 BrowserActivity。

- `GET /health`: 免认证 → `{"ok":true,"status":"online","tools":6,"openMode":bool}`
- `POST /mcp`: body `{"tool":"<命令>","args":{...}}`; 安全模式需
  `Authorization: Bearer <token>`, 开放模式免认证
- 工具名 = 45 条命令键 + 原生 6 工具名; 参数 map 按位置键序展开
  (`url/selector/text/x/y/script/value/name/width/height/...` + `--flag` 映射)
- 工具执行双路径分流(`BrowserMcpTools`): 内置命令后台线程 `runBlocking`;
  原生 6 工具主线程(截图 View.draw 必须主线程)

#### 10.2 am 桥(`RunCommandService`)

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

### 11. 安全模型

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

### 12. 存储与截图

- 截图/输出落盘: `/storage/emulated/0/MengPaw/截图存档`(公共目录)
- 超长页分段: 每段 ≈ 视口高, 上限 30 段, 超出标注 `partial:true`
- 坐标系统: 段图坐标 → 页面坐标由浏览器自动还原 (缩放比/段偏移)
- 设置持久化: `BrowserPrefs`(SharedPreferences `mp_browser`)

### 13. 测试

`src/test/kotlin/com/mengpaw/browser/`:

- `util/SmartNavigateTest.kt` / `util/AdBlockerTest.kt`
- `mcp/McpAuthPolicyTest.kt`(认证策略 7 用例)

改命令面/桥/安全逻辑必须补对应单测; 核心链路改动跑
`:mengpaw-browser:testDebugUnitTest` 全绿。

### 14. 开发规范

- 命令核对以注册处为准(`BuiltinBrowserPlugin.commands` 聚合, 不凭 grep 印象)
- 新增命令四源同步: 命令注册 / `MengPaw_Browser_skills.md` / 开发指南 / 提示词
- 端口单一事实源: `Ports.kt`(9876 内核保留 / 9880 浏览器 MCP / 9881 MCP 网关)
- 新 `.kt` 必须带 SPDX 双许可头; 单文件 ≤400 行; 禁 `!!`
- 双许可: `AGPL-3.0-or-later OR LicenseRef-Commercial`
