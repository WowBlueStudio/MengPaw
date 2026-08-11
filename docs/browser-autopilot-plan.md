# MP 浏览器「半自动武器」升级方案（Playwright 语义 + Termux 式调用）

> 状态：方案定稿 + 取舍定案（2026-08-11 两轮用户拍板）｜**已实施 Phase 1-3 + Phase 4 机器验证（2026-08-11 当日）**
> 待办：真机自测（APK 已构建 mengpaw-browser-v0.8.0-debug.apk）→ 9880 桥退役（决策 #7）
> 关联：mengpaw-browser（浏览器进程）、mengpaw-shell（Shell 进程）、mengpaw-kernel（CommandMonitor/Linux 命令通道）、mengpaw-connectors（退役 browser-mcp-plugin）

## 一、背景与痛点

当前 MP 浏览器经 9880 MCP 桥（HTTP 请求-响应）与 Shell 协作，存在三个问题：

1. **一步一命令，往返太多**：发送链接 1 次、等待 1 次、截图 1 次、滚动 1 次、坐标点击 1 次——LLM 每步一次调用，效率低。
2. **输出直接灌上下文**：`browser.content`/`tab.all` 的大段正文、自定义命令文档解释，挤占上下文。
3. **命令面是自定义的**：`browser.*` 命令名与参数形态是项目自造，LLM 不熟悉，需要提示词教；而 LLM 训练语料里最熟的是 Playwright。

目标：把浏览器变成「半自动武器」——收到链接后自动完成加载 + 全页长截图 + 返回坐标系统，Agent 基于截图用 xy 坐标直接操作；调用方式对齐 Termux（shell 子进程可直接触发）；命令面模仿 Playwright 语义，减少上下文膨胀。

## 二、设计决策（用户拍板 2026-08-11）

| # | 决策 |
|---|------|
| 1 | `page.load` **默认自动全页截图**，非全页无意义；`--shot` 不再作为开关（全页为默认行为）。 |
| 2 | 浏览器**安装后第一次打开即弹窗申请存储权限**（`MANAGE_EXTERNAL_STORAGE`，与 Shell 对齐），用于截图/输出落盘公共目录。 |
| 3 | 截图**只回路径 + 尺寸/坐标信息**，**不做 base64 预览**（2026-08-11 拍板去掉小图预览，省上下文）。 |
| 4 | **新增 `page.*` 语义组**，与 `browser.*` 并存过渡；**去重本次会话执行**（2026-08-11 拍板）——`page.*` 能完成的指令，LLM 不会倾向使用 `browser.*`，冗余无意义，去重阶段删除。 |
| 5 | **超长页截断分多段发送，坐标按段拆分**（2026-08-11 拍板）：全页截图超限时不再单张硬拼，按段截取、每段独立坐标系统，`page.click` 带段号。 |
| 6 | **存储权限拒绝降级 = 每次 `page.load` 提示重授**（2026-08-11 拍板，选项 c）：拒绝后不落盘，命令结果明确提示重新授权。 |
| 7 | **9880 桥 + browser-mcp-plugin 退役**（2026-08-11 确认）：Agent 侧功能完全可由 am 桥覆盖（审计证据——Shell App UI 不依赖 9880，唯一消费方是外置 BrowserMcpPlugin）。Phase 2 端到端验证后执行退役。 |

## 三、目标架构（三层）

```text
Shell 进程                                   浏览器进程（mengpaw-browser）
┌──────────────────────────┐                ┌──────────────────────────┐
│ Linux 命令通道            │   am 桥        │ RunCommandService         │
│   am startservice ...     │ ─────────────► │   (signature 权限)        │
│ CommandMonitor 白名单校验 │                │ page.* / browser.* 命令集 │
│ 输出落盘 → agent.read     │ ◄───────────── │ 全页截图 / 文本 / 坐标     │
│ Linux grep/head/tail 管道 │   文件回传      │ 落盘 + 分段坐标            │
└──────────────────────────┘                └──────────────────────────┘
        │ 9880 HTTP 桥（过渡保留，Phase 2 后退役）         ▲
        └───────────────────────────────────────────────┘
```

