# Changelog

## v0.8.4 (2026-07-22) — 会话管理增强 + 引擎可靠性修复 + UI 体验升级

### 会话管理 (核心)
- **跨会话历史搜索**: 新增 `agent.sessions <keyword>` CLI 命令，搜索 `session_history.json` 中所有已保存会话
- **会话切换恢复**: 新增 `switchToSession()` — 点击历史记录自动切换 Agent 并恢复完整消息
- **独立会话文件**: 每个会话独立保存到 `sessions/{id}.json`，切换 Agent 不再丢失当前会话
- **原子写入**: 所有会话 JSON 先写 `.tmp` 再 rename，防止进程崩溃导致文件损坏
- **损坏自动恢复**: 错误状态的会话文件不再恢复（如崩溃结尾），损坏文件自动删除
- **自动编号标题**: 新会话标题从首条消息改为 `会话 #N`，按 Agent 独立计数

### 引擎可靠性
- **安全命令白名单**: 19 个只读/列表命令（`agent.docs`/`agent.sessions`/`self.stats` 等）不触发循环检测
- **循环检测优化**: 阈值 3→5 次，窗口 5→8 条，减少误判
- **引擎状态重置**: 每次提交任务前强制 `resetLoopDetection()` + `stop()` 旧引擎，防止跨任务状态污染
- **全面状态同步**: 异常捕获时同步重置 `isRunning`/`inputEnabled`，杜绝 UI 卡死

### UI 体验
- **消息区自适应宽度**: 平板 80%、手机 95%，内容居中显示
- **思考完成自动定位**: Agent 输出结束时自动滚动到输出顶部 + 聚焦输入框
- **滚动安全防护**: `safeScrollTo()` 边界检查，消除 `animateScrollToItem` 越界崩溃
- **侧边栏智能体头像**: 从 `avatar.png` 加载真实头像，回退首字母圆形
- **框架通讯录**: 从 `ACP_TRUSTED` 目录加载真实框架联系人
- **智能体显示名称**: 侧栏读取 `profile.md` 中 `name` 字段，非目录名
- **历史侧栏简化**: 移除修复按钮，滑动操作精简为压缩+删除
- **Markdown 渲染增强**: 新增 Heading 块支持（`##` 标题语法）

### 插件市场
- **plugins.json 重构**: 数据结构优化，支持更细粒度的插件元信息
- **市场 UI 更新**: PluginMarketScreen 和 PluginViewModel 联动改进

### 浏览器
- **版本号统一**: mengpaw-browser 使用 `gradle.properties` 统一版本号
- **扩展清单更新**: `maxCoreVersion` 升至 0.8.1

### 设计系统
- **MengPawVersion**: 新增版本信息工具类，`CORE_VERSION` 统一使用 `MengPawVersion.FRAMEWORK`
- **ArcoTheme**: 色值 Token 增强
- **MarkdownText**: 支持 Heading 块 + 代码块改进

### 构建
- **统一版本源**: `gradle.properties` 中的 `mengpaw.version=0.8.4` 为所有模块版本号唯一来源
- **mengpaw-design-system**: 新增 `mengpaw-kernel` 依赖
- **mengpaw-kernel**: 新增 `kotlinx-serialization-json` 依赖

### 测试
- AdaptiveLlmProviderTest / PromptEngineTest: 适配安全命令白名单和循环检测新阈值

### 发行
- Shell: v0.8.0 → v0.8.4 (versionCode=30→31)
- Kernel: CORE_VERSION 0.8.0 → 0.8.4
- 27 文件修改, +1198 / -622 行

## v0.7.2 (2026-07-22) — Android 13-17 兼容性专项修复 + 国内 OEM 适配

