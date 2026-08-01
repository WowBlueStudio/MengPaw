# Changelog

## v0.25.0 (2026-08-02) — 火种模式（Swarm Mode）

### 新增
- **火种模式（Swarm）**: `AgentEngine.runWithSwarm()` — 规划器拆解 → 并行 Worker（`roles` 按角色混合不同模型）→ Verifier 验证 → 合成器输出。命名释义"星星之火，可以燎原"。设计文档见 [docs/swarm-design.md](docs/swarm-design.md)
  - JIT 看板三闸门: `maxTotalSteps` 共享步数预算（AtomicInteger CAS）+ `maxParallel` WIP 并行上限 + `maxStepsPerSubtask` 单任务闸
  - Andon 失败协议: worker 失败回报协调器决策（重派可切 `worker.alt` 模型 / 终止），不静默重试
  - 零待命 Worker: 独立 Session（`scope="swarm"`）用完即销毁，无跨任务记忆；轻量 ReAct 循环复用全局 Pipeline，不建完整 AgentEngine
  - 上下文分片: worker 不入 `conversationSessionId`，只回报结构化结果卡片 `SwarmResultCard`
  - `runWithFleet()` 转发到火种模式（默认单模型，向后兼容）
- **UI 触发**: 设置页 Loop 模式新增"火种模式"选项（`LoopMode.SWARM`），AgentViewModel 分发到 `runWithSwarm`
- **系统提示词**: 中英双语新增 /Swarm（火种模式）说明
- **worker 记忆屏蔽**: `ExecutionContext.scope` 字段，`agent.memory.*` 写入命令对 swarm 会话静默屏蔽（防并行噪音污染三轨记忆）

### 修复
- `SessionManager.createSession`/`deleteSession` 并发 CAS 竞态: 并行 worker 创建会话会丢更新、并行删除会复活会话（`@Synchronized`）

### 测试
- `SwarmModeExecutorTest` 12 用例: 混合模型角色分发 / 并行时序 / 会话隔离回归锚点 / 预算闸停线 / Andon 重派与终止 / Fleet 兼容 / 缺省回退 / SwarmBudget / 拆解兜底 / 记忆屏蔽

### 发行
- Shell APK v0.25.0 (versionCode 25000)

## v0.24.0 (2026-08-01) — 双许可 + 连接器拆分独立仓库 + 插件市场接线

### 新增
- **双许可**: 社区版 AGPL-3.0 免费 + 商业授权（闭源分发/白标/嵌入/不公开修改源码的服务化部署需购买），见 COMMERCIAL-LICENSE.md；SPDX 头全量更新为 `AGPL-3.0-or-later OR LicenseRef-Commercial`
- **贡献政策**: 主仓库仅接受 Bug 报告与功能请求（Issue 模板），暂不接受 PR；连接器仓库社区开放贡献
- **连接器拆分**: 5 个连接器模块移至独立仓库 [mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors)（MIT 许可，独立构建，内核依赖 JitPack 构件）
- **插件市场接线**: 5 条连接器条目补齐 downloadUrl/checksum/size，指向 mengpaw-connectors release plugins-v0.1.0（openclaw 首次正式发布）；校验 8 错→3 错
- **Gitee 自动同步**: 主仓库 + 连接器仓库均新增 gitee-sync workflow（git-mirror-action）

### 修复
- 文档: 「GitHub Pages 托管 plugins.json」修正为 raw 直读双源（GitHub raw / Gitee raw）；补 2 个历史缺失 SPDX 头的文件

### 重构
- settings.gradle.kts 模块 26→21（连接器移出）；开发文档新增 §11 许可证与商业

### 发行
- Shell APK v0.24.0 (versionCode 24000)

## v0.23.0 (2026-08-01) — 智能体进化 SPI + 外置连接器 ×4 + 大文件拆分

### 新增
- **智能体进化 SPI 化**: 内核 EvolutionProvider 接口 + 注册表 + EvolutionEngine 默认实现 (仿梦境模式); 内置 plugin-evolution 注册默认实现 (UNINSTALLABLE 锁定, 第三方可实现接口覆盖); evolution.* 命令保持内核注册
- **外置连接器插件 ×4** (FrameworkAdapter SPI, 外部分发不捆绑, 经 framework.connect/call 委派任务到 PC):
  - plugin-connector-common 共享库 — jsch (MIT) SSH 传输 + 交互式通道 + 凭据原子存储 + config/info 命令
  - Claude Code 通讯 (SSH → claude -p headless) / Reasonix 通讯 (SSH → reasonix run) / TREA IDE 通讯 (SSH → trae-cli run)
  - QwenPaw 通讯 v0.2.0 真实协议重写 — REST 8088 (POST /api/console/chat, SSE 流) + SSH ACP 实验通道 (stdio JSON-RPC)
  - 上游许可全部兼容: Claude Code 闭源商业 CLI 仅互操作调用 / QwenPaw Apache-2.0 / DeepSeek-Reasonix + trae-agent + jsch MIT
- **插件装配清单 PluginRegistrar** — 内置插件 ID/WowBlue 标识/显示信息/类注册/自动安装五份数据独立成文件, 新增捆绑插件只改一处
- plugins.json v7 (28 条目: 12 builtin + 14 remote + 2 embedded)

### 修复
- 框架通信十二问审计 P1×4+P2×5: BM25 索引补 5 条 / EvolutionStore 三处原子化 / PluginManager 生命周期对称 (disable→onUninstall) / framework.add 手动添加 / discover 异步化 --wait / trust --yes 二次确认
- 连接器审计 P2×6: 工具可见性 (toolsDescription + framework.adapters 输出) / config --yes 确认 / QwenPaw Bearer token / AcpOverSsh close 清理 / SSH 防火墙提示
- 连接器闪退与泄漏审查: textBuffer 竞态 synchronized / describe !! 清零 / channel 与重复 connect session 泄漏
- Ports.QWENPAW_REST 8080→8088 (官方默认端口); self 标签「Agent 进化」误伤 9 处 →「Agent 自我管理」

### 重构
- AgentExecutor 53.5KB → 31KB (memory.* 18 命令移至 AgentMemoryExecutor); MainActivity 52.5KB → 46KB (装配清单移至 PluginRegistrar) — ≥50KB 大文件清零

### 发行
- Shell APK v0.23.0 (versionCode 23000)
- plugins.json v7 (28 条目, 含 4 个 connector 新条目 + qwenpaw 0.2.0)
- 测试: 内核 187 全绿 + connector-common 单测 6/6

