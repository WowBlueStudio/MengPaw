# Changelog

## v0.45.0 (2026-08-31) — 用量统计增强: 总调用/输入输出 Token/按模型统计 + 图表纵坐标冻结

### 新增
- **用量统计卡片扩容**: `TokenUsageStatsPanel` 拆分为独立文件 (自 SystemSettingsContent)，第一行 4 卡展示总调用次数 / 输入 Token / 输出 Token / 总 Token。
- **按模型统计表格**: 另起一行表格按模型列出 [模型名称][总调用次数][输入 Token][输出 Token][总 Token]。
- **图表纵坐标冻结**: 每日/每周/每月用量统计图表保持数据区横向可滑动，纵坐标 (Y 轴) 冻结不随滚动移动，网格线对齐。
- **图表柱顶标注**: 当日数据 ≠ 0 时在柱顶标注两行 — 第一行当日总 Token，第二行总缓存命中 (缓存 > 0 时显示)。
- `TokenStatsCollector` 新增 `totalCalls()` / `totalPromptTokens()` / `totalCompletionTokens()` / `byModel()` 聚合；`record()` 增补 `cacheHit` / `cacheHitTokens` 缓存命中数据。

### 测试
- `TokenStatsCollectorTest` 新增用例：总调用次数聚合、按模型聚合 (含输入/输出 Token)。

### 发行
- Shell APK: `mengpaw-shell-v0.45.0-release.apk` (versionCode 45000)
- Browser APK: 本轮无变更，不构建；浏览器独立版本线 (v0.9.0) 保持不变
- 插件: 本轮 plugins/ 无变更，不打 `plugins-v0.45.0` tag，plugins.json 不重写
- 测试: 全量 1523 用例 0 failures (kernel 641 + core 116 + shell 236 + 插件 530, browser 已拆独立仓库不在此口径)
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.44.3 (2026-08-29) — 同步浏览器 v0.9.0: 9880 桥退役, 浏览器控制统一 am 桥单通道