### Android 版本兼容 (P0)
- **Android 14+**: 所有 `registerReceiver()` 添加 `RECEIVER_NOT_EXPORTED` 标志 (否则 `IllegalArgumentException`)
- **Android 14+**: 新增 `FOREGROUND_SERVICE_DATA_SYNC` 权限声明 (否则 `SecurityException`)
- **Android 14+**: 新增 `SCHEDULE_EXACT_ALARM` 权限声明 (减少 TriggerEngine OEM 惩罚)
- **Android 13+**: `ShellService.start()` 添加 try/catch 处理 `ForegroundServiceStartNotAllowedException` (广播接收器后台启动限制)
- **Android 15+**: ShellService 增加 `specialUse` 前台服务类型，缓解 6 小时超时 + OEM 白名单
- **Android 9+**: 新增 `usesCleartextTraffic=true` 支持自建 HTTP 端点

### OEM 兼容 (华为/小米/OPPO/vivo/荣耀)
- ShellService 通知渠道从 `IMPORTANCE_LOW` 升至 `IMPORTANCE_DEFAULT` (国产 OEM 隐藏低优先级通知 → 前台服务被误杀)
- 前台服务声明同时注册 `dataSync` + `specialUse` 双类型，覆盖不同 OEM 的权限检查策略

### 诊断增强
- `MainActivity.onCreate()` 增加全局 `UncaughtExceptionHandler`，崩溃栈写入 `filesDir/crash.log`

### 文档
- `LESSONS.md` 新增 4 条 Android 版本兼容教训
- `docs/crash-prevention-guide.md` 新增 OEM 兼容性附录

## v0.7.1 (2026-07-22) — 闪退修复：原子写入 + 损坏恢复 + 协程保护

### 闪退修复 (P0 严重)
- **非原子写入 → 崩溃循环**: `TriggerEngine.save()` / `AgentViewModel.saveSessionHistory()` / `AgentDocManager` 等 9 处 `File.writeText()` 改为原子写入 (tmp + rename)，避免进程崩溃时文件部分写入导致二次崩溃
- **损坏文件自动清理**: `TriggerEngine.load()` / `AgentViewModel.loadSessionHistory()` 解析失败时主动删除损坏文件，确保下次从干净状态重建
- **协程异常保护**: `AgentViewModel.submitTask()` 协程体包裹 try/catch，捕获 `OutOfMemoryError` 等 Error 类型，优雅降级到错误消息而非进程崩溃
- **触发器启动时序**: `TriggerEngine.start()` 从 `MainActivity.onCreate()` 移至 Composable 中 `onFire` 设置后，防止启动窗口期静默消耗触发
- **bootstrap 快速路径**: `AgentDocs.bootstrap()` 先检查 `soul.md` 是否存在，存在则跳过 7 次文件系统操作

### 文档更新
- `LESSONS.md` 新增 5 条教训 (原子写入 / 损坏恢复 / 协程保护 / 启动时序 / bootstrap 优化)
- `docs/crash-prevention-guide.md` 新增 §3.4 原子写入模式 + v0.7.1 案例

## v0.6.2 (2026-07-21) — Agent 逻辑修复 + API 模型更新 + DeepSeek 解析修复

### Agent 引擎修复 (P0 严重)
- **DreamEngine**: dream 命令 agentId/sessionId 混淆 (ctx.sessionId→agentName)；PROFILE.md → profile.md 大小写匹配；formatBytes MB 单位错误 (÷GB→÷MB)；dreamLog 写入缺失
- **AgentDocManager**: Memory.md 索引结构损坏 — enforceLimits 覆写丢失分隔符；parseMemoryRecords ID 解析用错误索引重建；updateMemory split limit=3 导致每次新记录覆盖所有旧记录 (数据丢失)
- **Goal 模式**: runWithGoal 每轮调用 run() 创建新 session 丢失前轮上下文，现提取 runReActLoop 共用 + 累积前轮结果
- **snipStaleToolResults**: 只追加 system 消息不实际修改旧 tool result，上下文未压缩
- **Pipeline 缓存**: 每次命令执行重建 CommandRegistry，现缓存仅在插件变更时重建