### 新增
- **设备内 MCP 通道打通（浏览器 MCP 首次真正工作）**：浏览器 APK 内置 McpHttpServer（127.0.0.1:9880，GET /health + POST /mcp）；plugin-browser-mcp 改 HTTP 桥——根因是跨进程静态字段赋值因类加载器隔离不可见；Shell 侧 BrowserReturnWatcher 轮询 browser_return_*.md → FileProvider 预览回传
- **网页转档管道**：plugin-browser-search 重定义（网页转档）——HtmlConverter 网页→Markdown + extract/summary/engines/clean/md/outputs/clear 7 命令，删除重复 search.fetch
- **Agent 前台唤醒浏览器**：`sys.browser.open` 命令 + PromptEngine 浏览器协作段落重写（三通道：唤醒 / MCP 工具 browser.mcp.* / 网页转档 search.*）
- **框架通信协议升级 — 协议进内核, 连接器进插件**：
  - 内核 McpServer 补 `tools/call`（插件命令 + McpToolProvider 委托）
  - ACP 增强：AcpMessage.requestId + DISCOVER 版本协商 + MCP_REQUEST/MCP_RESPONSE 完整往返（McpOverAcpBridge）
  - FrameworkAdapter SPI + Registry（内核不持有具体框架实现）
  - plugin-framework 升级内置协议插件：本机标准 MCP server（localhost:9881 McpGateway）+ framework.connect/call/disconnect/adapters
  - 外部分发连接器插件（remote 不内置）：connector-openclaw（WS 18789）/ connector-qwenpaw（REST 8080）
- **梦境模式 SPI 化**：内核 DreamProvider + DreamProviderRegistry（第三方可整体替换梦境管道，后注册者胜，默认回退 DreamEngine）；plugin-dream 内置插件（UNINSTALLABLE 保护，不可直接移除）
- docs/PROTOCOL.md 协议接入指南

### 修复
- 浏览器 MCP 从未工作的根因：类加载器隔离下跨进程静态字段赋值不可见 → 替换为 HTTP bridge
- error-report/update 插件失效反射：remote 插件不能编译期依赖 → MainActivity Class.forName 反射注入
- agent-tools SSRF 面；fs 命令去重；插件提示词与命令列表对齐
- toolsCall 字符串插值注入面 → JsonObject 构造

### 移除
- 退役 4 半成品插件（notification/workflow/incubator/browser-inspector）+ browser-cdp（无用户安装，不做旧兼容）
- 22 个无消费者 plugin-manifest.json 遗留文件；tribe-vs-hermes-comparison.md（目标已达成）

### 发行
- Shell APK v0.22.1（versionCode 22001）
- plugins.json 24 条（11 builtin + 11 remote + 2 embedded，新增 dream-plugin + 2 connector）
- 测试：kernel 188/188 全绿（含 DreamProviderTest 4 用例），APK 签名已验证

## v0.22.0 (2026-08-01) — 单轨记忆 + 孪生工作区同步 + 梦境管道重构

### 新增
- **单轨记忆化**：`{agent}/memory/` 三轨持有全部记忆，旁轨 `memory.md` 轨道删除——任务记忆（recordTaskMemory）改接三轨中期，DreamEngine.buildContext 删除旁轨读取段，agent 模板同步删除 memory.md
- **孪生改造 — 哈希链账本 → 工作区文件同步**：同步单元从账本条目改为整个 `{agent}/` 工作区——TwinWorkspace 清单（SHA-256+mtime）比对 + 新协议 WS_MANIFEST/WS_PULL 差异传输 + LWW 冲突落盘（.conflict 备份 + 审计 + PromptEngine 缓存失效钩子）。同步范围：根文档（soul/profile/agents/boost/trigger/HEARTBEAT/{date}_dream.md）+ `memory/` 全部；排除 CLI.md / inbox/ / dialog/ / memory/backup/
- **梦境管道重写**：读全部中期分片 → 复制 `memory/backup/` → 提炼 `{agent}/{date}_dream.md`（同日多次追加，新条目前置）→ 删除已整理分片 → 30 天前备份自动清理（替代旧 mem- 解析/归档；产物 DREAM.md → {date}_dream.md，随孪生工作区同步传播）

### 移除
- `twin.ledger.*`（6 条：show/verify/diff/stats/repair/encrypt）+ `twin.identity.*`（4 条：push/pull/diff/merge）+ `twin.dream.*`（2 条：sync/history）——账本删除、身份文档随工作区自动同步、梦境产物随工作区同步传播；删除 TwinLedgerStore/TwinLedger/TwinDreamSync/TwinIdentity/rebuildMemoryDoc/applyDreamEntry/applyIdentityUpdate（twin.* 命令 29→16）
- DataPaths.TWIN_LEDGER / TWIN_DREAMS 路径
- AgentDocs.deleteDream 死代码

### 修复
- **AcpTransport 响应体丢弃（账本同步端到端从未跑通的根因）**：原 send() 只发不读 HTTP 响应体——新增 sendForResult() 解析响应体，请求-响应一轮完成
- **twin 双引擎债务**：cmdStart 复用 MainActivity 激活时创建的 activeEngine，避免双引擎
- 删除 peers.json 只写不读；配对签名不再依赖 LedgerEntry.sha256

### 发行
- Shell APK v0.22.0（versionCode 22000）
- plugins.json 同步 twin 命令列表
- 测试：kernel 169/169 全绿，APK 签名已验证

## v0.21.1 (2026-07-31) — 记忆系统融入内核 + 任务记忆接入 Dream

### 新增
- **记忆查询能力并入内核 `agent.memory`**(18 条,原 14 + 新 4):
  - `agent.memory.read <id>` — 按 ID 跨三轨(长期/中期/项目)读单条,歧义检测复用 countMatchingEntries
  - `agent.memory.search <关键词> [--track long|mid|project]` — 跨轨搜索,复用 AgentDocs 三轨 search API(此前零调用者的库函数首次接线)
  - `agent.memory.stats` — 三轨统计(长期条数/中期日期分布/项目数)
  - `agent.memory.write <id> <内容>` — 指定 ID 写长期(已存在则更新,AgentDocs.appendLongTermMemory 新增 title 参数)
- **任务记忆接入 Dream 管道**: DreamEngine.buildContext 新增"任务记忆"输入段(读 `{agent}/memory.md` 系统管道)——梦境分析从"中期+长期"扩为"中期+任务+长期",任务记忆首次进入 LLM 视野(生产端不变:memory-twin 重建落点与 Incubator 统计兼容)
- CLI.md 生成器/BM25 索引新增 4 条命令

### 退役
- **plugin-memory 退役**(构建/注册/plugins.json/_artifacts.json/设置页/文档 20+ 处同步):`memory.*` 6 命令独立库并入内核——审计确认零程序化依赖(Tribe 走 ACP、DreamEngine 走三轨、memory-twin 独立包),memories 目录弃用(实际未启用,坏引导的 browser-tools 文档引用改写为 `agent.cli`/`skill.run`)
- 内置插件 12 → 11,plugins.json 28 → 26 条目,捆绑口径同步

