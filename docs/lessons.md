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

### 14.8 静默判定阈值与网络门卫 (v0.29.2, 高铁场景用户追问)

- **对齐 Reasonix 阈值**：readTimeout 180s → 120s（静默判定）。余量论证：思考期 60s+ 无数据，120s 仍留 ~60s；Reasonix 以 120s 上线多年。加 `pingInterval(60s)`：HTTP/2 主动探活，半死连接 60s 内被发现（比静默超时早 60s），HTTP/1.1 无副作用。两者合计把"断连感知"追平 Reasonix。
- **用户提议 → 网络门卫 SPI**：`NetworkConditionGate`（kernel 接口，零 Android 依赖）+ `NetworkConditionMonitor`（shell 实现）。断网 → 重试失败快返（6 次退避 + fallback 链全跳过，错误气泡直出，不烧配额/电量）；弱网（VALIDATED + 带宽档位）→ 退避 ×3/×1.5。
- **权限现实**：蜂窝 dBm 需 READ_PHONE_STATE（危险权限 + 运行时弹窗），拒绝；用免权限代理（onAvailable/onLost + VALIDATED + linkDownstreamBandwidthKbps）——对"避免注定失败的重试"目的足够。升级路径：WifiManager RSSI（ACCESS_WIFI_STATE 普通权限）。
- **坑**：kernel 零 Android 依赖是硬约束——信号源必须走 SPI 注入（先例 KernelLog.logger）；provider 构造点有 7 处（AppRoot×2 / AgentSessionFactory×3 / DreamWorker×1），漏一处就静默退回无门卫路径。


---

## 15. 历史教训浓缩库（v0.2.2 ~ v0.23.0，原根 LESSONS.md 去重提炼）

> 原根目录 `LESSONS.md`（1088 行编号教训）2026-08-05 删除，118 条去重（含 3 对重复条目）并按主题浓缩为要点。
> 已被 §1-14、记忆（bug-audit-methodology / no-broken-code-push / release-checklist / no-release-without-ask）、RELEASE.md 覆盖的条目不再重复。

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


*最后更新: 2026-08-05 · §1-14 主题经验 + §15 历史教训浓缩库（原 LESSONS.md 118 条 → 约 80 条要点）*
