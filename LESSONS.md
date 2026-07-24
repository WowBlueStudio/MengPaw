# 教训记录

> 每次犯错都记下来，防止再犯。按日期倒序。

---

## 2026-07-24 — v0.14.1 验证反馈修复

67. **IconButton 是键盘焦点泄漏的元凶**
    - 场景：v0.14.0 修复了头像的 clickable，但底部栏 `+` 新建会话按钮仍是 `IconButton`。Enter 后焦点漂移到它 → 每次 Enter 都创建新会话。
    - 修复：底部栏所有操作按钮统一 `Box + pointerInput + detectTapGestures`，彻底消除键盘可聚焦性。
    - 教训：项目中应禁用 `IconButton` 用于输入区域附近的按钮，全部改用 pointerInput。

66. **数据源的赋值顺序就是 UI 的渲染顺序**
    - 场景：`PluginViewModel.refreshMarketplace()` 先设 `_marketplacePlugins.value` 再调 `registerBuiltins()`。StateFlow combine 在赋值瞬间触发 → 此时 pluginClassRegistry 还没更新 → 内置插件显示为"可下载"。
    - 修复：`registerBuiltins()` 移到赋值之前。
    - 教训：StateFlow 赋值前确保所有依赖数据已就绪。

65. **DexClassLoader 类名不能靠猜**
    - 场景：`loadPluginJar()` 硬编码 `PluginMain` 类名，但实际类名是 `TavilyPlugin`、`HermesPlugin` 等 PascalCase 模式。
    - 修复：尝试多候选项 `{Ns}Plugin` → `PluginMain`，DexClassLoader 失败时注册元数据降级。
    - 教训：动态加载的类名必须有注册表，不能靠字符串拼接猜测。

64. **空会话是无声垃圾**
    - 场景：用户点新建会话但没发消息就切换 → `sessions/{id}.json` 为空 → `session_history.json` 有 record → 永远残留。
    - 修复：`cleanupOrphanSessions()` 加 `messageCount ≤ 0` 自动清理。
    - 教训：任何创建操作都必须考虑"创建了但没用"的清理路径。

---

## 2026-07-24 — v0.14.0 全链路审计修复

63. **功能闭环必须用方法论检验，不能靠直觉**
    - 场景：插件系统表面"能用"，但 12 问审计暴露了 Agent 不知道下载源、安装后无指引、内置插件可卸载、错误无建议等 8 个缺口
    - 教训：任何子系统上线前用 `docs/audit-methodology.md` 三层十二问过一遍，P0 不过不发布
    - 产出：方法论文档 + 插件/会话/GitHub/键盘 四个系统全部闭环

62. **会话 ID 不能只存在内存里**
    - 场景：`current_session.json` 只存消息数组不存 sessionId，重启后 restore 被迫造新 ID `"sess_restored"`，原 ID 变成孤儿 → 重复
    - 修复：`current_session.json` 改为 `{"sessionId":"...","messages":[...]}` 格式，ID 跨重启保持
    - 教训：任何需要跨进程/跨重启保持的 ID 都必须持久化，不能依赖内存

61. **`Class.forName()` 在 Android R8 Release 下不可靠**
    - 场景：10 个捆绑插件用反射加载，`Class.forName("com.mengpaw.plugin.xxx.XxxPlugin")` 在 R8 混淆后类名可能变化 → 只安装成功 2-3 个
    - 修复：所有插件是编译时依赖，直接 `FrameworkPlugin()` 实例化，零反射
    - 教训：编译时已知的类绝不用 `Class.forName()` — 直接 new

60. **重试次数需要区分错误类型，参照成熟项目**
    - 场景：`maxRetries=19` 导致 API 失败时等待 ~27 分钟，401 认证错误也被重试 20 次
    - 修复：参照 QwenPaw `retry_chat_model.py` — `maxRetries=5`，永久错误 {400,401,403} 立即失败
    - 教训：重试策略三要素 — 次数上限、错误分类、退避上限。缺一不可

59. **双源不够，要三级回退**
    - 场景：GitHub → Gitee 双源在两者都挂时仍无解（校园网/企业网可能同时封 GitHub 和 Gitee）
    - 修复：`PluginMarketplaceClient` + `UpdatePlugin` 都加了 ghproxy.com 第三级回退
    - 教训：关键外部依赖至少三级回退，`net.proxy` 命令给 Agent 自主解决能力

58. **方法论要写成文档，否则每次从零开始**
    - 场景：#1 插件审计花了 3 个 Agent 探索，#2 GitHub 又重来一遍类似的套路
    - 修复：提炼出 `docs/audit-methodology.md` 三层十二问，#4 会话系统审计复用，效率显著提升
    - 教训：重复两次以上的工作流就值得文档化

---

## 2026-07-24 — v0.12.0 全量审校 + v0.12.1 收尾

61. **LLM 预训练知识会覆盖系统提示词**
    - 场景：系统提示写「执行模式有 /Mission /Research /Translate /Silent」，用户问「有什么执行模式」→ Agent 回答「Normal、Deep、Dream」
    - 后果：Agent 对自身功能认知完全错误，用户困惑
    - 改进：① 换用 LLM 训练数据中没有的术语（「斜杠命令」而非「执行模式」）② 加否定语句「没有 Normal/Deep/Dream 模式」③ 加明确行为指令「用户问模式时列出这四种」
    - 原则：**系统提示中功能名称要和通用 AI 术语区分开，否则 LLM 会用预训练知识覆盖。**

62. **会话切换丢失消息的根本原因**
    - 场景：用户跳出对话→切换回旧会话→提示「暂无已保存的消息记录」
    - 根因链：`currentSessionId` 初始为空（第一条消息前不分配 ID）→ 自动保存检查 `isNotBlank()` 跳过 → 消息只在内存中 → `switchToSession` 存到临时文件而非会话记录文件 → 切换回时加载失败
    - 改进：① `ensureSessionId()` 首次保存时自动分配 ID ② `switchToSession` 保存到 `currentSessionId` 而非临时文件 ③ `saveCurrentSession` 自动创建 SessionRecord
    - 原则：**任何涉及持久化的 ID 必须在数据产生前分配，不能等"第一次显式保存"。**