- **调用通道**：Termux 式 `am startservice` 桥（shell 子进程可调）+ 9880 HTTP 桥（过渡期保留，Phase 2 验证后退役，决策 #7）。
- **执行引擎**：浏览器内置命令集（现有 `browser.*` + 新增 `page.*`），输出统一落盘。
- **命令面**：Playwright 语义，LLM 零学习成本。

## 四、Playwright 语义命令面（page.*）

命令名与参数形态对齐 Playwright（LLM 训练语料中最熟悉的浏览器自动化语义），不依赖项目自定义文档。

| 命令 | 语义（对齐 Playwright） | 替代的 browser.* |
|------|------------------------|------------------|
| `page.goto <url> [--wait domcontentloaded\|networkidle]` | 导航，精确等待 `onPageFinished`/JS ready | `nav`、`open` |
| `page.load <url> [--max-height N]` | **半自动合体**：goto + 自动全页截图 + 返回坐标系统（见 §五） | `nav` + `screenshot.full` ×2 往返 |
| `page.screenshot [--full] [--view]` | 全页/视口截图；只回路径 + 尺寸/坐标；超长页按段返回 | `screenshot`、`screenshot.full` |
| `page.click <seg> <x> <y>` ｜ `page.click <css>` | 坐标点击（基于分段截图坐标，超长页带段号）或选择器点击 | `coord.click`、`click` |
| `page.fill <css> <text>` | 填表/输入 | `type` |
| `page.content [--grep P] [--regex] [-i] [--head N] [--tail N]` | 提取正文 + 内置过滤 | `content` |
| `page.text <css>` / `page.attr <css> <name>` | 元素文本 / 属性 | `text`、`attr` |
| `page.wait_selector <css> [--timeout N]` | 等待元素出现 | `wait.selector` |
| `page.scroll <x> <y>` / `page.scroll_by <dy>` | 绝对/相对滚动 | `coord.scroll`、`scroll` |
| `page.eval <js>` | 执行 JS | `eval` |
| `page.url` / `page.title` | 当前页信息 | `url`、`title` |
| `page.back` / `page.forward` | 历史导航 | `back`、`forward` |
| `page.select <css> <value>` / `page.submit <css>` / `page.check` / `page.uncheck` | 表单操作 | 同名 |
| `page.screenshot.element <css>` | 元素截图 | `screenshot.element` |
| `page.key <key>` | 按键 | `key` |

**上下文优化（治膨胀）**：
- 命令名/参数 = Playwright，提示词无需解释自定义语义；
- `--grep/--head/--tail` 内置过滤（参照 fs.grep），大输出不进上下文；
- 截图只回路径 + 尺寸/坐标信息（决策 #3），超长页按段返回、坐标按段拆分（决策 #5）。

## 五、半自动模式 page.load

```text
page.load <url> [--max-height 30000]
```

执行流程（浏览器进程内一次完成）：

1. 导航到 URL；
2. 精确等待 `onPageFinished`（替代 `browser.nav` 的固定 1.5s，支持 `--wait` 语义）；
3. 自动全页缝合截图（复用 `FullPageScreenshotter`，max-height 默认 15000，坐标缩放状态由它持有）；**超长页截断分多段**（每段 ≈ 视口高，段数上限 30，超出截断并标注 `partial:true`）；
4. 每段截图分别落盘（公共目录），生成**按段坐标系统**（段号/宽/高/缩放比）；
5. 返回结构化结果。

返回格式（Agent 拿到即可直接操作）：

```text
## page.load 完成
URL: https://example.com
标题: Example
段数: 3 (partial: false)
段 1: /storage/emulated/0/MengPaw/截图存档/page_20260811_121500_seg1.png (1080 × 2400, 缩放 0.44)
段 2: /storage/emulated/0/MengPaw/截图存档/page_20260811_121500_seg2.png (1080 × 2400, 缩放 0.44)
段 3: /storage/emulated/0/MengPaw/截图存档/page_20260811_121500_seg3.png (1080 × 1800, 缩放 0.44)
坐标系统: page.click <seg> <x> <y> 按段内截图坐标，框架自动还原为页面坐标
```

