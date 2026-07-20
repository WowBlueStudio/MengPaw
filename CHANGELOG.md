# Changelog

## v0.3.0 (2026-07-20) — MengPaw Shell + MP 浏览器 v0.2.0

### 新模块
- **MengPaw TV**: Android TV 启动器替代方案，语音输入+TTS 输出，D-pad 遥控器优化
- **mengpaw-relay.py**: PC/服务器自建大模型中转服务，局域网转发 API 到 Ollama/vLLM

### 新增插件 (10)
- **错误上报** (error-report-plugin): 79 处埋点全量收集，WiFi 自动上传 GitHub/Gitee
- **自动更新** (update-plugin): GitHub Releases 检查+WiFi 自动下载+APK 安装
- **Agent Loop** (agent-loop-plugin): 受控迭代+重复检测+3级干预+完成检查+审计账本
- **Mission** (agent-mission-plugin): Worker+Verifier 子 Agent 协作，独立上下文
- **跨设备推送** (browser-push-plugin): ACP 协议推送网页，TRUSTED 自动/GUEST 审批
- **搜索分析** (browser-search-plugin): Google/Bing/百度/DuckDuckGo 结果提取
- **浏览器 MCP** (browser-mcp-plugin): 6 个 MCP 工具暴露浏览器能力
- **CDP 调试** (browser-cdp-plugin): Chrome DevTools Protocol 仅 debug 构建
- **网页开发套件** (browser-inspector-plugin): 元素选择器+悬停高亮+批注+导出

### 浏览器核心升级
- **BrowserBridge**: Java↔JS 双向桥，Agent 可 click/type/scroll/content/eval 操控页面
- **多标签页控制**: browser.tabs/tab/tab.open/tab.close/tab.all 4 标签页并行
- **效率命令**: browser.nav (导航+提取) / batch (批量) / q (快捷选择器) / inject (持久桥) / diff (增量) / preload (预加载)
- **输入框**: 平板 60%/手机 80% 宽度，回车搜索，→ 按钮统一风格
- **地址栏**: 修复文字裁半问题 (40→44dp)

### 模型系统升级
- **新增 Provider**: Grok (xAI)、火山引擎 (豆包)、OpenModel、Self-Hosted (自建)
- **Provider 总计**: 6→12 (含 CUSTOM)
- **折叠列表**: 设置页 Provider 改为展开式卡片，点击显示模型列表
- **自动拉取**: 选中 Provider 自动调 GET /v1/models 获取远程模型
- **缓存优化**: Grok/火山/OpenModel 加入 CacheStrategy.forProvider()
- **多模态**: LlmRequestBuilder 支持 `_image` 构建 vision message
- **翻译中间件**: 美国模型自动中→英→模型→英→中，节省 ~40% token
- **每 Agent 独立模型**: AgentSession 存自己的 endpoint/model/apiKey，顶栏显示

### UI 改造
- **双侧面栏**: 左侧 Agent+右侧历史，平板双栏常驻，毛玻璃匹配顶栏
- **会话历史**: 自动保存，左滑删除/压缩，已压缩不可继续对话
- **气泡长按**: 撤回+引用+复制+大爆炸+一键分享+保存图片+标注图片
- **新建会话**: 直接创建不弹窗，自动保存当前会话
- **Agent 名称下**: 显示 API 供应商/模型
- **模拟服务**: 彻底移除开关，API Key 为空自动用模拟模式

### BUG 修复 (15+)
- WebView 线程池死锁 (CountDownLatch 5s→2s+降级)
- WebView.destroy() 从未调用 (onDestroy 清理)
- BrowserActivity DataPaths 未初始化
- HttpClient 泄漏 (换 Provider 先关旧的)
- CookieManager Android 14+ 崩溃 (try/catch)
- loadUrl 重载循环 (wv.url ≠ currentUrl 才 reload)
- 模型切换缓存策略不更新 (configureCacheStrategy)
- calibrateTokPerChar 不重置 (updateSystemPrompt)
- compactStuck 跨模型残留 (rebuildSystemPrompt 重置)
- 循环检测命令跨会话泄漏 (resetLoopDetection)
- switchAgent 不停止旧引擎 (stopAgent+isRunning 重置)
- 双 APK 切换分屏返回首页 (launchMode+taskAffinity+onBackPressed)
- 插件市场虚假下载链接 (全部移除+增加 status:builtin)
- 插件版本 1.0.0 假数据 (全部改为 0.1.1)