63. **重命名枚举值必须 grep 所有引用（包括字符串常量）**
    - 场景：`ExecutionMode.DREAM` → `SILENT`，代码引用全部更新，但 `PanelOrderStore` 默认值是字符串 `"dream"` → `mapNotNull` 匹配不到 `"silent"` → `/Silent` 从面板消失
    - 改进：grep 时搜索枚举值名称的小写形式（字符串常量、JSON、文件名）
    - 原则：**枚举值可能以字符串形式出现在配置/持久化/JSON 中，grep 只搜代码引用是不够的。**

64. **Compose `widthIn(min)` 在 Row 中不保证列对齐**
    - 场景：`TableTextView` 用 `widthIn(min = chars * 7.dp)` 设列宽 → 实际渲染宽度取决于 Row 分配 → 表头和数据行列不对齐
    - 改进：固定列宽用 `Modifier.width()`，不用 `widthIn(min)`
    - 原则：**Row 中需要列对齐的场景，必须用 `width()` 指定精确宽度。**

65. **系统提示里写死的技能列表需要和实际播種的技能一一对应**
    - 场景：新增 `execution-modes` / `dream-engine` 等 skill，但 Agent 不知道它们存在 → 用户问功能，Agent 说不知道
    - 改进：系统提示中增加「发现更多能力」区块，列出 `skill.ls` 和 `skill.run <name>` 作为自发现入口
    - 原则：**被动文档（skills/assets）必须有主动发现机制（系统提示/引导命令），否则等于不存在。**

66. **视图数据持久化时要注意旧版本升级兼容**
    - 场景：`panel_order.json` 存了 `["dream"]`，代码已改为 `["silent"]` → 旧用户升级后面板少一项
    - 改进：load 时过滤掉不在当前枚举中的无效值，或默认值兜底

## 2026-07-23 — v0.11.3 收尾 + 开发文档重构

58. **版本号公式的边界条件不可忽视**
    - 场景：0.9.1→0.10.0, versionCode 由 `replace(".","").take(3)+"0"` 计算
    - 后果：0.10.0→"0100"→100, 小于 0.9.1 的 910 → 安装被拒绝 downgrade
    - 改进：`split(".").let { major*1000 + minor*100 + patch*10 }` → 0.10.0=1000 > 0.9.1=910
    - 原则：**任何基于字符串截取的版本号公式都不可靠。数学分解才是唯一正确答案。**

59. **Release 只靠 Tag 不会出现在 GitHub/Gitee 页面上**
    - 场景：git tag 推了，仓库里看不到 Release
    - 后果：用户找不到下载链接，CHANGELOG 不可见
    - 改进：`gh release create <tag> --title "..." --notes "..."` 显式创建
    - 原则：**Tag = 版本标记, Release = 用户可见的发布页面。两步缺一不可。**

60. **嵌套滚动的黄金法则：一个方向一个 scroll**
    - 经历链条：AnimatedVisibility 闪退 → 加 heightIn → 还是闪退 → 去外层 scroll → 还是闪退 → 去 LazyColumn → 终于稳定
    - 最终方案：`nestedScroll` 参数让 MarkdownText 感知环境
    - 原则：**排查嵌套滚动崩溃时，逆序排查——从最内层开始逐层去掉 scroll，直到找到冲突层。**

## 2026-07-23 — v0.11.2 嵌套滚动崩溃 + commonmark AST 转换

53. **LazyColumn 在嵌套滚动环境中是毒药，不是优化**
    - 场景：`MarkdownText` 用 `LazyColumn` 优化长文档，License/Attribution/Workspace 等多处调用
    - 崩溃：`AnimatedVisibility` 内 LazyColumn → 高度动画过渡中无固定高度 → Compose 测量死锁 → 白屏/闪退
    - 修复：统一回退 `Column + verticalScroll`。5000 字符以内的文档用 Column 完全流畅，LazyColumn 只适合根级别独立滚动
    - 原则：**LazyColumn ≠ 无脑优化。凡是有嵌套滚动（AnimatedVisibility、另一个 ScrollState、LazyColumn item 内）一律用 Column。**

54. **`weight(1f)` 在 `horizontalScroll` 内列宽为零**
    - 场景：`TableView` 用 `Modifier.weight(1f)` 等分列宽
    - 后果：`horizontalScroll` 提供了无限水平空间 → `weight` 无法计算 → 所有列为 0 宽 → 灰色空块
    - 修复：`widthIn(min = 字符数 × 7.dp)` 替代 `weight(1f)`，按内容计算最小列宽
    - 原则：**`weight` 依赖固定父容器宽度，`horizontalScroll` 打破了这一点。滚动容器内用 `widthIn(min)`。**

55. **commonmark AST 遍历：必须递归扁平所有后代，不能浅层 while**
    - 场景：`convertTable` 用 `while (row = row.next)` 浅层遍历 `TableBlock` 的直接子节点
    - 后果：`TableBlock → TableHead → TableBody → TableRow` 结构，`TableBody` 被跳过 → 数据行全部丢失 → 空表格
    - 修复：`walkRows` 递归遍历所有后代，匹配 `TableHead/TableBody/TableRow/TableCell` 四种节点
    - 原则：**AST 树不是列表。`firstChild/next` 只给一级兄弟。深层节点必须递归进 `firstChild`。**

56. **通知渠道 `deleteNotificationChannel` 在前台服务运行时被系统拒绝**
    - 场景：`ShellService.onCreate()` → `deleteNotificationChannel()` → `SecurityException`
    - 后果：服务重启时崩，用户看到闪退
    - 修复：先 `getNotificationChannel` 检查是否已存在 + `try/catch SecurityException` 吞异常
    - 原则：**前台服务跑起来后，通知渠道是锁死的。要改渠道参数必须等下次冷启动。**

57. **`AnimatedVisibility` + `verticalScroll` + `MarkdownText(verticalScroll)` = 三层嵌套滚动**
    - 场景：Workspace 文件展开，外层 `verticalScroll`（AnimatedVisibility 内容），内层 `MarkdownText` 自带 `verticalScroll`
    - 修复链条：先加 `heightIn(max)` 限高 → 去掉外层 `verticalScroll` → 最终去掉 `MarkdownText` 的 `LazyColumn` 统一用 `Column`
    - 原则：**嵌套滚动容器是 Compose 第一杀手。一个方向只允许一个 `verticalScroll`。**

## 2026-07-23 — v0.11.0 线程架构优化 + Markdown 引擎重构

