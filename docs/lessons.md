# MengPaw 开发经验教训

> v0.16.0 ~ v0.29.2 主题经验（§1-14，33 条）+ v0.2.2~v0.23.0 历史教训浓缩库（§15，原根 LESSONS.md 118 条去重提炼为要点）。

---

## 1. 性能优化

### 1.1 缓存粒度的价值不在命中率，在失效精准度

`PromptEngine` 有三级缓存（文件级 mtime → 聚合系统提示词 → LLM API prefix cache）。优化前，任何 Agent 工作区写入都全量失效三级缓存。优化后，仅当三个缓存文件（agents.md/soul.md/memory.md）之一被修改时才触发失效。`appendMidTermMemory` 写入高频但不在系统提示词中，不触发失效。

**启示**：缓存系统设计时，失效路径比加载路径更重要。一次不必要的失效可以抵消前面所有的命中。

### 1.2 LLM 等待窗口是免费的 I/O 时间

ReAct 循环中 `completeWithMessages()` 的 HTTP 往返有 2-5 秒网络延迟。Agent 在这段时间完全空闲。将中期记忆写入入队、在 LLM 响应返回后立即批量刷盘——I/O 成本被已有的网络等待完全吸收，用户感知延迟为零。

**启示**：不要只看代码路径上的热点——看系统实际等待的时间间隙。异步不是"让代码跑得更快"，是"让等待时间内的 CPU 不空转"。

### 1.3 会话持久化用脏标记比 WAL 更省

不需要引入 WAL 文件。在 `saveCurrentSession` 里加一个 `lastPersistedMsgCount` 计数器，30 秒保存时若消息数未变就跳过全量 JSON 序列化+写入。简单、零新文件、完全向后兼容。对话空闲时（占大多数时间）30 秒周期变成无操作。

### 1.4 启动时间的本质是"用户什么都不需要等"

把 MainActivity.onCreate 切成两段：关键路径（DataPaths/SysExecutor/UI 渲染）和延迟路径（FrameworkDiscovery/TriggerEngine/ShellService/插件安装）。用户看到 UI 的速度取决于关键路径的长度，与延迟路径无关。

### 1.5 协程池分离的收益在饥饿预防，不在并行度

`Dispatchers.IO.limitedParallelism(N)` 不是让 I/O 更快——是让关键路径的 I/O 不被大量并发操作抢走线程。Prompt 构建需要 2 个线程（一次 LLM 调用最多读 3 个文件），LLM HTTP 调用需要 4 个并发——两个池不互相抢占。

### 1.6 `buildString` 替代 `+` 不是语法偏好

`identity + basePrompt + "\n" + fewShot + "\n" + docsBlock` 产生 5 个中间 String 对象。`buildString(capacity) { append(...) }` 是一次预分配。对几十 KB 的系统提示词，每次都省几 KB 的 GC 分配。累积效应在长对话中显著。

---

## 2. 循环模式重构

### 2.1 默认模式的惯性是最难改的设计决策

MengPaw 从 v0.6.1 起默认 `loopMode = GOAL`——每条消息都走 `runWithGoal` + RubricGate 评估。QwenPaw 源码的原始设计是默认 plain ReAct，Goal 是显式 opt-in。偏离这个设计导致：简单问答被包装成"目标"、每次对话多一次 LLM 评估、`agent.memory.keep` 的提示出现在"今天天气怎么样"这种场景。

**启示**：fork 一个项目时，搞清楚原作者的默认值为什么选那个值。QwenPaw 默认 ReAct 是有意为之，不是没来得及加 Goal 模式。

### 2.2 自动检测的评分维度要可解释

`scoreComplexity()` 四个维度（操作风险、跨域操作、任务长度、多步骤信号）每个都有明确的业务含义。Agent 和用户都能理解"为什么这条消息被升级为 Goal"。不可解释的自动升级 = 用户信任崩塌。

### 2.3 自动升级必须在 UI 标注

`detectComplexity() → GOAL` 时自动添加 `/Goal` AssistChip 标签。这满足两个需求：(1) 用户看到标签知道 Agent 在用什么模式 (2) 用户可以 × 掉标签降级为 REACT。自动系统必须保留手动覆盖的出口。

---

## 3. BM25 命令检索

### 3.1 BM25 + 同义词表 = 向量 RAG 在封闭域上的等价替代

150 条命令是封闭域。Agent 查询用词和命令描述之间的词汇鸿沟可以通过手写同义词表弥合——不需要 embedding 模型、不需要向量库、不需要 Android 上的额外依赖。条条命令 5-10 个中英文同义词，总共维护 ~1000 词条，一劳永逸。

### 3.2 Bigram 对中文分词是必需品

"网页搜索" → BM25 切词 `["网页", "搜索", "网页搜索"]`。中文没有空格分词，单字词过于碎片化（"网页"能匹配，"页"和"搜"不能）。双词短语组合显著提升对复合词的召回率。

### 3.3 先到先得去重——保护精编索引

`BuiltinCommandIndex.buildAll()` 先执行，用 `register()`（force=false）。`PluginManager.activate()` 后执行，用 `registerOrUpdate()`（force=true）。同 `fullName` 已存在时，`register()` 跳过（保护精编关键词），`registerOrUpdate()` 覆盖（允许插件重新激活时更新关键词）。

### 3.4 少一个 Few-shot 示例 = Agent 认知盲区

审计发现 `self.search` 在系统提示词中已提及，但 Few-shot 示例还是用 `self.tools` 遍历全量。Agent 会知道存在但不会形成习惯。加入示例后，Agent 的行为从"遍历全量再选"变成"自然语言搜索再选"，上下文占用降低 95%。

### 3.5 提示词宣称和 Agent 实际行为是两回事

"命令入口: self.tools" 改成 "命令发现: self.search <自然语言描述>" 只是一行文案改动。但这一行决定了 Agent 是否会在第一步就用正确的方式查找命令。提示词的每个入口声明都是一个权重调节器——Agent 不是不聪明，是给了它错误的默认路径。

---

## 4. 插件生态

### 4.1 内置插件不需要独立版本号

10 个捆绑插件的版本号以前是各自独立维护的（0.1.1, 0.2.0, 0.3.0...），但它们随 Shell APK 编译分发，没有独立的更新通道。清空版本号、UI 显示"内置"——减少维护负担的同时让版本来源更清晰。

### 4.2 plugins.json 是入口，源码是真相

`plugins.json` 里有 6 处版本与源码不一致——因为代码改了但 JSON 没同步。市场索引的版本必须与源码编译版本一致，否则 `plugin.update` 会反复提示"更新"或错过真正的更新。

---

## 5. UI / 主题

### 5.1 暗色模式必须用规范色阶，固定色值在深色背景上不可靠

Arco Design 暗色模式规范 bg-1~bg-5 五级背景 + text-1~text-4 白透明度层级。之前使用 `Gray7~Gray10` 固定色值，在深色模式下层次感不足且不符合 Arco 规范。改用 `#17171A` → `#232324` → `#2A2A2B` → `#313132` → `#373739` 五级背景和白透明度 `0.9/0.7/0.5/0.3` 文字后，层次清晰且语义明确。

### 5.2 主题色自定义必须守卫暗色模式

`ArcoTheme` 中 `if (customTheme != null) baseScheme.copy(...)` 在暗色模式下也会应用亮色自定义色值，导致暗色模式被亮色色值覆盖。修正为 `if (customTheme != null && !darkTheme)`，暗色模式始终使用默认 `DarkColorScheme`。

### 5.3 Material3 ColorScheme.copy 必须覆盖完整