### 修复
- **AgentDocs.writeAtomic Windows 覆盖 bug**: `tmp.renameTo(file)` 在目标存在时失败(Windows),editEntry/deleteEntry 静默失效——同文件 appendLongTermMemory 有 `file.delete()` 注释处理,writeAtomic 漏掉;由新测试暴露(生产 Android 不受影响)

### 发行
- Shell APK v0.21.1(versionCode 21001)
- plugins.json 移除 memory-plugin 条目(11 builtin)
- 测试: kernel 169/169 全绿(162 既有 + 7 新增记忆命令测试)

## v0.21.0 (2026-07-31) — Agent 进化系统 + plugin-self 退役

### 新增
- **进化系统（内核内置）**：Agent 从失败中学习的能力，取代已退役的 self-plugin——钩子归系统、省察归 Agent、终极 KPI 是"问题不复现"：
  - **失败钩子单点挂接**：`ErrorCollector.onReport` 回调，Pipeline/AgentEngine 全部失败（TOOL_CALL_FAILED / LOOP_DETECTED / AGENT_CRASH / failAudit）自动流入失败模式库，零调用点改动
  - **失败模式库**：`{AGENTS}/{agent}/evolution/failures.jsonl` 持久化，同模式（命令+错误码）第 2 次起判定"复现"（repeatCount）
  - **金字塔省察引导**：失败后下次 LLM 调用注入——轻失败一句提示+命令检索；复现失败四层自问（L1 事实 → L2 归因 → L3 用户视角 → L4 进化）+ 错误四分法处置（指令集/memory/soul.md/框架反馈），每会话限 3 次防刷屏
  - **用户反应档案（用户分身）**：AgentViewModel 识别纠正信号与撤回动作 → reactions.md，供 L3 用户视角检索
  - **绩效闭环**：`evolution.audit` 绩效报告 / `evolution.mark-corrected` 标记沉淀 / 会话开始注入未修正复现提醒
- **evolution.\* 命令命名空间（5 条）**：audit（绩效报告）/ report（框架反馈落盘+NotifyBus 推送）/ learn.command（指令集丰富，封装 CommandSearch.registerOrUpdate）/ reactions / mark-corrected
- **CLI.md 生成器 evolution 命令表** + BM25 命令索引 5 条（保留"自省"作为搜索同义词）

### 修复
- **AcpProtocolTest 硬编码断言过时**：删 22 类型数量断言（现 24 类型），改循环遍历 `AcpMessageType.entries`

### 变更
- **plugin-self 退役**：4 命令插件从构建/注册/文档/plugins.json 全部移除，`self` 完全归属内核 16 命令（此前插件同名命令经后写覆盖语义静默胜出，存在归属歧义）
- **全仓"自省"→"进化"改名**：README / 开发指南 / CHANGELOG / skill 文档 / 设置页同步

### 发行
- Shell APK v0.21.0（versionCode 21000）
- plugins.json 移除 self-plugin 条目
- 测试：kernel 162/162 全绿（155 既有 + 7 新增进化测试）

## v0.20.2 (2026-07-31) — 插件开发工具能力边界文档植入

### 新增
- **dev.plugin.guide 能力边界文档**: 插件开发工具（dev-plugin）能力边界总结为 md 随插件分发——命令清单/插件类型/开发流程/命名规范/审计规则/端口说明/能力边界（不能做什么）/发布链路 9 节
- **Agent 可读**: `dev.plugin.guide` 命令输出全文（dev-plugin 命令 5→6 条）
- **用户可读**: 自动落盘 `插件文档/plugin-dev-guide.md`（文件管理器可打开），安装/升级插件即写入
- **onInstall 联动**: 安装 dev-plugin 时自动确保文档落盘

### 修复
- Kotlin 三引号字符串不能用于 const val（编译错误）→ 普通 val

### 发行
- Shell APK v0.20.2（versionCode 20002）
- plugins.json dev-plugin 命令 5→6 条
- 测试：plugin-dev 6/6 全绿（新增 guide 测试：内容 + 落盘校验）

## v0.20.1 (2026-07-31) — 插件开发工具升级 + Agent 端口感知

### 新增
- **Ports.kt 端口单一事实源**: `mengpaw-kernel/.../ports/Ports.kt` 集中定义 7 个端口（9876 ACP / 9877 LLM / 9878 Office MCP / 8188 ComfyUI / 18789 OpenClaw / 8080 QwenPaw / 9528 collab-cli），替换 ≥17 处散落魔法数字（kernel/shell/plugins 三侧）
- **self.ports 命令**: Agent 可一键查询本机监听（ACP）与外部服务默认端口表，支持 `--json` 结构化输出；系统提示词新增「网络端口」章节（中英双语占位符注入），CLI.md 新增端口参考段
- **PluginMetadata.ports 端口声明**: 插件可声明占用端口，PluginManager.install 冲突检测拒绝同端口插件；市场协议 plugins.json 支持 `ports` 字段（comfy 条目示范 [8188]）；DevPlugin 模板含 ports 声明、audit 新增端口检查（9876 保留端口 🔴 / 越界 🟡）
- **插件开发工具链**: `scripts/build-plugins.ps1` 重写（模块列表动态派生自 settings.gradle.kts，26 模块零遗漏；产物 `releases/plugins/plugin-<name>-<version>-release.aar` 先清空再复制）+ `scripts/update-plugins-json.py`（checksum/size/changelog 回写，规避 PS5.1 中文转义）+ `scripts/validate-plugins.ps1`（结构/字段/SemVer/URL/checksum 与 AAR 实际比对/与代码交叉校验/端口检查）
- **plugin-dev skill**: `.claude/skills/plugin-dev.md` 插件开发发布全流程（创建→审计→构建→plugins-v tag 发布），与 release skill 分工
- **DevPlugin 骨架审计通过**: SCRIPT 骨架默认 description、NATIVE 模板 resolvePath try/catch（骨架生成后可直接通过 audit）；DevPluginChainTest 5 个链路测试（create→audit→端口冲突→examples）

### 修复
- **文档与代码对齐**: PLUGIN_DEV_GUIDE.md / 主指南 / CONTRIBUTING 统一为 v0.20.0 口径——插件类型 NATIVE/SCRIPT（删虚构 JAR/AAR）、删虚构 plugin.build/test/publish、命令前缀 dev.plugin.*（含 keywords）、插件数统一（26 模块 / 13 捆绑 / plugins.json 28 条目）、安全规则对照 audit 实际检查项
- **plugins.json 状态修正**: tribe-plugin remote→builtin（模块已随 APK 打包）、browser-cdp-plugin remote→deprecated（已下架）、dev-plugin 命令 4 条→5 条（dev.plugin.* 前缀 + keywords）、comfy 补 ports [8188]
- **CLI.md 过期插件表**: 删已删模块（ui/proc/vision/audio/pad），按实际 28 条目重列（内置/远程/嵌入三组）
- **DevPlugin 模板审计缺陷**: SCRIPT 骨架缺 description（空串触发 🔴）、NATIVE 模板 File 无 try/catch