### LLM 解析修复 (P0 严重)
- **非 ReAct 模型兼容**: DeepSeek-Chat 等无思考链模型返回自然语言时，parse() 误判为无 Action → 循环空转至 maxSteps。新增规则 3：无 Action/FinalAnswer 标记时直接视为 Final Answer
- **RubricGate 改进**: Goal 模式每轮均调用 LLM 评估（之前仅当含 "Final Answer:" 时才评估）

### API 模型更新
- **OpenAI**: gpt-4o→gpt-4.1 (默认), 新增 gpt-4.1-mini/o4-mini/gpt-4.1-nano
- **DeepSeek**: 模型列表保持 deepseek-chat/reasoner，API 路径不变
- **Kimi**: moonshot-v1-8k→kimi-latest (默认), 新增 kimi-thinking, 支持 kimi.com 域名
- **GLM**: 新增 glm-4.5
- **Qwen**: 新增 qwen3-plus/qwen3-max
- **Grok**: grok-2→grok-3 (默认)
- **端点检测**: detectProviderType + CacheStrategy 同步更新 Kimi 新域名

### 其他修复
- AgentDocManager.pluginManager 注入 (regenerateCliDoc 不再创建空 PluginManager)
- RubricEvaluator.evaluate() 死代码清理
- 开发文档更新至 v0.6.2

### 发行
- Shell: v0.6.1 → v0.6.2 (vc=12→13)
- Kernel: CORE_VERSION 0.6.0 → 0.6.2
- 6 文件修改, 14 Bug 修复, 84/89 测试通过

## v0.6.1 (2026-07-21) — 内核能力补全 + 安全加固

### Agent 引擎
- **Goal/Mission/Mission+ 内置模式**: `AgentEngine.runWithGoal()` + `runWithMission()`，参考 QwenPaw GoalMode 架构
- **RubricGate**: LLM 自动评估目标完成度 (SATISFIED/NEEDS_REVISION)，替代简单步数限制
- **Mission 模式**: LLM 拆解 → Worker 独立 ReAct → Verifier 验证 → 最终报告
- **Provider 热更新**: `updateLlmProvider()` 支持设置页 Per-Agent 模型实时切换
- 移除 `plugin-agent-loop` 和 `plugin-agent-mission` (模式已内置)

### Agent 自省扩展 (self 命名空间)
- `self.tools [namespace]` — 按命名空间列出所有可用命令
- `self.time [format]` — 获取当前时间 (支持 iso/date/time/timestamp)
- `notify.message <text>` — Agent 主动推送消息到聊天
- `notify.banner <text> [--level]` — Agent 推送横幅 (info/success/warn/error)
- `self` 命名空间从 9 命令扩展至 14 命令

### 文件搜索
- `fs.grep` — 文本/正则内容搜索 (含上下文行，参考 QwenPaw grep_search)
- `fs.glob` — 文件通配符模式匹配 (参考 QwenPaw glob_search)
- `fs` 命名空间从 8 命令扩展至 10 命令

### 技能系统
- 4 个默认 Skills (make-skill / make-plan / guidance / source-index)，参考 QwenPaw 移植
- 首次运行自动播种，已有 skill 时跳过

### 安全修复
- **API Key 持久化**: `savedProviders` JSON 加密存储到 Vault，启动自动恢复，支持多供应商
- **Vault 安全加固**: Keystore 失效时降级到 InMemoryPreferences (绝不明文)
- **ProGuard**: Shell + Browser 均添加 `-keep com.google.crypto.tink.**` 规则
- **Android 权限**: 6→17 项，覆盖 sys.location/camera/apps + 插件安装 + 音频/振动

### Bug 修复
- 修复引擎使用 SimulatedLlmProvider 导致 "System check complete" 假回复
- 修复 `plugin.install` DexClassLoader 失败时静默返回 ok
- 修复 `plugin-plugin` 幽灵条目在 KEEP_AWAKE
- 修复 `Icons.Default` deprecated warning (×3)