自定义主题时 `baseScheme.copy()` 只覆盖 primary/surface/container 是不够的。`onSurface`、`onSurfaceVariant`、`outline`、`surfaceContainerLow`/`high` 等如果不覆盖，会在亮色模式下保留基方案色值，导致文本/边框与自定义背景不匹配。应推导所有衍生色值一次性覆盖。

### 5.4 Dialog 提取到独立文件后必须删除旧 private 定义

`AgentCardDialog`/`NewAgentDialog`/`AddFrameworkDialog`/`FrameworkCardDialog` 从 `SidebarContent.kt` 提取到 `sidebar-dialogs/` 后，旧 private 函数未删除导致 `Conflicting overloads` 编译错误。Kotlin 的 private 函数是文件级作用域，不会与同包其他文件的同名函数冲突，但 `FrameworkCardDialog` 调用了 `SidebarContent.kt` 的 `private fun frameworkTypeIcon`——private 函数不能被同包其他文件访问，必须改为 `internal` 或公开。

### 5.5 大文件拆分的体积线与两坑 (v0.29.1 第二轮拆分)

**体积线定案** (用户实测: >60KB 的 Compose 文件打开设置卡, 拆完流畅):
- Compose/UI 文件 ≤ 40KB (重组范围热点)
- 纯逻辑文件 ≤ 60KB (ViewModel/Service 等)
- data class ≤ 200 字段 (ART 255 寄存器上限, 见 §VerifyError)

**段式拆分模式**: 单一大 @Composable 无私有辅助时, 按内联布局段抽独立文件 (头栏/侧栏/底表/下拉), 共享状态 12 项 hoist 在原组合内, 写入点经回调上抛 (onPluginCommand/onPickXxx/onDismiss)。跨文件符号保持 public; 移走的组合函数从原文件删除不留桩。

**坑 1 — 同包顶层符号名冲突**: 新文件加 `internal val appJson` 与既有对话框文件 (AddFrameworkDialog/FrameworkCardDialog) 各自的 `private val appJson` 同包同名 → `Overload resolution ambiguity`。顶层 private 在文件内不遮蔽同包 internal 同名声明。解法: 共享实例用唯一名 (`sidebarAppJson`), 各文件私有副本不动。

**坑 2 — material3 1.3.x 移除了 `ModalBottomSheetState` 类型**: `rememberModalBottomSheetState()` 返回泛化的 `SheetState` (1.3.0 起 `ModalBottomSheetState` 类型删除, 仅 Kt 函数保留)。显式声明类型必须写 `SheetState`, 且 `ModalBottomSheet` 仍需 `@OptIn(ExperimentalMaterial3Api::class)`。

## 6. 设置页重构

### 6.1 API 供应商设置与模型选择分离

"连接 API" 和 "选择模型" 是两个不同的用户意图。前者只需要 endpoint + API Key，后者需要 API 返回模型列表。分离到框架设置（新增 API Key）和智能体设置（选择模型）两个页面后，职责清晰，且智能体设置页可通过 `refreshModels()` 实时拉取模型列表。

---

## 7. UI 细节

### 7.1 气泡头部只放操作语义，不放 Agent 名

气泡头部放 "MengPaw /Mission" 占用空间且信息冗余——输入框已经明确当前 Agent。去掉 Agent 名只保留执行模式标签后，气泡更简洁，且多 Agent 场景下 `/Mission` 标签已经足够区分模式。

### 7.2 不把进度条放在用户频繁注视的区域

顶栏 LinearProgressIndicator 在每次 LLM 调用时闪烁，干扰用户阅读历史消息。思考过程中的进度条同样造成视觉噪音。移除后用户注意力集中在回复内容本身，生成状态通过输入框图标变化或气泡出现时机暗示即可。

### 7.3 表格列宽用自适应而非固定字符倍数

`Modifier.width(charCount * 7.dp)` 在宽表格时把渲染区域推出屏幕外，在窄内容时留白过多。改为 `Modifier.widthIn(max = 360.dp)` 后列宽由内容决定，表格自然适应父容器宽度。

### 7.4 ProviderCard 复杂度过高时应拆分为内联表单

ProviderCard 同时承担供应商选择 + 模型选择 + API Key 编辑功能，导致单组件复杂度爆炸。API Key 编辑和模型选择分属不同页面后，供应商选择简化为 chip 标签，API Key/地址直接内联展示——组件职责单一、代码可维护性提高。

---

## 8. 版本发布与插件生态（v0.16.1 ~ v0.20.1）

### 8.1 插件版本号必须双元一致

插件版本实际有两处：`PluginMetadata.version`（代码，运行时语义）与 `plugins.json`（市场，须与 AAR 资产版本一致）。AAR 构建版本单一事实源 = `gradle.properties` 的 `mengpaw.version`（build-plugins.ps1 读取）。升级时必须同步，否则 Agent 通过 `plugin.marketplace` 看到的版本与运行时不符——市场版本 > 代码版本时 `plugin.update`/`plugin.upgrade` 恒报「有更新」（2026-08-01 教训：12 个 remote 插件代码 0.2.0 对市场 0.20.2，已同步）。每次升级后 grep 检查两处一致。

> 历史：旧版曾有三处（含 `plugin-manifest.json`）。该文件无任何代码/脚本消费者，2026-08-01 已删除全部 22 个遗留文件。

**启示**：版本号只在实际有代码变更时升（X=正式发布, Y=逻辑重构, Z=修BUG），仅为改 manifest 或市场描述升版本是错的。

### 8.2 功能审计必须跑两轮

第一轮「功能审计」→ 修复 → 第二轮「审计审计」再跑一遍三层十二问。Agent 市场在第二轮被查出 3 个 P0（无 fallback、无 SHA256、无网络受限提示），都是第一次修复后过筛时发现的。

**启示**：不要把审计当一次性任务——二轮专查首轮盲区，P0 常在二轮暴露。

### 8.3 插件需要内核能力时，三行代码就够

workflow 插件要执行命令需拿到 CommandExecutor。微内核不意味着不给插件能力——意味着能力通过接口暴露而非直接耦合：定义接口（kernel 侧）→ PluginContext 暴露 → AgentEngine 注入。

### 8.4 市场下载必须三级回退 + SHA256

下载链接 GitHub→Gitee→ghproxy 三级回退；SHA256 校验不能省（开源社区贡献的文件可能被篡改）；`minCoreVersion` 避免用户装不兼容的 Agent。隐私清理是上传的硬门槛：API Key、session ID、IP、内存路径、token 全部自动移除。

### 8.5 同一值多处分发，先建单一事实源（Ports.kt 模式）

7 个端口曾散落 ≥17 处魔法数字。`Ports.kt` 定义全部端口 + `describe(zh|en)` 生成 Markdown 表，供 `self.ports`/提示词/CLI.md 共用——新增端口只改一处。

### 8.6 命令注册后写覆盖会静默覆盖内核命令

plugin-self 退役教训：同名命令经 CommandRegistry 后写覆盖语义静默覆盖内核命令。注册命令前检查冲突。

### 8.7 多主题未提交变更时，先分主题 commit 再发布

工作区含多主题未提交变更时，先分主题 commit（chore 退役 / feat 进化 / release）再 tag + 双远端推送 + gh release——比单一大 commit 更清晰，发布记录可回溯。

---

## 9. 插件退役（v0.21.1）

### 9.1 退役前必做来源/去向审计

判定"孤岛/死代码"前的正确顺序：先审计消费者。四查：程序化调用（命令执行点）→ 安全策略（PromptFirewall）→ UI（补全 suggestion/设置项字符串）→ 文档文本（含 CLI.md 生成器里的引导）。确认零程序化依赖（Tribe 走 ACP、DreamEngine 走三轨、memory-twin 独立包）才可安全退役。