### 发行
- Shell APK v0.20.1（versionCode 20001）
- plugins.json 新增 ports 字段协议（向后兼容）
- 测试：kernel 154/155（AcpProtocolTest round-trip 已知预存在失败）+ plugin-dev 5/5 全绿

## v0.20.0 (2026-07-31) — Agent 命令集注册 + 设置页 UI 信息一致性

### 新增
- **Agent Tools 命令集注册（新内置插件 plugin-agent-tools）**: Agent 通过 `tools.import <名称> <URL|JSON>` 导入外部 CLI 命令集（GitHub CLI / 飞书 CLI 等），`tools.ls` / `tools.remove` / `tools.search` 管理检索
- **命令集摘要注入系统提示词**: 注册后紧凑摘要（每集 400/总 2000 字符截断）注入提示词，Agent 每次对话直接可见，无需遍历完整命令文档；≤5s 自动同步
- **per-agent 存储**: `Agent文档/{agent}/tools/{name}.json`，命令集上限 20 个/Agent、单集 200 条命令、512KB 校验
- **WowBlue 标识补齐**: 记忆孪生/部落协作/Agent 命令集/记忆三轨/双层技能池/mDNS 框架发现/插件开发链 7 个领先插件带粉标
- **设置页五个列表区块默认折叠**: 全局插件/全局工具/全局技能/智能体工具/智能体技能，header 带条目计数与展开箭头
- **工作区文件 memory 目录聚合**: 三重记忆（长期 memory.md / 中期 memory_{date}.md / 项目 project_{name}_memory.md）聚合条目，展开查看全部文档
- **工作区文档删除**: 列表项可删除（含 boost.md，走内核删除语义），删除有确认对话框

### 修复
- **全局插件列表**: BUILTIN_PLUGIN_IDS 补全 12 个（漏 memory-twin/root/tribe），内置未安装插件兜底显示，列表打开设置页实时刷新（修复启动竞态）
- **全局工具**: 动态插件命令显示真实命令名（原为 "." 占位）
- **全局技能**: 只显示 /技能剧本/ 真实技能文件（删除 skill.ls 命令名混入，v0.19.5 原则完整落地）
- **智能体工具**: 不再把全局工具冒充专属工具（LESSONS 99），改为显示 Agent 命令集注册
- **智能体技能**: 显示 per-agent 本地技能（修复全局技能冒充），空态文案"暂无触发器"→ 正确的本地技能引导
- **DataPaths 双重路径 bug**: agentSkillsDir/agentToolsDir 双重拼接 `Agent文档/Agent文档/`（safeAgentDir 已含前缀），修复 + 旧数据一次性懒迁移
- **智能体工具标题**: 硬编码 → 双语资源"智能体工具(Agent Tools)"

### 发行
- Shell APK v0.20.0（versionCode 20000）
- plugins.json 市场新增 tools-plugin（内置）
- 22 个新单测（AgentToolsTest 全绿）

## v0.19.7 (2026-07-31) — 部落协作十特性全量上线

### 功能增强 (P1)
- **Tribe 重命名**: HermesPlugin 升级为 TribePlugin（部落协作），hermes.* 命令向后兼容
- **Kanban 看板状态机**: 任务全生命周期（PENDING→ASSIGNED→RUNNING→COMPLETED/FAILED/TIMED_OUT/CANCELLED），JSON 持久化
- **ACP 实时委派**: 双模委派（文件/AUTO），优先级 P0/P1/P2，指数退避重试（30s→60s→120s）
- **心跳检测**: 30s 心跳广播 + 120s 对端超时清理，tribe.peers/ping 在线检测
- **LAN 自动组队**: tribe.discover --lan 同步 FrameworkPeerStore 局域网框架成员
- **看板竖条可视化**: 框架通讯录条目右侧竖条（绿=完成/黄=排队/黄闪烁=执行/红=错误）
- **任务模板**: tribe.delegate --template（summarize/translate/research/review/brainstorm/draft）
- **LLM 能力路由**: tribe.route / --route 基于角色+历史成功率智能分配
- **收件箱自动感知**: Agent system prompt 注入待办提醒 + NotifyBus 用户通知
- **Fleet 并行**: tribe.fleet LLM 分解→并行委派→LLM 合成
- **嵌套委派链**: --parent 最多 3 层 + 环形检测 + 结果沿链回传（tribe.task.done）
- **共享记忆去重+压缩**: SHA256 指纹 + 100 条自动 LLM 摘要
- **多人聊天/广播**: tribe.chat（ACP TRIBE_CHAT）+ tribe.discuss 多 Agent 讨论
- **上下文裁剪传递**: --context 裁剪对话上下文附带（ref:// 引用）

### 内核改动
- **TRIBE_CHAT 消息类型**: AcpMessageType + AcpMessage.tribeChat() 工厂
- **AgentEngine middleware 可变**: setMiddleware() + 无副作用 refreshSystemPrompt()
- **AcpServer**: sendViaTransport() + DELEGATE 分发不 break + delegate() 清理

### 架构完善 (P2)
- **TribeAcpHandler**: onDelegate inbox 写入修复（接收方目录）
- **Companion Object DI**: llmProvider/acpServer 注入（参照 MemoryTwinPlugin）
- **插件依赖**: shell→plugin-hermes, plugin-hermes→plugin-framework

## v0.19.6 (2026-07-31) — 记忆孪生链路完整修复

### 安全修复 (P0)
- **配对安全**: 未配对设备的 CAPABILITY_ANNOUNCE 不再写入配对请求 inbox
- **超时安全**: 同步超时 deferred 使用 tryComplete CAS 模式，防止泄漏
- **运行状态真实采集**: RuntimeStatus 不再全硬编码，isOnline 使用 ConnectivityManager 检测

### 功能增强 (P1)
- **配对冷却期**: 10 分钟内最多 3 次，超限锁定 30 分钟
- **远程撤销 REVOKE**: 新增 twin.lost CLI 命令，广播解绑到所有节点
- **集群梦境协调**: 整个集群只需一台设备执行梦境，6 小时内防重复
- **自动能力采集**: 注册 Android 广播监听电池/网络/充电变化
- **运行时状态注入**: currentSessionId/isBusy 从 AgentEngine 读取真实值