Agent 循环：**看图（段图）→ `page.click <seg> x y` → `page.scroll_by` → 再看图**。发链接与截图合成一步，往返减半以上。

## 六、Termux 式调用（am 桥）

**浏览器侧新增 `RunCommandService`**：

- Manifest：`exported="true"` + `android:permission="com.mengpaw.permission.RUN_BROWSER_COMMAND"`（signature 级——只有同签名 Shell 可调，第三方 App 拒绝，优于 Termux 的 `allow-external-apps`）；
- action：`com.mengpaw.browser.RUN_COMMAND`；
- intent extra：
  - `com.mengpaw.browser.RUN_COMMAND_ARGUMENTS`：`-c,<page.*|browser.* 命令串>`；
  - `com.mengpaw.browser.RUN_COMMAND_OUTPUT`：输出文件路径（调用方指定）；
  - `com.mengpaw.browser.RUN_COMMAND_BACKGROUND`：后台执行（默认 true）。
- 执行走内置命令集，输出落盘到指定路径，然后 Shell 用 `agent.read` / Linux `grep/head/tail` 管道读回处理。

**Shell 侧（kernel）**：

- `CommandMonitor` 识别新形态 `com.mengpaw.browser.RUN_COMMAND_ARGUMENTS`：payload 提取后**白名单校验 = 必须是浏览器命令集内命令**（`page.*`/`browser.*`），不是任意 shell——比 Termux 严格，安全面收敛；
- `am` 命令本身在 Linux 通道放行（非提权/非高危），浏览器形态由 CommandMonitor 兜住；
- 与 Linux 命令通道关系：浏览器操作融入 shell 命令流，输出落盘后可直接接管道（主线程已实现的 `\|` 能力）。

**9880 HTTP 桥**：过渡期保留；Phase 2 端到端验证后退役（决策 #7）。退役范围：浏览器侧 `McpHttpServer` + `BridgeTokenProvider` + 外置 `browser-mcp-plugin`（mengpaw-connectors）+ kernel 文档表/提示词节/PluginManager 命名空间特例，同步清理。

## 七、安全设计

| 层 | 措施 |
|---|------|
| 调用方认证 | `RunCommandService` signature 级权限；9880 桥 token（签名级 ContentProvider）不变 |
| 命令面 | am 桥 payload 白名单 = `page.*`/`browser.*` 命令集，拒绝任意 shell 命令 |
| 输出路径 | 输出文件路径由调用方指定，限制在公共输出目录（`MengPaw/` 下），禁止系统路径 |
| 现有防线 | `CommandMonitor`（BLOCK/CONFIRM + 元字符）+ `SecurityPolicy` 继续兜底 shell 侧 |
| 权限 | 浏览器新增 `MANAGE_EXTERNAL_STORAGE`，首次打开弹窗申请（决策 #2）；**拒绝降级 = 每次 `page.load` 提示重授**（决策 #6） |

## 八、browser.* 去重清单（决策 #4）

去重原则：`page.*` 能完成的指令，`browser.*` 冗余删除；`page.*` 不覆盖的保留。

| browser.* 命令 | page.* 替代 | 处置 |
|----------------|-------------|------|
| `nav`、`open` | `page.goto` | 去重 |
| `content` | `page.content`（含过滤） | 去重 |
| `screenshot`、`screenshot.full`、`screenshot.element` | `page.screenshot` 系 | 去重 |
| `coord.click`、`click` | `page.click` | 去重 |
| `type` | `page.fill` | 去重 |
| `text`、`attr` | `page.text`、`page.attr` | 去重 |
| `wait.selector` | `page.wait_selector` | 去重 |
| `coord.scroll`、`scroll` | `page.scroll`、`page.scroll_by` | 去重 |
| `eval` | `page.eval` | 去重 |
| `url`、`title`、`back`、`forward` | `page.url`、`page.title`、`page.back`、`page.forward` | 去重 |
| `select`、`submit`、`check`、`uncheck` | `page.*` 同名 | 去重 |
| `key` | `page.key` | 去重 |
| `batch` | `page.load` 半自动 + 多 Action 并行替代 | 过渡期保留，后去重 |
| `q` | 快捷方式，LLM 不熟 | 过渡期保留 |
| `tabs`、`tab`、`tab.open`、`tab.close`、`tab.all` | 标签页管理，`page.*` 不覆盖 | **保留** |
| `storage`、cookies 系 | `page.*` 不覆盖 | **保留** |
| `viewport`、`userAgent`、`version` | 设置类 | **保留** |