49. **手写 Markdown 解析器是个坑，越挖越深**
    - 场景：`parseMarkdown()` 手写了 300 行正则/逐行扫描，支持标题/段落/代码块/引用/列表/表格
    - 后果：每加一种语法（删除线、脚注、嵌套列表）就要加一套解析逻辑；表格渲染创建数十个嵌套 Composable → ANR
    - 改进：`commonmark-java` 单次 AST 解析替代所有手写代码，表格改为单 `Text` 等宽对齐渲染
    - 原则：**文本解析用成熟库（commonmark/markwon），不要手写。手写解析器 = 永远少一种语法 + 永远有性能坑**

50. **Column 在长文档下是性能杀手，LazyColumn 才是正确答案**
    - 场景：`MarkdownText` 用 `Column { blocks.forEach { ... } }` 渲染所有块
    - 后果：ATTRIBUTIONS 63 行 = ~200 Composable 同时进组合树 = 主线程卡死
    - 改进：`blocks.size >= 30` 切换 `LazyColumn` + stable key，只组合可见块
    - 原则：**超过 30 个 item 的静态列表必须考虑 LazyColumn。Column 不是"简单就安全"，是"少才安全"**

51. **文件 IO 在 Compose 组合线程是隐式炸弹**
    - 场景：`AgentDocs.recallMemory()` 和 `appendMemory()` 在 `viewModelScope.launch`（默认 Main 线程）中被调用
    - 后果：用户发消息 → 读 memory.md → 主线程阻塞 → ANR
    - 改进：所有 AgentDocs 调用点包裹 `withContext(Dispatchers.IO)`
    - 原则：**ViewModel 的 `launch` 默认在主线程。任何文件 IO 必须显式切换到 Dispatchers.IO**

52. **第三方 Java 库的 Kotlin 互操作陷阱（commonmark 特供）**
    - `node.children` 在 Kotlin 中不可用 — commonmark 用 `getFirstChild()`/`getNext()` 而非 `Iterable`
    - `sumOf` 在 commonmark 的 `Node` 类型推导中有歧义 — 需用 `.map{}.sum()` 替代
    - `FencedCodeBlock.info` / `TableBlock.header` 等属性在不同版本签名不同 — 需显式导入精确类型
    - 原则：**引入 Java 库后，先在 Kotlin 文件里写 5 行测试，确认属性/方法名的 Kotlin 映射正确**

## 2026-07-23 — v0.10.0 框架协议 + 侧边栏交互

44. **versionCode 公式必须覆盖多位数版本号**
    - 场景：`mengpaw.version = "0.10.0"`，旧公式 `replace(".","").take(3)+"0"` 得 `"010"+"0"=100`
    - 后果：versionCode 100 < 旧版 0.9.1 的 910 → `INSTALL_FAILED_VERSION_DOWNGRADE`
    - 改进：`split(".").let { major*1000 + minor*100 + patch*10 }` → 0.10.0 = 1000 > 910
    - 原则：**版本号公式必须考虑跨位边界，不能依赖固定位数截取**

45. **无线调试端口每次重开会变，需要 mDNS 自动发现**
    - 场景：平板无线调试端口从 43381 变到 45053，手动重连失败
    - 后果：每次设备重启或超时就要用户手动提供新端口
    - 改进：`adb mdns services` 自动扫描局域网设备，无需用户手动查端口
    - 原则：**ADB 连接信息记到 memory 文件，优先用 mDNS 发现而非用户手动输入**

46. **Swipe 手势的物理直觉 = 手指移动方向，非内容移动方向**
    - `detectHorizontalDragGestures` 的正值方向 = 手指从左往右（内容往右移）
    - 但用户直觉是"往右滑 = 打开右边的东西" → 需求是右滑打开右侧栏
    - 最终设计：右滑(drag>0)→左侧栏，左滑(drag<0)→右侧栏（手指从哪边来，打开哪边的抽屉）
    - 原则：**手势交互先做原型让用户试，方向感不是代码问题，是设计问题**

47. **卸载插件必须物理删除目录 + grep 清零引用**
    - 场景：PAD 插件从 settings.gradle.kts 移除，但引用散落在 5 个文件中
    - 后果：编译失败、运行时崩溃、grep 结果被废弃代码污染
    - 改进：`grep -r "PadPlugin\|plugin-pad\|pad-plugin" --include="*.kt" --include="*.kts"` → 逐文件清理 → `rm -rf plugins/plugin-pad`
    - 原则：**删除 = settings.gradle 移除 + 所有引用 grep 清零 + 物理删除目录，三步缺一不可**

48. **`remember` 不能放在 `if` / `when` 条件分支内**
    - 场景：`if (showMentionDropdown) { val agents = remember(mentionQuery) { ... } }`
    - 后果：条件变化时 `remember` 被移除重组 → 状态丢失 → 列表闪烁
    - 改进：`remember` 提到条件外，用 key 控制刷新
    - 原则：**`remember` / `LaunchedEffect` / `DisposableEffect` 必须在顶层组合，不能在条件/循环内**

41. **mDNS 发现必须持续扫描，不能只扫一次**
    - 场景：NsdManager.discoverServices() 是单次操作，扫描完就停
    - 后果：首次发现后不再更新，2 分钟后 lastSeen 过期 → 所有框架显示离线
    - 改进：GlobalScope 协程每 30s 循环 stopDiscovery()→startDiscovery()，保持 lastSeen 新鲜
    - 原则：**任何"发现"功能必须有持续刷新机制，单次扫描只适合手动触发的场景**

42. **NsdServiceInfo.setAttribute() 是 API 33+，低版本需静默回退**
    - 场景：框架协议的版本/能力/Agent列表通过 mDNS TXT 记录广播
    - 后果：API <33 设备调用 setAttribute 抛 NoSuchMethodError
    - 改进：`if (Build.VERSION.SDK_INT >= 33)` 包裹所有 setAttribute 调用
    - 原则：**使用 API 33+ 方法前必须 SDK_INT 判断，不能假设用户设备版本**

43. **detectHorizontalDragGestures 的方向语义**
    - 正值 = 手指从左往右滑（内容右移），应打开左侧栏
    - 负值 = 手指从右往左滑（内容左移），应打开右侧栏
    - 交互直觉："右滑打开左边，左滑打开右边"

