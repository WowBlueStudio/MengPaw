# MengPaw_Browser_skills.md — 第三方 AI Agent 控制手册

> 适用对象: 需要控制 MengPaw.Browser APK 的第三方 AI Agent 框架 / 开发者。
> 版本: v0.8.x · 2026-08-17 · 本文件独立成文, 不依赖 MengPaw Shell。
> 开发者视角的架构细节见同目录 `MengPaw.Browser 开发文档.md`。

## 1. 快速上手(3 步)

```bash
# 1. 唤起浏览器(Android 设备上执行)
am start -a com.mengpaw.action.OPEN_URL --es url "https://example.com"

# 2. 探测桥状态(PC 经 adb forward 或设备本机)
curl http://127.0.0.1:9880/health
# → {"ok":true,"status":"online","tools":6,"openMode":true}

# 3. 调用命令(开放模式下免认证; 安全模式需 Bearer token, 见 §6)
curl -X POST http://127.0.0.1:9880/mcp \
  -d '{"tool":"page.content","args":{"head":"20"}}'
```

> 提示: 第三方进程若在设备外(PC), 用 `adb forward tcp:9880 tcp:9880` 后连本机 9880。

## 2. 唤醒浏览器

浏览器未运行(或被杀)时, 9880 桥不可达, 必须先唤起:

| Intent | 用途 |
|--------|------|
| `am start -a com.mengpaw.action.OPEN_URL --es url <url>` | 唤醒 + 打开 URL (推荐) |
| `am start -a android.intent.action.VIEW -d <http(s)://...>` | 系统级打开网页 |
| 桌面图标 / `MAIN + LAUNCHER` | 手动唤醒 |

唤起后 9880 桥自动启动 (BrowserActivity onCreate), 无需手动启用。

## 3. 命令面总览

命令分三组: `page.*`(22 条, Playwright 语义, 主用)、`browser.*`(23 条, 保留能力)、
原生 6 工具(兼容旧调用)。

### 3.1 page.* — 主命令面

| 命令 | 说明 |
|------|------|
| `page.load <url> [--max-height N]` | **半自动合体**: 导航 + 全页分段截图 + 坐标系统 (推荐起手) |
| `page.goto <url> [--wait domcontentloaded\|networkidle]` | 导航 + 精确等待 |
| `page.screenshot [--full] [--view]` | 截图; 只回路径 + 尺寸/坐标; 超长按段 |
| `page.screenshot.element <css>` | 元素截图 |
| `page.click <seg> <x> <y>` / `page.click <css>` | 段图坐标点击 / 选择器点击 |
| `page.fill <css> <text>` | 输入 |
| `page.select <css> <value>` / `page.submit <css>` | 下拉选值 / 提交表单 |
| `page.check` / `page.uncheck` | 勾选/取消 |
| `page.content [--grep P] [--regex] [-i] [--head N] [--tail N]` | 提取正文 + 过滤 |
| `page.text <css>` / `page.attr <css> <name>` | 元素文本 / 属性 |
| `page.wait_selector <css> [--timeout N]` | 等待元素出现 |
| `page.scroll <x> <y>` / `page.scroll_by <dy>` | 绝对/相对滚动 |
| `page.eval <js>` | 执行 JS |
| `page.url` / `page.title` | 当前页信息 |
| `page.back` / `page.forward` | 历史导航 |
| `page.key <key>` | 按键 (Enter/Tab/ArrowDown/单字符) |

### 3.2 browser.* — 保留命令

- 标签页: `tabs` / `tab` / `tab.open` / `tab.close` / `tab.all`(最多 4 标签)
- 效率: `batch`(分号分隔批量执行) / `q`(快捷选择器) / `inject` / `diff` / `preload`
- 等待: `wait` / `wait.nav`
- 存储/Cookie: `storage` / `cookies` / `cookies.set` / `cookies.clear`
- 对话框: `dialog.accept` / `dialog.dismiss`
- 查询/设置: `visible` / `enabled` / `viewport` / `userAgent` / `version`

### 3.3 原生 6 工具(旧 MCP 工具名)

`browser_navigate` / `browser_screenshot` / `browser_click` / `browser_type` /
`browser_extract` / `browser_eval`(参数 `url`/`selector`/`text`/`script`)。

## 4. 半自动循环(推荐工作流)

```text
page.load https://example.com        # 一次完成: 导航 + 分段截图 + 坐标系统
page.click 1 320 480                  # 看图 → 按段图坐标点击 (段 1)
page.scroll_by 800                    # 滚动后 page.screenshot --full 核对
page.content --grep "价格" --head 20  # 过滤提取, 不进上下文
```

返回格式示例:

```text
## page.load 完成
URL: https://example.com
段数: 3 (partial: false)
段 1: /storage/emulated/0/MengPaw/截图存档/page_..._seg1.png (1080 × 2400, 缩放 0.44)
坐标系统: page.click <seg> <x> <y> — 框架自动还原页面坐标
```

