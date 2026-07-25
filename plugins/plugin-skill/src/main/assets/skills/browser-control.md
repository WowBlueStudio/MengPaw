---
name: browser-control
description: 浏览器操控命令完整参考手册 — 导航、交互、提取、等待、标签页、高级、存储、截图、配置
enabled: true
category: browser
---
# 浏览器操控命令参考

## 快速上手

```
1. 打开页面:      browser.open <url>
2. 等待加载:      browser.wait.nav
3. 提取内容:      browser.text "h1"       # 提取标题
4. 截图:          browser.screenshot
5. 关闭:          browser.tab.close
```

## 一、导航 (Navigation)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.open` | `browser.open <url>` | 打开新页面，可选 `newTab:true` 在新标签页打开 |
| `browser.nav` | `browser.nav <url>` | 当前标签页导航到指定 URL |
| `browser.back` | `browser.back` | 返回上一页（历史记录后退） |
| `browser.forward` | `browser.forward` | 前进到下一页（历史记录前进） |
| `browser.url` | `browser.url` | 获取当前页面的 URL |
| `browser.title` | `browser.title` | 获取当前页面的标题 |

**示例：**
```
browser.open https://example.com
browser.nav https://www.baidu.com/s?wd=天气
browser.back
browser.forward
let $url = browser.url
let $title = browser.title
```

## 二、页面交互 (Page Interaction)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.click` | `browser.click <selector>` | 点击指定元素 |
| `browser.type` | `browser.type <selector> <text>` | 在输入框中输入文本（带 focus + 清空） |
| `browser.scroll` | `browser.scroll <px>` / `browser.scroll to <selector>` | 滚动页面，正数向下、负数向上；也可滚动到指定元素 |
| `browser.select` | `browser.select <selector> <value>` | 选择下拉框选项 |
| `browser.submit` | `browser.submit <selector>` | 提交表单 |
| `browser.check` | `browser.check <selector>` | 勾选复选框或单选按钮 |
| `browser.uncheck` | `browser.uncheck <selector>` | 取消勾选复选框 |

**示例：**
```
browser.type "#search-input" "MengPaw"
browser.click "#search-button"
browser.select "#lang-select" "zh-CN"
browser.scroll 500           # 向下滚动 500px
browser.scroll to "#footer"  # 滚动到页脚
```

## 三、内容提取 (Content Extraction)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.content` | `browser.content` | 获取完整页面 HTML |
| `browser.text` | `browser.text <selector>` | 提取元素的纯文本内容 |
| `browser.attr` | `browser.attr <selector> <attribute>` | 提取元素的指定属性值 |
| `browser.visible` | `browser.visible <selector>` | 检查元素是否可见（返回 true/false） |
| `browser.enabled` | `browser.enabled <selector>` | 检查元素是否可用（返回 true/false） |

**示例：**
```
let $html = browser.content
let $title = browser.text "h1"
let $link = browser.attr "a.main-link" "href"
let $isVisible = browser.visible ".popup"
let $isEnabled = browser.enabled "#submit-btn"
```

## 四、等待 (Waiting)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.wait` | `browser.wait <ms>` | 等待指定毫秒数 |
| `browser.wait.selector` | `browser.wait.selector <selector> [timeoutMs]` | 等待元素出现在 DOM 中，可选超时（默认 5000ms） |
| `browser.wait.nav` | `browser.wait.nav [timeoutMs]` | 等待页面加载完成（监听 load 事件） |

**示例：**
```
browser.wait 2000                    # 等 2 秒
browser.wait.selector "#results" 10000  # 等结果出现，最多 10 秒
browser.wait.nav                     # 等页面加载
```

## 五、标签页管理 (Tab Management)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.tabs` | `browser.tabs` | 列出所有标签页 ID 和标题 |
| `browser.tab` | `browser.tab <indexOrId>` | 切换到指定标签页（按索引或 ID） |
| `browser.tab.open` | `browser.tab.open <url>` | 在新标签页中打开 URL |
| `browser.tab.close` | `browser.tab.close [indexOrId]` | 关闭指定标签页（默认关闭当前页） |
| `browser.tab.all` | `browser.tab.all` | 获取所有标签页的 URL 和标题详情 |
| `browser.preload` | `browser.preload <url>` | 后台预加载页面（不切换到该页） |

**示例：**
```
browser.tab.open "https://example.com"
browser.tabs                # 查看所有标签页
browser.tab 0               # 切到第一个标签页
browser.tab.close           # 关闭当前标签页
browser.preload "https://next-page.com"   # 后台预加载
```