### 9.2 融合入既有命名空间，不保留旧名

并入内核命名空间（plugin-memory → agent.memory）不保留旧命令名——系统提示词只教过新名，保留旧名 = 两套心智。无存量数据时（用户判定"实际未启用"）直接弃用目录，不做迁移。

### 9.3 插件命名用隐喻，不用第三方名

不要直接用第三方项目的名字（hermes），会被误认为 fork 或抄袭。用隐喻命名（部落协作 tribe），致敬原作但在概念上保持独立。

---

## 10. 多Agent协作（Tribe，v0.19.7 ~ v0.20.x）

### 10.1 KDoc 块注释内禁止 `/*` 序列

注释里写 `team/*.md` 会因 `/*` 开启嵌套注释导致 "Unclosed comment" 编译错误。块注释内出现 `/*` 序列即编译失败。

### 10.2 AcpMessageType 加枚举值的唯一强制破坏点

`AcpServer.processMessage` 的 when 无 else 分支（穷尽性），加枚举必须同步改这里；其他 when 都有 else 兜底。

### 10.3 refreshSystemPrompt() 不能重置循环检测

5 秒 inbox 轮询每次刷新系统提示词，若顺手重置循环检测会屏蔽死循环检测机制。middleware 改 `@Volatile var` + `setMiddleware()`。

### 10.4 收到委派写接收方自己的 inbox

onDelegate bug：原代码把委派写入发送方目录，应写接收方 `localAgentName` 的 inbox。

### 10.5 嵌套委派闭环

`--parent` 建链（深度 ≥3 拒绝 + 沿链环形检测）+ `task.done` 发 RESULT + onResult 自动转寄父任务（判断 `parentFrom != localAgentName` 避免自己转给自己）= A→B→C 全链回传。

### 10.6 suspend 传播与 smart cast

调 `llm.complete()` 的函数本身必须 suspend（compactOldest 踩坑）；`!parentFrom.isNullOrBlank() && parentFrom != localAgentName` 之后 parentFrom 才能当非空用。

---

## 11. Kotlin / 编译坑（v0.18 ~ v0.21）

### 11.1 BOM 导致整包编译失败（铁律）

PowerShell `Set-Content -Encoding UTF8` 在文件头加 BOM（Byte Order Mark），Kotlin 编译器遇到 BOM 导致整个 package 编译失败，连锁 `Unresolved reference`。**永远用 Write 工具或 Git Bash 创建 .kt/.kts 文件**。已中招清除：`sed -i '1s/^\xEF\xBB\xBF//' file.kt`。

### 11.2 三引号 raw string 无法转义 `$`

Kotlin 三引号字符串中 `$` 仍触发模板插值，无法转义。提示词占位符用 `__PORTS_TABLE__` 风格；三引号字符串也不能用于 const val（只允许字面量）。

### 11.3 PowerShell 5.1 无 BOM UTF-8 按 GBK 误读

ps1 脚本含中文且无 BOM 时被按 GBK 解码成乱码——ps1 脚本必须纯 ASCII 或带 BOM。`-replace` 优先级要括号；读 PS 写入的文件用 utf-8-sig。

### 11.4 replace_all 误替换

`replace_all` 会误替换同名字符串：`CaptureSession → CameraCaptureSession` 误替换 method name；`json.` 替换为 `appJson.` 时破坏 `import kotlinx.serialization.json.Json`。用 replace_all 前检查目标串在别处的意外出现。

### 11.5 Kotlin 小坑集

- init 块不能引用后面声明的属性 → 声明放 init 前
- 同包多个 `private val json` 与 import Json 冲突 → 重命名 appJson
- `process.waitFor(30, TimeUnit.SECONDS)` 返回 Boolean 而非 Int
- 老版本 kotlinx.serialization 不支持 `.list` 扩展 → 用 `ListSerializer()`
- Windows `renameTo` 不覆盖已存在文件（writeAtomic 踩坑）

### 11.6 文件拆解时的可见性冲突

`private fun` 是文件级作用域，拆到独立文件后同包其他文件访问不了 → 改 internal/public，但改后可能与同包其他文件的同名函数冲突（SectionHeader/HistorySidebar 踩坑）。与枚举耦合的内部函数（detectComplexity）不适合拆出，保留原文件。

---

## 12. 会话与稳定性（v0.17 ~ v0.18.2）

### 12.1 Android 进程死亡不是异常，是日常

用户每天被杀进程数十次，所有纯内存状态必须能重建。`conversationSessionId`（@Volatile）进程死亡即丢 → 持久化到 current_session.json，重启推回引擎。**任何新增会话状态字段都要问"进程死亡后能不能重建"**——不能就持久化，或标注为非持久化暂态字段。

### 12.2 "看起来在调但没生效"先怀疑响应被吞

AcpTransport.send() 丢弃 HTTP 响应体 → 旧账本同步端到端从未跑通。根因修复：新增 sendForResult() 解析响应体（请求-响应一轮完成）。底层传输函数必须把响应体传回调用方。

### 12.3 设计文档是参考，不是圣经

Reasonix 的 streamWithReconnect（Go 风格）在 Kotlin 用 bodyAsChannel() + readUTF8Line() 等效；OpenClaw 的 dedupe_key（SQLite UNIQUE）单进程 Android 不需要；准入控制 identity queue lock 太重，@Synchronized 足够。**横评比盲实现高效——知道"抄什么"和"不抄什么"**。

### 12.4 SSE 流式修复的核心

问题不是"要不要流式"，而是 `bodyAsText()` 把 SSE 多行 JSON 当单个 JSON 解析。修复：`bodyAsChannel()` + `readUTF8Line()` + 行级 `Json.parseToJsonElement`。

---

## 13. UI / Compose 补充（v0.19.5 ~ v0.20.0）

### 13.1 FlowRow 替代 Row 防手机溢出

Provider chip 用 Row 在手机上溢出。FlowRow 自动换行——所有横向标签/芯片布局优先考虑 FlowRow。

### 13.2 图标先确认在 core 集合

ExpandLess/More 等图标在 material-icons-extended（10MB），core 集没有——用 KeyboardArrowUp/Down 代替。加图标前先确认在 core 集合里。

### 13.3 系统提示词必须覆盖所有架构变更

架构改了但提示词没改 = Agent 不知道新能力。UI 只做输入容器，不替用户选择；模式名字要传达差异（Mission+ → FLEET）；全局工具列表必须动态生成而非硬编码。

---

## 14. 跨进程与插件隔离（v0.22.1，浏览器 MCP 通道 + 框架协议升级）

### 14.1 类加载器隔离: 插件静态字段跨进程赋值不可见

BrowserMcpPlugin 用静态字段注入浏览器进程的 handler 实例,运行期完全无效——Android 各 APK 类加载器隔离,浏览器进程拿到的是自己类加载器里的类副本,静态赋值互不可见(且无编译错误,静默失效)。**跨进程共享状态必须走进程间通道**(BrowserMcpPlugin 最终改为 HTTP bridge: shell→127.0.0.1:9880)。

### 14.2 远程插件不能编译期依赖宿主

error-report/update 插件由 remote 分发,Shell APK 不能编译期 import → 用 Class.forName 反射注入 static 字段(拆解反射调用点,勿跨类传递反射对象)。

### 14.3 插件独立构建时的隐藏依赖

connector 插件单独构建时 kotlinx-coroutines 不可见——依赖不随 kernel 传递;显式声明 `kotlinx-coroutines-core:1.9.0`(放在 dependencies 块内)。