### 架构完善 (P2)
- **冲突解决**: soul.md/profile.md 冲突时保存 .conflict 备份文件
- **账本修复**: 新增 twin.ledger.repair 命令
- **可选加密存储**: twin.ledger.encrypt on/off 控制 AES-256-CBC 加密
- **设备丢失应急**: twin.lost → broadcastRevoke → mark compromised → twin.recover

### 内核改动
- **REVOKE 独立消息类型**: AcpMessageType.REVOKE + AcpMessage.revoke() 工厂方法
- **AgentEngine 状态暴露**: 新增 activeSessionId / isExecuting 公共属性

### 代码质量 (P3)
- **统一 kotlinx.serialization**: 替换 org.json 手动拼接
- **防御拷贝**: getPeers() 返回不可变深拷贝
- **空字符串处理**: lastAckedHash 空值正确处理

## v0.19.5 (2026-07-30) — 清理硬编码 + 全局技能剥离 CLI 管理命令

### 修复
- **全局技能移除 skillMgmt**: 不再把 skill.ls/skill.run/skill.create 等 10 条 CLI 管理命令混入技能列表
- **全局工具动态化**: 内置命令保留 curated 描述，末尾动态追加已激活插件命令
- **全局技能标签修复**: "全局工具(Skills)" → "全局技能(Skills)"
- 全局技能只显示 /技能剧本/*.md 的真实 Skill 文件

### 修复
- **全局工具列表动态化**: 保留内置命令curated描述，末尾动态追加已激活插件命令
- **全局技能标签修复**: "全局工具(Skills)" → "全局技能(Skills)"，消除命名混淆

## v0.19.4 (2026-07-30) — 智能体设置页重构 + 五区块关系梳理

### 架构
- **五区块明确分工**: 框架设置(全局插件/全局工具/全局技能) + 智能体设置(专属工具/本地技能)
- **Agent Tools 加回**: 智能体专属工具入口就绪，支持动态展开

### UI
- **智能体设置页精简**: 移除全局工具索引、全局 Skills 池、分区工具
- **Agent Skills 动态列表**: 有 Markdown 内容的可点击展开，无内容的不显示展开箭头
- **Agent Tools 动态列表**: 同上，空态提示"暂未配置专属工具"
- **Framework 设置页重排**: 插件管理按钮移到全局插件前面，清理重复分隔线
- **暗色模式修复**: NavigationLink 背景改用 ThemeColors.bgCard

## v0.19.3 (2026-07-30) — 暗色模式修复 + WowBlue 标识补齐 + FlowRow 手机适配

### 暗色模式修复
- **NavigationLink**: 背景 ArcoColors.Gray1→ThemeColors.bgCard，箭头 Gray5→textSecondary
- **"需安装插件"标签**: 背景 Gray3→bgCardHigh，文字 Gray6→textSecondary

### UI 修复
- **Provider 预设 Chip**: Row→FlowRow，手机宽度下自动换行，不再溢出

### WowBlue 标识
- **FLEET 模式**: LoopMode 卡片添加粉色 WowBlue 徽标
- **捆绑插件**: 动态 isWowBlue 标记，memory-twin/framework/dev 等自动带徽标
- **补齐标记**: agent.boost/browser-tools/self.trigger/self.avatar/self.theme 等

## v0.19.2 (2026-07-30) — 双层 Skills+Tools 架构 + FLEET 重命名 + WowBlue 标识

### 架构
- **双层 Skills 池**: 全局池(/技能剧本/) + Agent本地(skills/{name}/)，skill.pull/push，skill.run 先查本地再查全局
- **skill.rm**: 新增删除本地技能命令
- **FLEET 模式**: Mission+ 重命名为 Fleet，新增独立 runWithFleet() 引擎方法
- **SCHEDULE 触发器**: 从 LIFETIME 改名，支持可配 count/interval，±5min 抖动

### UI
- **WowBlue 标识**: 原创功能(sys.*/agent.dream/self.trigger等)加粉色WowBlue徽标
- **CRON/SCHEDULE 对话框简化**: 去掉预设选项，纯输入+引导找Agent配置
- **Agent 设置页**: Tools 只读索引，Skills 双层显示(本地+全局池)
- **@mention 修复**: DropdownMenu→内联Surface，消除输入法闪烁
- **全局 Skills 池**: 显示可用技能列表，每项带"拉取"按钮

### 系统提示词
- 📋 Skills 双层池引导(优先查本地)
- 🚀 BOOST.md 首次引导注入
- ⏰ HEARTBEAT.md 定时任务规则注入

### 其他
- 框架Agent首次访问自动bootstrap boost.md
- 活跃标签行移入消息区，不干扰侧边栏
- plugin-clipboard 粘贴按钮移除
- 三层十二问审计修复

## v0.19.1 (2026-07-30) — UI 调整：标签行移入消息区 + 删除模式按钮行 + @mention 修复

### 布局调整
- **活跃标签行移入消息区**: 从全宽 Column 移入消息区 Box，约束在 msgWidth 下，不再干扰左侧边栏长度
- **执行模式按钮行整行删除**: 输入栏顶部 /Mission /Research /Translate /Silent 横排按钮移除，底部弹窗仍可访问
- **Mission/Goal 互换顺序**: 扩展底部弹窗执行模式区 Goal 居左

### @mention 修复
- **DropdownMenu→内联 Surface**: DropdownMenu 创建 Popup 窗口与输入法 IME 冲突，每次输入字母输入法跳出。改为内联 Surface 不走 Popup，消除焦点争夺

### 插件 UI 清理
- **clipboard 粘贴按钮移除**: Agent 内部剪贴板命令不暴露给人

### 底部弹窗优化
- **插件工具区空态显示 "<空>"**: 无激活按钮时不占按钮布局高度

## v0.19.0 (2026-07-30) — 代码审查全量修复 + 超大文件拆解重构

### 全量代码审查与功能审计
- **九维代码审查**: 按 9-Dimension 方法论对 v0.18.0~v0.18.4 新增代码进行全面审查
- **三层十二问功能审计**: 对会话恢复/ACP同步/事件总线/中断恢复子系统逐条过审
- **P0 修复**: `DefaultCommandExecutor` shell 注入漏洞 — `sh -c` 替换为带元字符检测的沙箱, 添加 30s 超时实现
- **P1 修复**: 10 项 — 非原子文件写入(8文件), readLines OOM(4文件), 插件循环依赖检测, ACP JSON注入, 事件日志完整性缺失
- **P2/P3 修复**: 50+ 项 — 空catch日志(10+文件), renameTo Windows兼容, Locale统一, ProGuard去重, CHANGELOG补充