### 发行
- Shell: v0.2.2 → v0.3.0
- Browser: v0.1.0 → v0.2.0
- 插件总数: 16 → 26
- **DataPaths 路径 Crash**：`/Android/data/...` 硬编码路径在真实设备上不存在 → 改为 `Context.filesDir` 动态初始化
- **文件 IO 全量保护**：所有 `readText()` 调用包裹 try/catch，防止文件损坏闪退
- **EventReceiver 内存泄漏**：新增 `unregister()`，修复永不注销的广播接收器
- **HttpClient 泄漏**：`AgentViewModel.onCleared()` 中关闭所有 Ktor 客户端
- **跨智能体状态串扰**：`isRunning` 从全局共享改为每 `AgentSession` 独立
- **BrowserActivity NPE**：两处 SharedPreferences `!!` 改为安全默认值
- **BigBangPopup NPE**：`ClipboardManager` 强制转型改为 `as?`
- **框架通讯录**：移除假数据，空态显示"你的智能体还没有朋友"

## v0.2.1 (2026-07-20) — MengPaw Shell

> **勘误**：MengPaw 浏览器此前误标为 v1.0.0，实际为首个公开发布版本，已更正为 v0.1.0。

## v0.1.0 (2026-07-20) — MengPaw 浏览器

- 首个公开发布版本
- 版本号更正：此前误标为 v1.0.0

---

## v0.2.1 (2026-07-20) — MengPaw Shell

### 手机 UI 重构
- 顶栏适配系统状态栏 (`statusBarsPadding`)，不再被遮挡
- 顶栏从浅蓝色改为白色毛玻璃质感
- 手机上移除侧边栏按钮（右滑打开），平板保留
- 底栏适配输入法 (`imePadding` + `navigationBarsPadding`)
- 发送按钮 "+" 改为 ↑ 箭头，圆形统一 44dp
- 发送按钮增加 ↑ 飞出动画
- 空输入时按钮使用线性图标

### 智能体系统
- "多 Agent" 重命名为 "智能体"（英文 Agents）
- 初始仅有 MengPaw，其他需新建
- "ACP 通讯录" 重命名为 "框架通讯录"
- 框架支持层级展开：框架 → 智能体
- 可调度框架显示 "已信任" 标识
- 长按智能体名称弹出菜单 → "申请智能体调度权限"
- 新建智能体自动创建 6 个初始化 .md 文件（AGENTS/SOUL/BOOTSTRAP/MEMORY/PROFILE/HEARTBEAT）

### Markdown + Emoji 渲染
- 新增 `MarkdownText` 组件：支持粗体、斜体、行内代码、链接、代码块、表格
- 所有聊天气泡支持全功能 Markdown 渲染
- Agent 消息移除强制等宽字体，Emoji 正常显示
- 长按文本支持系统选择 + 复制
- 新增 "大爆炸" 分词弹窗（BigBangPopup）

### 多会话架构
- AgentViewModel 从单例改为多会话 Map：每个智能体独立持有 AgentEngine + LlmProvider + 消息历史
- 切换智能体自动切换会话，消息历史隔离
- 每个智能体可独立配置模型
- 系统 prompt 包含智能体身份：名称、框架归属、驱动模型

### Agent 引擎升级
- 系统 prompt 注入中英双语 3 组 few-shot 示例（设备查询、文件操作、插件发现）
- ReAct 过程可折叠展示：思考 → 工具调用 → 观察结果
- Max steps 从 settings 传入（之前永远用默认 50）
- 错误信息根据 Agent 语言设置显示中文/英文

### 上下文缓存优化（Reasonix 移植，MIT）
- 四级折叠阈值：50% 软通知 → 60% 裁剪旧工具结果 → 80% 完整折叠 → 90% 强制折叠
- 陈旧工具结果裁剪（snip）：60% 时先改写旧 output 为短标记，避免触发昂贵的摘要 API 调用
- tokPerChar 动态校准：从真实 API usage 反算，替代硬编码 `/3`
- 折叠经济性检查：<400 tokens 跳过
- 卡死检测：连续两次折叠后暂停 + 警告

### 跨模型缓存优化
- DeepSeek：自动前缀缓存（PREFIX_STABLE）
- OpenAI / Kimi / GLM / Qwen：注入 `cache_control` 断点（CACHE_CONTROL）
- 设置界面显示 "已优化 ✓" 标签 + 缓存策略说明

### Dream 梦境模式
- WorkManager + 充电广播：仅接通电源 + Doze 空闲时触发
- LLM 分析：Scroll 索引（62x 压缩）+ 记忆 → 单次 API 调用 → DREAM.md（≤500 字）
- 自动工作区清理：3 天前截图、过期 inbox、空缓存目录
- 存储空间汇报 + dream.log 持久化

### 中间件架构
- `AgentMiddleware` — fun interface，零分配 SAM
- `PostCallMiddleware` — LLM 调用后处理
- `PromptBuilder` — 锚点式 prompt 组装
- `ScrollContext` — LinkedHashMap LRU + 文本冷存储

### 致谢
- 重写 ATTRIBUTIONS.md，严格分离「代码参考」与「灵感来源」
- Reasonix (MIT) / QwenPaw (Apache 2.0) / ReAct