## 六、高级操作 (Advanced)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.eval` | `browser.eval <jsCode>` | 在页面上下文中执行 JavaScript，返回结果 |
| `browser.batch` | `browser.batch <commands>` | 批量执行多个浏览器命令（减少 IPC 开销） |
| `browser.q` | `browser.q <selector>` | 快速查询元素并返回其基本信息（标签、文本、属性） |
| `browser.inject` | `browser.inject <cssOrJs>` | 向页面注入 CSS 或 JavaScript |
| `browser.diff` | `browser.diff` | 对比当前页面与上次截图的差异 |

**示例：**
```
browser.eval "document.title"
browser.eval "JSON.parse(localStorage.getItem('user'))"
browser.inject "$('body').css('background', '#fff')"
browser.batch ["browser.scroll 200", "browser.click '#load-more'"]
browser.q ".article"                   # 快速探查元素结构
```

## 七、存储 (Storage)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.cookies` | `browser.cookies` | 获取当前页面的所有 Cookie |
| `browser.cookies.set` | `browser.cookies.set <name>=<value> [domain]` | 设置 Cookie |
| `browser.cookies.clear` | `browser.cookies.clear` | 清除当前域的所有 Cookie |
| `browser.storage` | `browser.storage <type> [key]` | 读取 localStorage 或 sessionStorage（type: local/session） |

**示例：**
```
browser.cookies                      # 列出所有 cookie
browser.cookies.set "token=abc123" ".example.com"
browser.cookies.clear
browser.storage local "userPrefs"    # 读取 localStorage
```

## 八、截图 (Screenshots)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.screenshot` | `browser.screenshot [filename]` | 截取当前视口截图 |
| `browser.screenshot.full` | `browser.screenshot.full [maxHeight]` | 全页滚动截图（默认最大高度 15000px，最高 30000px） |
| `browser.screenshot.element` | `browser.screenshot.element <selector> [filename]` | 截取指定元素的截图 |
| `browser.coord.click` | `browser.coord.click <x> <y>` | 基于坐标的精确点击（配合全页截图使用） |
| `browser.coord.scroll` | `browser.coord.scroll <y>` | 基于坐标的滚动定位 |

**示例：**
```
browser.screenshot "page.png"
browser.screenshot.element ".card:first-child" "card.png"
```

## 九、快速点击 (Quick Click)

### 1. Quick Click 工作流

全流程示意 — 基于全页截图 + 视觉分析的坐标点击方案：

```
browser.screenshot.full                # 步骤1: 截取全页截图（含全部滚动内容）
# → 将截图传递给 Vision 模型分析坐标
browser.coord.click 850 1200           # 步骤2: 根据分析结果进行坐标点击
browser.coord.scroll 1500              # 步骤3: 滚动到目标区域验证
browser.screenshot.full                # 步骤4: 再次截图确认操作结果
```

> **核心流程：** screenshot.full（全页截图 → Vision 分析获取坐标）→ coord.click（坐标点击）→ coord.scroll（坐标滚动验证）

