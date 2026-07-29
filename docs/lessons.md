# MengPaw 开发经验教训

> 从 v0.16.0 开发中提炼。含性能优化、模式重构、命令检索、插件生态共 14 条。

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

*最后更新: 2026-07-30 · 提炼自 v0.18.4 完整开发周期*