去重执行时同步四源：`BuiltinCommandIndex` / 系统提示词 / 开发指南 §5.3 / `self.tools`（运行时自动），并清理对应测试与幽灵引用。**本次会话完成（决策 #4）**。

## 九、实施步骤（后续执行）

1. **Phase 1 · 命令面**（mengpaw-browser）：新增 `page.*` 命令组（含 `page.load` 合体命令、`--grep/--head/--tail` 过滤、截图只回路径、超长页按段拆分 + 按段坐标）；浏览器 Manifest 加 `MANAGE_EXTERNAL_STORAGE` + 首次打开权限弹窗 + 拒绝后每次 page.load 提示重授（决策 #5/#6）。
2. **Phase 2 · am 桥**（mengpaw-browser + kernel）：新增 `RunCommandService` + signature 权限；`CommandMonitor` 识别浏览器 RUN_COMMAND 形态 + payload 白名单。**端到端验证后退役 9880 桥 + browser-mcp-plugin（决策 #7）**。
3. **Phase 3 · 去重**：按 §八 删除被覆盖的 `browser.*` 命令，四源同步 + 测试清理。**本次会话完成（决策 #4）**。
4. **Phase 4 · 验证**：`:mengpaw-kernel:test` 全量 + `browser` 模块编译 + 真机自测（半自动截图、分段坐标、am 桥端到端）。

**执行状态 (2026-08-11)**：
- ✅ Phase 1：`BrowserPlaywrightCommands`（page.* 22 条）+ `FullPageScreenshotter.captureSegments/tapSegment/scrollToSegmentY` + `BrowserStorage` 公共目录 + 首启权限弹窗（`BrowserActivity.ensureStoragePermission`）
- ✅ Phase 2：`RunCommandService`（signature 权限 + 命令前缀白名单 + 输出路径公共目录限制）+ `CommandMonitor.detectReinterpret` 浏览器形态（`Reinterpret.BrowserCommand` 白名单放行 / `BlockedBrowserCommand` 拒绝）
- ✅ Phase 3：browser.* 45→23 条（被 page.* 覆盖的 22 条删除），四源同步（BuiltinBrowserPlugin / PromptEngine 中英提示词节 / 开发指南 §3.4+§5.3 / AgentCliDocTables + 5 个浏览器技能文档），幽灵引用清理
- ✅ Phase 4（机器部分）：`:mengpaw-kernel:test` 558 用例全绿；`:mengpaw-browser:compileDebugKotlin` + `assembleDebug` 通过（v0.8.0-debug.apk）
- ⏳ 真机自测（用户）：page.load 半自动截图 / page.click 分段坐标 / am 桥端到端 / 存储权限弹窗 → 通过后执行 9880 桥 + browser-mcp-plugin 退役

## 十、待定与风险

- **分段粒度**：超长页分段阈值（max-height 15000 内单张全页、超限按段；段高 ≈ 视口高，段数上限 30）与 `partial` 标注语义，实施时验证体验后微调；
- **`page.load` 超长页**：分段截取后 30 段上限仍不足的极端页面，返回 `partial:true` + 已截段，不再报错（决策 #5）；
- **退役衔接**：9880 桥退役前需对齐 mengpaw-connectors 发布节奏（browser-mcp-plugin 下版标记废弃/删除），避免已装插件残留引用（决策 #7）；
- **存储权限**：拒绝降级已定（每次 page.load 提示重授，决策 #6）；系统设置页跳转的引导文案与频率（同一次会话不重复弹）实施时定稿。