### 2. 命令参考

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.screenshot.full` | `browser.screenshot.full [maxHeight]` | 全页滚动截图，自动拼接完整页面。`maxHeight` 默认 15000px，最大 30000px |
| `browser.coord.click` | `browser.coord.click <x> <y>` | 基于页面绝对坐标进行点击。`x` 为水平像素，`y` 为垂直像素 |
| `browser.coord.scroll` | `browser.coord.scroll <y>` | 滚动到指定的垂直坐标位置，使目标区域进入视口 |

**参数说明：**
- `browser.screenshot.full` 的 `maxHeight` 用于限制截图最大高度，避免超长页面导致超时。超出部分会被裁剪。
- `browser.coord.click` 使用页面绝对坐标（document 坐标系），不受当前视口滚动位置影响。引擎自动处理偏移计算。
- `browser.coord.scroll` 将页面滚动到指定 `y` 坐标处，滚动后可通过 `browser.screenshot` 截取视口确认。

### 3. 使用场景

| 场景 | 说明 | 推荐方式 |
|------|------|----------|
| **视觉模型分析** | 使用 GPT-4V / Claude Vision 等模型分析页面截图，根据视觉输出进行点击操作 | Quick Click（截图 → 分析 → 坐标点击） |
| **Canvas / WebGL 页面** | 传统 CSS 选择器无法定位 Canvas 内部的图形元素 | Quick Click（坐标定位） |
| **Shadow DOM** | Shadow DOM 封闭作用域内元素无法通过常规选择器访问 | Quick Click（视觉定位 + 坐标点击） |
| **验证码处理** | 需要将验证码截图传递给识别服务，再点击特定坐标区域 | Quick Click（截图 → OCR → 坐标点击） |
| **SPA 动态 DOM** | 单页应用中 DOM 频繁重建，选择器引用可能失效 | Quick Click（坐标点击不受 DOM 变化影响） |

### 4. 与传统方式对比

| 维度 | 传统 CSS 选择器方式 | Quick Click 坐标方式 |
|------|-------------------|---------------------|
| **定位原理** | 基于 DOM 结构和属性匹配元素 | 基于页面视觉坐标定位 |
| **稳定性** | 依赖 DOM 结构，重构/动态加载时可能失效 | 不受 DOM 变化影响，位置不变即可 |
| **适用范围** | 标准 HTML 元素、可访问 DOM | Canvas、Shadow DOM、非标准渲染 |
| **速度** | 直接操作 DOM，快速 | 需要截图 + 分析，相对较慢 |
| **精度** | 精确匹配元素边界 | 依赖坐标计算的准确性 |
| **依赖** | 无需额外模型 | 需要 Vision 模型配合分析 |
| **学习成本** | CSS 选择器语法 | 需要理解坐标系 |

**何时使用传统方式：**
- DOM 结构稳定且可预测
- 元素有明确的 ID、类名或属性标识
- 需要高频率重复执行的自动化操作

**何时使用 Quick Click：**
- 页面包含 Canvas / WebGL / SVG 等非标准渲染
- 元素位于 Shadow DOM 内部
- 使用 Vision 模型进行视觉理解驱动的操作
- 需要绕过 DOM 选择器限制的场景

### 5. 配置

通过 **设置 → 智能体协同 → 快速点击** 进行配置：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| 快速点击开关 | 启用/禁用 Quick Click 功能 | ON |
| 全页截图最大高度 | 限制全页截图的最大高度（px） | 15000 |
| 截图质量 | 截图压缩质量（1-100） | 100 |

**配置示例：**
```
# settings.toml / config.yaml
[quick_click]
enabled = true
max_screenshot_height = 20000
screenshot_quality = 95
```

### 6. 故障排除

| 问题 | 可能原因 | 解决方案 |
|------|----------|----------|
| **页面过长导致超时** | 页面内容过多，截图高度超出限制 | 降低 `maxHeight` 参数，或分区域多次截图 |
| **拼接错位** | 动态内容（如无限滚动、动画）导致截图拼接时位移 | 截图前暂停动画：`browser.eval "window.scrollTo(0,0)"` 重置滚动位置 |
| **坐标偏移** | 页面缩放比例、CSS transform、iframe 等因素导致坐标不准确 | 确认 viewport 设置为 100% 缩放；检查是否在 iframe 内部操作 |
| **点击无响应** | 目标元素被覆盖、未加载或不可交互 | 先使用 `browser.wait.selector` 确保元素可交互；或增加等待时间 |
| **滚动未到位** | 动态加载内容改变了页面高度 | 使用 `browser.coord.scroll` 后等待内容加载：`browser.wait 500` |

---

## 十、配置 (Configuration)

| 命令 | 语法 | 说明 |
|------|------|------|
| `browser.viewport` | `browser.viewport <width>x<height>` | 设置视口大小（如 `375x812` 模拟手机） |
| `browser.userAgent` | `browser.userAgent <uaString>` | 设置自定义 User-Agent |
| `browser.version` | `browser.version` | 获取浏览器引擎版本 |

**示例：**
```
browser.viewport 375x812            # 模拟 iPhone X
browser.viewport 1920x1080          # 模拟桌面
browser.userAgent "Mozilla/5.0 ..."
```

## CSS 选择器速查表

| 模式 | 说明 | 示例 |
|------|------|------|
| `#id` | ID 选择器 | `#username` |
| `.class` | 类选择器 | `.btn-primary` |
| `tag` | 标签选择器 | `div`, `a`, `img` |
| `parent > child` | 直接子元素 | `#nav > a` |
| `parent descendant` | 后代元素 | `div p` |
| `el[attr]` | 带属性的元素 | `input[type="text"]` |
| `el:first-child` | 第一个子元素 | `li:first-child` |
| `el:nth-child(n)` | 第 n 个子元素 | `tr:nth-child(2)` |
| `el:contains(text)` | 包含特定文本的元素 | `button:contains("提交")` |
| `el:visible` | 可见元素 | `.modal:visible` |

## 常见工作流

### 工作流 1：登录流程
```
browser.open "https://example.com/login"
browser.type "#username" "user@example.com"
browser.type "#password" "mypassword"
browser.click "#login-btn"
browser.wait.nav
browser.text ".welcome-message"     # 验证登录成功
```

### 工作流 2：搜索结果提取
```
browser.open "https://www.baidu.com/s?wd=MengPaw"
browser.wait.selector ".result"
let $titles = browser.eval "document.querySelectorAll('.result .t').forEach(e => console.log(e.innerText))"
browser.screenshot "search-result.png"
```

### 工作流 3：多标签页协作
```
browser.tab.open "https://page1.com"
browser.tab.open "https://page2.com"
browser.tab 1
browser.wait.nav
browser.tab 0
browser.screenshot
```
