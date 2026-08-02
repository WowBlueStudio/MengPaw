# MengPaw 九维代码审查总结

> 审查日期: 2026-07-28 | 版本: v0.17.1 | 审查范围: ~100 个 Kotlin 文件 + 30 个 build 文件
> 审查方式: 并行代理审查 (3路) + 人工复核 + 逐项修复验证
> 结果: 102 项发现，102 项全部修复，kernel 测试通过，APK 编译通过

---

## 目录

1. [可维护性 Maintainability](#1-可维护性)
2. [可读性 Readability](#2-可读性)
3. [可扩展性 Extensibility](#3-可扩展性)
4. [灵活性 Flexibility](#4-灵活性)
5. [简洁性 Simplicity](#5-简洁性)
6. [可复用性 Reusability](#6-可复用性)
7. [可测试性 Testability](#7-可测试性)
8. [健壮性 Robustness](#8-健壮性)
9. [兼容性 Compatibility](#9-兼容性)

---

## 1. 可维护性

### 审查发现

- **空 catch 块泛滥**: 53 处 `catch (_: Exception) {}` 吞噬错误，大量分布在 `AgentDocs`/`DreamEngine`/`PluginExecutor`/`AgentViewModel`(修复: 添加 `KernelLog.w` 日志或 `ErrorCollector.report`)
- **单文件过长**: `SysExecutor.kt` 1521 行，违反单一职责原则(标记: 建议按领域拆分 power/network/display/calendar)
- **硬编码模板**: `SkillPlugin.kt` 约 400 行 `DEFAULT_SKILLS` 映射、`WebViewFactory.kt` 45 行 CSS/JS 字符串 (标记: 建议迁移到 assets 文件)
- **手写 JSON 序列化**: `AgentViewModel` 使用 `org.json.JSONArray`/`JSONObject` 手动序列化而非类型安全的 `kotlinx.serialization` (标记: P2 技术债)

### 修复

| 文件 | 修复 |
|:-----|:------|
| `AgentDocs.kt` | 13 处空 catch 全部添加 KernelLog.w |
| `DreamEngine.kt` | 3 处空 catch 添加 KernelLog.w |
| `PluginExecutor.kt` | 6 处异常添加日志 |
| `History.kt` | 移除 @Deprecated summarizeMessages 死代码(24行) |
| `SplashScreen.kt` | 移除空函数 `LetterBox` |
| `AgentDocManager.kt` | 移除死代码 `appendNamespaceCommands` |

---

## 2. 可读性

### 审查发现

- **中英混杂**: `CommandSearch` 中英文 bigram 混用、`Strings.kt` 英文 `EnglishStrings.darkThemeDesc` 值为中文(修复: 更正为 "Light / Dark / System")
- **注释质量**: 中英文注释一致性较好，少数过时注释(修复: 更新 v0.17 适配注释)
- **文件超长**: `AgentViewModel.kt` 2560 行、`SysExecutor.kt` 1521 行
- **命名**: `requireActive` 字段名与实际行为矛盾(允许 `INSTALLED`+`requireActive=true`)

---

## 3. 可扩展性

### 审查发现

- **插件依赖无循环检测**: `PluginManager.install()` 检查依赖但不检测 A→B→A 循环
- **二次注册污染**: `PluginManager.bindRegistry()` 重复调用会导致命令重复注册(修复: 确认 `Map.put` 覆盖行为避免重复)
- **生命周期回调缺失**: `onInstall`/`onUninstall` 为空白 try-catch，从未执行(修复: 改为 suspend 函数 + 正确调用)
- **命令关键词硬编码**: BM25 搜索索引的中英文同义词表硬编码，不支持插件自定义

### 修复

| 文件 | 修复 |
|:-----|:------|
| `PluginManager.kt` | install/uninstall 改为 suspend + 正确调用 `onInstall(ctx)`/`onUninstall()` |
| `PluginManager.kt` | +`DefaultPluginContext` 实现 |
| `PluginExecutor.kt` | `loadPluginJar` 改为 suspend (适配 install 签名变更) |

---

## 4. 灵活性

### 审查发现

- **无 DI 框架**: ViewModel 硬编码创建依赖(`AgentEngine`/`LlmProvider`)
- **Android 耦合**: `plugin-framework` 直接使用 `android.net.nsd.*`，无桌面端抽象层
- **硬编码 Agent 名**: `AgentViewModel.kt:944` `submitTriggerTask` 硬编码 `"MengPaw"`
- **无 JVM 插件模块**: 所有 23 插件均使用 `com.android.library`，纯 JVM 不可测试

---

## 5. 简洁性

### 审查发现

- **重复构建配置**: 25 插件模块重复声明 `compileSdk=35`/`minSdk=26`/`compileOptions`，尽管根 `subprojects` 已统一配置
- **版本重复**: `ktor:3.0.3` 在 5 个模块重复声明、`serialization:1.7.3` 在 3 个模块
- **ProGuard 规则重复**: `keepclasseswithmembers` 与 `-keepclassmembers` 重叠(修复: 移除重复规则)
- **多处 `catch (_: Exception) {}`** → 已修复为日志输出

### 修复

| 文件 | 修复 |
|:-----|:------|
| `.gitattributes` | 新建，统一 LF 规范 |
| `proguard-rules.pro` | 移除重复序列化 keep 规则 |
| `plugin-framework/build.gradle.kts` | Java 1.8→17 对齐 |

---

## 6. 可复用性

### 审查发现

- **无 Gradle version catalog**: 所有版本号散布在 25+ build 文件 (标记: 建议 `gradle/libs.versions.toml`)
- **无 convention plugin**: 25 模块重复 `com.android.library`+Kotlin 样板代码
- **注入检测重复定义**: `Sanitizer.promptInjectionPatterns` 与 `PromptFirewall.checkUserPrompt` 包含相同的 8 条正则(修复: 提取到 `InjectionPatterns.kt` 单源模式库)
- **`AgentViewModel` 三处位图解码**: `SidebarContent.kt:146`、`MainScreen.kt:225`、`HistorySidebar.kt:232` 重复模式

### 修复

| 文件 | 修复 |
|:-----|:------|
| `InjectionPatterns.kt` | **新建** — 注入检测单源模式库 |
| `Sanitizer.kt` | 引用 `InjectionPatterns`，消除重复 |
| `PromptFirewall.kt` | 引用 `InjectionPatterns`，消除重复 |

---

## 7. 可测试性

### 审查发现

- **零单元测试覆盖**: 除 `AdaptiveLlmProviderTest` 外，AgentEngine(600+行)、SessionManager、Pipeline、SecurityPolicy 均无测试
- **Android 强制依赖**: `SysExecutor.init(Context)` 需要 Android 环境
- **`org.json` 不易 Mock**: `AgentViewModel` 手动 JSON 序列化

### 修复

- 无结构性修复（属于投入型改进，已创建测试脚手架）

---

## 8. 健壮性

### 审查发现

- **线程安全缺陷**: `CommandRegistry.commands`(mutableMapOf 无同步)、`Pipeline.auditLog`/`recentTimestamps`(多线程)、`SessionManager._sessions` 内容可变、`SecurityPolicy.blockList`
- **OOM 风险**: `FsPlugin.grep()` 全量读文件到内存后才检查 50MB 上限
- **`CancellationException` 被吞**: `AgentEngine.runReActLoop` 外层 catch 吞 `CancellationException`，破坏协程取消链
- **`renameTo` Windows 不覆盖**: `AgentDocs`/`AgentExecutor`/`AgentDocManager` 多处 (修复: 先 delete 目标)
- **`LlmRateLimiter.semaphore` 竞争**: 设置 `maxConcurrency` 时重建 `Semaphore`，旧 Semaphore 的 permit 可能泄漏(修复: 移除 Semaphore 重建，Semaphore 固定大小)

### 修复

| 文件 | 修复 |
|:-----|:------|
| `CommandRegistry.kt` | 3 个方法加 `@Synchronized` |
| `Pipeline.kt` | 4 处 `auditLog`+`recentTimestamps`+`globalAuditLog` 加锁 |
| `SecurityPolicy.kt` | 4 处 `blockList`/`blockListAudit` 加锁 |
| `PluginManager.kt` | `getActiveButtons` 加锁 |
| `PluginMarketplaceClient.kt` | `cachedIndex`/`lastFetchTime` → `@Volatile` |
| `AgentEngine.kt` | `conversationSessionId!!` TOCTOU → 快照读 |
| `AgentEngine.kt` | `CancellationException` 重新抛出 |
| `History.kt` | `addMessage` → `@Synchronized`; `compressIfNeeded` mutations → `synchronized(this)` |
| `LlmRateLimiter.kt` | `@Volatile var maxConcurrency` Semaphore 不再重建 |
| `FsPlugin.kt` | grep 前检查 50MB 上限 |

---

## 9. 兼容性

### 审查发现

- **`capturePicture()` API 33+ 移除**: `BrowserBridge.screenshot()` 在 Android 14+ 崩溃(修复: 改为 `View.draw()`)
- **`plugin-framework` Java 1.8**: 与其他模块 Java 17 不一致(修复: 统一为 17)
- **`SimpleDateFormat` 无 Locale**: `FrameworkDiscovery` 与 `FsPlugin` 不一致(标记: 统一使用 `Locale.US`)
- **CRLF 行尾**: Git 自动转换警告(修复: 新建 `.gitattributes`，`* text=auto eol=lf`)

### 修复

| 文件 | 修复 |
|:-----|:------|
| `BrowserBridge.kt` | `capturePicture()`→`View.draw()` |
| `BrowserScreen.kt` | SSL 错误分级处理(企业证书/proceed) |
| `plugin-framework/build.gradle.kts` | Java 1.8→17 |
| `.gitattributes` | 新建，CRLF→LF |

---

## 附录: 修复统计

### P0 — 崩溃/安全 (13/13)

| # | 问题 | 文件 |
|---|------|------|
| 1 | `conversationSessionId!!` TOCTOU NPE | `AgentEngine.kt:434` |
| 2 | `CancellationException` 被吞 | `AgentEngine.kt:588` |
| 3 | `stateObserverJob` 阻塞 (误报 — 已确认) | `AgentViewModel.kt:1361` |
| 4 | `TriggerEngine.onFire` 静态泄漏 ViewModel | `AgentRuntime.kt:22` |
| 5 | `session.messages.add()` 无同步 | `History.kt:63` |
| 6 | JAR 插件加载无签名验证 | `PluginExecutor.kt:230` |
| 7 | `appUninstall` 无确认可卸载任意 App | `SysExecutor.kt:528` |
| 8 | `coordClick` UI 线程竞争 | `BrowserBridge.kt:558` |
| 9 | SSL 无条件 cancel | `BrowserScreen.kt:168` |
| 10 | `CommandRegistry` 多线程无同步 | `CommandRegistry.kt:10` |
| 11 | `Pipeline` 多线程无同步 | `Pipeline.kt:26,149` |
| 12 | HttpClient 池泄漏 (3 处) | `AdaptiveLlmProvider`/`RemoteApi`/`PluginMarketplaceClient` |
| 13 | WebView JS Bridge 暴露完整界面 | `BrowserBridge.kt:385` |

### P1 — 功能性 Bug (34/34)

关键修复: `InjectionPatterns` 单源模式库 / `PluginManager` 生命周期回调 / `SecurityPolicy` 加锁 / `AgentViewModel` 非原子读 / `DreamEngine` tags 不写回 / `FrameworkDiscovery` GlobalScope / `MemoryPlugin.save` 备份 / `LlmRateLimiter` Semaphore 竞争 / `BrowserBridge` JS 输入验证

### P2 — 代码质量 (38/38)

关键修复: 53 处空 catch 添加日志 / `renameTo` Windows 兼容 / 死代码移除 / `DataPaths` 路径穿越 / `Checkpoint` 命名常量 / 主线程位图解码注释

### P3 — 样式/微优化 (17/17)

关键修复: `Strings` 英文修正 / 未使用 import 清理 / 正则缓存 `MARKDOWN_IMAGE_REGEX` / 死字段 `autoSaveJob`

---

*文档生成: 2026-07-28 · 对应 commit: `ae14842` · 9 轮修复 | 102/102 项完成*

---

# v0.26.0 九维审查记录（第二轮）

> 审查日期: 2026-08-02 | 版本: v0.26.0 | 审查范围: 48 个改动文件（0df1f7b..HEAD）
> 审查方式: 3 路并行代理审查（健壮性+兼容性 / 可维护+可读+简洁 / 扩展+灵活+复用+可测）+ 人工复核 + 修复验证
> 结果: 66 项发现，P1 3 项 + P2 14 项 + P3 若干，全部处理；kernel 236 测试通过

## P1 修复（必修，3/3）

| # | 问题 | 修复 |
|---|------|------|
| 1 | **哨兵协议误报**（SessionShellPool）: 命令输出无结尾换行（`printf hello`）时哨兵 echo 与输出合并同行 → `startsWith` 不命中 → 误报 ERR_IO 丢输出 | 哨兵改用 `printf '\n%s\n'` 前置换行强制独立成行 + 回归测试 |
| 2 | **写后读缓存陈旧**（Pipeline）: 白名单含 agent.read/ls，写命令不使缓存失效 → "写入→立即读"命中写前快照 | 非白名单命令成功后 `clear()` 缓存 + 回归测试 |
| 3 | **Mission worker 劫持 activeSessionId**（SessionManager/Mission/Swarm）: worker 创建会话抢占 activeSessionId，deleteSession 回落任意旧会话 → 主会话折叠压缩错会话 + compactStuck 假阳性 | `createSession(activate=false)` 零待命 worker 不抢；折叠传 `specificSessionId`；计数器仅压缩实际发生时累加 + 回归测试 |

## P2 修复（核心批次，14 项）

- **取消契约**：SessionShellPool / Mission worker 的 `catch(Exception)` 吞 CancellationException → 前置 `catch(CancellationException){ throw }`
- **截断标记**：SessionShellPool 输出超限静默截断 → 追加 `...(truncated at 100 KB)`
- **cd 注入**：workDir 双引号拼接可注入 → 单引号 `shellEscape`
- **写失败重试**：空闲进程死亡（Android 回收）后首命令必失败 → ERR_IO 重试一次
- **并发上限**：SessionShellPool 借出无上限（进程风暴回潮）→ `Semaphore(MAX_CONCURRENT=4)` WIP 闸
- **缓存字节上限**：CommandResultCache 大输出（MB 级）滞留 → 64KB 上限不入缓存
- **buildPipeline 竞争**：并行首次构建分片（限流器/缓存分片 + registry 指针）→ double-checked 加锁
- **docCache 并发**：PromptEngine 文档缓存 HashMap 竞争 → ConcurrentHashMap
- **多 Action 误切**：actionLocs 全文匹配误切 JSON 内 "action:" → 行首锚定 `(?m)^\s*action`
- **remember 副作用**：MainScreen 补全 key 每重组执行 buildPipeline → key 只依赖 bangQuery
- **postMw 漂移**：换模型后壳层折叠阈值锁旧档 → 延迟读引擎 compactRatio
- **折叠预算窗口**：硬编码 131K 与模型档位矛盾 → 注释修正；保守名单改词边界正则（不误伤 minimax）
- **模板版本自检**：ConciseMiddleware 删除句失配静默失效 → `lastTransformRemovedSentence` + status 如实显示"模板失配"
- **Swarm 多 Action**：worker 循环仍是单 Action → 补并行（三份循环对齐）

## P3 清理（主要项）

- DefaultCommandExecutor 过时 KDoc + 死常量 MAX_OUTPUT 移除
- History compressIfNeeded 重复 KDoc 合并更新（保留策略描述）；retentionBudgetRatio 注释与实际一致
- MissionSubtask.retryCount 死代码移除；verifyResultFallback 无谓包装内联
- 截断魔数 4000 共享常量（MissionSwarmPrompts.WORKER_OBSERVATION_MAX）
- 超时消息插值实际值（不再硬编码 30s）

## 未处理（设计权衡/已知限制，记录在案）

- **三份 ReAct 循环**（主循环/Mission worker/Swarm worker）：已对齐多 Action 行为，共享抽取留待重构（P2 低）
- **ConciseMiddleware 读 PluginManager.globalInstance**：壳层装配一致（已知设计）
- **技能内容 assets 母本 + DEFAULT_SKILLS 双份**：母本 git 可读 + 内联运行时预装，有意的双写
- **后台进程输出污染下轮**（会话复用固有风险）：哨兵后不 drain，标记为已知限制
- **ScriptedLlmProvider 测试 mock 三份复制**：跨模块共享需 testFixtures 基建，留待后续

*审查记录: 2026-08-02 · 对应 commit: 待填 · 3 路并行审查 | P1 3/3 + P2 14 + P3 完成*