### 14.4 设备内 HTTP bridge 用裸 ServerSocket

Android 无 com.sun.net.httpserver;本地服务(MCP 网关 9881 / 浏览器桥 9880)用 ServerSocket + 逐线程 accept,localhost 仅信任本机。

### 14.5 协议枚举只加字段,不改枚举

AcpMessageType 枚举改动必须同步 processMessage 的 when(穷尽性);新增可选字段(requestId)向后兼容旧消息。

### 14.6 data class 字段数超 255 触发 ART VerifyError 闪退

AppStrings 305 字段 data class → 构造参数 305 > ART 255 寄存器上限 → 类 clinit 验证失败，启动即闪退且 crash.log/crash buffer 无记录（仅 logcat 主缓冲有 FATAL）。修复：普通 class + 无参构造 + apply 块初始化，引用点零改动。阈值：构造参数 ≤254 安全。

### 14.7 Reasonix 对照印证：传输层三差距与落地 (v0.29.2)

对照 DeepSeek-Reasonix 分析文档五机制（自动 prefix cache / HTTP 连接复用 / SSE 低延迟 / 上下文维护 / 会话恢复）彻查自身代码：
- **已有且被证实**：时间戳剥离、system prompt 固定顺序 + mtime 缓存、压缩只动尾部、SSE 逐行增量、断线首 token 前重试、append-only 事件日志、崩溃最多丢一轮——全部命中。
- **真差距三处**（均落地）：① HTTP 客户端每会话新建 = 连接池归零，每次切换重新握手 → `LlmHttpClient` 共享单例（ConnectionPool(8,5min) + retryOnConnectionFailure + read 180s）；② 工具轮流式空屏 → 流式 `Action:` 行一落地即推送"正在执行 X…"（多行锚定正则，半截工具名不误报）；③ 悬空指标（AgentEngine cacheHitTokens 系恒 0 从未累加）+ fallback 链路无 usage 统计 + 无前缀形状监测 → 清死指标 + `SystemPromptShape` SHA-256 告警 + RemoteApi 补 lastUsage 透传。
- **文档偏差修正**：Reasonix 文档称"逐 chunk 即到即渲染"——实测 DeepSeek 服务端按 ~1s 批 flush，客户端"逐 chunk"受服务端批次约束，打字机观感必须 UI 播放器兜底（v0.28.5 已定型）；"中断后提示继续 ≤3 次"在自身代码中对应"恢复块注入一次"，无 ≤3 计数。
- **方法论**：连接池共享的关键风险是"配置死而不知"——`requestTimeoutMillis` 在 Ktor 3 OkHttp 引擎是死配置（字节码实证），清配置时必须以"实测活超时"为准（readTimeout 防思考期误杀，无 callTimeout 防长流误杀）。
- **前提条件**：共享客户端前先 grep 全部构造点确认超时/配置全走默认值，否则共享会吞掉自定义超时。

### 14.9 Compose Dialog 内嵌 WebView (v0.31.0, md 预览 WebView 化)

- **M3 AlertDialog 的 text 槽不能嵌 WebView**: text 槽被包在 `verticalScroll` + 无限高测量里, WebView 渲染成 ~150dp 小方块 + 双层滚动。必须换 `Dialog(usePlatformDefaultWidth=false)` + `Surface` 自定义布局, Box(weight 1f) 内 AndroidView。
- **`Dialog`/`DialogProperties` 在 `androidx.compose.ui.window`**, 不在 material3 — material3 只有 AlertDialog/BasicAlertDialog, 编译报 Unresolved reference。
- **WebView `allowFileAccess` API 30+ 默认 false**: targetSdk 35 下不开则 `file:///android_asset/` 子资源 (css/js) 静默加载失败。
- **`loadDataWithBaseURL` 大内容 (~1.2M 字符) 有设备相关截断**: 超限回退 cacheDir 临时文件 + loadUrl, 模板内相对资源 replace 成绝对 asset 路径。
- **模板替换标记用 HTML 注释**: commonmark 转义后不可能产生 `<!--`, 无碰撞; 花括号标记会与真实文档撞车 (转义不处理 `{}`)。

### 14.8 静默判定阈值与网络门卫 (v0.29.2, 高铁场景用户追问)

- **对齐 Reasonix 阈值**：readTimeout 180s → 120s（静默判定）。余量论证：思考期 60s+ 无数据，120s 仍留 ~60s；Reasonix 以 120s 上线多年。加 `pingInterval(60s)`：HTTP/2 主动探活，半死连接 60s 内被发现（比静默超时早 60s），HTTP/1.1 无副作用。两者合计把"断连感知"追平 Reasonix。
- **用户提议 → 网络门卫 SPI**：`NetworkConditionGate`（kernel 接口，零 Android 依赖）+ `NetworkConditionMonitor`（shell 实现）。断网 → 重试失败快返（6 次退避 + fallback 链全跳过，错误气泡直出，不烧配额/电量）；弱网（VALIDATED + 带宽档位）→ 退避 ×3/×1.5。
- **权限现实**：蜂窝 dBm 需 READ_PHONE_STATE（危险权限 + 运行时弹窗），拒绝；用免权限代理（onAvailable/onLost + VALIDATED + linkDownstreamBandwidthKbps）——对"避免注定失败的重试"目的足够。升级路径：WifiManager RSSI（ACCESS_WIFI_STATE 普通权限）。
- **坑**：kernel 零 Android 依赖是硬约束——信号源必须走 SPI 注入（先例 KernelLog.logger）；provider 构造点有 7 处（AppRoot×2 / AgentSessionFactory×3 / DreamWorker×1），漏一处就静默退回无门卫路径。


### 14.10 400 行文件拆分项目方法论 (v0.33.0，37 文件 → 127 文件收官)

- **拆分铁律**：纯平移禁止重写；公开 API 签名零变化；同包提取（private 外移改 internal）；新文件必带 SPDX 双许可头；嵌套类型被外部以 `Outer.Nested` 限定引用时原位保留（先 grep 消费面再动手）；`@JavascriptInterface` 方法必须留在 addJavascriptInterface 注册对象实例上（只能外移实现委托/脚本常量）；delegate-object 模式 = 构造器闭包注入 + 共享 lock 保持监视器语义。
- **测试守护的隐性约束**：`PromptGhostReferenceTest` 从 `PromptEngine.kt` 源文件提取模板常量做幽灵引用扫描 —— 模板常量字符串绝不能移出该文件，否则测试仍绿但覆盖静默失效。
- **5 批次并行 agent 模式**：按模块边界分区（kernel/browser/plugins/shell+design），互不越界；每个 agent 自带编译+测试验证，交叉编译双向确认；并行 Gradle 锁竞争（"Waiting to acquire"）遇锁等待重试非错误。
- **Kotlin 坑**：`lateinit var` 后备字段跨类不可赋值/`isInitialized` 跨类不可用（内部方法桥接）；`const val` 不允许声明在类内；internal 属性与同签 internal 函数 JVM 层撞车（`getX()$module`——属性保持 private 调既有 getter）；类内成员扩展函数跨类不可解析（提为同包顶层 internal 扩展）；注释内 `*/` 提前终止块注释；同名顶层 internal 函数与类内 private 成员自然遮蔽无冲突。
- **Compose 坑**：`Modifier.weight` 要求 ColumnScope 接收者；BoxScope 版 `AnimatedVisibility` 需全限定名；`@OptIn(ExperimentalMaterialApi)` 必须带；被移动的状态变量与监听器整体迁移防快照失效；面板 composable 提取时公开签名不动。
- **bash 链式命令陷阱**：同一 `&&` 链里先覆盖源文件再 sed 提取，后续文件全部取到空内容 —— 先提取所有区块到 /tmp 再组装。
- **验证口径**：kernel 是纯 JVM 模块用 `:mengpaw-kernel:test`（非 testDebugUnitTest）；判定成功看 exit code 或产物，不靠管道 tail（管道吞退出码）。