## 2026-07-22 — v0.9.1 品牌焕新 + 扩展功能重构

### 品牌/主题

32. **换主题色不是改一个 hex 就完事**
    - 场景：`ArcoColors.Blue6 = #165DFF → #0E4397`，看似只改一行
    - 后果：项目中有 5 处硬编码 `0xFF165DFF` + 2 处 CSS `#165DFF` + 多处文档注释，遗漏导致不一致
    - 改进：`grep -r "165DFF" --include="*.kt" --include="*.md" --include="*.xml"` 全覆盖
    - 原则：**改品牌色 = 改色板定义 + grep 全局替换所有硬编码 + 重新生成色板梯度 + 验证深色模式**
    
33. **色板梯度不能手动猜，需要算法生成**
    - 场景：Blue1-Blue10 旧色板基于 #165DFF，改主色后手动估算了 5 个梯度
    - 后果：Blue4 (`#5B8BD1`) 作为深色模式 primary，与主色 #0E4397 的对比关系变了
    - 改进：保持 Arco Design 的 HSL 梯度算法（L 从 95→5 均匀分布，H/S 微调），用脚本生成而非手写
    - 原则：**色板用工具/脚本生成，人工只确认视觉效果**

34. **SVG 转 Android Vector Drawable 的坑**
    - 场景：SVG `fill="#0e408d"` 设计稿存为近似色，与指定的 `#0E4397` 不一致
    - 后果：品牌色偏差，设计师困惑
    - 改进：转换前先检查 SVG 中的色值，与标注值不一致时用 sed 替换
    - 注意：Android Vector Drawable 不支持某些 SVG 特性（filter, clip-path, mask），复杂渐变需拆分为多个 path

### 扩展功能重构

35. **Compose `ModalBottomSheet` 重写时注意闭包引用**
    - 场景：面板内的模式按钮需要访问 `activeTags`（来自 ViewModel）和 `inputText`（Compose 状态）
    - 后果：闭包捕获写法不当会导致状态过期或编译错误
    - 改进：使用 `val text = inputText; inputText = ""` 模式在修改前快照，标签通过 ViewModel API 操作
    - 原则：**Compose 状态修改在闭包中先快照再操作，避免 recomposition 过程中状态丢失**

36. **`onValueChange` 中检测 `@` 的简化假设**
    - 场景：用 `lastIndexOf('@')` 检测 @mention 触发
    - 问题：假设光标始终在末尾，多个 `@` 时判断不准
    - 接受：v1 简化实现，常用场景（末尾输入）覆盖良好
    - 改进方向：v2 用 `TextFieldValue` 跟踪光标位置

37. **DREAM 模式不能用主引擎执行**
    - 场景：最初用 `session.engine.run()` 执行 DREAM 任务
    - 后果：引擎 state flow 观察者触发 `isRunning=true`→UI 锁定输入 → 与 "不阻塞 UI" 的设计目标冲突
    - 修复：改用独立协程 + `session.provider.complete()` 直接调用 LLM，不经过 AgentEngine
    - 原则：**后台任务不能用共享引擎，要么独立引擎实例，要么直接 LLM 调用**

38. **文件选择器：`content://` URI 不能直接传给 Agent**
    - 场景：Android `ACTION_OPEN_DOCUMENT` 返回 `content://` URI
    - 后果：Agent 的 `fs` 插件只认文件路径，不认识 content URI
    - 修复：通过 `ContentResolver.openInputStream()` 拷贝到工作区，传绝对路径
    - 注意：50MB 上限检查必须在路径插入前完成，否则路径残留

### 状态管理

40. **Android FileProvider：三步缺一不可**
    - 场景：相机拍照需要 `FileProvider.getUriForFile()` 生成 content URI
    - 崩溃：`IllegalArgumentException: Couldn't find meta-data for provider` — 缺少 Manifest `<provider>` 声明
    - 根因：只写了 Kotlin 代码调用 `FileProvider.getUriForFile()`，没在 AndroidManifest.xml 注册 provider，也没创建 `res/xml/file_paths.xml`
    - 必须三步：① `res/xml/file_paths.xml` 定义可访问目录 → ② AndroidManifest `<provider>` 注册 → ③ 代码中 `getUriForFile(context, authority, file)`
    - **这不是第一次**：之前 `self.avatar`、`sys.camera` 等功能也因同样原因崩溃过。FileProvider 需要 Manifest 注册是 Android 基础，但 Compose 开发时容易忘记
    - 原则：**任何用到 `FileProvider`/`ContentProvider` 的功能，第一件事就是检查 Manifest 是否有对应 `<provider>` 声明**

39. **`try` 块中定义的变量在 `catch` 中不可见**
    - 场景：`savedLoopMode` 和 `modePrefix` 在 `try { }` 内声明，`catch` 中引用
    - 后果：编译错误 "Unresolved reference"
    - 修复：移到 `try` 块之前声明
    - 原则：**需要在 catch/finally 中使用的变量，声明在 try 前面**

---

## 2026-07-22 — v0.9.0 安全架构建模 + MD 模板文件化

### 安全架构建模

26. **假开关比没有开关更危险**
    - 场景：设置页三个安全开关（内核完整性/插件完整性/文件完整性），看起来给了用户控制权
    - 后果：插件完整性 `integrityCheckEnabled` 从未被任何代码读取——纯假开关；文件完整性 IntegrityGuard 从未实例化，NoOp 空实现——形同虚设
    - 改进：移除所有开关，保护始终强制启用；IntegrityGuard 接入 AgentEngine → Pipeline 指令链
    - 原则：**安全功能要么不做，要做就必须真正接入执行链。假开关制造虚假安全感。**

27. **接口定义后必须验证实现类是否被实例化**
    - 场景：`IntegrityProvider` 接口在 kernel 中定义，`IntegrityGuard` 在 core 中实现，`Pipeline` 调用 `securityPolicy.validateIntegrity()`
    - 后果：SecurityPolicy 默认构造用 NoOpIntegrityProvider（永远返回 null），IntegrityGuard 从未被 new，所有路径保护是空操作
    - 改进：grep 验证每个接口实现类的实例化位置，确保不是 dead code
    - 原则：**写完接口→找实现→确认实例化→确认调用链。四步缺一不可。**

### MD 模板文件化

