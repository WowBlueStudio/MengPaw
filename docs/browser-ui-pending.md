# 浏览器 UI 未完成需求

> 最后更新: 2026-07-27

---

## ✅ 全部完成

P0/P1/P2 共 15 项已于 2026-07-27 会话完成。

---

## 🔮 未来发展方向

> 从浏览器当前能力出发，可演进的方向。按实现难度排序。

### 1. 协同浏览：Agent 旁白 + 标注层

浏览器当前假设是"要么人在用，要么 Agent 在用"。协同浏览让二者同时发生：

- **Agent 旁白模式**: 人浏览网页，Agent 在侧边栏实时提供上下文——"这篇文章引用的论文你上周读过"、"这个产品比京东贵 15%"
- **Agent 标注层**: Agent 在 WebView 上叠加 Canvas 标注——高亮关键段落、划掉虚假信息、标注来源。不修改网页 DOM，纯覆盖层
- **会话式交互**: "这个页面讲了什么" → Agent 回答；"帮我填这个表单" → Agent 执行。交互粒度从 URL 级变成元素级

**关键挑战**: 旁白的实时性——Agent 需要在页面加载后就绪，而非等用户提问。需要页面语义预分析机制。

### 2. CDP 协议完整接入

当前 `plugin-browser-cdp` 仅 `enable` / `status` 两个命令。Chrome DevTools Protocol 完整协议栈 40+ 域：

| 域 | 能力 | Agent 场景 |
|----|------|-----------|
| **Network** | 请求拦截/修改/重放 | Agent 在请求发出前改 header、在响应返回后改写 body |
| **Runtime** | JS 断点/变量抓取/调用栈追踪 | 比现有 `browser.eval` 强一个量级 |
| **Performance** | 性能 trace/帧率/内存 | Agent 自检页面性能并给出优化建议 |
| **DOM** | DOM 树查询/节点高亮 | 精确定位页面元素，配合 screenshot 做视觉验证 |
| **Emulation** | 设备模拟/地理位置/时区 | Agent 模拟不同终端环境测试网页 |

CDP 打开的不是"更多命令"，而是一个完整的浏览器调试协议层。

### 3. Playwright 兼容运行时

现有 `skill.run browser-playwright` 是 skill 级别的浅层映射。Playwright 的核心抽象比裸 WebView 命令高一级：

```
Playwright 思维                WebView 裸命令
─────────────────────    ─────────────────────
page.goto(url)           browser.nav url1; browser.nav url2
page.locator('.btn')     browser.eval "document.querySelector('.btn')"
page.waitFor('.result')  browser.eval + 轮询 + 超时处理
```

长远目标：**MP 浏览器成为 Playwright-compatible runtime**——任何为 Playwright 写的脚本，不经修改就能在 Android WebView 里跑。

**实现路径**: `playwright-core` 的协议层是 CDP——如果能完整接入 CDP（方向 2），就等于间接兼容了 Playwright 的大部分 API。

### 4. 结构化页面理解

现有 `browser.content` 返回全文，但 Agent 真正需要的是**语义结构**而非文本：

- **语义切片**: 导航区 / 主内容区 / 评论区 / 侧栏 / 页脚——每块有独立的语义标签，Agent 按需读取
- **视觉+文本双通道**: 截图给视觉模型 + accessibility tree 给文本模型。截图解决"这个按钮长什么样"，accessibility tree 解决"这个按钮的语义是什么"
- **增量更新**: 页面动态加载时，只推送变化的语义块，不重传全文
- **交互元素提取**: 自动识别页面中所有可交互元素（按钮/表单/链接），生成结构化清单供 Agent 操控

**实现参考**: `BrowserBridge.kt` 已有的 `accessibility-tree` 提取架构（灵感来自 Kuri），可在此基础上做语义标注。

## 🚫 暂不可行

| 项目 | 原因 |
|------|------|
| 右击支持 | Compose BOM 2024.12.01 缺少 onPointerEvent API |