---

## 15. 历史教训浓缩库（v0.2.2 ~ v0.23.0，原根 LESSONS.md 去重提炼）

> 原根目录 `LESSONS.md`（1088 行编号教训）2026-08-05 删除，118 条去重（含 3 对重复条目）并按主题浓缩为要点。
> 已被 §1-14、记忆（bug-audit-methodology / no-broken-code-push / release-checklist / no-release-without-ask）、发布流程（`.claude/skills/release.md`）覆盖的条目不再重复。

### 15.1 发布与流程

- 编译通过才 push；发布前模拟器验证；hotfix 必须迭代版本号（v0.3.x 连续 4 版发布后崩溃教训）
- release 前 grep 确认改动真正打包（v0.19.5 空壳 release 教训：声称移除实际没删）
- tag ≠ release：`gh release create` 显式创建发布页；发布必带 APK（v0.2.2 漏传 APK 用户无下载链接）
- `gh release --notes` 含特殊字符用 `--notes-file`（backtick 被 shell 当命令替换执行）
- versionCode 用数学分解（`Y*1000+Z`），字符串截取跨位必错（0.10.0→"0100"=100 < 0.9.1=910 降级拒绝）
- 对外分发 APK 必须 release key 签名（debug 签名全网通用，被 Play Protect 拦截）
- 结构性修改用 Edit 工具逐段，不用 sed 行号（行号漂移连锁破坏，文件损坏需 git checkout）

### 15.2 Kotlin / 编译

- 第三方库 get/set 类型不一致 → Kotlin 只合成只读属性赋值报 val 错：javap 看真实签名 + Java 方法调用绕过（jsch 教训）
- typealias 函数类型用 lambda 字面量不构造调用；需要 return 的函数一律块体（表达式体禁 return）
- `when(subject)` 分支是"值与 subject 比较"语义——要写条件就放弃 subject 形式
- 语句式 try-catch 每分支显式 return；catch 要用的变量声明在 try 前
- 跨模块共享 data class 放 core；新模块必须加 ProGuard keep；companion object 同文件只能一个；sealed class 子类字段名统一
- 引入 Java 库先写 5 行测试确认 Kotlin 映射（commonmark 用 getFirstChild/getNext 非 Iterable）
- 编译时已知的类绝不用 `Class.forName`——直接 new（R8 混淆后类名变化，10 插件只装成 2-3 个）
- 库模块 R8 比应用模块更危险；release APK 正常 8-13MB，过小（2MB）说明类被过度删除
- kernel 层统一 kotlinx.serialization，shell 层才可用 org.json；新依赖能用 Regex 替代就不引入

### 15.3 Compose / UI

- LazyColumn 是嵌套滚动毒药：AnimatedVisibility/另一 ScrollState/item 内一律用 Column；一个方向只允许一个 scroll，排查崩溃逆序逐层去 scroll
- `weight` 依赖固定父宽，horizontalScroll 内权重列宽为零 → `widthIn(min)`；Row 列对齐用 `width()` 精确宽（widthIn(min) 不保证）
- DropdownMenu 是独立 Popup 窗口，与 TextField 同现抢焦点弹输入法——用内联 Surface
- remember/LaunchedEffect/DisposableEffect 必须在顶层组合，不能在 if/when 分支内
- IconButton 键盘焦点泄漏（Enter 触发新建会话）——输入区附近按钮用 pointerInput
- 超过 30 个 item 静态列表用 LazyColumn + stable key（Column"少才安全"）；手写 Markdown 解析器是坑，用 commonmark
- 手势方向是设计问题：右滑开左、左滑开右（手指来向），先原型再定
- Compose scope 扩展（weight/align）不要显式 import（scope receiver 自带）；输入框清空后必须 requestFocus()
- 闭包改 Compose 状态先快照再操作（ModalBottomSheet 重写教训）；数据源赋值顺序=UI 渲染顺序（StateFlow 赋值前依赖数据就绪）
- 后台任务不能用共享引擎（DREAM 经 run() 致 UI 锁定）——独立实例或直接 LLM 调用
- ViewModel 的 launch 默认 Main——文件 IO 必须 `withContext(Dispatchers.IO)`

### 15.4 Android 平台

- `startForeground()` 失败必须 stopSelf()——否则 5 秒后系统杀进程；`startForegroundService()` 从广播调用 Android 13+ 可能抛异常，所有调用点 try/catch
- 每个 foregroundServiceType 配对应 `FOREGROUND_SERVICE_<TYPE>` 权限（Android 14+，OEM 延迟检查）
- `registerReceiver()` 必须带 RECEIVER_EXPORTED/NOT_EXPORTED 标志（API 34+）
- 国产 ROM 前台服务通知至少 IMPORTANCE_DEFAULT（LOW=没通知，进程被 LMK 优先回收）
- FileProvider 三步缺一不可：file_paths.xml + Manifest `<provider>` + getUriForFile（多次踩坑）
- `content://` URI 不能直接给 Agent——拷贝到工作区传绝对路径，50MB 检查在路径插入前
- API 33+ 方法（NsdServiceInfo.setAttribute）必须 SDK_INT 判断静默回退；通知渠道删除在前台服务运行中被拒绝，先查存在再 try/catch
- WakeLock 复用已有实例不新建；无线调试端口每次重开会变——连接信息记 memory 文件 + mDNS 发现

### 15.5 数据 / 持久化

- 任何覆盖写都原子写（tmp→rename）；Windows renameTo 不覆盖，必须显式 delete 目标（writeAtomic 漏删，§11.5）
- 加载失败显式 delete 损坏文件，不依赖下次写入覆盖（否则崩溃循环）
- 多租户存储先持久化 agentName 字段——JSONL 重读后要能回答过滤条件
- 涉及持久化的 ID 必须在数据产生前分配（会话 ID 孤儿教训）；跨重启 ID 必须持久化（current_session.json 存 sessionId）
- 空会话/空产物要有清理路径（messageCount ≤ 0 自动清理）；视图持久化兼容旧版本（load 时过滤无效枚举值）

### 15.6 架构 / 设计

- UI=纯展示+事件分派、运行时=所有副作用（AgentRuntime，主文档 §2.1）；Agent 唯一启动时机=用户第一条消息（主文档 §10）
- 提示词模板独立于源码放 assets；模板→只读→工作区三层路径模型；提示词是目录 Skills 是正文
- 假开关比没有开关更危险——安全功能必须真正接入执行链（integrityCheckEnabled 从未被读）
- 写完接口→找实现→确认实例化→确认调用链四步（IntegrityGuard 从未被 new，路径保护是空操作）
- 系统提示词是 Agent"功能说明书"：任何架构变更/文件重构必须同步更新；每次改模板查硬编码提示词是否矛盾（两套真理）
- LLM 预训练知识会覆盖系统提示词——功能名称用训练数据没有的术语（斜杠命令 vs Normal/Deep/Dream）+ 否定语句；section 占比超 30% 警惕；过时 Few-shot 比没有更危险
- 插件命令键不带命名空间前缀——PluginManager 会再加一次（twin.start 双写 Bug）；同名命令归属由构建决定不靠注册时序（§8.6）
- 同步函数不返回虚假值——区分"已提交"和"已完成"（CompletableDeferred 桥接异步回调）
- 硬编码延迟是对不确定性的投降——轮询/回调检测就绪；mDNS 不可靠：手动 fallback + 持续刷新扫描
- 配置命令必须落到引擎 if/else 分支——返回文本不是实现（QoS 声明≠执行）；对等网络必须心跳（90s 无响应离线）
- 配对是安全关键操作——UI 明确告知操作对象（确认弹窗显示设备名）；危险能力插件隔离（Root），plugin.disable 即紧急关停
- 重命名枚举必须 grep 字符串形式（配置/JSON/文件名，PanelOrderStore "dream"→"silent" 丢项教训）
- 卸载/移除模块三步：settings.gradle 移除 + grep 引用清零 + 物理删除目录（不留僵尸）
- 模型列表 API 实时拉取 > 预置列表；换主题色=色板定义 + grep 全局替换 + 算法生成梯度 + 验证深色模式
- 重试策略三要素：次数上限 + 错误分类（400/401/403 立即失败）+ 退避上限（maxRetries=19 等 27 分钟教训）