28. **提示词模板独立于源码，用 assets 存放**
    - 场景：智能体 7 个 MD 文件的内容硬编码在 AgentDocs.kt 的 `"""...""".trimIndent()` 中（~270 行）
    - 后果：改一个字要改 Kotlin 源码→重新编译；字符串拼接在每次新建智能体时执行→卡顿
    - 改进：MD 文件放入 `assets/agent-templates/zh/`，首次启动复制到只读路径，新建智能体时直接文件复制（~1ms）
    - 原则：**所有提示词/模板/文档内容放在 APK assets 中作为独立文件，源码只负责文件复制逻辑。**

29. **MD 模板的三路径模型**
    - `assets/agent-templates/` → APK 内（改模板需重建 APK）
    - `{filesDir}/agent-templates/` → 只读运行时路径（Agent CLI 不可写）
    - `{filesDir}/Agent文档/{name}/` → 工作区（Agent 可自由修改）
    - 原则：**模板→只读→工作区，三层隔离，每层权限明确。**

### 历史债务清理

30. **CHANGELOG 声称移除不等于代码真移除**
    - 场景：v0.8.0 CHANGELOG 写 "PadPlugin 移除"，但 plugin-pad 仍在 settings.gradle.kts 中注册且 bundle 在 Shell APK 中
    - 后果：文档和代码不一致，开发者困惑
    - 改进：声称移除某个模块后，必须验证 `settings.gradle.kts` 中的 `include()` 和 `build.gradle.kts` 中的 `implementation()` 都已删除
    - 原则：**任何"移除"操作后，grep 验证引用是否清零。**

31. **废弃代码目录应物理删除**
    - 场景：plugin-agent-loop 和 plugin-agent-mission 自 v0.6.1 起已从 settings.gradle.kts 移除，但目录仍在磁盘上
    - 后果：grep 搜索结果被废弃代码污染，新人困惑
    - 改进：废弃模块从 settings.gradle.kts 移除时同步删除目录
    - 原则：**从构建系统移除 = 从磁盘删除。不留僵尸代码。**

---

## 2026-07-22 — v0.8.0 架构重构 + 启动崩溃根因

### 架构教训

19. **UI 和运行时必须完全分离 (AgentRuntime 模式)**
    - 场景: UI 层 LaunchedEffect 直接调用 configureLlm → startAgent → LLM 调用 → 主线程阻塞
    - 后果: 多次重构 (IO/Main 线程切换) 均失败, withContext(Main) 导致死锁
    - 改进: 创建独立的 AgentRuntime 单例, 所有 IO/网络/文件操作在此, UI 只观察 StateFlow
    - 原则: **Compose UI 层 = 纯展示 + 事件分派; 运行时层 = 所有副作用。中间用 StateFlow 通信**

20. **Agent 初始化必须是用户驱动的 (QwenPaw 模型)**
    - 场景: 自动在"退出设置"或"粘贴 API Key"时启动 Agent, 导致 ANR 和崩溃
    - 后果: 尝试过: 粘贴即启动 → ANR; 退出设置即启动 → ANR; IO 线程启动 → 死锁
    - 改进: 安装→配置→用户发消息 三阶段。Agent 永远不会自动启动
    - 原则: **用户发第一条消息 = Agent 唯一启动时机。所有自动启动方案都是错的**

21. **`startForeground()` 失败必须 `stopSelf()` — 否则 5 秒后系统杀进程**
    - 场景: Android 14+ `FOREGROUND_SERVICE_SPECIAL_USE` 权限缺失 → `startForeground()` 抛异常 → try/catch 静默吞掉
    - 后果: 5 秒后系统抛 `ForegroundServiceDidNotStartInTimeException` → 进程崩溃。这解释了"运行 10 分钟后崩溃"的模式 (WakeReceiver 每 10 分钟重启服务)
    - 修复: `if (!startForeground(...)) { stopSelf(); return }` — 立即自杀, 不给系统 5 秒后杀进程的机会
    - 原则: **`startForeground()` 的 try/catch 不能只打日志, 必须 `stopSelf()`**

22. **每个 `foregroundServiceType` 都需要对应的权限声明**
    - `dataSync` → `FOREGROUND_SERVICE_DATA_SYNC`
    - `specialUse` → `FOREGROUND_SERVICE_SPECIAL_USE`
    - Android 14+ targetSDK=35 强制执行, 且不同 OEM 在不同时机检查 (荣耀延迟检查)
    - 原则: **添加任何 foregroundServiceType 时, 同时添加对应权限, 不可遗漏**

23. **Compose TextField 发送后必须重新请求焦点**
    - 场景: Enter 发送消息 → `inputText = ""` → TextField 内容为空 → 焦点丢失
    - 后果: 用户需要手动点击输入框才能继续输入, 严重影响体验
    - 修复: `inputFocus.requestFocus()` 在清空文本后立即调用
    - 原则: **任何清空输入框的操作后必须 `requestFocus()`**

24. **会话恢复不只恢复 UI, 还要恢复 Agent 上下文**
    - 场景: 保存会话消息到 JSON → 重启后 UI 显示消息 → 但 Agent 引擎内部 session 是空的
    - 后果: Agent 把新消息当成第一条, 看不到上文 → 用户困惑
    - 修复: `submitTask` 时检测历史消息 → 构建"先前对话"上下文 → 注入到 prompt 中
    - 原则: **保存/恢复必须同时覆盖 1)UI 状态 2)引擎状态 3)文件系统 三个层面**

25. **系统提示词必须反映当前文件结构**
    - 场景: 提示词仍引用 CLI.md (早已迁移到 Tools 系统), Agent 执行无意义的 `agent.cli` 调用
    - 后果: Agent 浪费步数查阅不存在的文件
    - 修复: 系统提示词更新为 `self.tools [ns]` + `agent.docs` 为主要入口
    - 原则: **每次重构文件结构后, 必须同步更新所有 Agent 可见的提示词和文档模板**

---

## 2026-07-22 — v0.7.2 Android 13-17 兼容性修复

### 版本兼容

15. **Android 14: `registerReceiver()` 必须带 `RECEIVER_EXPORTED`/`NOT_EXPORTED` 标志**
    - 场景: `EventReceiver.register()` 和 `PowerConnectionReceiver.register()` 都直接调用 `context.registerReceiver(receiver, filter)`
    - 后果: targetSdk≥34 在 Android 14+ 上抛 `IllegalArgumentException` → 启动即崩溃
    - 改进: `if (Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE) { registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) }`
    - 原则: **所有 `registerReceiver()` 必须检查 API 34 并加标志**