### 超大文件拆解
- **AgentViewModel.kt** (70KB→35KB): 提取 SessionPersistenceService / AgentSessionFactory / InputTagManager / ComplexityDetector
- **MainScreen.kt** (65KB→35KB): 提取 ChatBubbles / InputComponents / SidebarOverlay / FilePickerUtils
- **AgentEngine.kt** (58KB→28KB): 提取 AgentEngineTypes / AgentErrors / ToolResultManager / PipelineManager / GoalModeExecutor / MissionModeExecutor / PlanModeExecutor
- **BrowserActivity.kt** (51KB→25KB): 提取 BrowserTopBar / NewTabPage / DesktopTabBar

### 代码质量
- `org.json` → `kotlinx.serialization` 迁移 (5文件25处)
- 国际化: 107条中英文字符串迁移, 7个TODO(i18n)解决, 5个设置文件本地化
- SectionHeader 去重: 6份私有定义 → 共享 design/components 组件
- AgentExecutor 硬编码"MengPaw"(19处) → agentName(ctx) 辅助方法
- PromptEngine 缓存陈旧修复 / SHA256格式校验 / DefaultPluginContext inner→class / AgentSession封装
- 新增 24 项 kernel 测试 (InterruptedRecoveryTest + CheckpointManagerTest + SessionManagerTest扩展)

## v0.18.4 (2026-07-30)

### 会话恢复 (Session Recovery)
- **Level 1 流中断**: AgentEngine catch 块记录已完成工具 → `recordInterruptedTurn()` 注入恢复块
- **Level 2 中断轮次**: `InterruptedTurnRecovery` 数据结构 + `localOnly` 安全过滤 + 结构化恢复块注入
- **Level 3 持久化**: `CheckpointManager` 每 5 步自动保存 + `restoreConversation()` 进程死亡恢复
- **事件总线**: `SessionEventBus` (11 种事件种类, SharedFlow) + 持久化 JSONL 事件日志
- **恢复决策树**: `decideRecovery()` 5 种策略 (NoAction/SimpleRetry/RecoverFromInterrupt/RecoverWithGoal/SuggestCleanup)
- **完整性终端锁**: `checkSessionIntegrity()` + `integrityFailed` 门控 — 损坏数据阻断 LLM 调用
- **事件日志裁剪**: `pruneSessionEvents()` 防止 JSONL 无限增长
- **Schema 迁移**: `migrateSession()` + `schemaVersion` 基础设施
- **进程死亡恢复**: `engineSessionId` 持久化 + checkpoint 消费 + 重新挂载引擎会话

### 流式传输修复 (Streaming)
- **SSE 逐行解析**: `AdaptiveLlmProvider.consumeSseStream()` — 替代 `bodyAsText()` 整块读取
- **RemoteApi 流式修复**: 同样的 SSE 逐行解析模式
- **onToken 回调**: 每条 delta content/reasoning_content 即时回调

### ACP 会话同步 (SessionSync Protocol)
- **4 种新消息**: `SESSION_HEAD/PULL/DELTA/ACK`
- **SessionSyncHandler**: 基于 `SessionEventBus` + `SessionManager` 的事件级会话同步
- **AcpServer 集成**: 自动注册 + 消息路由 + 配对信任门控
- **跨设备会话恢复**: 与记忆孪生共享 ACP 传输/发现/配对层

### 事件系统
- 10 个事件发射点: SESSION_CREATED / RUN_COMPLETED / LLM_CALL_ERROR / RUN_INTERRUPTED / SESSION_RECOVERED
- AgentViewModel 观察 `SessionEventBus` 自动显示恢复提示
- UI 系统消息: 中断恢复 / 网络超时 / 连续错误 各类型提示

## v0.18.4 (2026-07-30)
- 气泡精简：对话气泡样式简化，减少冗余边框和背景层
- 表格自适应：Markdown 表格支持水平滚动和自适应列宽
- API供应商表单重构：设置页供应商卡片 UI 重构，支持更多 API 提供商

## v0.18.3 (2026-07-29)
- UI重构：Compose UI 大规模重构，按模块拆分设置页和侧边栏
- 暗色模式 Arco规范：统一深色模式下 Arco Design System 色彩令牌
- 主题色安全加固：颜色推导添加边界检查和类型安全
- 设置页重构：设置页拆分为 AgentSettings / SystemSettings / FrameworkSettings / SecurityRules 四个独立组件

## v0.15.2 (2026-07-26) — 功能闭环审计 + 浏览器 v0.6.0

### 审计修复 (6 项, PromptEngine 三层十二问)
- **缓存失效**: `invalidateDocCache` 路径前缀匹配替代子串 `contains`
- **缓存key**: 提示词缓存检查前置到文件读取之前 + `docCache.isNotEmpty()` 守卫
- **Plan进度**: 任务边界标记根据 `agentLanguage` 输出中英双语版本
- **错误消息**: `AdaptiveLlmProvider` 移除无效双重 `bodyAsText()` 重试, 改用 `LlmApiException`
- **容器高度**: `TraceStepItem` 恢复 Step 编号 + 放宽观察显示条件 (action为null也显示)
- **提示词**: Few-shot 恢复示例2 (插件发现→查详情→安装), Agent 学会发现插件

### 浏览器 v0.6.0
- **暗色模式**: 跟随系统 UI_MODE_NIGHT_MASK
- **页面查找**: `BrowserFindBar` + WebView `findAllAsync`
- **阅读模式**: `BrowserReaderMode` + JS 内容提取 + 大字号渲染
- **Markdown 文件**: intent-filter `text/markdown` + `.md pathPattern`, 浏览器直接查看
- **Agent 协同设置**: Quick Click/自动注入/截图高度·质量/ 可配置
- **MCP 解耦**: `toolExecutor` 委托模式, 浏览器模块注入 BrowserBridge, 插件不依赖 APK
- **Skills**: 6 个浏览器 Skill → plugin-index 链接更新

### 网络 & 超时
- **RemoteApi**: 连接超时 10s→20s, 请求超时 60s→120s
- **AdaptiveLlmProvider**: socketTimeoutMillis=60s 闲置超时保护

### 系统提示词优化
- 浏览器控制 section 新增 45 命令完整参考 (中英文)
- 插件/会话/记忆孪生 sections 压缩为紧凑格式, 给出 `skill.run` 指路
- 命令参考精简: "权威来源 self.tools" + 常用命令速查

### 插件更新
- **plugin-browser-mcp v0.2.0**: 新增 `browser.mcp.invoke` 命令 + toolExecutor 委托
- **plugin-skill**: plugin-index 增加 5 个浏览器 Skill 入口