### 15.7 记忆与 Agent 认知（架构部分已入主文档 §4.8）

- 记忆必须有完整 CRUD + 底层安全守卫（entryId 唯一才执行）——删除能力不能靠 prompt
- 新功能上线前做 Agent 认知层审计——Agent 不知道 = 功能白做（方法论已入记忆 bug-audit-methodology）
- Skill 纯文件驱动零 Kotlin 改动扩展知识（改文件即生效不重新编译）；生成器产出的模板必须通过自己的校验器
- 审计类命令语义是"输出报告"而非执行结果——先读实现再写断言（DevPlugin.audit 恒 success 教训）

### 15.8 版本规则（用户定案）

- X (0.X.0) 发布正式版递增；Y (0.0.Y) 变更底层逻辑递增；Z (0.0.Z) 修复漏洞/UI 递增
- 提交指令自动化（版本号→CHANGELOG→Tag→Push→APK 上传）已由 .claude/skills/release.md 接管

### 15.9 OEM 速查（国产 ROM 保活）

| 厂商 | 适配要点 |
|------|---------|
| 小米 MIUI/HyperOS | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；自启动须用户手动开启 |
| 华为 HarmonyOS | `SCHEDULE_EXACT_ALARM` 声明；手机管家→启动管理→手动管理 |
| OPPO ColorOS | 应用速冻——前台服务通知必须可见级别 |
| vivo OriginOS | `WAKE_LOCK` + `FOREGROUND_SERVICE_DATA_SYNC` 双保险 |
| 荣耀 MagicOS | 华为建议全用 + specialUse 前台服务类型 |

### 15.10 Android 系统 API 行为差异与崩溃排查（v0.34.1, NSD 事故）

- **新 Android 版本的系统 API 行为变化是隐藏炸弹**：NsdManager 共享 listener 并发复用旧版静默容忍、Android 14 抛 `IllegalArgumentException: listener already in use` 直接崩进程——"Android 13→最新兼容性"检查必须覆盖 API 行为差异, 不能只看 API 级别是否存在
- **系统回调线程未捕获异常 = 进程崩溃**：`onServiceFound` 等回调在系统 HandlerThread 执行, 裸调 `resolveService` 抛异常直接杀掉整个进程——所有系统回调必须 try-catch + 每次 new listener（Android 官方模式, 共享回调对象并发复用是竞态源）
- **dropbox 是崩溃真相的唯一完整来源**：荣耀/vivo 的 crash buffer 会丢、自定义 crash.log 可能无记录, 只有 `dumpsys dropbox --print` 保留全部历史崩溃（含进程存活时长, 判断"启动即闪退"死亡循环）——发布前必巡检（已入 release skill §2）
- **"启动即闪退 + 清数据仍崩" 排查路径**：先 dropbox（跨版本历史都在）→ 再看 logcat → 最后查代码；adb 列表 mDNS 与 IP 条目可能是同一台设备（用 `getprop ro.serialno` 确认），平板不在线时先别假设
- **同 APK 设备矩阵差异是常态**：vivo 不崩、荣耀必崩——真机冒烟至少覆盖 Android 13/14/15 各一台

### 15.11 CLI 参数层吞换行（v0.34.1+ 自检, P4）

- **框架自检实测暴露**：Agent 用 agent.write 写 Markdown, 三种参数形态全部单行——JSON `\n` 转义、JSON 真实换行、纯文本 `\n` 转义。用户生成 md 全部变成标题（换行丢失, `# 标题` 连成一行）。
- **根因链（三环）**：① ReActParser 用 kotlinx JSON 解析 Action Input, `\n` 已是真实换行；② HighRiskCommandGate 模板展开把 content **无引号**拼进命令行；③ CliInterpreter.tokenize 把空白（含换行）当参数分隔符、把 `\` 当转义符（`\n`→`n`）——writeFile 再 joinToString 还原, 换行只能变空格。
- **修复模式**：HighRiskCommandGate 展开时对含空白/引号/反斜杠的值加双引号包裹并转义（`quoteIfNeeded`）, 引号内空白不切分、`\\`/`\"` 可还原；无特殊字符不加引号（既有行为零变化）。回归测试锁死展开→CliInterpreter 解析→参数完整还原。
- **边界**：非高危命令（如 agent.memory.record）的纯文本参数若带真实换行仍会被分词切散——纯文本参数没有引号保护, 多行内容官方通道是 `agent.write --from <源文件>` / 引号包裹。
- **可发现性教训（用户要求）**：Agent 误判"无法写多行"是因为不知道 --from 通道——修复不只是改 bug, 还要同步提示词（高危示例加 --from 指引）、搜索索引 usage、CLI.md。Agent 无法获知的信息缺口 = 框架缺陷。

### 15.12 结果可信度校验层（v0.34.1+ 自检, P0/P1）

- **P0 根因**：结果纪律只写在提示词（PromptEngine 响应格式节"必须遵守"），LLM 幻觉时框架无任何机制发现"声称成功但命令实际失败"——框架返回 ERR_NOT_FOUND/REASON_REQUIRED，Agent 照样说"写入成功"，假结果闭环。
- **落地三件套**：① 写操作成功回传内容预览（agent.write/agent.memory.keep 前 200 字符 + 行数）——声称成功必须引用真实落盘内容；② 会话幻觉率（recordSessionOutcome 对比 Final Answer 与失败命令，含错误码或任一失败词=如实提及，否则计入；audit 展示 X/Y + 疑似幻觉告警）；③ 失败如实提及检测是启发式（文本匹配），有误判边界——失败后 Agent 中途已如实汇报但 Final Answer 未复述时会计入未提及，属可接受噪声。
- **P1 闭环强制**：复现 ≥2 次且未修正 → recurrenceReminder 注入失败 Observation（prune 后追加），强制 Agent 当场二选一（learn.command 登记 or memory.keep 沉淀）；已修正不再强制；stats() 对"有失败 0 沉淀"显示红灯。
- **框架教训**：进化系统"记录→识别"都做了，缺"强制处置"一环——闭环的最后一步不能靠 Agent 自觉，要由框架把动作摆到 Result 里。下一环是"声称成功时的证据引用"（内容预览已铺路）。

### 15.13 三层十二问缺口收尾（2026-08-08 缺陷处理）