16. **Android 14: 前台服务类型需要专属权限**
    - 场景: ShellService 声明 `foregroundServiceType="dataSync"`，但 manifest 只有通用的 `FOREGROUND_SERVICE`
    - 后果: `startForeground()` 抛 `SecurityException` (部分 OEM 延迟检测 → 运行一段时间后崩溃)
    - 改进: 添加 `FOREGROUND_SERVICE_DATA_SYNC` 权限
    - 原则: **每个 `foregroundServiceType` 都需要对应的 `FOREGROUND_SERVICE_<TYPE>` 权限**

17. **Android 13: `startForegroundService()` 从广播接收器调用可能抛异常**
    - 场景: `WakeReceiver.onReceive()` 调用 `ShellService.start()` 启动前台服务
    - 后果: Android 13+ 后台启动限制 → `ForegroundServiceStartNotAllowedException` (RuntimeException)
    - 改进: `ShellService.start()` 内部 try/catch
    - 原则: **所有 `startForegroundService()` 调用点必须有 try/catch (RuntimeException)**

18. **OEM 差异化: 通知重要性影响前台服务存活**
    - 场景: `NotificationManager.IMPORTANCE_LOW` — 小米/OPPO/vivo 会隐藏或折叠此类通知
    - 后果: 系统不认为这是"可见的"前台服务 → 进程被 LMK 优先回收
    - 改进: 提升到 `IMPORTANCE_DEFAULT`
    - 原则: **国产 ROM 至少用 IMPORTANCE_DEFAULT 保活，LOW 等于没通知**

### OEM 速查

| 厂商 | 关键特性 | 适配要点 |
|------|---------|---------|
| 小米 MIUI/HyperOS | 后台自启动管理 | 权限: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; 用户: 自启动须手动开启 |
| 华为 HarmonyOS | 关联启动 + 电池优化 | `SCHEDULE_EXACT_ALARM` 声明; 用户: 手机管家→启动管理→手动管理 |
| OPPO ColorOS | 应用速冻 | 前台服务通知必须可见级别; 用户: 设置→电池→应用速冻→关闭 |
| vivo OriginOS | 后台高耗电 | `WAKE_LOCK` + `FOREGROUND_SERVICE_DATA_SYNC` 双保险 |
| 荣耀 MagicOS | 类似华为, 更激进 | 所有华为建议 + `specialUse` 前台服务类型 |

---

## 2026-07-22 — v0.7.1 闪退修复

### 根因: 非原子文件写入 + 崩溃后状态损坏

10. **`File.writeText()` 非原子 = 崩溃即损坏**
    - 场景: `TriggerEngine.save()` 和 `AgentViewModel.saveSessionHistory()` 都用 `File.writeText()` 写 JSON
    - 后果: 进程在写入中途崩溃 (OOM / 系统杀进程) → 文件部分写入 → 重启后加载损坏数据 → 再次崩溃 → 死循环
    - 崩溃模式: 前台正常 → 崩溃 → 重启瞬间崩溃 ×1-2 → 恢复 → 再崩溃 (经典"持久化状态损坏"症状)
    - 改进: 原子写入模式 — `tmp.writeText()` → `tmp.renameTo(file)` (rename 在同文件系统上是原子的)
    - 影响范围: TriggerEngine.save(), AgentViewModel.saveSessionHistory(), AgentDocManager (6 处 writeText)
    - 原则: **任何覆盖写操作都用原子写入 (tmp + rename)，确保崩溃时要么旧文件完整，要么新文件完整**

11. **损坏文件必须显式删除，不能只吞异常**
    - 场景: `TriggerEngine.load()` 的 try/catch 只记录了警告日志，损坏文件仍然留在磁盘
    - 后果: 下次启动再次尝试加载同一损坏文件 → 再次失败 → 如果损坏恰好是合法 JSON (如截断数组) 则解析成功但数据不全 → 后续运行时崩溃
    - 改进: catch 块中 `file.delete()` 删除损坏文件，确保下次 save() 从干净状态开始
    - 原则: **加载失败时删除损坏文件，不要依赖下次写入覆盖**

12. **`viewModelScope.launch {}` 必须有 try/catch**
    - 场景: `submitTask()` 中 `viewModelScope.launch { engine.run() }` 无异常保护
    - 后果: `runReActLoop` 内部只 catch `Exception`，`OutOfMemoryError` 等 `Error` 类型穿透协程 → 进程崩溃
    - 改进: 外套 `try { } catch (e: CancellationException) { throw e } catch (e: Throwable) { 降级到错误消息 }`
    - 原则: **ViewModel 中所有顶层 launch 必须有 catch Throwable，OOM 也优雅降级**

13. **后台轮询不应早于回调注册**
    - 场景: `TriggerEngine.start()` 在 `MainActivity.onCreate()` 调用，但 `onFire` 在 Composable 中才设置
    - 后果: 启动窗口期内如果有触发器命中 → `onFire` 为 null → `fireTrigger` 更新 `lastFired` 并落盘 → 触发被静默消耗
    - 改进: `TriggerEngine.start()` 移到 Composable 的 `LaunchedEffect` 中，`onFire` 设置后再启动轮询
    - 原则: **异步回调驱动的组件，先注册回调再启动生产者**

14. **bootstrap 模板写入应加快速路径**
    - 场景: `AgentDocs.bootstrap()` 每次 `ensureDefaultSession()` 都调用，执行 7 次 `file.exists()` 检查
    - 后果: 每次启动都有不必要的文件系统操作 (主线程)
    - 改进: 先检查 `soul.md` 是否存在 → 存在就整个跳过 (一个文件存在说明全部已初始化)
    - 原则: **幂等初始化操作应有 O(1) 快速路径，一个标记文件即可判断是否已初始化**

---

## 2026-07-22 — v0.7.0 发布

### 架构经验

1. **提示词模板 > 硬编码**
   - 场景：CRON 触发器最初在 `submitTriggerTask` 中用大段 String 硬编码横幅推送指令
   - 后果：用户无法定制 Agent 行为
   - 改进：将行为规范写入 `trigger.md` 工作区文件，Agent 自行读取 → 用户可编辑文件控制行为
   - 原则：**Agent 的行为规则放在 workspace 的 Markdown 文件中，不要硬编码在 Kotlin 代码里**