### UI 优化
- **自适应图标**: brand 色 `#0E4397` 背景 + 白色地球 + 光标指针
- **滚动感知工具栏**: 向下滚动隐藏，向上显示 + fade/slide 动画
- **冷启动页**: 品牌 logo + 快捷方式 (GitHub/百度/Google/Bing)
- **material-icons-extended**: 完整图标集，与 Shell 一致
- **MD 文件浏览**: `MarkdownText` 渲染 `.md` 文件 (Intent + WebView URL)

## v0.14.1 (2026-07-24) — 验证反馈修复

### 修复
- **底部栏**: 所有操作按钮 `IconButton`→`pointerInput+detectTapGestures`，根除键盘焦点泄漏
- **插件页**: `registerBuiltins` 时序修复，内置插件正确显示"已内置"
- **UI下载**: `loadPluginJar` 多类名尝试，DexClassLoader 失败优雅降级
- **空会话**: 启动时自动清理 `messageCount≤0` 的空会话

---

## v0.14.0 (2026-07-24) — 全链路审计修复

### 修复 (6 项)

- **Plugin**: 捆绑插件改用直接实例化替代 `Class.forName()`，R8 混淆安全，10/10 全部安装
- **Plugin**: `PluginManager` 所有方法加 `synchronized` 线程安全
- **GitHub**: 全部网络链路三级回退 (主源 → Gitee → ghproxy.com) — marketplace/download/update/check
- **会话**: 修复重复 Bug — `current_session.json` 嵌 sessionId，启动孤儿清理 + dedup
- **硬键盘**: Enter 事件全消费 (DOWN+UP)，`doSend` 加 300ms 防抖
- **LLM**: `maxRetries 19→5`，参照 QwenPaw 区分可重试/永久错误 {400,401,403}

### 新增

- **Plugin**: `net.proxy <url>` — 为 GitHub 资源生成 ghproxy.com 代理地址
- **Plugin**: `plugin.verify <id>/--all` — 文件系统校验 JAR/Odex
- **Session**: `agent.session.delete/archive/current` — Agent 会话管理命令
- **Session**: 归档粒度 + 删除/压缩确认弹窗 + `session_history.json.bak` 自动备份
- **Prompt**: 中英文系统提示词新增"插件管理"和"会话管理" section
- **Docs**: `docs/audit-methodology.md` — 三层十二问审计方法论

### 优化

- `plugin.marketplace` 返回 description
- `plugin.install` 成功返回命令摘要 + skill 提示
- `plugin.info` 显示 Size
- `plugin.update` 显示 changelog
- `agent.storage` 含会话统计
- 错误消息含 VPN/Gitee/ghproxy 建议
- 内置插件卸载保护
- 卸载清理 JAR + odex

### 测试

- 5 个预存失败全部修复 (Sanitizer ×4 + AgentEngine ×1)
- Kernel 测试 88/88 全部通过

---

## v0.12.12 (2026-07-24) — 记忆孪生配对 + 自动恢复

### 新功能: 记忆孪生 (plugin-memory-twin)
- **5连击激活**: 侧边栏 MengPaw 图标连续点击5次 → 安全确认弹窗 → 激活孪生
- **发起配对**: FrameworkCardDialog 中 "发起孪生配对" 按钮 → 直接 HTTP POST 到对方 ACP
- **接收方弹窗**: 配对请求写入 inbox 文件 → UI 轮询 → 安全确认弹窗 "请确认是个人设备请求"
- **自动同步**: 配对后自动启动 60s 周期账本同步
- **重启自动恢复**: `twin_activated` 标记, 下次启动自动恢复 ACP + 同步

### 内核改动
- `AcpMessageType` 新增 6 个孪生消息类型 (LEDGER_HEAD/PULL/BATCH/ACK, CAPABILITY_ANNOUNCE, TWIN_DELEGATE)
- `CAPABILITY_ANNOUNCE` / `TWIN_DELEGATE` 绕过 PromptFirewall (配对即建立信任)
- `PluginManager.initializeGlobalInstance()` 注入真实核心版本
- `DataPaths` 新增 TWIN_LEDGER/TWIN_PEERS/TWIN_AUDIT/TWIN_DREAMS 路径

### BUG 修复
- `AcpHttpTransport.startListener()` 显式启动 ServerSocket
- JSON payload 正确转义 (JSONObject.quote)
- inbox 文件轮询替代 StateFlow 跨层传递
- 孪生服务重启自动恢复

### 经验教训
- `docs/lessons-memory-twin.md`

## v0.12.1 (2026-07-24) — 表格渲染修复 + 系统提示优化 + 会话恢复 + 经验总结

### UI
- **表格渲染重构**: 固定列宽+网格边框+斑马条纹+品牌色表头
- **`/Silent` 模式恢复**: PanelOrderStore 默认值 `dream`→`silent`
- **执行模式面板**: 移除无效的「长按拖拽顺序」提示

### Agent 认知
- **斜杠命令重命名**: 从「执行模式」改为「斜杠命令」，增加否定语句防止 LLM 预训练覆盖
- **系统提示增加自发现**: DreamEngine / 斜杠命令 / skill.ls / skill.run 引导
- **新增 2 个 skill**: execution-modes、dream-engine

### 会话管理
- **`currentSessionId` 自动分配**: 首次保存时自动生成 ID
- **`switchToSession` 修复**: 保存到会话记录文件而非临时文件
- **`saveCurrentSession` 增强**: 自动创建 SessionRecord
- **打断恢复**: 检测卡住的 `AgentWithTrace(isRunning=true)` 自动修复

### 经验
- 6 条新教训记录（LESSONS #61-66）

## v0.12.0 (2026-07-23) — 安全防火墙 + 插件生态 + Agent说明书 + 全量审校修复

### 安全
- **PromptFirewall 接入 LLM 调用链**: run/runWithGoal/runWithMission 入口点检测注入攻击（指令覆盖/越狱/策略绕过/隐藏），自动添加防御前缀
- **ProGuard/R8 规则全面更新**: 3 个 proguard-rules.pro 更正为 `com.mengpaw.kernel.**`（自 v0.5.0 微内核拆分后首次修正）
- **CONNECTIVITY_CHANGE→NetworkCallback**: Android 14+ 广播失效修复
- **MissionMonitor**: 线程安全 + 状态转换去重 + Compose 反应式监听模式

### 插件生态
- **插件下载超时保护**: Ktor HTTP 10s/30s/60s 超时 + AgentEngine 命令 60s 超时
- **Skill 插件 v0.3.0**: 7 个插件说明书（tavily/filesystem/self/plugin-system/hermes/self-update/plugin-index），增量播种
- **PAD 悬浮窗彻底清除**: `BUILTIN_PLUGIN_IDS`/`plugins.json`/`README.md` 三处残留清零
- **plugin.auto 命令**: 插件电源管理（wake/sleep/status/sleep-idle）