- **幻觉率持久化（3.5 ❌→✅）**：`veracityTotals` 原为进程内内存统计，重启清零——十二问 3.5 数据完整性问打 ❌。修复：`evolution/veracity.jsonl` 每会话一行（agent/total/unmentioned/ts），`ensureVeracityLoaded` 懒加载历史，统计跨进程累计；坏行跳过、读写失败降级，不阻塞主链路。新增 `resetVeracityForTest` 供测试隔离。
- **声称成功无证据（2.6 ⚠️→更强）**：写操作 Result 加 `[校验锚点] 内容开头: "…"`（取内容前 12 字符、换行压平），提示词结果纪律要求"声称写入成功必须引用锚点真实文本"。仍是软约束（LLM 可无视），但 Agent 已无"看不到真实内容"的借口。
- **强制二选一升级（P1）**：复现 2 次提醒（⚠️ 请当场处理）→ 复现 ≥3 次升级 🚨"必须立即处理, 不得继续同类操作"。软→更硬的渐进，避免打断单次失败。
- **非高危多行（P4 边界）**：高危 JSON 通道已有引号保护；非高危纯文本通道补提示词通用规则"含空格/换行的内容用双引号包裹"（`agent.memory.record "第一行\n第二行"`，引号内换行保留）——CLI 解析层本就支持引号内换行，缺的是 Agent 认知。
- **UI 呈现（2.3）**：设置页工作区文件树 evolution 节点摘要附加"会话失败如实提及 X/Y" + "⚠️ 有失败未沉淀"，开发者/用户无需进 audit 文本即可看到状态。
- **方法循环**：本轮即用「三层十二问」（docs/audit-methodology.md）审查 P0-P4 修复——方法论本身被自己的实践验证：12 问逼出的 5 个缺口里，3.5（持久化）是唯一 ❌，其他都是 ⚠️ 增强项。

### 15.14 幻觉问题从"度量"到"干预"（2026-08-08 实质化）

- **用户纠正**：统计幻觉率（X/Y + 告警）不改变任何行为——度量不是优化。实质 = 幻觉发生时框架当场拦截。
- **Final Answer 门禁（静默 + 无上限拒绝）**：本轮有失败但最终回答未如实提及（`unmentionedFailures`：错误码 或 任一失败词 均未出现；词表 2026-08-08 扩充"没成功/未成功/报错/没能"等口语表述，且不再要求命令名——自然语言汇报里不会出现内部命令名，含失败词即视为已承认）→ 框架**拒绝接受** Final Answer，反馈**只注入下一轮 LLM 请求**（buildConversation 末尾追加 system，不写会话历史——UI/持久化/后续上下文零污染），引导 Agent 优先静默纠正（重试/换命令，成功则正常收尾）或自然语言如实说明，不再强制堆内部错误码。**拒绝不设次数上限**：幻觉答案绝不放行，每次拒绝消耗一步预算，顽固幻觉由 effectiveMax 终止（返回 max_steps）而非放行假成功。**失败已弥补豁免**：同命令同参数重试成功 → 从待提及清单移除（换参数 = 不同操作不豁免），幻觉率统计同步只计最终仍失败的条目。位置 AgentReActLoop isFinal 分支，检测逻辑与幻觉率统计共用 `isFailureMentioned` 单点。
- **写操作自动读回验证**：agent.write 成功后框架自动 `readText` 比对（≤200KB 全量，大文件验证存在+字节数），Result 标注"读回验证: 内容一致 ✓/⚠️ 不一致"——成功断言从"Agent 声称"变为"框架验证"。预览 + 校验锚点仍是软约束，读回验证是硬事实。
- **教训**：检测/统计类能力（幻觉率、复现率）是"看到问题"，必须配套干预类能力（门禁、验证）才构成闭环——三层十二问 2.6"异常路径有处理吗"的深层含义：处理 = 阻断/纠正，不是记录。

### 15.15 回合内重试循环：先"给转向机会"再终止（2026-08-08，对齐 QwenPaw RETRY LOOP DETECTED）

- **观察**：既有 detectLoop（同命令 5 次）/ trackResult（连续 5 败）都是"终止型"，但空转的浪费发生在第 3-5 次之间——Agent 不知道自己正在空转。
- **QwenPaw 做法**（qwen-code PR #3178）：检测到同一错误反复重试时，直接在错误响应注入 `RETRY LOOP DETECTED`，要求停止、重查 schema、换根本不同的方法、或向用户说明。
- **落地**：回合内 `(commandLine, errorCode) → 次数`，同命令同错误码满 3 次注入停指令（每 key 一次防刷屏）；同命令成功一次计数清零（中间成功即非死循环）；与跨会话 `recurrenceReminder`（沉淀二选一）正交，与 detectLoop/trackResult（终止）形成"先引导后终止"梯度。
- **教训**：干预粒度应有梯度——提示（3 次）先于终止（5 次）；注入必须是框架级指令（区别于不可信工具数据），且要防重复注入。

### 15.16 失败截断要进进化，且要带上下文片段（2026-08-08）

- **观察**：循环/步数/中断终止时，进化系统只记录"命令+错误文本"（经 ErrorCollector 钩子），且 max_steps / consecutive_failures 终止路径**根本没有失败记录**——进化素材缺了"当时在做什么"。
- **落地**：AgentReActLoop 四类截断路径（loop_detected / consecutive_failures / max_steps / 异常中断）统一调 `recordTermination`：剪取会话尾部最近 6 条消息（Thought/Action/Observation 序列，≤500 字符）作为上下文片段，写入 failures.jsonl（message 带 `[截断: reason]`，source=Termination，空命令以 "(终止: reason)" 为模式键），复用复现计数/种子匹配/缺陷升级。
- **教训**：截断本身就是重要的进化信号——"Agent 未能完成任务"和"某条命令失败"是不同粒度的学习素材；剪取上下文时只取非 localOnly 消息、限长限条数，防把恢复元数据/超长正文灌进档案。

### 15.17 进化产物要可读、可追溯、可采纳（2026-08-09，实测三项缺陷）

- **实测缺陷 1（重复）**：failures.jsonl 每失败追加一行，同模式反复失败产生 N 行几乎相同的内容。修复：按 (agent+command+errorCode) upsert，每模式一行，repeatCount 累计，firstSeen/lastSeen 记录时间线；历史重复行首次加载时自动去重整理。
- **实测缺陷 2（无上下文）**：EvolutionFailure 只有命令+错误文本，不知道"当时在做什么任务"。修复：新增 task/sessionId/contextSnippet 字段（截断路径已剪取，命令失败经 ErrorCollector 钩子带 sessionId），audit 逐条展示 id/时间/任务/上下文。
- **实测缺陷 3（不被采纳）**：两个根因——① failures buffer 启动时不加载文件，重启后 repeatedPatterns/stats/复现提醒/已修正状态全丢；② learn.command 只写 CommandSearch 内存索引，重启即丢。修复：failures.jsonl 按 agent 懒加载；learn.command 持久化到 `进化档案/commands.json`，EvolutionHook.install 时恢复进检索索引。长期记忆（memory.md）本就注入提示词，教训可自动采纳。
- **教训**：进化系统"记录→沉淀→采纳"三段中，前两段只有文件持久化还不够——**读取侧必须懒加载文件**，否则重启后进化等于没发生过；登记类产物（learn.command）必须落盘，不能只驻内存索引。

### 15.18 命令字段会被 LLM 参数污染，去重必须按命令名（2026-08-09，真实 failures.jsonl 46 行实证）

- **现象**：用户实测文件 46 行中 termux.run 出现 9 次、agent.write 6 次、evolution.* 各 3 次——同命令反复失败各自成行，且 `command` 字段被 LLM 整段 Thought+Action+Observation 多行文本污染（如 `evolution.status Result: <untrusted_data>...`），命令名被淹没。
- **根因**：LLM 传参错误把多行文本带进 commandLine → ErrorCollector metadata["command"] 存的是污染后的完整命令行；去重键若用完整命令行，同命令不同参数/不同 Thought 生成不同 key，去重失效。
- **修复（v3）**：`recordFailure` 存储前 `cleanCommand`（剥离换行单行化，截断 120）；去重/复现键改 `commandNameOf`（清洗后第一 token）+ 错误码——同命令不同参数视为同一模式；`mergePatterns` 历史合并同规则。实测模拟：46 行 → 16 个模式（termux.run ×9 归一）。
- **教训**：进化记录的"模式键"必须抗污染——用户输入不可信，**命令字段里唯一可靠的是第一个 token**；展示字段（command）与模式键（命令名）分离，两者都做清洗。