2. **API 拉取 > 预置列表**
   - 场景：LLM Provider 模型列表硬编码在 `LlmProviderPreset` 中
   - 后果：Kimi K2→K3, DeepSeek V3→V4 每次都要手动更新，永远慢半拍
   - 改进：输入 API Key → 自动调用 `/v1/models` → 下拉选择，预设只放最主流的几个
   - 原则：**模型列表从 API 实时获取，预设只做 fallback**

3. **ExecutionContext 命名冲突**
   - 场景：SysExecutor 中 Android `Context` 属性名 `ctx` 与 `ExecutionContext` 参数名 `ctx` 冲突
   - 后果：大量 `ctx.context` 调用在 bulk replace 中全部损坏，逐个修复耗费大量时间
   - 教训：**Kotlin 中不要给不同语义的类型用同一个缩写名**。Android Context 用 `app`，ExecutionContext 用 `ec`

4. **kotlinx.serialization vs org.json 的分层**
   - 场景：TriggerEngine 在 kernel 层用 `org.json` 序列化 → 编译失败（kernel 是 JVM 模块没有 Android SDK）
   - 改进：kernel 层统一用 `kotlinx.serialization`，shell 层可用 `org.json`

### 设计决策

5. **LIFETIME 从随机间隔 → 心跳 + 预生成时间点**
   - 旧方案：每次触发后随机计算下次时间，依赖协程轮询
   - 问题：`start()` 从未被调用 → 触发器永不触发；时间不可预测
   - 新方案：每日生成 1-3 个精确到分钟的随机时间点，30s 心跳检查 ±2min 匹配
   - 好处：时间可预测（`dailySlots`），实现简单，易于调试

6. **CRON 模糊窗口**
   - 用户不需要精确到秒的定时 → ±5 分钟窗口覆盖真实使用场景
   - `lastFired` 防止同窗口重复触发

### 审校发现

7. **孤儿代码和未使用变量**
   - `bootstrapTemplate()` 被 `boostTemplate()` 取代后未删除
   - `newAgentForm` / `fullName` 声明后从未使用
   - 每次功能替换后应 grep 检查引用，清理死代码

8. **WakeLock 对象泄漏**
   - `onStartCommand` 中每次创建新 WakeLock 而不释放旧的
   - 应先尝试 `acquire()` 复用已有实例

### 编译陷阱

9. **bulk replace (`replace_all: true`) 的副作用**
   - `ctx.context` → `ctx` 的 replace_all 把 Android Context 引用全部破坏
   - 应该用更精确的匹配字符串，或分步小范围替换
   - Kotlin 中 `_` 作为参数名在 2.0+ 中受限

---

## 2026-07-19 — v0.2.2 发布日

### 致命错误

1. **硬编码路径 `/Android/data/com.mengpaw`**
   - 后果：真实设备上所有文件操作失败 → 首次启动闪退 ×2 + 新建智能体闪退
   - 教训：永远不要硬编码 Android 文件路径。用 `Context.filesDir`、`Context.getExternalFilesDir()` 或 `Environment.getExternalStorageDirectory()`
   - 修复：`DataPaths.initialize(context)` 动态初始化

2. **APK 上传漏掉**
   - 后果：发了 tag 但没上传 APK → 用户没有下载链接
   - 教训：版本发布必须：commit → tag → push → `gh release create <tag> <apk>` 四步都执行

3. **假数据留在代码里**
   - 后果：框架通讯录显示不存在的设备和 Agent
   - 教训：Mock 数据仅用于开发测试，提交前必须检查并替换为空态 UI

4. **Google Play Protect 拦截**
   - 后果：debug 签名的 APK 被 Play Protect 直接标记为可疑
   - 教训：对外分发的 APK 必须用 release key 签名。debug.keystore 全网通用，不被信任

### 编码错误

5. **`!!` 强制解包**
   - 后果：SharedPreferences 返回 null → NPE 崩溃
   - 教训：永远不用 `!!`。用 `?: defaultValue` 或 `as?` 安全转型

6. **文件 IO 无 try/catch**
   - 后果：文件损坏或权限不足 → 崩溃
   - 教训：所有 `readText()` / `writeText()` / `listFiles()` 必须包裹 try/catch

7. **广播接收器不注销**
   - 后果：`EventReceiver.register()` 创建新实例但引用丢失 → 永不注销 → 内存泄漏
   - 教训：`registerReceiver()` 后必须有对应的 `unregisterReceiver()`，保存引用

8. **HttpClient 不关闭**
   - 后果：每个 AgentSession 创建新的 Ktor HttpClient → 资源泄漏
   - 教训：实现了 `Closeable` 的资源在 ViewModel/Service 销毁时必须关闭

9. **状态跨智能体共享**
   - 后果：Agent A 在运行 → 切到 Agent B → isRunning=true → 无法发送消息
   - 教训：每个 AgentSession 必须持有独立的状态 Flow

### 流程错误