### 变更
- **浏览器控制 Skill 同步 (v0.9.0 am 桥单通道)**: 浏览器 v0.9.0 退役 9880 HTTP 桥与 MCP 开放模式 (决策 #7), 浏览器控制统一 am 桥单通道 (仅同签名 Shell 可调)。同步更新 plugin-skill 的 5 个浏览器技能文档 (browser-control/debug/form/playwright/spider): 移除 `browser.mcp.*` 工具 / `/mcp` `/health` 端点 / 开放模式引用, 对齐 am 桥单通道。
- **shell 侧 9880 桥退役清理**: 移除 `BridgeTokenProvider` (MCP 桥 token 通道 ContentProvider) 与 `com.mengpaw.permission.MCP_BRIDGE` signature 权限 (9880 桥认证通道, 退役后不再需要)。`Ports.BROWSER_MCP (9880)` 标注已退役。

### 修复
- 浏览器 v0.9.0 起 `browser.*` 去重收尾 `batch/q` 移除 (23→21), 同步开发指南 §5.3 命令清单 (page.* 22 + browser.* 21 = 43)。

### 发行
- Shell APK: `mengpaw-shell-v0.44.3-release.apk` (versionCode 44003)
- Browser APK: 已拆分独立仓库, **v0.9.0** (9880 桥退役, am 桥单通道), 于 `WowBlueStudio/MengPaw-Browser` 独立发布
- 插件: 本轮 plugin-skill 有变更 (浏览器技能文档同步), 构建 AAR 回写 plugins.json, 打 `plugins-v0.44.3` tag, GitHub Release 附 AAR
- 测试: 全量 1519 用例 0 failures (kernel 641 + core 116 + shell 232 + 插件 530, browser 已拆独立仓库不在此口径)
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.44.2 (2026-08-28) — 修复 browser 安装版本校验误判

### 修复
- **`update.install browser` 恒失败 (P0)**: `UpdateDownloader.install()` 的版本校验 `installVersionError` 用 **Shell 当前版本** (v0.44.x) 校验**所有目标**的 APK 版本 — browser 是独立版本线 (v0.8.x), 拿它与 shell 版本比较恒判为「残留旧包」删除, 导致 `update.install browser` 永远失败。修复: browser (及非 shell 目标) 跳过 shell 版本门禁 (`shouldSkipVersionCheck`), 仅保留签名验证 + 安装 — browser 由 Agent 按需自行下载安装 (设计定案)。
- 新增单测 `shouldSkipVersionCheck skips only non-shell targets` 锁定行为。

### 发行
- Shell APK: `mengpaw-shell-v0.44.2-release.apk` (versionCode 44002)
- Browser APK: 已拆分独立仓库, 独立版本线 v0.8.1, 于 `WowBlueStudio/MengPaw-Browser` 独立发布
- 插件: 本轮 plugin-update 有变更 (browser 版本校验修复), 构建 AAR 回写 plugins.json, 打 `plugins-v0.44.2` tag, GitHub Release 附 AAR
- 测试: 全量 1519 用例 0 failures (kernel 641 + core 116 + shell 232 + 插件 530, browser 已拆独立仓库不在此口径)
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.44.1 (2026-08-28) — 仓库拆分: 浏览器独立仓库 + 自动更新双仓库解析

### 新增
- **浏览器独立仓库拆分 (按 APK 产物)**: `mengpaw-browser` 模块源码移至独立仓库 `WowBlueStudio/MengPaw-Browser` (Gitee 镜像同步), 浏览器独立版本线 (v0.8.x); 共享地基 (kernel/core/design-system) 配置 `maven-publish` 支持 JitPack 构件 (`com.github.WowBlueStudio.MengPaw:<module>:<tag>`) 供浏览器仓库依赖。
- **共享地基 JitPack 化**: kernel/core/design-system 发布 release 变体, 浏览器独立仓库经 JitPack 拉取, 与 mengpaw-connectors 同模式。

### 修复
- **自动更新改双仓库解析源**: `update.check` 分别拉取 Shell 主仓库 (`WowBlueStudio/MengPaw`, 提供 Shell APK) 与浏览器独立仓库 (`WowBlueStudio/MengPaw-Browser`, 提供 Browser APK) 合并进同一 ReleaseInfo; 浏览器仓库不可达不影响 Shell 更新。修复浏览器拆分后 Browser APK 更新源指向错误仓库的问题。

### 发行
- Shell APK: `mengpaw-shell-v0.44.1-release.apk` (versionCode 44001)
- Browser APK: 已拆分独立仓库, 独立版本线 v0.8.1, 于 `WowBlueStudio/MengPaw-Browser` 独立发布
- 插件: 本轮 plugin-update 有变更 (自动更新双仓库解析), 构建 AAR 回写 plugins.json, 打 `plugins-v0.44.1` tag, GitHub Release 附 AAR
- 测试: 全量 1517 用例 0 failures (kernel 641 + core 116 + shell 232 + 插件 528, browser 已拆独立仓库不在此口径)
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.44.0 (2026-08-25) — 静默分支进化 + Chat 气泡闪烁修复

### 新增
- **静默分支进化 (智能体进化闭环重构)**: 进化从主会话彻底移出，改为静默分支会话自动沉淀——
  - **持久化进化队列 `EvolutionQueue`**: 任意失败 / 用户纠正即入队（失败按命令名+错误码去重、幂等），落盘 `queue.jsonl` 重启不丢；
  - **分支会话 Runner (`EvolutionBranchRunner`)**: scope=evolution 零待命分支会话，读失败/纠正 → 按 `evolution-branch` 剧本（金字塔四层 + 5-Why + 聚类 + 基础进化优先 + 四要素模板）分析 → 沉淀到共享工作区（memory / 指令集 / soul / 技能）→ `markCorrected`；会话保留供右侧边栏复盘，主对话全程不被污染；
  - **新 skill 剧本 `evolution-branch`**: 随 plugin-skill 分发，Agent 与开发者可读可改；
  - **触发**: `EvolutionHook` 失败捕获 + 用户纠正均入队；会话收尾异步处理进化队列，不再把进化报告当 User 消息丢进聊天。

### 修复
- **Chat 生成期气泡高频闪烁**: `MainScreenScrollBehavior.scrollToBottom` 每 100ms 先 `scrollToItem` 把末条顶部对齐视口、再以绝对目标 `scrollBy` 累加应用导致过滚被 clamp，视口"上跳再下跳"往复 → 流式答案较长时高频闪烁。改为"末条尚未可见才一次性定位，跟随末条时只按真实溢出向下滚"，消除上跳下跳。
- **复现计数跨重启丢失 (G2)**: `ensureFailuresLoaded` 从磁盘回填 `repeatIndex`，跨会话复现数不再被覆盖为 1。

### 发行
- Shell APK: `mengpaw-shell-v0.44.0-release.apk` (versionCode 44000)
- Browser APK: 本轮无变更, 不构建
- 插件: plugin-skill 有变更 (新增 evolution-branch skill 资产) — 构建 AAR 回写 plugins.json, 打 `plugins-v0.44.0` tag, GitHub Release 附 AAR
- 测试: 全量 1565 用例 0 failures (kernel 641 + core 116 + shell 232 + browser 56 + 插件 520)
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.43.0 (2026-08-19) — 利用 DeepSeek Harness 开发 (ReAct/Loop 加固 + Ralph 迭代)

> **本版本利用 DeepSeek Harness 进行开发**: 参照 DSH 的 `ReactLoopAgent` / `agent-loop` /
> `dsh-goal-round-driver` / `dsh-tool-ralph` 设计, 对 MengPaw 的 ReAct/Loop 全部潜在问题
> (P1-1 至 P2-6) 逐项修补并补齐两类编排能力。

### 新增
- **ReAct 解析器支持 JSON 数组工具调用 (P1-1)**: 模型直接输出 JSON 数组 (或 json 代码块)
  形态工具调用时转译为 ToolCall, 复用去重/循环检测/门卫/超时链路; 保守门禁避免把文件清单等
  合法 JSON 答案误判。降低"依赖复读文本标记"的脆弱性。
- **Goal 会话持久续跑 (P2-4)**: `GoalModeExecutor` 支持 `sessionFile` 把 Goal 会话状态落盘
  JSON, 任务中断后可从存档续跑 (对齐 DSH goal-round-driver 同目标续跑); 完成/终结时自动清理存档。
- **Ralph 串行 fresh-agent 迭代 (P2-5)**: 新增 `RalphRunner` + `AgentEngine.runRalph` — 同一
  不可变目标交给多个全新子 agent (每轮新会话, 注入目标 + 上一轮结构化交接, 共享工作区作长期
  记忆), LLM 评估完成度 (对齐 DSH `dsh-tool-ralph`)。

### 修复
- **循环检测隔离并补强三通道 (P2-2/P1-3/P2-1)**: 检测状态收敛到每次运行一个 `LoopDetector`,
  不再共享 PromptEngine 可变状态 (主循环与并行 worker 天然隔离); `detectLoop` 补 精确串重复 /
  命令名等价变体 / 周期2交替 三通道, 消除 A-B 交替与参数变体绕行缺口, 并对整批命令逐一检测
  (原只查首命令); 修正 `loop_detected` 文案阈值 3+ → 5+ 与实现一致。
- **工具并行执行加有界并发 (P1-2)**: 主循环工具执行加 `Semaphore` 有界并发 (默认 8), 防单次
  LLM 输出大量 Action 瞬间并发击穿本地/上游 (对齐 DSH agent-loop 有界滚动池)。
- **幻觉门禁抑制自适应步数扩展 (P1-4)**: 模型顽固输出含幻觉 Final Answer 时不再触发 1.5×
  步数扩展, 避免成本/时长放大。
- **Swarm worker 渐进式纠正 (P2-3)**: 对齐主循环 — 同命令同错误码失败满阈值注入重试停指令,
  连续 5 次失败早停, 不再干烧预算到 maxSteps。
- **复用 ReActParser 实例 (P2-6)**: 最终答案退化检测不再每轮 new 解析器。

### 发行
- **开发工具**: 本版本利用 DeepSeek Harness 进行开发。
- Shell APK: `mengpaw-shell-v0.43.0-release.apk` (versionCode 43000)
- Browser APK: 本轮无变更, 不构建
- 插件: 本轮 plugins/plugins.json 无变更, 不构建 AAR、不打 `plugins-v0.43.0` tag
- 测试: 全量 1560 用例 0 failures (kernel 636 + core 116 + shell 232 + browser 56 + 插件 520)；并把 `SwarmModeExecutorTest.workers execute in parallel` 断言由墙钟阈值改为并发重叠 (负载无关 flake 修复)
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.42.5 (2026-08-19) — Tavily API Key 配置入口

### 新增
- **框架设置「Tavily API Key」配置入口**: 在框架设置页 API 供应商下方、记忆管理上方
  新增「Tavily API Key」卡片 — 内置 Tavily 网页搜索插件的 key 入口, 密码框直接填写 +
  保存/清除 (可切换明文显示), 卡片右侧显示「已配置 / 未配置」状态徽标, 标题下方附副标题
  「申请 Tavily API 之后，可以免费提升搜索性能」。保存走插件侧 `TavilyPlugin.saveApiKeyFromUi`
  XOR 混淆落盘 (`tavily.json`, 复用 `tavily.setup` 同款格式) — key 明文不进会话历史/审计日志
  (遵守 API Key 安全红线); 已配置状态经 `isApiKeyConfigured` 判定, 兼容旧明文 + `obf:` 格式。
  免 Key 即可使用默认搜索, 配置 key 后可免费提升搜索性能。

### 修复
- (无功能性 bug 修复; 本轮为功能 + 文档/流程沉淀)

### 发行
- Shell APK: `mengpaw-shell-v0.42.5-release.apk` (versionCode 42005)
- Browser APK: 本轮无变更, 不构建
- 插件: plugin-tavily 有变更 (新增公共设置页存储/状态 API) — 构建 AAR 回写 plugins.json,
  打 `plugins-v0.42.5` tag, GitHub Release 附 AAR
- 测试: 全量 1543 用例 0 failures (kernel 619 + core 116 + shell 232 + browser 56 + 插件 520)
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.42.4 (2026-08-18) — 思考气泡层级定案改回

### 修复
- **思考气泡层级按用户定案改回 (v0.42.4)**: v0.42.2 "每轮独立思考气泡 + 逐轮折叠"
  不符合用户预期, 本轮按四条定案重构: ① 思考过程容器恢复**整体一级折叠** — 折叠头
  「思考过程」收起答案前的全部过程 (跨所有轮次), 运行中强制展开, 回答开始后默认
  收起, 手动状态跨重组保留; ② 每轮 `ProcessStep` 渲染为独立气泡, 无折叠头/计数,
  思维链全文直展 (不做二级折叠); ③ 工具调用+调用结果每组二级折叠, 默认收起,
  无气泡底色; ④ 思考区 (Agent 头/折叠头/每轮气泡) 相比回答气泡左右各缩进 8dp。
  运行态行去掉 "N 轮思考 · M 次调用" 摘要, 只保留「思考中…」+ spinner

### 发行
- Shell APK: `mengpaw-shell-v0.42.4-release.apk` (versionCode 42004)
- Browser APK: 本轮无变更, 不构建
- 插件: 本轮无变更, plugins.json 无变更
- 测试: 全量 1543 用例 0 failures (kernel 619 + core 116 + shell 232 + browser 56 + 插件 520)
- 崩溃巡检: 无设备在线, 巡检未执行, 待用户反馈
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.42.3 (2026-08-18) — 更新链路残留包加固 + bang 结果气泡定案

### 修复
- **防残留旧包装错版本 (v0.42.3)**: 0.41.0 用户复现 — updates 目录残留旧 APK 时,
  设置页 `hasDownloaded` 按文件名扫描命中旧包, 显示「安装」而非「下载」, 点击安装的
  是旧包, 导致无法升级。加固: ① `update.install` 增加版本校验 — 待安装包不高于
  当前版本 (残留/重复包) 或低于最新版本 (中间残留包) 直接删除并提示重新下载;
  ② `update.check` 发现新版本时清理低于最新版的残留包; ③ `update.download`
  下载前清理同目标旧 APK, 防下载失败残留
- **bang 命令结果气泡定案 (v0.42.3)**: `!命令` 执行结果直接返回 — 成功且输出为
  空值时不追加气泡; 成功有输出返回灰气泡 (CommandResult 正常样式); 失败返回红气泡
  (error 为空兜底「命令执行失败」)。不引入"执行中"气泡 (用户定案, 避免闭环负担);
  输出保留原文 (空判定用 isBlank, 不 trim), 超长截断 4000 字符

### 发行
- Shell APK: `mengpaw-shell-v0.42.3-release.apk` (versionCode 42003)
- Browser APK: 本轮无变更, 不构建
- 插件: plugin-update 有变更 (残留包加固 + install 版本校验) — 构建 AAR 回写
  plugins.json, 打 `plugins-v0.42.3` tag, GitHub Release 附全部 AAR
- 测试: 全量 1543 用例 0 failures (kernel 619 + core 116 + shell 232 + browser 56 + 插件 520)
- 崩溃巡检: 无设备在线, 巡检未执行, 待用户反馈
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.42.2 (2026-08-18) — 无障碍屏幕级操控 + 思考气泡独立嵌套

### 新增
- **无障碍屏幕级操控 (v0.42.2)**: 新增系统无障碍服务 (`MengPawAccessibilityService`),
  用户需在系统『无障碍』手动开启。Agent 经 `sys.accessibility.*` 命令驱动:
  `status` (服务状态) | `dump` (读取当前屏幕控件树 JSON, 深度/节点数可限) |
  `click <x> <y>|--text <文本>|--id <viewId>` (坐标/文本/控件 id 点击) |
  `swipe <x1> <y1> <x2> <y2> [--duration ms]` | `input <文本>` (写入聚焦输入框) |
  `back` / `home` / `recents` (全局导航)。安全分级: dump MID (读屏), 模拟操作全部
  HIGH (弹窗确认); 服务被动响应命令, 不监听/记录无障碍事件。Manifest 声明 +
  `accessibility_service_config.xml` + 中英服务描述, 系统提示词/开发指南/无障碍指南同步
- **思考气泡独立嵌套 (v0.42.2)**: 思考过程容器不再整体折叠 — 每轮思维链为独立嵌套
  气泡 (`ThinkingStepBubble`), 工具调用/结果各自独立折叠, 折叠工具不再连带收起思维链;
  运行中全部展开, 最终答案开始后逐轮收起

### 修复
- **聊天自动滚动修复 (v0.42.2)**: 贴底改为"定位末条 + 补滚到真正末端", 修复流式
  长答案时"生成中视口不动" (animateScrollToItem 对高气泡只对齐顶部); 删除过时的
  "思考结束回顶" (错误命中历史 Agent 消息, 生成结束跳回上一条开头), 结束后保持贴底;
  用户上滑冻结跟随, 回到底部自动恢复

### 发行
- Shell APK: `mengpaw-shell-v0.42.2-release.apk` (versionCode 42002)
- Browser APK: 本轮无变更, 不构建
- 插件: 本轮无变更, plugins.json 不变
- 测试: 全量 1529 用例 0 failures (kernel 619 + core 116 + shell 224 + browser 56 + 插件 514)
- 崩溃巡检: 无设备在线, 巡检未执行, 待用户反馈
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.42.1 (2026-08-18) — 自动更新对账 + DeepSeek 思维链回传 + 对话需求跟踪

### 修复
- **自动更新安装结果对账 (v0.42.1)**: 系统安装器是外部异步流程, App 无法感知安装
  结果 — 安装生效后 `updates` 目录残留 APK, 设置页 `readyToInstall` 只认文件存在,
  新版装好后仍显示「安装」按钮误导用户重复安装同一版本。修复: 每次启动与设置页
  刷新对账 — 已下载 Shell APK 版本 ≤ 当前版本即删除并清除待安装状态; 用户取消安装
  (版本未变) 时 APK 保留可重试
- **思考气泡取消路径收口 (v0.42.1)**: 用户点停止按钮 / 切换智能体 / 撤回消息 →
  `engine.stop()` 取消 `run()`, 原实现 `CancellationException` 分支只取消播放协程就
  re-throw, 思考容器残留 `isRunning=true` 恒显「思考中…」转圈 — 取消分支补
  `coordinator.finish()` + `writer.fail("已停止执行")` 折叠容器并退出运行态
- **DeepSeek 思考模式多轮工具调用回传 (v0.42.1)**: 官方要求 assistant 的
  `reasoning_content` 必须在下一次请求中原样回传, 否则 API 400 ("The reasoning_content
  in the thinking mode must be passed back to the API") — 原实现只存 content 不回传,
  多轮工具调用任务后段 400/行为漂移, 表现为前几次对话正常、后几次折叠/格式判断混乱。
  修复: `Message.reasoning` 随 assistant 消息落历史 → `getStructuredHistory` 附键 →
  `buildRequestBody` 仅 deepseek 端点透传 (OpenAI 等端点不接受该字段)

### 新增
- **sys 敏感命令权限前置引导 (v0.42.1)**: 短信/联系人/通话/拨号命令依赖 Android
  运行时权限 — 系统提示词 + android 技能文档注入「执行前置」: 先
  `sys.permission.check` 确认, 未授予先 `sys.permission.request` 弹窗引导用户授权,
  禁止绕路或谎报已执行
- **对话需求跟踪 (v0.42.1)**: 规则式目标栈 — 每轮请求自动从会话消息抽取最近需求,
  最新为「当前重点」置顶、旧需求为「待办/背景」, 配合提示词纪律: 新话题不丢旧目标、
  旧目标不淹没新重点; 零持久化、零 LLM 评估成本, 不破坏前缀缓存
- **右侧历史边栏精简**: 去掉「智能体」分组标题, 会话数量数字去底色

### 发行
- Shell APK: `mengpaw-shell-v0.42.1-release.apk` (versionCode 42001)
- Browser APK: 本轮无变更, 不构建
- 插件有变更: plugin-update (自动更新对账) + plugin-skill (android 技能文档权限前置) —
  构建 AAR 回写 plugins.json, 打 `plugins-v0.42.1` tag, GitHub Release 附全部 AAR
- 测试: 全量 1502 用例 0 failures (kernel 618 + core 90 + shell 224 + browser 56 + 插件 514)
- 崩溃巡检: 无设备在线, 巡检未执行, 待用户反馈
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.41.0 (2026-08-18) — MiniMax 接入 + 全厂商思维链解析 + 浏览器开放模式

### 新增
- **MiniMax 供应商接入 (v0.41.0)**: 预置 MiniMax (稀宇科技) — 端点
  `https://api.minimaxi.com/v1/chat/completions`, Bearer 认证, 模型 MiniMax-M3
  (1M 上下文, 旗舰) / M2.7~M2 全系; 思维链两种官方形态全支持 — `<think>` 内联剥离
  (ThinkTagSplitter 跨 chunk 拆分) 与 `reasoning_details` 数组 (流式全文 buffer 增量去重);
  预置供应商按首字母排序
- **浏览器 MCP 开放模式 (v0.8.1)**: 第三方 AI Agent 可经 127.0.0.1:9880 免认证控制
  浏览器 (Playwright 式, 仅回环) — 设置 →「开放 MCP 控制」显式开启, 默认关闭保持
  签名级安全模型; 认证策略抽为纯函数 McpAuthPolicy + 7 用例单测锁定; /health 返回
  openMode 供第三方探测; am 桥 signature 权限与 Shell 侧 token 流程不变
- **浏览器模块独立文档**: `mengpaw-browser/docs/` — 开发文档 (快速开始 + 从源码开始)
  + MengPaw_Browser_skills.md (第三方 Agent 控制手册)

### 修复
- **全厂商思维链响应解析兼容 (v0.40.4)**: ReasoningExtractor 按官方文档字段归一 —
  DeepSeek/Kimi/GLM/Qwen/xAI/火山 `reasoning_content`、Anthropic
  `thinking_delta`/`signature_delta`、MiniMax `<think>`/`reasoning_details`; RemoteApi
  复用主解析器 (parseBody+Accumulator), 非流式 reasoning 独立分离
- **DeepSeek thinking mode 思维链分流 (v0.40.3)**: reasoning_content 独立通道显示,
  消除误判根因; P2-8 思维链失败清空 — lastReasoning 调用即清空不留陈旧值, fallback
  直通; 同包多键只取首个 (防重复显示)
- **v0.40.4 P2 审查修复**: 思维链锁内读取 / parseBody+Accumulator 复用 /
  RemoteApi 可注入直测 / AgentReActLoop 400 行拆分
- **预置模型官方核对 (2026-08-17)**: Qwen/DashScope 更新为 qwen3.8-max 旗舰;
  OpenAI/Grok/火山更新 — GPT-5.6 家族、grok-4.6 旗舰、火山托管模型

### 发行
- Shell APK: `mengpaw-shell-v0.41.0-release.apk` (versionCode 41000)
- Browser APK: `mengpaw-browser-v0.8.1-release.apk` (versionCode 14, 本轮有变更)
- 插件有变更: plugin-skill 技能文档 (browser-control.md 开放模式) — 构建 AAR 回写
  plugins.json, 打 `plugins-v0.41.0` tag, GitHub Release 附全部 AAR
- 测试: 全量 1480 用例 0 failures (kernel 606 + core 90 + shell 220 + browser 56 + 插件 508)
- 崩溃巡检: 无设备在线, 巡检未执行, 待用户反馈
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.40.2 (2026-08-17) — 气泡输出链路重构

### 修复
- **气泡输出链路重构 (v0.40.1 简化显示未生效, 用户复现三症状: "思考中… xxs" 单独气泡计时不停 / 一轮思考后停止无最终答案 / 思考过程不展开)**: 删除轮次队列 / `skipRound` / `snapshotRounds` / `flushText` / `thoughtShown` / `stepClosed` / `finalAnswerRoundId` 六套互相牵扯的状态 — `StreamPlaybackBuffer` 只保留当前轮累积 + 完整 `Action:` 行扫描 + 最终答案流式文本, 思考轮不再经过播放协程; `BubbleStreamCoordinator` 收敛为三件事 (思考一次性显示 / 工具行 onStep 挂观察 / 最终答案无条件流式) — 纯文本最终轮 (parse Rule 3/4) 经引擎返回兜底后由播放器把整轮文本流式送进答案气泡, 不再误写进思考容器
- **运行态零残留**: `ThinkingProcessWriter` 删除 `RunningStepTracker` 身份追踪 (列表重建/恢复后身份失效), 改按类型定位最后一条, `beginFinalAnswer`/`finalize` 幂等 — 任何路径 (含异常/截断/进程死亡恢复) 都终止运行态, 不残留 "思考中… Ns" 计时气泡; 进程中断恢复时归一化 ThinkingProcess/FinalAnswer 运行态消息; UI 运行中强制展开思考容器

### 发行
- Shell APK: `mengpaw-shell-v0.40.2-release.apk` (versionCode 40002)
- browser 无变更不构建
- plugins.json 无变更 (本轮无插件构建/tag)
- 测试: 全量 1419 用例 0 failures (kernel 577 + core 90 + shell 202 + browser 42 + 插件 508)
- 崩溃巡检: 无设备在线, 巡检未执行, 待用户反馈
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.40.1 (2026-08-16) — 气泡显示简化 + 自动更新修复

### 修复
- **气泡显示简化 (用户定案)**: 思考阶段取消逐字流式打字机 — 每轮思考在流式检测到完整 `Action:` 行时一次性显示 (思考先出现), 工具行在 `onStep` 按顺序挂入并带观察; 纯思考轮在 `onStep` 时一次性显示; 截断路径 (引擎异常未走 onStep) 由 `finish()` 兜底完整显示。最终答案保留流式输出, 收到 `Final Answer:` 时折叠思考容器并创建答案气泡。标记识别全部行首锚定 — 思考/正文复述 "Final Answer:" / "Action:" 字样不再误截断、不再与折叠摘要内容混同
- **检查源只认应用发布**: GitHub `/releases/latest` 被同刻创建的 `plugins-v*` 插件发布顶替 → `update.check` 显示新版本但 `update.download` 报「该组件无可用下载」; 改用 `releases?per_page=10` 列表接口, 取第一个 tag 为 `vX.Y.Z` 且含 Shell APK 的应用发布, 拒绝 `plugins-v*` / 预发布 / 缺 APK (UpdateLogicTest +4)
- **自动更新 ANR (设备实测)**: 设置页「检查更新」主线程同步 OkHttp 网络请求 → Input dispatching timed out (MainActivity 无响应); 检查/安装按钮统一包 `withContext(Dispatchers.IO)`, `UpdateDownloader.doDownload` 网络+文件 IO 整体移出调用方线程 (双保险)

### 发行
- Shell APK: `mengpaw-shell-v0.40.1-release.apk` (versionCode 40001)
- browser 无变更不构建
- 插件有变更: plugin-update (检查源 + 下载线程) — 构建 AAR 回写 plugins.json, 打 `plugins-v0.40.1` tag, GitHub Release 附全部 AAR
- 测试: 全量 1419 用例 0 failures (kernel 577 + core 90 + shell 202 + browser 42 + 插件 508)
- 崩溃巡检: 两台设备在线 — 设备1 残留 v0.35.3 旧 ForegroundService 崩溃 (留档); 设备2 今日 ANR 根因即本轮修复的 update.check 主线程阻塞, 已闭环
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.40.0 (2026-08-16) — 技能体系三闭环 (派生 / 索取 / 进化)

### 新增
- **技能派生** `skill.from.project <项目名>`: 项目记忆 → LLM 提炼为可复用技能 (可流程化判定 + description 语义查重, 命中中止), 产物带 `## 适用场景/执行步骤/验证规则/来源/进化目标` 三要素; `agent.memory.project.save` 提示派生入口 (提示词闭环)
- **技能索取**: `skill.request <技能名> <来源Agent>` 同设备复制 (冲突以简介为准不覆盖) + `skill.ls --agent <Agent名>` 浏览; `skill.import <技能名> [来源Agent]` 跨设备从 Fleet共享 导入 (fleet.delegate 请对端发送 → 本地落位)
- **进化断点修复**: 系统提示词引导技能失败走 make_skills 进化升级循环; make_skills 模板补可移植规范 (完善/中性/通用/无敏感)
- **工程**: 技能管理命令拆至 SkillManageCommands (行数红线内注释完整); 写盘原子化 (tmp+rename) + 派生/导入读回验证; skill.enable/disable 先本地后全局

### 修复
- 技能流转命令补 reason 门禁表 (HighRiskCommandGate) — 中危命令 JSON+reason 调用链路完整 (分级与门禁双源同配)

### 发行
- Shell APK: `mengpaw-shell-v0.40.0-release.apk` (versionCode 40000)
- browser 无变更不构建
- 插件有变更: plugin-skill (技能闭环) — 构建 AAR 回写 plugins.json, 打 `plugins-v0.40.0` tag, GitHub Release 附全部 AAR
- 测试: 全量 1407 用例 0 failures (kernel 577 + core 90 + shell 198 + browser 42 + 插件 500)
- 设备交付走自动更新链路 (check → download → install, 不再 ADB 推送)

## v0.39.2 (2026-08-16) — 自动更新下载链路修复 (源选择/主线程 ANR/进度条)

### 修复
- **下载源按 check 命中源优先**: ReleaseInfo 记录命中源 (github/gitee/ghproxy),
  下载按同源优先排序 — 国内设备 Gitee 通但 GitHub HTTPS/ghproxy 被墙,
  不再首源白等 15s 连接超时
- **下载超时调整**: connectTimeout 15s→10s (快速失败切换), readTimeout 30s→60s
  (慢速网络大文件); 显式 instanceFollowRedirects=true 跟随 Gitee 302→CDN
- **下载失败可诊断**: 每源失败原因记入日志, 错误消息附各源具体原因摘要
- **下载主线程 ANR**: 设置页下载切 `withContext(Dispatchers.IO)` —
  原 rememberCoroutineScope 主线程 + 同步阻塞 HttpURLConnection 下 10MB,
  慢网直接卡死 UI 报「无响应」
- **下载进度可见**: UpdateDownloader.onProgress 回调 + 设置页绿色进度条
  (有总量走百分比, 未知总量走不确定循环); 下载中禁卡片刷新防打断

### 发行
- Shell APK: `mengpaw-shell-v0.39.2-release.apk` (versionCode 39002)
- 仅 Shell 构建 (browser 无变更不构建)
- 插件有变更: plugin-update (下载链路修复) — 构建 AAR 回写 plugins.json,
  打 `plugins-v0.39.2` tag, GitHub Release 附全部 AAR
- 设备交付走自动更新链路 (v0.39.1 起不再 ADB 推送, 用户定案)
- 测试: 全量 1356 用例 0 failures (kernel 576 + core 90 + shell 198 + browser 42 + 插件 450)

## v0.39.1 (2026-08-16) — UI 动画丰富 + Token 图表 Chart.js 风格重构

### 新增
- **Token 用量图表 Chart.js 风格重构**: 渐变柱 (品牌色 → 白 lerp)、堆叠圆角细化
  (仅边缘段圆角)、点击柱显示 tooltip (该日模型明细/缓存节省/总计)、选中柱白色描边高亮 —
  全部使用 Arco 品牌色板, 无外部色
- **Token 图表柱状生长动画**: 打开设置页时从 0 平滑生长 (FastOutSlowIn 650ms)
- **消息列表平滑动画**: Compose 1.7 `animateItem` — 新消息淡入、移除/重排平滑过渡,
  滚动回收不重放
- **设置页主要卡片按压反馈**: 主题/输出目录/自动更新卡片按下 0.97 缩放回弹 (120ms)

### 发行
- Shell APK: `mengpaw-shell-v0.39.1-release.apk` (versionCode 39001)
- 仅 Shell 构建 (browser 无变更不构建); plugins.json 无变更 (插件目录无改动)
- 测试: 全量 1356 用例 0 failures (kernel 576 + core 90 + shell 198 + browser 42 + 插件 450)

## v0.39.0 (2026-08-15) — 自动更新安装链路闭环 + 系统提示词剧本化重构

### 新增
- **自动更新设置页开关**: WiFi 自动检查 / 自动下载 — 此前仅命令行 `update.auto` 可配置,
  现设置页可直接切换 (状态随打开设置/点击卡片刷新)
- **下载/安装拆两步**: 设置页「下载 APK」只下载, 成功转「安装」入口, 失败保留重试 —
  原一步下载+拉起安装, 文案与行为不符
- **自动下载完成通知**: `update.auto download=on` 后台下载完成发系统通知 (原用户无感知)
- **启动版本更新通知**: 安装结果回传兜底 — 系统安装器异步无法感知结果, 启动时比较
  上次记录版本与当前 versionName, 有变化即通知「已更新到 vX.Y.Z」(任何来源升级通用)
- **安装中重复下载防护**: install 唤起后按「目标+版本」记录标记, 自动下载跳过该版本;
  当前版本追上或新版本出现时自动清除

### 修复
- **安装链路 P0**: FileProvider authority 与 Shell Manifest 不一致 (`update.provider` →
  `fileprovider`) + `file_paths.xml` 未映射 `插件仓库/updates/` — 修复前
  `update.install` / UI「安装」按钮必然失败
- **安装按目标选文件**: 修复「下载了 browser 却点 UI 安装会装错包」隐患
- **待安装状态重启丢失**: 按文件名约定扫描 updates 目录兜底; `lastCheckTime` 即时落盘
- **下载并发锁 / 旧版本 APK 清理**: UI 与自动检查双路径不再抢写 `.part`, 新包下载后
  自动清理同目标旧包

### 重构
- **系统提示词剧本化瘦身**: 会话/记忆孪生/Tribe/浏览器/插件/文件设备/常用命令/更新/
  斜杠命令 9 处段落换 `skill.run` 指针 (剧本全部现成, 零新增);
  网络端口独立节删除; 常用命令 22 行压到 3 行 — 中文模板 8,546→6,659 字符
  (≈4,361→3,544 token, -19%)
- **execution-modes 剧本补全**: /Swarm /Fleet /Goal /Plan /Research /Silent 六模式,
  与工作区 modes.md 权威定义一致 (原剧本仅 3 模式)
- **核心行为保留常驻**: 安全分级/信任边界/结果纪律/记忆三轨/工作区边界/探针不外置

### 发行
- Shell APK: `mengpaw-shell-v0.39.0-release.apk` (versionCode 39000)
- 仅 Shell 构建 (browser 无变更不构建)
- 插件有变更: plugin-update (安装链路/通知) + plugin-skill (execution-modes 剧本) —
  构建 AAR 回写 plugins.json, 打 `plugins-v0.39.0` tag, GitHub Release 附全部 AAR
- 测试: 全量 1356 用例 0 failures (kernel 576 + core 90 + shell 198 + browser 42 + 插件 450)

## v0.38.3 (2026-08-14) — 表格行间横线渲染修复

### 修复
- **Markdown 表格行间横线不渲染**: 行间横线从 Material3 HorizontalDivider 改为
  Box+background 画法 — 与列间竖线同一渲染路径, 修复 0.38.2 起 IntrinsicSize.Min
  行间横线消失的问题; 横线/竖线/外框同为 0.5dp #808080 一致线型

### 发行
- Shell APK: `mengpaw-shell-v0.38.3-release.apk` (versionCode 38003)
- 仅 Shell 构建 (browser 无变更不构建); plugins.json 无变更 (插件目录无改动)
- 测试: 全量 1346 用例 0 failures (kernel 576 + core 90 + shell 198 + browser 42 + 插件 440)

## v0.38.2 (2026-08-14) — 自动更新设置入口完善 + 表格圆角/竖线修复

### 新增
- **自动更新设置入口 (系统设置)**: 打开设置自动检查更新 (无需手动点击); 发现新版本
  显示「下载 APK」按钮, 点击下载并自动拉起安装; 下载完成后保留「安装」入口 —
  安装被取消/按错后可再次点击重新唤起
- **UpdatePlugin 状态暴露**: `hasUpdate` / `readyToInstall` — 设置页结构化判断
  是否可下载/可安装, 不再解析检查文本

### 修复
- **Markdown 表格右上角圆角**: 表头白底自身 clip 顶部圆角, 不再依赖外层整表裁剪
  (蓝底用户气泡右上角缺角修复)
- **表格列间竖线补齐**: 0.5dp 同色竖线与外框/行线线型一致, 形成完整网格

### 发行
- Shell APK: `mengpaw-shell-v0.38.2-release.apk` (versionCode 38002)
- 仅 Shell 构建 (browser 无变更不构建); 插件有变更 → plugins-v0.38.2 同步
- 测试: 全量 1346 用例 0 failures (kernel 576 + core 90 + shell 198 + browser 42 + 插件 440)

## v0.38.1 (2026-08-14) — 表格圆角修复 + 系统设置分割线 + 思考回填定型修复

### 修复
- **Markdown 表格四角圆角缺一**: 外框由整表 border+clip 一次绘制, 不再依赖表头/末行
  边框拼角; 表头白底同步裁圆角 (蓝底用户气泡不再出现白色方角); 框线统一 50% 灰度灰线
  (日间/夜间/用户侧一致)
- **系统设置分割线**: 后台运行与输出目录区块之间补齐分割线, 页面区块间距统一
- **思考回填定型隔离**: 最终答案创建后 pushThought 回填不再改写 tracker.ref, finalize
  按类型定位 FinalAnswer — 残留"思考中…计时"气泡根因修复 (0.38.0 补提交), 新增回归测试
- **自动更新插件描述同步**: 更新源描述改为 GitHub/Gitee 双源回退, 与实现一致

### 发行
- Shell APK: `mengpaw-shell-v0.38.1-release.apk` (versionCode 38001)
- 仅 Shell 构建 (browser 无变更不构建); 插件有变更 → plugins-v0.38.1 同步
- 测试: 全量 1346 用例 0 failures (kernel 576 + core 90 + shell 198 + browser 42 + 插件 440)

## v0.38.0 (2026-08-13) — Evolution Agent 进化闭环 + 思考气泡重构 + 手机端 UI 系列

### 新增
- **Evolution Agent 进化闭环**: 独立子 Agent (进化分析师提示词: 金字塔追问 + 5-Why +
  增量教训) 读取未分析失败批次 (累计 5 条触发, 会话结束兜底), 产出 md 报告落盘
  reports/ (status: pending, 15 天自动清理); 报告以子 Agent 产出投递到用户气泡侧,
  主 Agent 按 evolution 技能审阅采纳
- **evolution 技能**: 主 Agent 审阅采纳方法论 (结论先行/根因验证/增量沉淀/采纳清单)
- **Goal 模式**: LLM 主动中断 (不可完成信号) + 目标一致性约束 (RubricGate 三态
  YES/NO/OFFTRACK, 连续偏离自动中断)
- **模型退化输出拦截**: 重复 XML 标签/单一 token 流判定退化并提示重试; 兼容
  `<action name>` 合理 XML; 进化记录清洗防污染
- **自动更新插件内置**: update-plugin 迁入 Shell, 系统设置新增「自动更新」入口
- **高危操作确认改通知栏横幅**: 允许/拒绝按钮, 后台可见, 不再静默拒绝
- **手机端 UI**: 思考 Markdown 渲染、按实际执行顺序显示、工具行逐字误报修复、
  生成中发送按钮变停止按钮 (深蓝)、插件市场排版/安装即激活、复制横幅毛玻璃、
  Markdown 表格样式 (去格底/90% 白表头/主题自适应/4dp 圆角)

### 发行
- Shell APK: `mengpaw-shell-v0.38.0-release.apk` (versionCode 38000)
- 仅 Shell 构建 (browser 无变更不构建); plugins.json 有变更 → plugins-v0.38.0 同步
- 测试: 全量双套 1346 用例 0 failures

## v0.37.3 (2026-08-13) — 手机端 UI 系列修复 + 思考气泡卡住修复

### 修复
- **插件市场排版**: 版本号移出标题行 (长中英标题不再挤压竖排); 主标题拆两行
  (一行中文名 + 一行英文名, 列表页与详情页同步); 副标题 description 自动换行不截断
- **插件下载后自动激活兜底**: 远程插件安装成功后 status 非 ACTIVE 时补 activate
- **思考过程摘要按需显示**: 思考/调用次数为 0 时隐藏对应文案
- **气泡复制成功横幅**: 输出/用户气泡及大爆炸选中复制后 NotifyBus 横幅提示「已复制」
- **思考气泡轮次路由修复 (根因)**: ThinkingProcessWriter.pushThought/addTool 改为
  按 roundId 在 steps 中定位 — 引擎先为多轮插入工具行后, 播放协程回填思考不再
  因"只与最后一个 step 比较"而另起空思考 step (第一轮思考消失/折叠区只剩三五字/
  气泡混工具行/步骤重复的根因); 新增交错场景回归测试
- **Final Answer 检测行首锚定**: 只有独立成行的 "Final Answer:" 才判为最终答案轮,
  思考行中段出现字样不误判 (0.37.2 全量累积 contains 误判的根治)
- **思考文本剥离 Action 工具行**: 工具调用由 ProcessTool 行承载, 不再重复出现在
  思考文本里 ("工具调用怎么在气泡里"的视觉错乱)
- **编排逻辑拆分**: 流式气泡编排收敛到独立 BubbleStreamCoordinator (finalAnswerStarted/
  轮次封口/播放路由三处共享状态), 行为由 4 个用例锁死
- StreamPlaybackBuffer.finish 封口全部轮次 (引擎截断路径下播放器不再卡 NothingNew)
- **思考支持 Markdown**: 思考气泡 (ThinkingProcessBubble/AgentStepBubble) 思考全文
  改为 MarkdownText 渲染, 不再纯文本
- **按实际执行顺序显示**: 工具行延迟到该轮思考播完才挂入 (pending 机制), 最终答案
  轮之前的思考轮按序播完才进 FinalAnswer — 修复"第一轮思考还在流式输出, 后面
  几轮工具调用已跑出来"的乱序; 根因: beginFinalAnswer 后 tracker.ref 指向
  FinalAnswer, 播放协程的思考回填被误路由 (updateProcess 改为按类型定位过程容器,
  协调器记录 finalAnswerRoundId 区分思考轮/最终答案轮)
- **自动更新插件内置**: update-plugin 由外置 remote 迁入 Shell APK (内置无版本号),
  系统设置新增「自动更新」入口 (输出目录与 Token 用量统计之间, 点击执行 update.check)
- **插件安装即激活**: Android 上加载失败不再降级为"重启后生效", 明确报错可重试;
  安装成功徽标改为"已激活"
- **工具调用流式逐字误报修复**: ACTION_LINE_REGEX 行尾必须换行落地, 不再逐字
  宣布 (agent→agent.m→… 展开与调用次数虚高 47 vs 12 的根因)
- **高危操作确认改通知栏横幅**: 取代应用内 AlertDialog — 后台任务/其他页面触发
  高危命令时, 通知栏高优先级横幅带「允许/拒绝」按钮直接确认, 不再 30s 静默拒绝
  用户无感知; 30s 超时自动取消通知 (HighRiskNotification + confirmQueue 移除)
- **Goal 模式支持 LLM 主动中断**: LLM 明确表达任务无法完成 (中英文信号) 时提前
  终止, 不再空转到 maxTurns 耗尽; goal prompt 提示可中断, 返回「任务中断」摘要
- **停止按钮保持品牌深蓝底色**: 生成中停止按钮不再变红, 维持深蓝圆形

### 发行
- Shell APK: `mengpaw-shell-v0.37.3-release.apk` (versionCode 37003)
- 仅 Shell 构建 (browser 无变更不构建); 本地验证推送, 未打 tag 未发布
- 测试: 全量双套 1330 用例 0 failures (shell +12 = BubbleStreamCoordinator 4×2
  + ThinkingProcessWriter 交错回归 1×2; plugin-update 迁入 +12)

## v0.37.2 (2026-08-13) — 印象笔记连接器登记 + 系统提示词插件指引

### 新增
- **印象笔记连接器 (connector-yinxiang 0.1.0, 外置插件)**: EDAM 云 API 直连全量 CRUD —
  search / get (正文纯文本 + 附件下载) / create / update / delete (废纸篓) /
  notebooks / tags; token 经 `connector-yinxiang.config --token-file <路径>` 配置
  (dev.yinxiang.com 申请, 7 天短效)
- **系统提示词插件节新增印象笔记指引**: Agent 可直接发现 connector-yinxiang.* 命令
- plugins.json 新增 connector-yinxiang-plugin remote 条目 (downloadUrl → plugins-v0.6.0)

### 发行
- Shell APK: `mengpaw-shell-v0.37.2-release.apk` (versionCode 37002)
- 仅 Shell 构建 (browser 无变更不构建); plugins.json 有变更 → 内置插件
  plugins-v0.37.2 同步
- 外置插件: `plugin-connector-yinxiang-plugin.jar` (0.1.0, plugins-v0.6.0)
- 测试: 全量双套 1292 用例 0 failures (kernel 562 + core 90 + shell 182 +
  browser 42 + 插件 416; 本轮主仓库无新增用例, 印象笔记连接器 31 用例在
  mengpaw-connectors 独立仓库)

## v0.37.1 (2026-08-12) — Token 用量统计图表重构 + 历史保留上限

### 新增
- **Token 用量统计图表重构 (用户定案)**: 日/周/月保持为统计口径切换, 每档查询
  范围改为全量历史 — 连续序列补 0 值占位条形 (中间没用量的区间条形必须可见,
  不跳空), 图表横向滑动查询
- **历史保留上限扩展**: 存储淘汰从 90 天扩到 24 个月, 支持日 90 天 / 周 50 周 /
  月 24 月三档回溯; 淘汰改按日期清理 (防 CSV 乱序误删)
- **统计卡口径修复**: 总用量/缓存节省/预估节省改为全量历史口径, 修复"图表有
  数据但最近 14 天无用量 → 统计卡消失 → 容器底部空位"的复现问题

### 修复
- 设置 → 使用指南 → USB/Root/无障碍 弹窗无限高度约束闪退 (上一版引入修复,
  本版含真机验证前的最终构建)

### 发行
- Shell APK: `mengpaw-shell-v0.37.1-release.apk` (versionCode 37001)
- 仅 Shell 变更 (browser 无变更不构建); plugins.json 无变更 (v0.37.0 已随
  plugins-v0.37.0 同步)
- 测试: 全量双套 1292 用例 0 failures (kernel 562 + core 90 + shell 182 +
  browser 42 + 插件 416; shell +6 = TokenStatsCollectorTest)

## v0.37.0 (2026-08-12) — 打通 Termux 与 sys. 命令支持

### 新增
- **sys.* 命令面 51 → 85 (Termux:API 全功能面对齐)**: 用户交互对话框 11 种
  (confirm/text/radio/checkbox/spinner/sheet/date/time/counter/color/speech)、
  语音转文字/文字转语音/麦克风录音/手电筒、短信/联系人/通话记录/拨号、
  通知列表、下载+状态查询、壁纸、toast、唤醒锁、红外、USB 授权、WiFi 扫描;
  敏感 22 项命令归入中危 (默认拒绝, 信任等级放行), Manifest 新增
  SEND_SMS/READ_SMS/READ_CONTACTS/READ_CALL_LOG/CALL_PHONE/SET_WALLPAPER/CHANGE_WIFI_STATE
- **plugin-termux**: Termux→ubuntu (proot)→miniconda→Python 多层环境桥 —
  `termux.status` 逐层探测 / `termux.python` 直接调用 conda 环境内 Python /
  `termux.ubuntu` 通用命令; 脚本文件交换通道规避 am 逗号切分与多层引号陷阱
- **CommandMonitor.evaluateRulesOnly**: 仅高危规则审查, 供外部 shell 桥复用
- **ReAct 思考流式轮次队列化**: 前几轮思考不再截断成 1~3 字
- **UI**: 用户气泡底部操作行右对齐; 生成期间聊天列表持续贴底, 用户上滑查看
  历史时停止跟随

### 修复
- **设置弹窗闪退 (荣耀真机复现: 使用指南 → USB 测试)**: AlertDialog text 槽位给
  垂直滚动容器无限高度约束 → IllegalStateException。修复: 使用指南三个弹窗改自定义
  Dialog + Surface heightIn(屏高×0.85) 有界高度 (对齐 AttachmentPreviewDialogs 模式);
  同款风险的 CRON/伪人模式触发器弹窗补 heightIn(440dp) 上限
- **sys.* 12q 闭环六项**: dialog.speech 缺录音权限前置引导 (原误报超时) /
  新增 sys.download.status 下载状态验证 / 通知监听服务 exported=true (部分 ROM
  绑定失败) / 对话框失败语义区分 (取消/超时/无 Activity/在途) / TTS 引擎实例
  泄漏收口 / USB 授权超时接收器悬挂
- **sys.* 9d**: sys.screenshot/screenrecord 改 MediaProjection 免 root 截图录屏
  (Android 14+ 前台服务令牌); 权限清单双源漂移清除; alarm 反射耦合改显式 action
- **plugin-termux 9d**: env 名白名单防注入、输出上限防死循环、进程超时防泄漏、
  am 瞬时失败重试

### 发行
- Shell APK: `mengpaw-shell-v0.37.0-release.apk` (versionCode 37000)
- 仅 Shell 变更 (browser 无变更不构建); plugins.json 随 v0.37.0 同步回写
  (14 内置插件 checksum/size/changelog), 插件 AAR 上传 plugins-v0.37.0 Release
- 测试: 全量双套 1280 用例 0 failures (kernel 562 + core 90 + shell 170 +
  browser 42 + 插件 416)
- 崩溃巡检 (发布前, 双设备 dropbox): vivo 侧 0.31~0.35.3 历史崩溃均闭环
  (最新 08-10 dataSync 前台服务超时, 0.35.4 已修复); 荣耀平板 08-12 16:36
  使用指南弹窗无限高度崩溃 — 根因定位并随本版修复, 安装后回访验证

## v0.36.2 (2026-08-11) — 思考容器闭环兜底 + 通知栏常驻权限修复

### 修复
- **思考过程容器不闭环 (P1)**: 内核 `PromptEngine.parse` 规则 3/4 把无 `Final Answer:`
  标记的纯文本自然回答 / Thought-only 也判为最终答案 (闲聊/简单问答/非 ReAct 模型),
  但 Shell 流式检测只认 `Final Answer:` 前缀 → 这类回答思考容器永不折叠 (`isRunning`
  永 true), UI 恒显"思考中…"、自动折叠失效、手动折叠后滚动回收 (LazyColumn 重组,
  rememberSaveable 丢失) 又恢复展开。修复: 引擎 `run()` 返回后若流式从未检测到
  Final Answer 标记, 兜底 `beginFinalAnswer()` 折叠容器 + 创建最终答案气泡再定型。
- **通知栏常驻失效 (P1)**: Manifest 声明了 `POST_NOTIFICATIONS` 但从未请求运行时权限
  → Android 13+ 默认拒绝, 前台服务通知不显示 (服务仍在运行, 用户误以为失效)。
  修复: `MainActivity` 启动时请求通知权限 (API 33+, 拒绝后不再弹窗骚扰)。
- **折叠交互防御**: `ThinkingProcessBubble` 折叠状态异常组合 (`collapsed=true` 且
  `isRunning=true`) 不再强制展开, 避免覆盖用户手动折叠。

### 测试
- 新增 `ThinkingProcessWriterTest` 4 用例: 未闭环复现 / 闭环折叠+创建答案气泡 /
  finalize 定型 / 闭环后不残留运行态容器。

### 发行
- Shell APK: `mengpaw-shell-v0.36.2-release.apk`
- 仅 Shell 变更 (browser 无变更不构建); plugins.json 无变更
- 崩溃巡检: vivo 真机 dropbox 巡检 — 记录截止 2026-08-10, 均为 v0.35.3 及更早;
  NSD listener 竞态 (v0.34.1 修复) 与 dataSync 前台服务超时 (v0.35.4 修复)
  两类历史崩溃全部闭环, v0.35.4 之后零新崩溃
- 测试: 发布前全量实测数字见开发指南 §3.7

## v0.36.1 (2026-08-11) — 浏览器半自动武器 + 地址栏修复

### 新增
- **浏览器半自动武器 (browser v0.8.0, 方案 docs/browser-autopilot-plan.md 拍板落地)**:
  `page.*` Playwright 语义命令面 (22 条) — `page.load` 半自动合体 (导航 + 精确等待 +
  全页分段截图 + 坐标系统) / `page.goto` / `page.screenshot` / `page.click <seg> <x> <y>` /
  `page.fill` / `page.content --grep/--head/--tail` / `page.wait_selector` 等;
  超长页截断分多段 + 按段坐标还原 (决策 #5); 截图落公共目录
  `/storage/emulated/0/MengPaw/截图存档` (MANAGE_EXTERNAL_STORAGE 首启弹窗, 拒绝降级提示)
- **am 桥 (Termux 式调用)**: 浏览器侧 `RunCommandService` (signature 权限 +
  命令前缀白名单 + 输出路径公共目录限制); CommandMonitor 识别
  `com.mengpaw.browser.RUN_COMMAND_ARGUMENTS` 形态, 白名单只放行 page.*/browser.*
- **browser.\* 去重 (决策 #4)**: 45→23 条, 被 page.* 覆盖的命令删除, 四源同步 +
  5 个浏览器技能文档 + 系统提示词中英节
- **地址栏中文显示**: URL 百分号编码解码为中文 + 中文路径/带路径无协议 URL 输入识别
  (smartNavigate host 段判定)

### 修复
- **地址栏显示完整 URL**: 编辑态 editUrl 同步 activeTab.url (宽屏/平板地址栏恒为编辑态,
  remember(activeTabId) 不跟随页面 URL 变化, 残留用户输入的主域名); title 为空时仍渲染 URL 行
- **MCP 桥参数映射**: mcpArgsToPositional 支持 page.* flag (--max-height/--grep/--head 等)
  与 seg/dy 位置键 — browser.mcp.invoke page.content {"grep":...} 不再丢参数
- **提示词幽灵引用**: agent.read (v0.36.0 已删) → cat, PromptGhostReferenceTest 拦截后修正

### 发行
- 浏览器 APK: `mengpaw-browser-v0.8.0-release.apk` (vc=13)
- Shell APK: `mengpaw-shell-v0.36.1-release.apk`
- 外置插件 browser-mcp/browser-search/browser-push 代码同步 (mengpaw-connectors @d5cd98c, 未发 tag)
- 崩溃巡检: 2026-08-09 FileUriExposedException (v0.34.2) 已由 FileProvider 化修复闭环

## v0.36.0 (2026-08-11) — Linux 命令通道 + 命令去重 + 外置插件迁移

### 新增
- **Linux 命令通道 (双轨命令, 用户拍板)**: 注册表未命中的命令直接执行 (Android
  mksh/toybox 命令集, 不维护命令清单 — LLM 训练语料天然覆盖); 支持管道 `|` 与受控
  重定向 (`> 文件` / `2>&1`); 统一安全监控 CommandMonitor — 内置规则 +
  `{BASE}/配置/command_monitor.json` 可配置热加载, BLOCK 直接拒绝 / CONFIRM 弹窗
  (UserConfirmBus, 30s 超时默认拒绝, worker 直接拒绝); **sh -c / Termux
  (am startservice) / 直接命令三形态 payload 提取递归, 同一套规则**; 结构化元字符
  (拦 `;` `&&` `$()` 反引号 换行, 放行 `|` 与 fd 重定向); `sh <脚本>` 内容逐行扫描;
  无参 stdin 命令预检防挂起; ReAct 主循环 / Swarm worker / bang 三路径共用
- **命令去重 (用户拍板)**: `agent.read/write/ls/rm/mkdir` 与 plugin-fs 整体移除
  (Android 有等价命令 cat/echo/ls/rm/mkdir/cp/mv/stat/grep/find) — Agent 直接用
  Linux 命令; **验证功能拉回**: Linux 通道重定向写成功后自动附「请 cat 读回验证」提示,
  提示词「结果纪律」要求声称写入成功必须引用 cat 真实文本; 四源同步
  (种子 7→6 条改 Linux 语义 / 提示词中英「命令双轨」/ 风险表与 reason 门禁 /
  BuiltinCommandIndex / 开发指南 §5.2.1 / README / termux.md)
- **设置「使用指南」教程 (v0.36 声明兑现)**: 系统提示词「教程在设置中 — USB调试/Root/无障碍」
  从无入口变为真实功能 — 系统设置新增「使用指南」分区, 三个指南 (USB 调试 / Root / 无障碍)
  中英双语 (assets/guides/{zh|en}/*.md), 点击 Dialog 内 Markdown 渲染
- **/Translate 斜杠命令彻底移除 (用户拍板)**: 翻译需求回归普通对话/ReAct, 由 LLM 直接
  处理 — ExecutionMode.TRANSLATE / 输入框 + 号入口 / placeholderTranslate / tagModeTranslate
  全链路删除, modes.md/README/系统提示词/agent.modes 同步 7→6 种模式,
  PanelOrderStore 默认列表去 translate + 旧持久化迁移过滤; TranslateMiddleware 保持
  auto-translate 职责不变

### 变更
- **plugins.json v8 → v9**: 命令清单与实际代码对齐 — framework 6→15, net 3→4
  (curl/get/post/proxy, 移除不存在的 net.status), skill 4→10, tribe 22→28
  (补 hermes.* 兼容 6); **移除 agent-mission/agent-loop embedded 条目** (Mission 并入 Swarm
  v0.34.4 后 mission.*/loop.* 命令已不存在)
- **声明面同步现状**: 开发文档 §2.3/§2.4/§3.5/§3.6/§5.1/§5.2 + README 中英 — sys 40→51 命令
  补全 (悬浮窗/日历/媒体采集等), fs/skill/net/tribe 命令数, cdp/inspector 段落删除,
  外置插件标注, 权限表 17→22 项; 系统提示词 root/tribe "未捆绑"→"内置但默认未激活";
  /Fleet 输入框 placeholder 走 strings 国际化 (原硬编码中文)
- **过期内容清理**: AcpTransport NSD 注释改为现状说明 (mDNS 由 plugin-framework 实现);
  History v1→v2 迁移占位改为清晰模板说明; Manifest/开发文档 RECORD_AUDIO/VIBRATE
  "未来扩展"→实际用途 (语音输入/sys.vibrate); validate-plugins.ps1 空 commands 对
  provider-only 插件降为警告 (dream/evolution)

- **8 个普通外置插件源码迁入独立仓库 mengpaw-connectors (MIT)**: update / translate /
  error-report / render / comfy / browser-push / browser-search / browser-mcp 不再随主仓库
  构建, 主仓库仅保留 14 个内置插件模块 (全部捆绑 Shell APK)
- **协议同步**: 迁移插件 SPDX 头由 `AGPL-3.0-or-later OR LicenseRef-Commercial` 改为 `MIT`
  (插件仓库整体宽松许可)
- **打包链路迁移**: 主仓库 `scripts/package-plugins-dex.ps1` 移除; mengpaw-connectors
  `scripts/package-plugins.ps1` 统一打包 13 个外置插件 (8 普通 + 5 连接器)
- **plugins.json v8**: 13 个 remote 条目 downloadUrl/mirrorUrl 指向 `plugins-v0.4.0`,
  checksum/size 按新产物回写, version 不动
- **口径统一**: 开发指南 §3.5 / README / PROTOCOL 更新为「主仓库 14 模块 (内置) +
  13 外置插件 (mengpaw-connectors)」

### 发行
- APK: mengpaw-shell-v0.36.0-release.apk
- 测试: 全量 test 实测 1224 用例 0 failures（kernel 558 + core 90 + shell 148 + browser 34 + 插件 394）
- plugins.json: v9 沿用（无新变更）

## v0.35.6 (2026-08-10) — 远程插件假安装根治 + 插件版本号规则统一 + dex JAR 发布

### 新增
- **远程插件可加载闭环**: 发布 8 个 remote 插件 fat dex JAR (0.3.0, tag `plugins-v0.3.0`) —
  标准 AAR 无法被 DexClassLoader 加载的"假安装"问题根治, 客户端下载安装即真实激活
- **加载器支持 `META-INF/plugin-class` 主类清单**: 任意包名/类名的插件主类可被发现
  (连字符命名空间不再依赖非法候选类名), 产物缺 classes.dex 时安装明确报错而非静默占位
- **插件版本号规则统一 (用户定案)**: 内置/内嵌插件版本清空随壳更新; 非内置插件版本由
  源码 `PluginMetadata.version` 统一定义 (8 个 remote 插件统一 0.3.0); 工具链
  `update-plugins-json.py` 不再回写 version (杜绝"版本号随壳飘移")
- **tavily-plugin 补录市场索引**: 此前已捆绑但 plugins.json 缺失条目, 校验器 ERROR 消除

### 修复
- 8 个 remote 插件: QwenPaw 通道配置死锁 (默认 rest + 支持 ssh-acp) / CLI 路径双重引号 /
  OpenClaw 连接状态误报 (等待 WebSocket 握手) / QwenPaw REST 假在线 (连接时 TCP 探测)
- 连接器内核依赖升级 v0.23.0 → v0.35.5, SPI/Plugin API 二进制兼容验证
- validate-plugins.ps1: embedded 条目允许空版本 (随壳语义)

### 发行
- APK: `mengpaw-shell-v0.35.6-release.apk`（签名 CN=MengPaw, OU=Studio, O=WowBlue）
- plugins.json: 8 个 remote 插件 URL 指向 plugins-v0.3.0 dex JAR; tavily-plugin 新增 builtin 条目;
  内置/内嵌条目版本清空
- 测试数: 全量 1291 (kernel 529 + core 90 + shell 148 + browser 34 + 插件 490) — 0 failures（§3.7 实测快照）

## v0.35.5 (2026-08-10) — ACP 传输修复 + Fleet 深度进化 (委派/文件互传/能力收集/平台化) + 信任方案 A

### 新增
- **Fleet 深度进化 (v0.36 方向)**: ① 委派闭环 — `fleet.delegate/status/reply` + `FLEET_RESULT` 回传 + `FleetRuntimeStore` 状态回收; ② 文件互传 — `fleet.send/files`, 任意格式经 ACP 落 `{BASE}/Fleet共享/` (64MB 上限, 非孪生同步); ③ 能力收集 — `fleet.scan/capability`, 指挥所收集成员能力卡写入 Notes (规划分配依据); ④ **发起方即总指挥定案** (对等 P2P); ⑤ **命令平台化** — `FleetExecutor` 内核常驻 + `FleetPlatform` 注入 + 纯 JVM 能力卡采集, 桌面三端 (Win/OSX/Linux) 开箱指挥 (MengPaw OS 统一愿景定案)
- **swarm.run 指令**: Agent 可自主进入火种模式 (拆解→并行 Worker→验证→合成); Swarm 运行时持久化 + `swarm.status` 进度查询 (进程被杀可恢复查看)
- **framework.delegate 指挥舰委派**: 带 delegateId + 回传地址, 对端执行完自动回传
- **配对请求归档 UI**: 添加框架页显示已处理请求 (同意/拒绝历史)

### 修复
- **ACP HTTP body 单次 read 截断**: 配对请求 ~250B 被截断 → 对端 400 "发送失败" (`readFully` 循环读满, 孪生大消息同步受益)
- **connect/call/delegate 信任门禁**: 未信任节点禁止连接/委派 (引导 `framework.trust --yes`)
- **IPv6 直连支持**: `sendDirect` 地址规范化 (方括号 + scope 编码), mDNS 多地址优先 IPv4
- **信任方案 A (用户拍板)**: `framework.trust` 同时打通 ACP 入站域 (`PromptFirewall`), 所有 ACP 框架一视同仁; 解除信任同步清理
- **TribeTaskTest 时间戳毫秒边界**: 全量并行偶发失败 (固定时间戳)

### 发行
- APK: `mengpaw-shell-v0.35.5-release.apk`（签名 CN=MengPaw, OU=Studio, O=WowBlue）
- plugins.json 无变更（本轮无插件发布）
- 测试数: kernel 525 + core 45 + shell 74 + browser 17 + 插件 245 — 全量 906, 0 failures（§3.7 实测快照）

## v0.35.4 (2026-08-10) — 框架信任/通讯录添加流程修复 + Tools 副标题精简

### 修复
- **框架信任列表折叠计数归零**: 折叠时"已信任 N 个框架设备"恒 0 — 改为始终读真实信任列表, 折叠/展开计数一致 (展开仅控制列表显示)
- **手机端框架名片丢失信任框架按钮**: ACP 配对但未入册的框架 (peer==null) 名片无信任按钮 — 名片改接收完整 `FrameworkContact`, peer 按名称→指纹兜底解析; 有效信任 = 通讯录信任或 ACP 配对信任; 未入册 ACP 联系人点"信任"自动入册
- **框架通讯录添加流程**: ① 接收链路断裂 — `FrameworkPairHandler` 注册在无监听的 `AcpHolder.server`, 实际监听 9876 的是孪生独立 server, 请求到对端被静默丢弃 → 孪生/框架共用 `AcpHolder.server` + `ensureListening()` 幂等监听, 落盘/横幅/红点生效; ② 收到配对请求弹窗 (同意/拒绝/稍后); ③ 添加按钮发送反馈固定显示在底部按钮区 (此前在滚动区底部不可见, 误以为没反应); ④ 添加页面去掉居中大图标; ⑤ 通讯录列表不再出现未入册 mDNS 发现节点
- **Tools 副标题精简**: 副标题此前直接取完整释义且被 maxLines 截断 — `shortToolSummary()` 首段/剥插件名前缀/剥括号补充 + 24 字符上限, 展开区保留全文 (全局工具 + 智能体工具)
- **Android 15/16 前台服务超时崩溃 (vivo 实测 0.35.3)**: ShellService 声明 `dataSync|specialUse`, dataSync 前台服务 6 小时超时被系统强杀 (`ForegroundServiceDidNotStopInTimeException`) — 改为仅 `specialUse` (agent_keepalive 常驻), 超时崩溃消除

### 发行
- APK: `mengpaw-shell-v0.35.4-release.apk`（签名 CN=MengPaw, OU=Studio, O=WowBlue）
- plugins.json 无变更（本轮无插件发布）
- 测试数: kernel 484 + core 45 + shell 74 (unit) + browser 17 + 插件 238 — 全量 858, 0 failures（§3.7 实测快照）

## v0.35.3 (2026-08-09) — 暗色模式深蓝字体换白 + 九维/闭环审计修复

### 新增
- **暗色模式深蓝字体换白**: `ThemeColors.accentText` + `isDark` (背景亮度判断) — 暗色下原硬编码 Blue5/Blue6 的标题/标签/Step 等文字改白色, 亮色不变
- **框架配对 Agent 侧闭环** (闭环审计): `framework.pair.ls/accept/decline` 命令 (四源同步) — Agent 可查待处理请求、经授权后代为同意/拒绝; 收到请求写 inbox 提醒; pair.ls 顺带清理 7 天前已处理记录; 添加框架悬浮窗"清除已处理"入口; 发送失败补网络排查提示 (同一 WiFi/防火墙); discover 输出补跨版本指纹回退说明
- **stripMarkdown 回归锁**: 7 用例 (换行规范化/行内标记/链接图片/标题引用围栏/列表分行/软换行/段落保留)

### 修复
- **TokenBarChart 滚动协程死循环** (maxValue==0 无限 delay) → 固定延迟 + if 判断
- **新增 UI 文案本地化**: 气泡图标行/输出目录区块/安全分级面板/输出权限引导/名片指纹标签 → Strings.kt 中英双语
- **BubbleWrapper 空 clickable 占位移除**; **ActionIcon 点击热区 18→30dp**
- **AcpServer.sendDirect 地址消毒** (仅 IP/主机名, 防 URL 注入) + 失败日志
- **AcpServer mcpBridge!! 历史红线清理**

### 发行
- APK: `mengpaw-shell-v0.35.3-release.apk`（签名 CN=MengPaw, OU=Studio, O=WowBlue）
- plugins.json 无变更（本轮无插件发布）
- 测试数: kernel 484 + shell 65 (unit) + plugin-framework 42 (含新增 StripMarkdownTest 7 用例 + FrameworkPair 过期清理 2 用例)，全量 0 failures

## v0.35.2 (2026-08-09) — 框架配对流程 + 名片/气泡 UI 重构 + 输出目录修复

### 新增
- **框架通讯录配对请求流程**: 添加框架改"请求-同意"双向入册 — 独立悬浮窗口 (布局参考框架名片: 待处理请求/扫描发现/手动添加), 接收方"添加框架"按钮红点角标 + 通知横幅, 同意后双方入册 (ACP FRAMEWORK_PAIR_REQUEST/ACCEPT/DECLINE)
- **设备标识 ANDROID_ID 兜底**: Android 10+ 拿不到真实 MAC (NetworkInterface 返回 null) — 指纹绑设备标识, mDNS 广播 did 属性, 旧 no-mac 垃圾条目清理迁移
- **框架名片两行布局** + **智能体/框架名片 UI 全面重构** (去标题文字, 精致名片风格): 智能体名片 (大头像/名称/简介/工作目录短路径 `./com.mengpaw.shell/`), 框架名片 (五项内容: 名称/备注/系统环境/名称-版本号/智能体列表 + 信任框架/解除信任)
- **安全分级权限表达重构**: 标准=蓝盾 / 信任=粉盾, 普通行绿/中危行随盾牌/高危行红, 整块点击切换去开关
- **Token 用量统计改柱状图**: 每日堆叠柱 (模型分段着色), 固定柱宽 22dp+间隙 8dp, 横向滚动自动拉到最新日期
- **气泡交互重构**: 去点击动画 (indication=null) 与长按动作, 原长按菜单功能改为气泡下方线性图标行 (复制/大爆炸/引用/保存图片/标注图片/分享); 复制保留 markdown + 换行规范化, 大爆炸/分享剥离为带分行的纯文本
- **新建智能体页面对齐名片布局** + 添加框架改悬浮窗口
- **外观主题默认跟随系统**

### 修复
- **历史会话侧边栏跑左面**: 右侧栏内容 fillMaxSize 在持久侧栏 wrap 容器内撑宽, 改固定 300dp
- **输出目录仍显示旧私有路径**: 启动授权引导 (`OutputPermissionPrompt`) + onResume refreshOutput 实时切公共 /MengPaw/ + 设置页独立区块点击经系统文件管理器定位打开
- **框架名片 no-mac 字样**: 旧无效指纹迁移重算 + 行2 防御性省略
- **火种模式解释去 emoji 改文字**

### 发行
- APK: `mengpaw-shell-v0.35.2-release.apk`（签名 CN=MengPaw, OU=Studio, O=WowBlue）
- plugins.json 无变更（本轮无插件发布）
- 测试数: kernel 484 + core 90 + shell 114 + browser 34 + 插件 462 = 1184，0 failures（修复 SessionShellPoolTest flaky 超时窗口 1500→2500）

## v0.35.0 (2026-08-09) — /plan 模式 UI + Mission 并入 Swarm + 框架发现与命令反歧义

### 新增
- **/plan 模式 UI** (5d66913): 内核 `PlanMonitor` 实时发布计划与步骤状态 — Chat 消息区右侧竖列状态标识（灰空心圈/粉呼吸/蓝点/红叉），右侧边栏底部计划列表（当前步骤高亮），点击竖列展开侧栏；计划完成/失败/取消自动消失
- **Swarm 吸收 Mission** (62984ad): Swarm 是进化版的 Mission — 继承拆解→并行 Worker→验证→合成编排与 👍 DONE 降级语义（verifier 不可用标 DONE 非 VERIFIED），进化出角色混合模型/Andon 失败协议/JIT 看板三闸门；`/Mission` 斜杠命令、LoopMode.MISSION、MissionModeExecutor/MissionMonitor 全链路移除，原 Mission 任务全部由 Swarm 负责，自动升级收敛为四档（REACT/GOAL/SWARM/FLEET）
- **UI 侧同步** (f906008/4c3acc3): agent.modes 描述 7 种 + 中英文提示词同步 + 已部署 modes.md 旧模板自动迁移（含 /Mission 章节即覆盖，自定义文档不误伤）；智能体设置 Swarm 描述完整体现进化版 Mission
- **CLI.md 整体移除** (f7840ab): 工作区 22KB 命令文档删除，命令发现收敛为 self.tools（运行时枚举）+ self.search（CommandSearch 单一事实源）；描述语义回归锁
- **执行模式自动升级** (e1e26a5/899e3e0): 复杂度五档→四档自动升级；Loop 模式设置显示名带斜杠命令前缀（Swarm 火种模式/Fleet 步坦协同模式）
- **框架发现调整** (fa6f1a2/796db10): 本机名片可编辑/指纹码显示、指纹绑 MAC（框架类型|设备标识）、发现即入册改确认制、侧边栏扫描 10s 刷新、失效联络提醒；安全规则-框架信任列表起效（530cdac）
- **命令参数反歧义** (ce12006/bf7e487/08ccb3e): ParamGuard 通用化（路径/URL/时间戳污染提示 + 写类命令前置拒绝）+ fs/net 多余参数提示 + 提示词路径纯净规则 + 新增命令自动反歧义审查回归锁

### 修复
- **参数污染循环复现** (ce12006): 路径参数末尾携带多余文本时附污染提示，阻断同错重试
- **设置页清理** (ea63c94/4f41be3): 智能体设置移除引导进度面板；删除系统设置-时区（跟随系统）；Token 用量图表 Y 轴动态范围（消除底部大区域孔位）
- **数据流文档同步** (d336c30): 气泡 UI 重构 §8.5 + 金字塔彻查法升级 0.2.0（证据等级 + 链接闪退/测试 flaky 实战案例）

### 发行
- APK: `mengpaw-shell-v0.35.0-release.apk`（签名 CN=MengPaw, OU=Studio, O=WowBlue）
- plugins.json 无变更（本轮无插件发布）
- 测试数: kernel 484 + core 90 + shell 114 + browser 34 + 插件 462 = 1184，0 failures

## v0.34.3 (2026-08-09) — 气泡 UI 重构 + 安全分级系统 + 全量审查收尾

### 新增
- **气泡 UI 重构** (ef299ef): 时间轴主导 — 思考过程容器 (单一可折叠, 思考/调用/观察循环, "N 轮思考 · M 次调用" 摘要) + 最终答案独立气泡; 工具行只显命令名 (失败红字, 点击展开参数+观察全文); Final Answer 开始即自动折叠; 历史会话统一重排
- **命令安全分级系统** (f0b3afe/2229d95): 普通放行 / 中危权限 (Agent 权限等级: 标准/信任, 智能体设置) / 高危弹窗确认 (UserConfirmBus, 30s 超时默认拒绝); reason 门禁收窄到中危/高危
- **铲子检测** (6776fb5): 会话行为基线 (连续 ≥4 写/外联无读间隔告警) + 提示词遵从探针 (<!--mok--> 连续 5 次失配告警) + agent.write/mkdir 写路径边界 (工作区/输出目录外降级中危)
- **9881 MCP 网关 Bearer token 认证** (d965961): 无/错 token 一律 401 fail-closed (对齐 9880 桥), self.mcp token 获取
- **进化反馈状态机** (d59e089): evolution.feedback ls/mark (new/ack/scheduled/fixed) — report 落盘带 status frontmatter
- **输出目录公共 /MengPaw/** (244e8c0): 用户可见交付区 (Android 11+ 隐藏 Android/data 根治), 旧文件自动迁移, 设置页授权入口
- **P1-4 约束文档 summary 注入** (be82b66): profile/agents/soul 只注入 frontmatter summary + agent.read 外链 (memory 保持全文)
- **P1-5 触发器注入条件** (bb42e36): heartbeat/trumanshow 引导块按已启用触发器注入, 触发器指纹进提示词缓存
- **P0-1+P2-8 单一事实源** (f7bff3d): CLI.md 插件表/命令表去硬编码, 幻影条目永久删除 (notification/workflow/incubator 等)
- **P2-7 记忆行为侧** (c3fd5ca): 中期只写不编辑 (梦境自动整理), keep/record/project.save 按触发时机

### 修复
- **本地链接点击闪退** (a568841): FileUriExposedException — Markdown 链接改 Clickable + FileProvider content:// 抛系统选择器
- **输出目录不可见/未落盘** (244e8c0): 迁移公共 /MengPaw/ + 写路径前导 / 回退工作区 + 交付纪律 (先落盘→agent.ls 验证→再输出链接)
- **agent.memory.mid.rm 删不掉** (f0b3afe): deleteEntry/editEntry 长度下限 10→6 (HH:mm:ss 条目误伤)

### 发行
- Shell APK v0.34.3 (versionCode 34003) — 全量发布双远端 + GitHub Release
- Browser APK 无功能变更不构建
- 测试: kernel 472 + core 90 + shell 126 + browser 34 + 插件 462 = 1184 tests 全绿 (0 failures / 0 errors)
- plugins.json 无变更; 交付平板 (GDI-W09) + 手机 (V2361A)

## v0.34.2 (2026-08-09) — 幻觉干预全链路 + 进化系统产物重构 + 三层十二问审查闭环

### 新增
- **幻觉问题从度量到干预** (4ebfa0a): Final Answer 门禁 — 本轮有失败但最终回答未如实提及即拒绝并静默纠正; agent.write 自动读回验证 (≤200KB 全量比对, 成功断言由框架完成)
- **幻觉门禁静默化** (c624fdd): 门禁反馈只注入下一轮 LLM 请求, 不写会话历史 (UI/持久化零污染); 失败已弥补豁免 (同命令同参数重试成功不再拦截)
- **去超限放行** (43b1695): 幻觉答案绝不放行, 每次拒绝消耗步数预算, 顽固幻觉由循环上限终止; 失败词表扩充 + 自然语言汇报识别放宽
- **回合内重试循环停指令** (89af077, 对齐 QwenPaw RETRY LOOP DETECTED): 同命令同错误码失败满 3 次注入停指令 — 停止重试/换方法/向用户说明
- **失败截断进化介入** (739afd7/5b8e64d): 循环/步数上限/空响应/只思考不行动/异常中断/worker 终止全部剪取上下文片段写入失败模式库
- **进化产物 v2** (fc94392): failures.jsonl 去重 (每模式一行) + 懒加载 (重启恢复) + 可追溯字段 (task/sessionId/contextSnippet) + learn.command 持久化 (跨进程保留)
- **进化失败记录抗污染** (15cb98a): 命令字段清洗 + 按命令名去重 — 真实 46 行 failures.jsonl 合并为 16 个模式
- **三层十二问审查闭环** (49d0a19): 系统提示词按 hasEvolutionData 条件注入进化认知引导 + 进化数据指纹纳入缓存失效; learn.command 闭环指引; UI 摘要附复现模式数
- **方法论找回**: 三层十二问 (bb7a586) + 九维代码审查法 (60b0a66) 抽象为通用方法论 + Codex skills (closure-audit-12q / code-review-9d)

### 修复
- P4 agent.write 多行换行丢失 (5be9c70): HighRiskCommandGate 展开层 quoteIfNeeded 引号保护
- self.search 命令真实可用性 [ACTIVE] 标记 + audit 输出「下一步可用动作」 (5be9c70)
- 会话幻觉率持久化 (veracity.jsonl 跨进程累计) + 校验锚点 + 复现 ≥3 升级 🚨 (2b30715/ecdc5ea)

### 发行
- Shell APK v0.34.2 (versionCode 34002) — 全量发布双远端 + GitHub Release
- Browser APK 无功能变更不构建
- 测试: kernel 442 + core 90 + shell 126 + browser 34 + 插件 462 = 1154 tests 全绿 (0 failures / 0 errors)
- plugins.json 无变更; 手机/平板暂不推送 (用户指示)

## v0.34.1 (2026-08-07) — NsdManager 共享 listener 竞态崩溃修复（荣耀 Android 14 平板启动即闪退）

### 修复
- **NsdManager resolveService 共享 listener 并发竞态** (4445bf8): `onServiceFound` 并发回调复用同一 `ResolveListener` → Android 14 NsdManager 严格校验抛 `IllegalArgumentException: listener already in use` → 未捕获进程崩溃。两处修复: FrameworkDiscovery / TwinDiscovery 每次发现 new listener（Android 官方推荐模式）+ try-catch 兜底
- 该崩溃自 v0.32.0 即存在（dropbox 多版本有记录），v0.34.0 在荣耀 Android 14 平板上进入死亡循环（启动 0.3~1.3s 即崩 ×5 连）被暴露——与数据无关，清空数据仍崩

### 发行
- Shell APK v0.34.1 (versionCode 34001) — 全量发布双远端 + GitHub Release
- Browser APK 无变更不构建
- 测试: core 45 + kernel 409 + shell 63 + 插件 231 = 748 tests 全绿 (0 failures / 0 errors)
- plugins.json 无变更

## v0.34.0 (2026-08-07) — P0 注入防护硬软结合重构（高危命令 reason 门禁 + 攻击拉黑闭环）+ 设置页四件套整理

### 安全修复
- **提示词注入防护软硬结合重构** (cc47caf, P0 ①③⑤⑥+②): UntrustedContent 管道 — 工具结果/文档等不可信内容统一净化（stripInjection 剥离指令型注入）+ 信任边界提示词 + 静默剥离，攻击内容不进 Agent 上下文
- **高危命令 reason 门禁** (2df7fb1): 文件写/删、proc、插件管理、通知、剪贴板、记忆写、技能开关、root.* 等高危命令必须附 JSON `{"reason": "目的"}` 意图声明；错误文本内嵌模板动态生成的完整 JSON 示例（对齐 --force 自锁先例）；顺带修复单键 JSON 无 raw 放行漏洞
- **攻击提醒与拉黑闭环** (2df7fb1/d9a74db): InjectionPatterns 命中 → 对话提醒 + NotifyBus banner（只含来源+意图类别，不反射攻击原文）+ 询问用户是否拉黑 → `security.block <来源>` 持久化黑名单（域名后缀匹配），后续同来源内容直接阻止；三个循环（主 ReAct/Swarm/Mission）统一门卫防绕过；拉黑行为与范围由 Agent 自行确定 (d9a74db)
- **插件市场磁盘快照离线降级** (e483416): 市场不可达时用磁盘快照 + 超时参数化; ACP 端口绑定与安全文档化

### 功能增强
- **全局技能库整理** (121e9af): 23 个内置技能打标 `source: core|plugin`（预置不可删除，用户技能可删）+ @ 指定机制 = pinned 指针注入（不注入全文，LLM 自行 skill.run 按需读取，维持前缀缓存纪律）
- **全局工具面板整理** (eea13ff): 手工精选 40 条 → `engine.listCommands()` 全量动态列表（内核+插件 ~150 条，随注册表自动更新）+ 命名空间分组折叠（核心蓝/插件橙标签，修复第三方插件误标「官方」+ McpServer.listTools 同源重复）
- **智能体工具面板** (690fbfc): 命令集分组折叠（每组一个 CLI 如 飞书 CLI）+ 整组删除（AgentToolsStore.remove + 提示词摘要失效 + 列表即时移除）
- **智能体技能面板** (3c7f41f): 单条删除 + 技能形态全覆盖（md 剧本 / md+同名资源文件夹 / 纯文件夹 / 散资源文件，删除统一递归防残留）
- **工作区文件按钮重构** (0dd5850): cli.md/modes.md 只读 + 编辑按钮改为用其他软件打开
- **工作区文件树显示 evolution/ 目录** (7982d63/ad579d1): 进化档案目录节点与 memory/Notes 同款；无主进化档案自动迁移 + 统一 Agent 工作区判定 (c326e4d)

### 修复
- **ReActParser 转译 XML 工具调用** (c9e7d01): LLM 发 .md 文档未送达根因——XML 形式工具调用解析失败静默丢
- **下行媒体交付链路加固** (0d29800): 防 LLM 格式漂移导致媒体静默丢失
- **CLI.md 命令集指纹** (c09ddf7): 命令变更即自动重生成（巡检 P1/P4① 根因）

### 架构完善
- 内置插件无版本号设计定案 (4a3c6a6): 随 shell 更新，版本无语义
- 新命名空间 `security`（block/unblock/blocklist）+ CLI.md 动态表 + 搜索索引三触达同步

### 发行
- Shell APK v0.34.0 (versionCode 34000) — 全量发布双远端 + GitHub Release
- Browser APK 无变更不构建（自 v0.33.0 无 browser 功能提交）
- 测试: core 45 + kernel 409 + shell 63 + 插件 231 = 748 tests 全绿 (0 failures / 0 errors)
- plugins.json 无变更

## v0.33.0 (2026-08-06) — 400 行文件拆分收官 — 37 超长文件按职责拆为 127 文件全模块瘦身

### 代码质量
- **400 行文件拆分项目收官**（5 批次, 12 次验证提交）: 全部源码文件 ≤400 行, 无行为改动（纯平移）:
  - **kernel (14 → 49 文件)**: 批次1 History/TriggerEngine/AdaptiveLlmProvider/PluginExecutor + 批次5 AgentEngine (1033→400)/PromptEngine/AgentExecutor/AgentDocManager/AgentDocs/SelfExecutor/AgentMemoryExecutor/PluginMarketplaceClient/SwarmModeExecutor
  - **browser (3 → 13)**: BrowserActivity (934) / BuiltinBrowserPlugin (665) / BrowserBridge (657) — @JavascriptInterface 方法保留注册对象实例, 仅脚本常量/截图器外移
  - **plugins (7 → 33)**: TribePlugin (821→151) / MemoryTwinPlugin / TwinSyncEngine / TwinPairingEngine (嵌套类型因 shell 限定引用保留) / DevPlugin / UpdatePlugin / ErrorReportPlugin
  - **shell/design (13 → 47)**: AppRoot / MainScreen / SessionPersistenceService / SettingsViewModel / AttachmentBubbles / HistorySidebar / SidebarContent / ChatBubbles / PluginViewModel / MarkdownText / AgentViewModel (1181) / AgentSettingsContent / Strings (1157, 拆 AppStrings/EnglishStrings/ChineseStrings 引用零改动)
- **拆分方法论沉淀**: 同包提取 / 公开 API 签名零变化 / delegate-object 构造闭包注入 + 共享锁 / Compose 状态提升与 ColumnScope 接收者 / private→internal 可见性外移 / 嵌套类型消费方兼容检查
- **关键逻辑平移保留**（测试守护）: P0-1 幽灵命令文案 (模板常量留在 PromptEngine 供 PromptGhostReferenceTest 扫描) / P0-2 resolvePath 基准 + CLI.md 惰性生成 / P1-4 教学标题黑名单 / P1-5 WrittenGuard 自动摘要 / P1-6 引导状态机 / 每 ReAct 步骤气泡 (TraceStep 嵌套原位) / P2-12 Telemetry.recordLlm 遥测路径 / TEMPLATE_HASH 机制

### 修复
- **v0.32.0 界面闪退根因归档**（master 已修复, 随本版携带）: `sys.overlay.*` Agent 路径非主线程 addView 必崩 (withContext(Main) 修复) / Tribe 看板 delegate 状态机死锁 / MemoryTwinPlugin lateinit 崩溃 / 多模态附件全量 base64 重发内存压力 (latest-only 挂载)

### 发行
- Shell APK v0.33.0 (versionCode 33000) — 全量发布双远端 + GitHub Release
- Browser APK v0.7.3 (versionCode 12, 独立版本节奏) — 400 行拆分批次 2 (BrowserActivity 934→269 + BrowserApp/BrowserAppDialogs/BrowserContentArea/BrowserMcpTools, BuiltinBrowserPlugin 665→67 + 命令按域 4 文件, BrowserBridge 657→396 + BrowserScripts/FullPageScreenshotter), @JavascriptInterface 方法保留注册对象
- 测试: kernel 337 + shell 50 + 插件 229 = 616 tests 全绿 (0 failures / 0 errors)
- plugins.json 无变更

## v0.32.0 (2026-08-06) — Agent 触达全链路修复 + 每 ReAct 步骤气泡（思考全程可见）+ 多模态附件/语音

### 内核改动
- **Agent 自检报告四项框架修复** (f0d7332): ① 提示词去幽灵 — 未捆绑插件 (tribe/root/browser-search/browser-mcp) 命令全部条件化标注「需安装」, 纯幽灵示例移除 ② 命名空间推导权威化 `pluginNamespaceFor` — browser-mcp 插件正确映射 ns=browser (命令键自带 mcp. 前缀), 6 处调用点统一 ③ `parse()` 空参数放行 — 省略 Action Input / 显式 `{}` → emptyMap, `self.status` 不再 PARAM_FORMAT_ERROR (ToolCall 唯一构造点一处覆盖) ④ 记忆模板瘦身 (<500B 无教学标题) + TEMPLATE_VERSION 迁移 (老工作区自动重置) + 计数去模板黑名单
- **agentId 动态化 + CLI.md 惰性生成** (f0d7332/6243152): `AgentDocManager.bindAgent` 会话绑定 + stale 检测 (活跃插件数比对) 自动再生; CLI.md 三表运行时生成 — self 表遍历 SelfExecutor.commands, agent 表由 AgentExecutor init 注入注册键 (新增命令自动入手册), sys 表硬编码 51 行, 描述查 internal Triple 表 (缺失 fallback "见 self.search")
- **resolvePath 基准目录修正** (f0d7332): 相对路径基准 `{BASE}/` → `{AGENTS}/{agent}/` — 提示词教的工作区相对语义 (`agent.read profile.md`) 成为现实, 附带修复写文件缓存失效判定
- **自检报告二轮** (f5d41b9): 搜索索引可用性脱节修复 (静态种子与 registry.has 过滤一致化) + 路径两套体系统一
- **plugin.verify 发现性断裂** (086b15d): `--all` 批量分支自 v0.14.0 存在但索引/CLI.md/提示词三触达源缺失 — 补齐 + BM25 中文双字窗口 `cjkBigrams` (3+ 字符 CJK token 展开字符级 bigram, "校验插件"类短语可命中, 双字保持原始评分)
- **全量触达审计** (6243152): 索引补 11 条缺失 (self.ports/self.search/self.search.stats/agent.memory.mid.edit/mid.rm/project.delete/project.rm/project.edit/evolution.mark-corrected + self.notify.* 修正) — 隐性断裂根因: seed fullName 与注册全名不匹配被可用性过滤必杀
- **提示词幽灵引用检测测试** (fe7b611): `PromptGhostReferenceTest` 从源码提取 namespace.command 引用对照注册 key — 第三触达源 (提示词) 锁死, 程序化生成链路 (sys 补种/插件索引/CLI.md 动态表) 重构免疫, 手工种子/提示词引用有测试守护

### 功能增强
- **每 ReAct 步骤独立气泡** (210276b/cc41633, #45 收尾): 思考折叠头 (完整 thought 默认展开, 展开全程可见不截断) + 工具调用行 + 中间输出气泡 → 下一步 → 最终答案气泡 — 中间输出不再被最终答案覆盖; 多 Action 批按 step 合并 observation; 最终轮思考从流式缓冲提取; 错误路径/引用格式化适配; 持久化 `agent_step` 类型 (新字段默认值零迁移, 旧 agent_trace 兼容渲染)
- **工具轮思考过程流式可见** (4f72554): 思考+Action 命令行流式播放, 工具宣布降级兜底 (仅短思考时)
- **多模态附件气泡 + 语音输入** (f968c5a/69e76da/1e25cb2): 附件全链路结构化 — AttachmentData 上行 content 数组 (`_image` dataURI ≤8MB / `_input_audio` base64 ≤15MB), 下行媒体卡片 (图片采样 ≤2048px + 全屏, 音频单实例播放器, 视频封面帧, 文件 ACTION_VIEW); 语音按住录音松手直发 (VoiceCapability 模型判定显隐, 上滑/左滑取消, <300ms 丢弃); 待发栏统一一行 (缩略图/文档名称块/语音条块 + 斜杠/@标签合并); 图片气泡全宽原比例浮动 + 文件卡片精简 + md 内置预览
- **附件文件名取 DISPLAY_NAME** (b0b9360): DocumentsUI content URI lastPathSegment 是文档 ID 非文件名
- **browser: md 预览大纲按钮 + 站内 .md URL 渲染 + 近全屏预览** (dd4b5cf): md-reader 导航 + 站内 md 链接直接渲染 (browser APK v0.7.2)

### 修复
- **错误双弹 + Dialog 无限高度闪退** (ff3e54a, shell+browser): 重复错误展示去重 + 全屏对话框高度约束

### 代码质量
- **UI emoji 全部替换为 Icons.Outlined 线性图标** (5261bda, shell+browser): 有语义用图标, 无语义直接删除 — 规范沉淀 Dev Guide §3.3
- **附件路径行 📎 前缀移除** (7ca8442): 纯视觉标记非语法, 路径裸行保留
- **工作区文档注入策略定案** (02f5c2f): Dev Guide §4.1.4 常驻明文/场景链接式分治
- **LLM 多阶段输出数据流文档** (879d15d): docs/llm-multistage-dataflow.md — 每轮 LLM/框架/UI 各环节实际收到内容

### 发行
- Shell APK v0.32.0 (versionCode 32000) — 全量发布双远端 + GitHub Release
- Browser APK v0.7.2 (versionCode 11, 独立版本节奏) — md 预览大纲 + 站内 md URL + 近全屏
- 测试: kernel + plugin-agent-tools 285 tests 全绿 (0 failures / 0 errors)
- plugins.json 无变更 (v0.31.0 已清理)

## v0.31.0 (2026-08-05) — 工作区文档编辑/重置 + md 预览 md-reader 化 (WebView+CSS) + 框架提示词加固

### 新增
- **工作区文档「重置」按钮** (5874b5d): 8 份预置文档 (agents/heartbeat/modes/profile/soul/trigger/trumanshow/memory.md) 可重置为 APK 内置模板版 — `AgentDocs.resetDoc` 模板原子覆盖 (缺失回退 zh), 含路径穿越防护; 名单外文档保持可删除
- **Notes 笔记目录树** (5874b5d): `{agent}/Notes/` 记忆之外的笔记区 (如其他 Agent 知识信息), 设置页 memory 节点下方固定显示, `AgentDocs.bootstrap` 预建
- **工作区 md「编辑」按钮** (d282988): 所有 md 文档行可经系统选择器用其他软件打开 — FileProvider content URI + ACTION_VIEW (text/markdown, 回退 text/plain, 无可用应用 Toast 提示)
- **浏览器 content:// md 打开** (d282988): intent-filter 补 content scheme (FileProvider/SAF 选择), `checkMdFile` 双通道 — 与编辑按钮闭环: 选择器选中浏览器即渲染
- **md 预览 WebView 化** (5e1409f): 预览界面 UI/动画/CSS 完全复刻开源项目 md-reader — 双主题 CSS 变量 (系统深浅色跟随) + 代码块 12px 圆角/lang 标签/复制按钮 (hover 淡入, .copied 1s) + 引用彩色圆角块 (info/tip/success/warning/danger) + 表格 max-content 横滚 + 图片点击放大模态 (backdrop blur) + highlight.js v11 语法高亮 (core+19 语言裁剪版)
- **预览页致谢底栏** (52f3ab7): md-reader / @md-reader/theme / highlight.js / Atom One 语法主题开源版权标注
- **框架提示词加固** (5987591): 「结果纪律」规则 (Observation 必为命令输出, 禁止幻觉编造) + 错误码细分 (PARAM_FORMAT_ERROR/DOWNLOAD_FAILED/NETWORK_OFFLINE) + JSON 参数门卫 (4 执行器共享 ToolCall.paramFormatError) + Observation 注入 errorCode + plugins.json 清理 (tavily 条目移除)

### 修复
- **MarkdownText 代码块溢出** (5e1409f): 根因 — 100KB 预截断不感知 fence, 切点落在围栏内时闭合丢失, 后续整段被解析成巨型代码块 ("内容掉出代码块")。改完整解析 + 块边界预算截断, 每个块完整渲染, 截断时追加提示块 — 聊天气泡/设置页共享组件同时受益

### 发行
- Shell APK v0.31.0 (versionCode 31000) — 全量发布双远端 + GitHub Release
- Browser APK v0.7.1 (versionCode 10, 独立版本节奏) — WebView 预览 + content:// md + 致谢底栏

## v0.30.0 (2026-08-05) — 固定前缀抽离 + 文件拆分收尾 + Reasonix 性能对照落地 + 网络状况门卫

### 架构完善
- **斜杠命令节抽离**: 8 种执行模式说明从系统提示词移到工作区文档 `modes.md` (模板资产 zh/en, 老工作区 bootstrap v4 自动补种); 前缀留一行指引, 模型回答「有什么模式」用新命令 `agent.modes` 读取
- **FewShot 示例完全移除**: 格式契约由主提示词「响应格式（必须遵守）」节 + parse 容错 + needsContinue 兜底; 前缀减 ~2K
- **拼接契约 parity 测试锁定**: 段顺序 (identity→main→docs) + 分隔符测试, 防重构静默改变前缀字节导致缓存失效
- **删除文件摘除固定字段**: agents.md 无条件注入改条件注入; 修复缓存 gate 误命中缺陷 (docCache.isNotEmpty() 无法感知单文件删除 → mtime 快照比对)
- **工作区边界段 (zh+en)**: 明确 Agent 私有目录 (Agent文档/inbox/技能剧本/配置等, 用户不可见) vs 用户共享目录 (agent.output, 文件管理器可见)
- **移除 v2/v3 迁移兼容包袱**: bootstrap 纯新建逻辑 — 无旧用户, 不再保留向后兼容迁移代码 (fb48657)
- **文档文件名大小写统一**: BOOST.md→boost.md — 引导链路修复 (26badf1)

### 新增命令
- `agent.modes` — 查看斜杠命令模式菜单 (modes.md); 已注册 self.search 索引/命令缓存/循环检测安全名单/CLI.md

### 代码质量 (文件拆分收尾)
- **MainActivity 50KB→11KB**: 初始化/生命周期拆至 AppInitializer, Compose 根拆至 AppRoot (第一轮)
- **MainScreen 47.6KB→38.3KB + SidebarContent 44.8KB→29.4KB**: 第二轮拆分 — 头栏/侧栏/展开底表 → MainScreenHeader/SidebarContentData/TwinPairingDialogs 等 6 新文件, 全 ≤40KB 线内; 纯移动零行为变更 (9726207)
- **AgentViewModel 54KB 不拆**: 纯逻辑 ≤60KB 线内, 委托重构高风险零重组收益 (用户拍板)

### 性能优化 (Reasonix 对照落地, 用户文档驱动)
- **共享 HTTP 客户端 `LlmHttpClient`**: ConnectionPool(8, 5min) + retryOnConnectionFailure + connect 20s/read 120s + pingInterval(60s) — 会话切换不再重建连接池重新握手 (2bdc68f)
- **工具调用提前通知**: 流式中完整 `Action: <tool>` 行一落地即推送「⚙ 正在执行 X…」— 消除工具轮流式空屏
- **前缀形状监测 `SystemPromptShape`**: 每轮 wire system prompt SHA-256, 形状变化即告警 — 自动前缀缓存失效可见 (对标 Reasonix cache_shape.go)
- **死指标清理**: 删 AgentEngine/LlmRequestBuilder 恒 0 的 cacheHitTokens 系悬空链 + timeoutMs/socketTimeoutMs 死配置 (OkHttp 引擎不映射 requestTimeout)
- **Fallback 缓存统计直通**: RemoteApi 补 lastUsage 解析 + fallback 成功后 usage 透传主 provider
- **静默判定追平 Reasonix**: readTimeout 180s→120s (对齐 idle watchdog 阈值) + pingInterval(60s) HTTP/2 主动探活 — 半死连接 60s 内发现 (457f565)
- **网络状况门卫 `NetworkConditionGate`** (用户高铁场景提议): Android 系统网络状态注入内核重试策略 — 断网失败快返 (不烧 6 次退避 + fallback 链), 弱网退避 ×3/×1.5; 免危险权限 (仅 ACCESS_NETWORK_STATE)

### 内核改动
- `NetworkConditionGate` SPI (kernel 零 Android 依赖, shell 注入 ConnectivityManager 实现) + `NetworkConditionMonitor` (7 处 provider 构造全量接线)
- `SystemPromptShape` (LlmRequestBuilder) — 前缀形状 SHA-256 监测
- `AdaptiveConfig`/`RemoteConfig` 删 timeoutMs/socketTimeoutMs; `LlmHttpClient` 共享单例; `close()` 转 no-op

## v0.29.0 (2026-08-04) — 搜索原生内置 + 插件发布架构迁移 + Gitee 全量镜像

### 功能增强
- **Tavily 搜索原生内置**: plugin-tavily 从 remote marketplace 插件改为随 APK 打包的 bundled 插件, 开机自动激活, Agent 原生即具备 `tavily.search` 搜索能力; 系统提示词 FewShot 示例同步改为原生搜索示例, 插件段明示"网页搜索已内置"

### 架构完善
- **触发器重命名 trueman → trumanshow**: 工作区规则文件全栈更名 (模板资产 + 代码 + 文档 + 旧工作区); AgentDocs.bootstrap 新增迁移 v3 — 文件重命名 + 指纹刷新 + heartbeat.md/agents.md 内容引用替换 (幂等, 兼容旧工作区)
- **插件发布路径迁移 mengpaw-connectors 独立仓库**: 主仓库 MengPaw 的 plugins-v0.2.0 tag 不存在 (实际 plugins-v0.20.2), 全部 remote 插件 downloadUrl 404; 迁移后 9 个主插件 + 5 个连接器 AAR 同仓双源发布 (GitHub + Gitee), checksum/size 实测补齐
- **Gitee 全量镜像**: mengpaw-connectors Gitee 仓库建成公开, 代码 + tags + 双 release (plugins-v0.20.2 / plugins-v0.1.0) 14 个 AAR 全量镜像, mirrorUrl 全部 200, 国内客户端 GeoRouter 首选 Gitee 不再依赖 GitHub fallback
- **浏览器版本动态化**: BuiltinBrowserPlugin self.version 不再硬编码, 改读 BuildConfig.VERSION_NAME; 提示词中"浏览器协作"节移除硬编码版本描述

### 修复
- **plugin-browser-mcp 编译缺陷**: 缺 kotlinx-coroutines-core 依赖 (remote 插件从未构建 release, 隐藏至今), 补依赖后构建通过

### 发行
- Shell APK v0.29.0 (versionCode 29000) — 全量发布双远端 + GitHub Release
- Browser APK v0.7.0 (versionCode 9, 独立版本节奏) — 产物命名改随浏览器自身版本, 不再跟随主版本号

## v0.28.4 (2026-08-04) — 流式第三轮彻查: 打点验证 + 五缺陷修复

### 修复
- **fallback 链路静默丢流式**: AdaptiveLlmProvider.executeWithRetry 的 fallback 分支此前恒走非流式 completeWithMessages(onToken 被丢弃)——主 API 失败后回答整段弹出;改为 stream 时走 completeStreamingWithMessages;RemoteApi 补 override(平移死代码 completeStreaming)并补齐 180s socketTimeout
- **60s 超时静默截断**: consumeSseStream 把 SocketTimeoutException 吞掉返回空/部分内容且不重试;改为区分取消(先行 rethrow, 保证 stop() 契约)与异常(首 token 前超时抛 LlmApiException 触发重试+fallback 链;已有内容则返回部分);socketTimeoutMs 提至 180s(推理模型思考期可达 60s+; Ktor 3.x OkHttp 引擎不映射 requestTimeout, socketTimeout 是唯一活超时)
- **Swarm/Mission 合成阶段流式化**: 最终报告(synthesize)改 completeStreaming 逐字输出;worker/decompose/verify 并行阶段保持非流式(onStep/traces 呈现进度)
- **PLAN 模式补流式通道**: runWithPlan → executePlanStep 透传 onDelta, 步骤执行 LLM 调用流式化
- **resolveRunningIndex 快路径修复**: 每次气泡替换后同步 runningMsgRef/runningMsgIndex(此前 ref 恒指向被替换的旧实例, 每次更新退化为 O(n) 类型兜底)
- **节流尾段 flush**: run() 返回后强制推送 50ms 窗口内残留增量, 修复"最后一段整块弹出"(doTranslate 开启时跳过)

### 诊断
- 全链路埋 MengPawStream 打点日志(传输层 S-OPEN/S-FIRST/S-DONE/S-ERR、引擎 ENG-REACT、UI UI-DETECT/UI-MODE/UI-PUSH/UI-FINAL、重试 RETRY), 真机 logcat 可完整还原流式链路, 验证后移除

### 发行
- Shell APK v0.28.4 (versionCode 28004) — 本地打包推送两台设备, 暂不上传远端

## v0.28.3 (2026-08-04) — 流式输出三根因修复 (金字塔彻查)

### 修复
- **流式缓冲跨轮污染 (根因1)**: onDelta 的 streamBuf 在 ReAct 循环中只累积从不清理——"Action:" 一旦出现即永久锁存 hasAction, 最终答案若按 parse Rule 3 输出纯文本(无 "Final Answer:" 前缀)则整段增量被过滤丢弃, UI 全程"思考中..."直到整段弹出。改为工具轮结束(onStep)即清空缓冲, 每轮独立累积——工具轮后的纯文本答案恢复逐字流式显示; 死变量 sawActionMarker 一并移除
- **显示过滤增强**: 新增 "Thought:" 样板隐藏(只显示其后内容), 避免思考样板混入流式气泡; "Final Answer:" 分支仍只显示答案部分
- **模式分发透传流式回调 (根因3)**: GOAL/MISSION/FLEET/SWARM 引擎方法与执行器新增 onDelta 参数透传——自动复杂度升级(ComplexityDetector)到这些模式后打字机不再丢失; 拆解失败退化单 Agent 的兜底路径同样流式
- **节流尾段兜底 (根因2)**: 50ms 节流窗口内残留的尾部增量由回复完成时的整条替换兜底, 不丢内容

### 发行
- Shell APK v0.28.3 (versionCode 28003) — 本地打包推送两台设备, 暂不上传远端

## v0.28.2 (2026-08-04) — 气泡流式输出 + 重复思考修复 + 提示词进化

### 新增
- **气泡流式输出（打字机效果）**: 内核 ReAct 循环 LLM 调用改 SSE 流式（新增 `completeStreamingWithMessages` 接口），增量 token 经 onDelta 实时直通气泡——最终答案边生成边逐字出现；中间轮 Thought/Action 样板不透传（由思考过程 traces 消化），50ms 节流防每 token 全量重解析 Markdown。调研结论：llm-typewriter 0.1.x 要求 compileSdk 36（项目 AGP 8.7.3 上限 35），自研实现复用现有内核能力与 MarkdownText

### 修复
- **一瞬间 N 个相同思考/工具调用**: 同批多个 Action 并行执行时每个 Action 都带同一 Thought → UI 重复渲染；改为同批相同命令去重只执行一次 + 后续 Action 思考置空（UI 渲染成缩进纯工具行）
- **提示词"带着答案回来"编造引导**: 删除"先自己想办法…带着答案回来，不是带着问题"（工具失败后模型被迫编造假结果）；恢复 QwenPaw 原文完整方法序列（读文件/查上下文/搜一搜/看看 skills/工具 → 卡住了再问），并新增"失败如实汇报，禁止编造——承认错误不可耻，每一次如实的失败都是进化的原料"（与进化系统失败钩子闭环）
- **工作区文件树 memory 目录化**: memory/ 由合成文档改为目录节点——点 memory（去斜杠）→ 展开出 memory.md / memory_*.md / project_*_memory.md 各自成行 → 再点单个文件展开完整正文；子文件可单独删除，目录节点只读

### 发行
- Shell APK v0.28.2（versionCode 28002）— 浏览器保持 v0.7.0 独立版本线

## v0.28.1 (2026-08-04) — 真机反馈七项修复 + 言简意赅收敛

### 修复
- **HEARTBEAT.md 与 heartbeat.md 并存**: CRON 读取路径确认指向小写 heartbeat.md；迁移 v2——并存时删除大写残留（孪生同步并集式不传播删除，每设备靠自身 bootstrap 启动自清理）
- **heartbeat.md 内容含旧大写自引用**: 内容含 "HEARTBEAT.md"（v0.27.1 旧模板指纹）→ 用当前模板原子覆盖；已演化内容不碰
- **memory/ 标题去括号**: 工作区文件树 zh/en 统一裸名 "memory/"
- **框架通讯录出现自己（手机）**: mDNS 自过滤只比实例名、被系统改名 "(2)" 后失效 → 改 IP 比对（遍历本机 NetworkInterface）+ register 时按 IP 清除存量自条目
- **任务状态竖条离线仍绿**: 竖条只吃部落任务聚合、离线永远落 GREEN 兜底 → 加 TribeBarState.GRAY 绑 Gray6，离线灰、在线保持原逻辑
- **框架条目展开标识去掉**: 主侧栏 + 历史侧栏两处 chevron 删除（点击展开行为保留）
- **展开智能体列表出现 inbox**: 发送端 agentNames 加 systemDirs 过滤（inbox/team/acp/incubator/agent-001，与本地列表同源），系统目录不再进 mDNS TXT
- **言简意赅插件两处越界修复**: 删除规则 1（强要求句删除——导致模型不再分步思考、气泡流式输出消失）与规则 2（反 Markdown 装饰约束——强制纯文本、本地与接收端 Markdown 渲染全失效）；收敛为提示词前缀注入一行温和简洁引导（幂等守卫），兑现"只动前缀"约定
- **工作区文件树 memory/ 目录化**: 原 memory/ 是一行合成文档（所有记忆文件拼接截断），点击直接展开一大段内容；改为目录节点——点 memory（去斜杠，无 .md 后缀即目录）→ 展开出 memory.md / memory_*.md / project_*_memory.md 各自成行 → 再点单个文件才展开完整正文；子文件可单独删除，目录节点只读

### 发行
- Shell APK v0.28.1（versionCode 28001）— 浏览器保持 v0.7.0 独立版本线

## v0.28.0 (2026-08-03) — 伪人模式定案 + 工作区进化 + 升级迁移

### 新增
- **profile.md 注入系统提示词**: 身份档案每轮可见（`agent.write profile.md` 可改，改名/换人设即时生效）——与 QwenPaw 对齐
- **memory/memory.md 初始指导剧本**: 模板机制支持子目录，新 Agent 工作区自带记忆玩法说明书（三轨制命令对齐）
- **trueman.md 进化剧本**: 随机对话（Truman Show/伪人模式）可自进化——每次聊天后 `agent.memory.record` 观察 → 约两周/10 次后提炼时段规律 → `self.trigger` 收紧窗口；铁律：窗口≥4h 保留随机、慢进化、透明汇报、主人最大（内核零改动，全用既有命令）
- **工作区模板全面进化 zh+en**: 命令对齐（self.tools/self.search）、三轨记忆、进化系统、记忆孪生说明；en 补齐 agents/soul/profile/trigger

### 调整
- **SCHEDULE 触发器命名定案**: 英文 **Truman Show**（电影全名，避免杜鲁门总统歧义）、中文「伪人模式」（明牌自嘲：不是真人，是演出来的、知情可控的伪人）；按钮保留「添加真人感」
- **heartbeat.md 小写统一**: 模板与读取路径全部小写（v0.27.1 及更早为大写 HEARTBEAT.md）；新增 trueman.md 双规则文件按触发类型载入（CRON→heartbeat.md，SCHEDULE→trueman.md）
- **老工作区一次性升级迁移**: bootstrap 检测旧大写文件 → 改名 + 补种缺失新文档（trueman.md/memory/memory.md，不覆盖已演化内容）

### 社区
- **开放 Pull Request**: 版权让渡声明 + CI 门禁（JDK17 + kernel/plugin 测试）+ 九维评审 skill，合并由作者拍板

### 发行
- Shell APK v0.28.0（versionCode 28000）— 浏览器保持 v0.7.0 独立版本线

## v0.27.1 (2026-08-03) — 真机闪退热修复 (ART VerifyError)

### 修复
- **启动即闪退（重大，真机必现）**: AppStrings 数据类 305 个字段 → 构造函数 305 参数 → invoke-direct/range 指令超出 ART 255 寄存器上限 → StringsKt 类加载验证失败（VerifyError）→ 任何界面渲染即闪退。v0.26.4 的 232 字段未超限，v0.27.0 新增 73 本地化字段突破阈值。改为**普通 class + 无参构造 + apply 块初始化**，引用点零改动；平板/手机真机验证通过

### 发行
- Shell APK v0.27.1（versionCode 27001）— 浏览器保持 v0.7.0 独立版本线

## v0.27.0 (2026-08-03) — 全面本地化 + 技能种子重构 + 实时刷新

### 新增
- **技能种子三层模型（重大重构）**: 全局技能不再硬编码进 SkillPlugin 代码（原 DEFAULT_SKILLS 约 230 行删除）——改为 assets/skills/ 随 APK 打包 → 首启复制到 `/技能剧本/seed/`（App 自带版本，只读）→ 全局技能池 `/技能剧本/`。启动同步：Agent 未演化的文件随 App 版本更新，演化过的文件保留；manifest（sha256 行格式，免 JSON 依赖）记录上一内置版本供 Agent 比对差异
- **补火种 execution-modes.md**: 原 DEFAULT_SKILLS 中缺失的种子技能（执行模式手册）补齐
- **设置页实时刷新（事件驱动）**: AgentDocs.onDocChanged 单回调改多播（CopyOnWriteArrayList），Agent 写工作区文档（记忆/文档/孪生同步）→ 工作区文件列表与 memory 文件树立即重扫——分屏聊天一边聊一边可见文档变动
- **工具/技能/插件列表实时刷新**: AgentEngine 新增命令完成监听器（与文档监听同构），ReAct 循环每批命令与 bang 命令执行完毕 → 全局工具/智能体工具/智能体技能/全局技能/插件五类列表实时重扫（此前仅设置页打开时快照）
- **SCHEDULE 触发器命名定案**: 英文 TrueMen（致敬《楚门的世界》）、中文「随机对话」，按钮保留「添加真人感」

### 修复
- **memory 文件树不显示**: 工作区文件列表 LaunchedEffect 缺 showSettings 键——首次快照停留在启动时，之后 Agent 写入的 memory/ 记忆文件不出现；加 showSettings 键 + guard 修复
- **英文模式残留中文**: Framework Status 三状态及描述、Framework Directory 在线/离线徽章、框架名片、智能体名片、新建智能体、记忆孪生配对对话框、顶栏「智能体还未配置模型」等全部走 AppStrings 双语

### 调整
- **PromptEngine 技能段说明**: 中文段注明 seed/ 只读官方版本与全局池演化版的关系；英文 Knowledge 段同步补充——Agent 可自行比对官方新技能吸收知识
- **SkillPlugin 收敛**: 删除 seedDefaults() 及其调用点，onInstall 只保留存量迁移（migrateLegacySkills）

### 发行
- Shell APK v0.27.0（versionCode 27000）— 浏览器保持 v0.7.0 独立版本线

## v0.26.4 (2026-08-02) — 执行模式补火种 + 技能进化 + 闪退修复

### 修复
- **技能/插件剧本文档展开闪退**: 去掉 300dp 裁切后 MarkdownText 自带 verticalScroll 在双层 AnimatedVisibility 下拿到无限高度约束崩溃——改 nestedScroll 交由外层滚动（真机复现，全局技能/插件/工具展开区同构一并修复）
- **MCP 工具数量纠正**: 技能文档误写 7 个工具（把 GitHub API 字段当工具）——真实 6 个：navigate/screenshot/click/type/extract/eval

### 新增
- **执行模式补 Swarm（火种）**: PromptEngine 已有 /Swarm 但 UI 枚举缺失无法选择——补齐 + 火种图标 + 输入提示双语；模式区 Row(take(6)) 截断改造为 **FlowRow 两行布局**，容纳全部 7 模式（4+3）

### 调整
- **设置页清理**: 删除角色模型路由区块（标题+角色下拉）与 roleEnglishLabel 死函数、9 条中英死字符串；ViewModel 层数据保留
- **技能进化**: make-plan/guidance/browser-spider/browser-form 按 make_skills 流程进化（命令全量核对源码、补 MCP 工具清单、统一适用场景/执行步骤/注意事项/**进化目标三要素**模板）
- **触发词全覆盖**: 24 个技能/手册 description 全部补齐触发词（「填表单」「抓取这个网页」「怎么装插件」「浏览器报错」等）——Agent 一说即触发

### 发行
- Shell APK v0.26.4（versionCode 26004）— 浏览器保持 v0.7.0 独立版本线

## v0.26.3 (2026-08-02) — 技能文档全文展开 + 表格渲染修复

### 新增
- **技能/插件文档全文展开**: 全局技能与插件卡片不再只取首行做摘要，改为 `extractSummary`（跳 YAML frontmatter 取首个标题/正文行）+ md 全文载入；展开区去掉 300dp 高度裁切，长剧本由页面滚动查看

### 修复
- **致谢表格列错位**: TableTextView 表头/数据行此前各自独立测宽导致竖线错位——改为 TextMeasurer 全表测量取列内最宽值，所有行共享列宽对齐
- **长单元格静默裁切**: 表格数据单元格去掉 maxLines=2，attributions 60+ 字长内容完整显示（表头保留 2 行）

### 调整
- **设置页清理**: 删除角色模型路由描述文本与两处「需安装插件」徽章（功能已内置，显示冗余）+ 随之失效的中英死字符串
- **prompt 实验记录**: scripts/prompt_compare/ 归档言简意赅插件（plugin-concise）的 before/after 系统提示词与采样对比依据

### 发行
- Shell APK v0.26.3（versionCode 26003）— 浏览器保持 v0.7.0 独立版本线

## v0.26.2 (2026-08-02) — UI 英文化 + 插件双语命名

### 调整
- **插件双语命名**: 全部插件显示为「中文名称 (English)」（如 言简意赅 (Concise) / 智能体进化 (Agent Evolution)）— 设置页/插件市场/详情页统一；统一 6 处中文名不一致（框架通信→框架发现 等）
- **UI 英文化**: 插件市场 21 处 / 插件详情 11 处 / 触发器对话框 17 处 / 卡片组件等硬编码全部走 AppStrings 双语体系；枚举 label（内置/官方/自建、亮色/暗色/跟随系统、后台模式、角色名、LLM 供应商）按语言切换 — 英文界面不再显示中文
- **数据层中英对照**: 插件描述 13 条 + 工具/命令释义 50 条 + 内核命名空间「中文 (English)」对照（工作区文件豁免 — 用户数据不翻译）
- **README 英文版**: README.en.md 全译 + 语言切换链接 + GitHub 简介双语
- **分割线清理**: 删除 3 处多余分割线（重复双线/末尾孤立）
- **技能合并**: make_skills 吸收 make-skill 优点（口语化触发词/会话沉淀入口/完整示例）后删除 make-skill — 单一技能体系

### 修复
- 死代码 AgentItemsSection（无调用点）移除

### 发行
- Shell APK v0.26.2（versionCode 26002）— 浏览器保持 v0.7.0 独立版本线

## v0.26.1 (2026-08-02) — 九维修复 + 技能池路径修复 + UI 调整

### 修复
- **九维审查修复**（P1 3 项）: 哨兵协议误报（printf 无换行输出） / 写后读缓存陈旧（写命令清缓存） / Mission/Swarm worker 劫持 activeSessionId（折叠压缩错会话）
- **九维审查补全**（识别未修项）: Mission 状态机复位 / 省察引导只注入主会话 / bang fallback 仅限真 Unknown command / 补全读本引擎注册表 / 提示词模板内容哈希自动失效缓存
- **全局技能池路径不一致（重大）**: SkillPlugin 收敛 `/技能剧本/`——此前运行时技能在插件私有目录、UI 读 `/技能剧本/`，三方不一致导致种子技能（含 Find_Skills/Make_Skills）在设置页不可见；存量迁移移动不留残留
- 会话式进程池并发上限 / cd 注入转义 / 取消契约 / 缓存字节上限 / buildPipeline 并发锁 / 多 Action 行首锚定等 14 项 P2

### 调整
- **Fleet 改名「步坦协同模式」**（Combined Arms Mode，仅显示层）；LoopMode 补英文翻译（英文 UI 不再显示中文）
- 火种模式/步坦协同加 WowBlue 徽标；智能体进化、言简意赅加入 WowBlue 原创标识
- 角色模型路由标题本地化；文件完整性防护行补展开标识（chevron）

### 发行
- Shell APK v0.26.1（versionCode 26001）— 浏览器保持 v0.7.0 独立版本线

## v0.26.0 (2026-08-02) — 指令直通 + 引擎提速

### 新增
- **! 命令体系**: 输入 `!cmd` 绕过 Agent 直接执行（Pipeline 安全管线优先 + 受控 shell fallback），命令文本完整保留在消息前缀中；输入 `!` 弹出命令补全下拉（与 @mention 同一悬浮控件，候选含命令名 + 功能描述）
- **言简意赅内置插件**: middleware 去除系统提示词结构性输出干扰（删除强制 Thought→Action 完整序列要求 + 反 Markdown 约束），插件可开关，停用即恢复原提示词
- **角色模型路由**: Fleet/火种各角色（planner/worker/verifier/synthesizer/worker.alt）可配不同模型 — 规划/验收用强模型、执行用便宜模型，Andon 重派自动切备用模型；框架设置页可视化配置 + Vault 加密持久化
- **全局技能池**: Find_Skills（整合 findskills.org API + skills.sh 排行榜检索外部技能）、Make_Skills（知识剧本/剧本+脚本/流程 Flow 三类技能设计 + 稳定进化目标 + 进化升级闭环）
- **多 Action 并行执行**: 一次 LLM 输出解析多个 Action 并行执行、合并 Observation（10 步任务省 60-70% 延迟）
- **Mission 并行化**: 串行子任务改走并行管线（WIP 闸 + maxParallel），worker 零待命独立会话（不污染主会话/不写记忆）；Mission/Swarm 公共提示词提取
- **工具进程会话式复用**: 常驻 sh 会话进程池（哨兵协议携带退出码，每次调用自动初始化 cwd，超时销毁防泄漏）

### 优化
- **提示词瘦身**: docs 摘要化（超 12K 字符截断 + agent.read 外链）、few-shot 精简、工具结果激进裁剪；`PROMPT_TEMPLATE_VERSION` 纳入缓存键
- **Pipeline 只读命令结果缓存**: 白名单 + 5s TTL + 会话级键，消除 ReAct 循环内重复查询
- **上下文折叠改造**: 阈值 0.9（模型档位化）+ MIN 组数保底 + token 预算保留原文（连贯性档位 8%/15%/25%）+ 摘要长度反相关（目标占用率 ~60%）
- **省察引导缓存修复**: Evolution 引导改末尾追加（前插会击穿整个前缀缓存）

### 修复
- 多 Action + 末尾 Final Answer 被 Rule 1 吞掉（并行执行形态让位 Rule 2）
- `!` 命令取消路径子进程泄漏（会话式池超时/取消 destroyForcibly）

### 发行
- Shell APK v0.26.0（versionCode 26000）— 浏览器保持 v0.7.0 独立版本线

## 浏览器 v0.7.0 (2026-08-02) — MCP 服务 + 前台唤醒

> 浏览器独立版本线。自 v0.6.0 起新增与 MengPaw Shell 的设备内互联改造，本版首次发布。

### 新增
- **设备内 MCP 服务**: APK 内置 `McpHttpServer` (127.0.0.1:9880) — `GET /health` + `POST /mcp`，与 Shell `BrowserMcpPlugin` 经 HTTP 桥互联（替代类加载器隔离下静态字段失效的方案）
- **前台唤醒**: `onNewIntent` 处理 OPEN_URL / OPEN_MD / VIEW — Agent 可唤起到前台并打开网页/Markdown
- **Agent 协同设置**: 设置弹窗新增协同相关配置

### 发行
- Browser APK v0.7.0 (versionCode 9)，独立版本线，与主应用 release 分开发布

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