### 开发者体验
- 编译问题速查表 (10 项已知陷阱) 记录到 `docs/compilation-issues.md`
- 6 项 Settings 待处理项全部解决 (`docs/settings-pending.md`)
- 开发文档全量重构至 v0.6.1

### 发行
- Shell: v0.6.0 → v0.6.1 (vc=11→12)
- 插件: 25→23 (loop/mission 已内置), fs 8→10 命令, self 9→14 命令

### 设置页重构
- **iPad 式双栏布局**: 平板侧栏 240dp + 内容区，手机侧栏 68dp 图标条
- **三大分区**: 01 Agent 设置（选用） / 02 框架设置（配置） / 03 系统设置
- **API Key 归属框架层**: Agent 只需从已配置的供应商列表中选用模型
- **Per-Agent 模型选择**: 每个 Agent 独立记住选用的供应商和模型，切换即加载
- **Loop 模式**: Goal / Mission / Mission+ 三模式选择，Mission+ 为插件需安装
- **工作区文件**: 实时读取 Agent 的 .md 核心文件，默认 MD 预览
- **定时任务 & 触发器移入 Agent 区**: Cron + Lifetime 管理
- **CLI / 插件 / Tools / Skills 列表**: 全局池（框架）+ 选用列表（Agent），按内置→官方→自建排序
- **安全规则**: 框架信任列表、内核/插件/文件完整性防护开关
- **Token 用量统计**: Canvas 折线图，每日/周/月，按模型分色 + 缓存节省线