### 15.19 三层十二问过进化全流程：认知入口与缓存失效（2026-08-09）

- **审查结论**：18 问中 15 项闭环，3 项缺口——1.1 系统提示词无进化认知入口（Agent 首次接触靠失败引导事后补认知）；1.5 learn.command 成功后未指引 mark-corrected 闭环；2.3 UI 摘要缺复现模式数。
- **修复 1.1（含隐藏坑）**：系统提示词按 `hasEvolutionData` 条件注入进化引导块（有失败/指令才注入，零数据零 token）。**隐藏坑**：failures.jsonl 不在提示词缓存 docMtimes 检查范围，写失败后缓存不失效 → 引导永远不出现。修复：`currentEvolutionFingerprint`（failures.jsonl + commands.json 的 size:mtime）纳入缓存命中条件，进化数据写入即重建提示词。
- **修复 1.5**：learn.command 成功响应追加"evolution.audit 找 id → mark-corrected 标记闭环"指引，登记与闭环动作链补齐。
- **修复 2.3**：设置页 evolution 节点摘要附加"复现模式: N 种"。
- **待办 2.5**：失败库无清理命令（去重后每模式一行、数据量可控，删除有风险需用户决策）。

### 15.20 链接点击闪退与「路径下没有文件」是同一根因链（2026-08-09，平板 0.34.2 实锤）

- **现象**：Agent 交付 md 文档 → 用户看到本地文件链接而非文件气泡；点击链接 App 直接闪退；按 Agent 给出的路径找不到文件；全程无流式动画（后者单独排查，未定案）。
- **实锤崩溃堆栈**：`FileUriExposedException: file:///storage/emulated/0/Android/data/com.mengpaw.shell/files/output/xxx.md exposed beyond app through Intent.getData()`，触发链为 Compose `TextLinkScope` → `AndroidUriHandler.openUri` → `ACTION_VIEW(file://)`。**新版本 Compose 对 `LinkAnnotation.Url` 有默认点击处理（LocalUriHandler 直接 ACTION_VIEW），不是"无处理"**——静态看代码以为链接不可点，实际默认处理对 file:// 是崩溃路径。
- **根因 1（闪退）**：MarkdownText 用 `LinkAnnotation.Url`，点击 file:// 链接触发 FileUriExposedException。修复：改用 `LinkAnnotation.Clickable` 自定义处理——http(s) 直接 ACTION_VIEW；本地路径去 file:// 前缀经 FileProvider 转 content:// 再抛系统选择器；目标不存在/打开失败 Toast。
- **根因 2（文件不可见/未落盘）**：输出目录在 `/Android/data/<pkg>/files/output/`——Android 11+ 文件管理器默认隐藏 Android/data，用户按路径找不到文件；且该次会话 Agent 输出的路径下实际没有文件（幻觉/未落盘）。修复：输出目录迁移公共 `/storage/emulated/0/MengPaw/`（MANAGE_EXTERNAL_STORAGE 授权，未授权回退私有）；交付纪律强化（先 agent.output 查路径 → agent.write 落盘 → agent.ls 验证 → 才输出链接）。
- **根因 3（写路径失败面）**：`resolvePath` 前导 `/` 宽容仅对"已存在"路径回退工作区，写新文件 `/Agent文档/x.md` 时回退失效、落根目录失败。修复：非系统挂载点前缀的前导 `/` 一律按工作区解析。
- **教训**：① Android 上 `file://` 链接点击 = 定时炸弹，任何经 Intent 出进程的文件必须 FileProvider；② "给用户的文件"绝不能放 Android/data 或应用私有目录——文件管理器看不到就等于没交付；③ Compose LinkAnnotation 的行为随版本变化，**默认行为不可假设，点击处理必须显式覆盖**；④ UI 提示"链接/文件不存在"比静默无反应强——静默吞异常让用户以为功能坏了。

### 15.21 子进程池测试的超时窗口要覆盖进程启动成本（2026-08-09，v0.34.3 发布全量实测）

- **现象**：`SessionShellPoolTest.timed out command destroys session` 单独跑全绿，`./gradlew test` 全量下稳定失败——`commandTimeoutMs=500` 时，`sleep 3` 超时销毁会话后，恢复命令 `echo recovered` 借**新会话**（新 sh 进程启动 + 管道初始化），全量并行负载下启动即超过 500ms → 恢复命令也超时（"Command timed out (0s)"）。
- **根因**：测试超时窗口只覆盖了"被测试命令"的耗时，没覆盖"超时销毁后重建会话"的进程启动成本；全量并行（`org.gradle.parallel=true` + 多模块同时跑）放大启动抖动。
- **修复**：`commandTimeoutMs` 500 → 1500（仍 < `sleep 3`，超时语义不变；给恢复命令留足启动时间）。
- **教训**：涉及真实子进程（sh/ADB/外部工具）的测试，超时窗口必须 ≥ 进程启动成本的 3 倍，且要在全量并行负载下验证——单独跑绿 ≠ 全量绿。

### 15.22 描述与实现错配潜伏 9 版：双源并存 + 合并时无语义核对（2026-08-09，CLI.md 移除）

- **现象**：CLI.md 审查发现 `agent.audit` 描述"7 类安全检查"（实现是审计日志）、`plugin.auto` 描述"自动更新策略"（实现是省电），自 v0.16.0 引入后潜伏 9 个版本。
- **根因链**：① v0.16.0 手写 BuiltinCommandIndex idx 时描述凭空写错（与实现从未一致）；② 正确描述（AgentCliDocTables）与错误描述（BuiltinCommandIndex）**双源并存 9 版**——agent.cli 一直显示正确的，错误只在 self.search 索引里；③ v0.34.3 P2-8 合并"单一数据源"时把 CLI.md 描述来源切到 CommandSearch（错误源），错配第一次进文档才暴露；④ IndexCoverageTest 只锁键集不锁语义，手写错误无测试拦截。
- **处置**：修正描述 + AntiAmbiguityTest 加**描述语义锁**（audit 含"审计"不含"安全检查"、auto 含"省电"不含"自动更新"、cleanup 含"截图/收件箱"、output 标"只读"）。
- **CLI.md 整体移除 (v0.34.3 用户拍板)**：22KB 命令参考不再生成/常驻 — `agent.cli` 改为轻量指引，命令发现完全走 `self.tools`（运行时枚举，天然新鲜）+ `self.search`（CommandSearch，IndexCoverageTest 锁覆盖）。生成链路（CliDocGenerator/ensureCliDoc/命令指纹/CliDocSyncTest）全部删除；设备旧残留 cli.md 无害。
- **教训**：① 手写元数据（描述/关键词）必须对照实现，且要语义测试锁；② **双源并存 = 定时炸弹**——合并时选错源会把潜伏错误带入生产路径；③ 大型参考文档（22KB）对 Agent 是每轮负担，"自动生成"不等于"值得常驻"，运行时枚举+检索索引是更轻的发现路径。


*最后更新: 2026-08-09 · §1-14 主题经验 + §15 历史教训浓缩库（原 LESSONS.md 118 条 → 约 80 条要点）*