10. **Bash 中 backtick 被解析为命令替换**
    - 后果：`gh release create` 的 --notes 参数含 `` ` `` 被 shell 执行
    - 教训：GitHub CLI 的 --notes 包含特殊字符时用 `--notes-file CHANGELOG.md`

11. **大版本号跳跃**
    - 后果：浏览器从 1.0.0 改到 0.1.0
    - 教训：首个公开版本从 0.1.0 开始，不要跳版本号

---

## 2026-07-20 — 防御性编程补全

### `!!` 强制解包清理

12. **所有 `!!` 必须消除**
    - 后果：即使上面有 `!= null` 检查，可变属性仍存在线程竞争 → NPE 崩溃
    - 教训：`cached!!` → `cached ?: emptyList()`；`map[key]!!.copy()` → `map[key]?.let { copy() }`
    - 修复数量：4 处（RenderPlugin, BrowserActivity, PluginMarketplaceClient, MemoryPlugin）

### 文件 IO 保护补全

13. **`readText()` 即使有 `exists()` 检查也会崩**
    - 后果：文件在 `exists()` 和 `readText()` 之间被锁定/损坏 → 崩溃
    - 教训：`if (file.exists()) file.readText()` 这样的模式不够，必须再加 `try/catch`
    - 修复数量：20+ 处（FsPlugin, SelfExecutor, MemoryPlugin, SkillPlugin, WorkflowPlugin, IncubatorPlugin, ComfyPlugin, HermesPlugin, DevPlugin）

14. **`writeText()` 同样需要保护**
    - 后果：磁盘满 / 权限变更 / 目录被删 → 写入失败崩溃
    - 教训：所有 `file.writeText()` / `file.writeText()` / `file.appendText()` 必须包裹 try/catch。对于返回 `ExecutionResult` 的命令，catch 中返回 `ExecutionResult.fail(..., ERR_IO)`；对于内部辅助函数，静默吞下
    - 修复数量：25+ 处（AgentDocManager 7 处, HermesPlugin 5 处, DevPlugin 4 处, IncubatorPlugin 3 处, ComfyPlugin 2 处，以及 AgentDocs, DreamEngine, PromptFirewall, SelfExecutor, Checkpoint, MemoryPlugin, SkillPlugin 各 1 处）

15. **新增错误码 `ERR_IO`**
    - 后果：文件 IO 失败时只能用 `ERR_INTERNAL`，不精确
    - 教训：为文件 IO 错误添加专用错误码 `ERR_IO`，方便 Agent 区分和处理
    - 位置：`CommandExecutor.kt` → `ErrorCodes.ERR_IO`

### 流程错误

16. **未授权发布版本**
    - 后果：2026-07-19 在 bug 修复完成后自动发布了版本，用户明确表示"这个不行"
    - 教训：任何发布操作（git tag, GitHub Release, APK 上传）必须等待用户说"发布新版本"。修复后只做本地 commit。

---

## 2026-07-20 — v0.3.x 发布日惨案合集

### 流程教训（最重要）

26. **绝对不推送未编译的代码**
    - 后果：v0.3.0 push 后有 7 个编译错误，连续 5 次修复提交
    - 教训：`./gradlew assembleRelease` 通过后才 commit+push
27. **绝对不在模拟器验证前发布**
    - 后果：v0.3.0-v0.3.3 连续 4 个版本都是发布后才测试，全部有崩溃
    - 教训：每次改完→编译→模拟器安装→启动→确认无崩溃→发布
28. **hotfix 必须迭代版本号**
    - 后果：修了 bug 没改版本号，Release 混乱
    - 教训：0.3.0→0.3.1→...→0.3.4，每次都递增
29. **发布必须带 APK + CHANGELOG**
    - 后果：用户多次提醒"带APK一起上传"
    - 教训：发布前确认：CHANGELOG ✓、Shell APK ✓、Browser APK ✓

### R8 混淆教训

30. **库模块的 R8 比应用模块更危险**
    - 后果：mengpaw-core `isMinifyEnabled=true` 导致所有引用它的 APK 都缺类
    - 教训：库模块 R8 要格外谨慎，DataPaths 这种基础类被删会影响所有 APK
31. **R8 开启后 APK 从 13MB→2MB 是危险信号**
    - 教训：正常 release APK 应该在 8-13MB，过小说明类被过度删除

### 编译教训

32. **sealed class 子类字段名必须一致**
    - `AgentWithTrace.finalContent` ≠ `Agent.content` → 批量替换出错
33. **companion object 同文件只能有一个** → 合并
34. **跨模块 data class 放 core** → MissionMonitor 放 plugin 导致 shell 引用不到
35. **新模块必须加 ProGuard keep 规则** → 不然 R8 删光
36. **能用 Regex 替代就不加新依赖** → kotlinx.serialization 跨模块引用失败

### 运行时崩溃

37. **嵌套 ModalNavigationDrawer 在 Material3 某些版本会崩**
    - 改为单抽屉
38. **ShellService 前台服务 Android 12+ 会崩**
    - try/catch `startForeground()`
39. **模拟器 "System UI isn't responding" 不是 app 的问题**
    - 模拟器性能不足导致，app 本身没崩

## 2026-07-20 — v0.3.1 紧急修复

24. **R8 混淆必须在真机验证后才能发布**
    - 后果：v0.3.0 APK 安装后立即闪退，R8 删除了核心类
    - 教训：开启 isMinifyEnabled 后必须在真机上验证启动。APK 从 13MB→2MB 就是危险信号。未验证前先关混淆。
25. **hotfix 必须迭代版本号**
    - 后果：修了 R8 问题但版本号没变，Release 复用旧的
    - 教训：任何修改（包括 hotfix）都要 0.3.0→0.3.1。删旧 Release+旧 tag，重建新 tag+新 Release。

## 2026-07-20 — v0.3.0 发布教训

### 编译教训

17. **不可编译的代码不能 push**
    - 后果：v0.3.0 push 后编译失败，连续 5 次修复提交
    - 教训：写完代码先 `./gradlew assembleRelease`，通过再 commit+push

18. **跨模块引用的 data class 放 core**
    - 后果：MissionMonitor 在 plugin-agent-loop 中，shell 无法引用
    - 教训：需要多模块共享的对象放在 mengpaw-core

19. **新增模块检查 ProGuard 规则**
    - 后果：Shell 和 Browser 的 R8 混淆删掉了 core 类
    - 教训：每个 application 模块都需要 `-keep class com.mengpaw.core.**`

20. **Kotlin companion object 只能有一个**
    - 后果：CacheStrategy 中两个 companion object 导致 `Unresolved reference`
    - 教训：合并到同一个 companion object 中

21. **sealed class 子类字段名要一致**
    - 后果：`AgentWithTrace.finalContent` vs `Agent.content` / `User.content`
    - 教训：批量替换时检查所有子类，统一字段名

22. **模块间依赖要检查**
    - 后果：shell 引用 kotlinx.serialization 但未声明依赖
    - 教训：能用简单替代方案（Regex）就不要引入新依赖

23. **每次编译通过后再 push+发布+上传 APK**
    - 后果：v0.3.0 首次 push 后有 7 个编译错误，APK 未成功构建
    - 教训：build → 验证 APK → commit → push → tag → release

---

## 版本规则

按用户要求，MengPaw 版本号规则：
- **X** (0.X.0)：发布正式版时递增
- **Y** (0.0.Y)：变更底层逻辑时递增
- **Z** (0.0.Z)：修复漏洞或 UI 问题时递增

提交指令自动化：版本号 → CHANGELOG → Tag → Push → APK 上传