### 侧边栏重构
- **左侧栏钉住**: 平板模式持久化显示，手机模式浮层，均不遮盖顶栏
- **右侧栏 QQ 通讯录式层级**: 智能体名称栏可折叠，框架可展开，每栏右侧 [+] 新建
- **框架状态选择器**: 在线/忙碌/离线，Chat 开放但委派策略不同，手动设置或自动切换
- **右侧栏左滑手势**: 修复(蓝)/压缩(橙)/删除(红) 三色动作按钮
- **长按多选**: 批量选中会话 → 删除或取消
- **会话修复**: 自动闭合被截断的 Markdown 语法（\`\`\`、**、*）

### 交互升级
- **发送按钮**: "↑" 箭头飞出/飞入动画，按钮本体不动
- **WowBlue 启动页**: W·O·W 字母弹簧弹入 + BLUE 滑入 + 轨道粒子环绕动画
- **通知栏常驻**: App 启动即前台服务，防止系统杀进程
- **圆形头像**: 侧栏 Agent 加载 avatar.png，回退首字母圆形

### 设计系统合规
- **全域色值标准化**: 11 个 UI 文件硬编码 Color(0x...) 清零，全部替换为 ArcoColors token
- **配色**: Blue6(品牌) / Green6(成功) / Orange6(警告) / Red6(危险) / Gray*(中性)

### 新增文件
- `SplashScreen.kt` — 启动动画
- `TokenStatsCollector.kt` — Token 用量收集器
- `TokenChart.kt` — Canvas 折线图组件
- `docs/settings-pending.md` — 后续待处理项清单

### 发行
- Shell: v0.5.0 → v0.6.0 (vc=10→11)

---

## v0.5.0 (2026-07-21) — 微内核拆分 + 架构重构

### 架构重构
- **mengpaw-kernel**: 新增纯 Kotlin/JVM 微内核模块 (44 文件)，零 Android 依赖，可脱离 Android 独立编译和 JVM 测试
- **mengpaw-core**: 从 46 文件精简至 6 文件，仅保留 Android 适配层 (Vault/IntegrityGuard/StorageMonitor/SysExecutor/桥接)
- **插件同级**: 内置 sys 命名空间通过 additionalNamespaces 注入，与 25 个外挂插件地位相同，均只依赖 kernel
- **插件依赖切换**: 全部 25 个插件从依赖 mengpaw-core 改为依赖 mengpaw-kernel
- **3 个 Android 解耦**: LlmRequestBuilder (java.util.Base64), AcpServer/TriggerEngine (KernelLog), PluginExecutor (DexClassLoader 反射)
- **2 个新接口**: IntegrityProvider (kernel) / KernelLog (可替换日志)

### 模块变更
- **移除 mengpaw-tv**: 预存资源 XML 错误，彻底删除 TV 模块
- **新增 DataPathsInitializer / AndroidLogger**: Android 桥接模式替代直接耦合
- **测试迁移**: 9 个测试移至 kernel，JVM 秒级运行 (83/88 PASS)

### 文档
- **开发文档全量重构**: 基于微内核架构重写，修正全部数据
- **README 同步更新**: 项目结构树、架构图、LLM Provider 列表

### 发行
- Shell: v0.4.0 → v0.5.0 (vc=9→10)
- Browser: v0.3.0 → v0.4.0 (vc=5→6)

---

## v0.4.0 (2026-07-21) — 全项目安全加固 + UI/AI 层深度修复

### 安全修复 (38 项)
- **WebView 安全**: 禁用混合内容, SSL 证书错误拒绝, 移除 JS Bridge eval() 暴露, URL scheme 白名单, 文件访问限制, 第三方 Cookie 禁用
- **网络安全**: NetPlugin SSRF 防护 (URL scheme 白名单 + 私有 IP 黑名单 + 禁用重定向)
- **文件系统**: FsPlugin 路径沙箱 (canonicalFile + workDir 限制 + 符号链接检测 + 50MB 读上限)
- **API Key**: Vault 加密存储替代明文 SharedPreferences (Shell/TV/DreamWorker), 禁用 allowBackup, Sanitizer 密钥脱敏
- **ACP 加密**: 设备指纹改用 Build.FINGERPRINT SHA-256 哈希, Android 10+ 兼容
- **插件安全**: APK 签名验证 (安装前), ProcessBuilder 命令白名单, 插件市场 HTTPS

### Agent 层修复 (11 项 CRITICAL)
- AgentEngine: snipStaleToolResults 步数修复, stop() 真正取消协程, planExecute 跨步骤上下文, compactStuck 不泄漏
- SessionManager: compressIfNeeded 快照防并发丢失
- PromptEngine: Final Answer 只在最后位置返回, 循环检测
- LlmRequestBuilder: buildRequest 正确传递 cache_control 和 _image 字段
- AgentDocs: 统一小写文件名 (与 AgentDocManager 一致)
- DreamEngine: 延迟路径获取 (避免 object 初始化时固化)
- PluginManager: 生命周期回调, install 允许覆盖更新

### UI 层修复 (15 项 CRITICAL)
- 聊天界面: 消息响应式绑定修复 (_messages 断开), 滚动索引修复, LazyColumn key
- 浏览器: 标签页切换修复 (key activeTabId), WebView 泄漏修复 (DisposableEffect), 协程泄漏修复
- 设置: ProviderCard 折叠状态, triggers 响应式刷新, 暗色模式颜色修复
- TV: MainScope → lifecycleScope 泄漏修复
- BigBangPopup: 重复词选择 Bug, selectedIndices 越界修复
- PadPlugin: 闪烁动画修复 (InfiniteTransition), R8 安全 Intent, Manifest 服务声明

### 按钮系统 (25 项)
- 12 个空操作按钮获得实际功能 (文件选择器/相机/电池优化/测试连接/广告拦截持久化)
- 插件按钮声明系统: PluginUiButton + ButtonPlacement 枚举, 未安装插件自动隐藏按钮
- 5 个 Stub/Mock 按钮修复 (testConnection 真实 API 调用, 翻译/升级/DevPlugin)

### 基础设施
- R8 混淆启用 (Shell + Browser) + ProGuard 规则
- 版本号: Shell 0.3.4→0.4.0, Browser 0.2.2→0.3.0
- 审计方法论固化到 memory/bug-audit-methodology.md

---

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