### UI
- **嵌套滚动崩溃修复**: 3 处 MarkdownText 添加 `nestedScroll=true`
- **ArcoTheme**: 自定义主题文件从重组读取改为 `remember` 缓存
- **TokenChart**: 硬编码颜色迁移至 `ArcoColors.Chart*` 设计令牌
- **MainScreen**: derivedStateOf 键修正 / header 按钮响应式 / LaunchedEffect 键简化

### 代码质量
- **!! 强制解包清零**: 7 处 → 0（生产代码）
- **文件 IO try/catch 补全**: 16 处（McpClient/DreamEngine/SidebarContent/AgentTemplates/BrowserActivity/插件）
- **协程 try/catch 补全**: 7 处 viewModelScope.launch
- **会话管理**: deleteSession 磁盘清理 / repairSession 正确 session 定位 / switchToSession 临时文件清理
- **Markdown AST**: collectText 新增 Image/HtmlInline 节点支持

### Android 合规
- 权限声明 17→21 项（+FOREGROUND_SERVICE_DATA_SYNC/SPECIAL_USE/SCHEDULE_EXACT_ALARM/CHANGE_WIFI_MULTICAST_STATE）
- 已弃用 API 审计（CONNECTIVITY_CHANGE/getLastKnownLocation/getExternalStorageDirectory 等 5 项）

### 文档
- 命令计数修正（self 14→13, agent 11→12, sys 39, plugin 10→11, skill 4→7, inspector 4→6）
- DevPlugin 命名空间说明 / 权限清单更新 / 审校记录补充

## v0.11.4 (2026-07-23) — 安全防火墙 + Android合规 + UI性能

### 安全
- **PromptFirewall 接入 LLM 调用链**: `run()`/`runWithGoal()`/`runWithMission()` 入口点检测注入攻击（指令覆盖/越狱/策略绕过），自动添加防御前缀
- **MissionMonitor 威胁检测去重**: `updateWorker` 仅统计状态转换，避免重复计数

### Android 合规
- **CONNECTIVITY_CHANGE 广播替换**: Android 14+ 不再投递，改为 `ConnectivityManager.NetworkCallback`
- **ProGuard/R8 规则更新**: 3 个 proguard-rules.pro 文件更正为 `com.mengpaw.kernel.**` 包路径（自 v0.5.0 微内核拆分后首次更新）
- **pad-plugin 残留引用清理**: `BUILTIN_PLUGIN_IDS` 替换为 `framework-plugin`

### UI 性能
- **ArcoTheme**: 自定义主题文件从每次重组读取改为 `remember` 缓存
- **MainScreen**: `derivedStateOf` 键修正 / header 按钮 observable / `LaunchedEffect` 键简化
- **SidebarContent**: 编译错误修复 (`return@IconToggleButton`→正确标签)
- **TokenChart**: 硬编码颜色迁移至 `ArcoColors.Chart*` 设计令牌

### 代码质量
- `!!` 强制解包清零（7 处 → 0，仅剩 DevPlugin 审计检查字符串）
- 文件 IO `try/catch` 补全 16 处 + 协程 `try/catch` 补全 7 处
- 会话管理: `deleteSession` 同步删除磁盘文件 / `repairSession` 正确 session 定位 / `switchToSession` 临时文件清理
- Markdown AST: `collectText` 新增 `Image`/`HtmlInline`/`Strikethrough` 节点支持

### 文档
- 命令计数修正（self 14→13, agent 11→12, sys 39, plugin 10→11, skill 4→7, inspector 4→6）
- `permission.check` / `plugin.auto` 等未记录命令补充
- DevPlugin 命名空间说明（`plugin.*`→实际注册为 `dev.plugin.*`）
- 审校记录更新

## v0.9.0 (2026-07-22) — 安全强化 + MD 模板文件化 + 智能体专属工具/技能

### 安全架构 (核心)
- **三大保护强制启用**: 移除内核/插件/文件完整性开关，保护始终生效
- **IntegrityGuard 接入 Pipeline**: 之前从未实例化（NoOp 空实现），现通过 AgentEngine → Pipeline 指令链真正执行路径保护
- **SecurityPolicy 强制执行**: 删除 `globalEnabled` 旁路，15 条危险模式 + 黑名单始终生效
- **PluginManager 清理**: 删除从未被读取的 `integrityCheckEnabled` 假开关

### MD 模板文件化 (性能)
- **模板从 Kotlin 拆除**: 删除 7 个硬编码 `xxxTemplate()` 函数（~270 行字符串），`DEFAULT_AGENTS_MD`/`DEFAULT_SOUL_MD`/`DEFAULT_MEMORY_MD` 常量（~80 行）
- **assets 存放**: 7 个 .md 模板文件放入 `assets/agent-templates/zh/`，QwenPaw 中文版
- **三路径模型**: APK assets → 只读模板路径 → Agent 工作区，文件复制替代字符串拼接
- **自定义简便**: 直接编辑 assets 下的 .md 文件，重新编译即可更新模板

### 智能体专属工具/技能
- **智能体工具(Agent Tools)**: 替代 "MengPaw CLI"，支持三种安装方式
- **智能体技能(Agent Skills)**: 替代 "Agent Skills"，支持三种安装方式
- **全局池安装对话框**: 从全局工具池/技能池勾选安装到当前智能体
- **三种安装方式**: ①从全局池安装 ②Agent 自行搜索下载 ③用户提供路径 Agent 安装

### 设置页文案重构
- 框架设置: `MengPaw CLI` → `全局工具(Tools)`, `全局 Skills` → `全局工具(Skills)`
- 智能体设置: `MengPaw CLI` → `智能体工具(Agent Tools)`, `Agent Skills` → `智能体技能(Agent Skills)`
- 三个安全开关 → 静态"已启用"指示器

### 架构改进
- `AgentTemplates.kt` 新增模板管理器 (mengpaw-core)
- `AgentDocs.kt` 377 行 → 68 行，新增 `bootstrapper` 回调模式
- `AgentDocManager.kt` 移除重复模板常量，统一走文件复制
- `DataPaths.kt` 新增 `AGENT_TEMPLATES` 路径常量
- `Pipeline.kt` 新增 `integrityProvider` 属性，直接调用 `validateCommand()`
- `AgentEngine.kt` 新增 `integrityProvider` 属性，`buildPipeline()` 注入
- `MengPawVersion` 自动生成自 `mengpaw.version` 属性

### 发行
- Shell: v0.8.4 → v0.9.0 (versionCode=900)
- Kernel: CORE_VERSION 0.8.4 → 0.9.0
- 8 新建文件, 10 修改文件, ~350 行 Kotlin 代码删除

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

### Agent 进化扩展 (self 命名空间)
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