## 5. 调用通道

### 5.1 9880 HTTP 桥(第三方主通道)

- 端点: `127.0.0.1:9880`
- `GET /health`: 在线探测, 返回 `openMode` 当前模式
- `POST /mcp`: body `{"tool":"<命令>","args":{...}}`, 返回工具结果 JSON
- 参数 map 键: `url`/`selector`/`text`/`x`/`y`/`script`/`value`/`css`/`n`/`seg`/
  `dy`/`key`/`domain`/`grep`/`head`/`tail`/`maxHeight`/`timeoutMs`/`full`/`view`...

### 5.2 am 桥(仅同签名 Shell)

```bash
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.goto https://example.com" \
  [--es com.mengpaw.browser.RUN_COMMAND_OUTPUT /storage/emulated/0/MengPaw/out.txt]
```

signature 权限 `RUN_BROWSER_COMMAND` 仅同签名应用可调; 输出落盘后自行读取。

## 6. 开放模式(第三方接入必读)

默认安全模式: 9880 桥要求 `Authorization: Bearer <token>`, token 仅同签名 Shell 可获取,
第三方拿不到 → 一律 401。**第三方接入需用户在浏览器设置中开启「开放 MCP 控制」**:

- 开启后 `/mcp` 免 Bearer token, 本机任意进程可直接控制 (Playwright 式)
- 仅回环 `127.0.0.1:9880`, 不暴露局域网; 切换即时生效, 默认关闭
- `/health` 返回 `"openMode":true` 表示已开放
- 安全注意: 开放期间本机任意 App 均可控制浏览器, 请仅在可信环境开启

第三方探测与调用:

```bash
curl http://127.0.0.1:9880/health          # 确认 openMode:true
curl -X POST http://127.0.0.1:9880/mcp \
  -d '{"tool":"page.title","args":{}}'     # 免认证调用
```

## 7. 表单自动化

```text
page.goto https://example.com/login
page.fill #username <用户>
page.fill #password <密码>
page.click button[type=submit]
page.title                                  # 验证跳转, 不要假定成功
```

注意: CAPTCHA 无法自动过 — 引导用户手动完成; 文件上传无 API — 引导用户操作;
输入疑似被清空 → type 后 `page.eval` 验证 value; 敏感凭据不要写进命令历史。

## 8. 网页抓取

```text
page.load https://example.com/list          # 唤醒 + 截图
page.content --head 50                      # 提取结构
page.eval JSON.stringify(Array.from(document.querySelectorAll('a')).map(a=>a.href).filter(h=>h.includes('/article/')))
page.eval var n=document.querySelector('.next');if(n){n.click();'next'}   # 分页
```

抓取策略: 需登录/JS 渲染/反爬强的页面走浏览器(带 cookie/JS 会话); 静态批量抓取可
自行用 HTTP 客户端并发; 403/验证码 → 换浏览器通道重试; 限流 → 逐条间隔。

## 9. 排障速查

| 现象 | 处理 |
|------|------|
| 9880 连不上 / `browser.mcp.status` 离线 | 浏览器未运行 → 先唤起 (§2) |
| `page.load` 提示存储权限 | 未授予「所有文件访问」→ 浏览器首启弹窗或系统设置授权 |
| `page.click` 错位/超界 | 先 `page.screenshot --full` 刷新段图, 用返回的段号 + 坐标 |
| 401 unauthorized | 安全模式且无有效 token → 开启开放模式 (§6) 或从主应用打开浏览器 |
| `WebView not available` | 无打开标签页 → 先 `OPEN_URL` 开页 |
| `Selector not found` | 元素未加载/在 iframe → `page.eval` 探测 DOM, 先等加载 |
| 页面提取为空 | JS 渲染未完成 → `page.goto` 等加载后再 `page.content` |
| 分段截图 `partial:true` | 超长截断 (30 段上限) 属正常 → 按已返回段操作或滚动重截 |

## 10. Playwright 对照(速查)

| Playwright | MengPaw |
|------------|---------|
| `page.goto(url)` | `page.goto <url>` |
| `page.goto + screenshot(fullPage)` | `page.load <url>` |
| `mouse.click(x, y)` | `page.click <seg> <x> <y>` |
| `page.fill(sel, text)` | `page.fill <css> <text>` |
| `page.selectOption()` | `page.select <css> <value>` |
| `page.evaluate(js)` | `page.eval <js>` |
| `page.waitForSelector()` | `page.wait_selector <css>` |
| `page.content()` | `page.content [--grep] [--head]` |
| `page.keyboard.press()` | `page.key <key>` |
| `page.mouse.wheel()` | `page.scroll_by <dy>` |

差异: 选择器仅 CSS(无 XPath); `--wait networkidle` 为近似实现; 截图只回路径;
超长页按段返回, 点击用段号 + 段内坐标。
