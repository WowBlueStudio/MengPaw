# 进化点提取：外部 Agent 心智模型反推

> **来源**：用户提供的一份外部文档（另一 Agent 仅凭用户口述的项目特点"猜"出的介绍，**不是事实陈述**）。
> **性质**：把这份"猜出来的介绍"当作免费的读者视角测试 —— 它补全的方向暴露真实缺口，它写错的地方暴露描述边界词的缺失。
> **状态**：定稿 ✓（E1/E2/E3/E6 可施工；E4/E5/E7/E8 只定启动条件） | **日期**：2026-10-22
> **关联**：[memory-git-sync-design.md](memory-git-sync-design.md)（P0-P4 记忆与库存 Git 化；本文件不另造 schema）
> **核对基准**：2026-10-22 工作区。所有 `file:line` 均为可点开的相对链接（自 `docs/` 起算，故带 `../`）；行号漂移与骨架纠错见 §8。

---

## §0 为什么要做这件事

外部 Agent 没读过代码，只听了描述，就写出了一份"技术上更强、更完整"的介绍。这份文档有两个用途：

1. **缺口探测**：它认为"应该存在"的能力，往往正是我们真正缺的那一环（例如它认为设备操作应该有 Intent→Shell→视觉的降级链）。
2. **表述体检**：它把 Kotlin+Compose 读成 Tauri+Rust、把设计稿读成已实现 —— 说明对外描述缺**边界词**，读者会按"业界最强形态"自动补全。

> 结论先行：8 个进化点（E1-E8），其中 E2（隔离分析）、E1（能力阶梯）、E3（服务注册）价值最高；3 个概念明确不做；1 条沟通规范定案。
> 本文件只写**能施工的部分**：E1/E2/E3/E6 给接口、判定表、阈值与文案；E4/E5/E7/E8 只写"什么时候才该启动"，不提前设计。

---

## §1 三类信号

| 信号类型 | 文档里的例子 | 说明 |
|---|---|---|
| **真实缺口** | "Intent → Shell → VLM 三级兜底" | 能力零件都有（Intent / 无障碍 / 视觉），但没有编排成链 |
| | "子会话隔离执行，防污染主上下文" | 有 strip+wrap，但不可信内容仍进主上下文 |
| | "GitDiff 分支回溯记忆" | 快照 DAG 本就支持多 head，缺的是命令面 |
| **被误读的强项** | "Tauri + Rust 微内核" | 微内核可移植是真的，但没有对外可验证的证据（零平台类型门禁只有内部知道） |
| | "已实现 GitDiff 账本" | 设计与实现没有分开表述 |
| | "虚拟管道 / 背压" | 常驻 `sh` 池能跑真管道，但我们没讲清"为什么不需要自造" |
| **可借概念** | "Service Registry" | 比我们的"设备库存"高一层的抽象：登记服务而非硬件 |
| | "Delta Context 降 token" | 我们在存储层做了 diff，没在**交接层**做 |
| | "冲突熔断" | 配对有冷却，同步没有熔断 |

---

## §2 八个进化点（总表）

| # | 进化点 | 来源句 | 价值 | 成本 | 归属阶段 | 本文件状态 |
|---|---|---|---|---|---|---|
| **E1** | 能力阶梯（设备操作统一降级链） | Intent → Shell → VLM 视觉兜底 | 高 | 中 | 新增 **P5** | 可施工 |
| **E2** | 不可信内容隔离分析 | 外源信息提纯 + 子会话隔离 | 很高 | 中 | 新增 **P6** | 可施工 |
| **E3** | 服务级注册（能力即服务） | 端侧环境名单（Service Registry） | 高 | 低-中 | 并入 **P4** | 可施工 |
| **E4** | 跨 Agent 增量交接（Delta Context） | 轻量级增量补丁降低 Token | 中 | 中 | **P3 之后** | 只定启动条件 |
| **E5** | 记忆实验分支（可试错归纳） | Git 分支实现记忆无污染回溯 | 中-高 | 低 | **P3 之后** | 只定启动条件 |
| **E6** | 同步熔断器 | 冲突熔断 | 中 | 低 | 并入 **P1** | 可施工 |
| **E7** | 能效可观测 | 功耗不足微信 10% | 中 | 低 | 随时 | 只定启动条件 |
| **E8** | 静默优先的跨端 UX | 无感跨端一致体验 | 中 | 低 | 随时 | 只定启动条件 |

---

## §3 详述

### 3.1 E1 能力阶梯（Capability Ladder）

**一句话**：设备操作不再"一条路失败即报错"，而是按**操作类型**逐级降级；每级留下可验证的成功判据与失败原因，整体耗尽时把"这台设备上这条路不通"沉淀给进化系统。

**边界（先说不是什么）**：
- 不新增权限模型：每级仍走既有 `CommandRiskLevels` + `RiskGate`（见 §3.1.6）。
- 阶梯不是全局一条链，而是**每个操作类型一张表**（启动/点击/输入/读屏/开关）。
- 视觉级只在装了外部视觉插件时存在；内置 16 个插件里没有视觉（证据见下）。
- 第二级不是"通用 shell"——Android 侧没有可用的通用 shell 桥（证据见下），只有 root 设备的 `root.shell`。

#### 3.1.1 操作类型枚举

| 枚举值 | 中文 | 目标 | 现有可复用命令 |
|---|---|---|---|
| `LAUNCH_APP` | 启动应用 | 让目标包进入前台 | `sys.app.launch`（[AppExecutor.kt:42-53](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/AppExecutor.kt#L42)） |
| `CLICK_CONTROL` | 点击控件 | 触发界面元素 | `sys.accessibility.click`（[AccessibilityExecutor.kt:58](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/AccessibilityExecutor.kt#L58)） |
| `INPUT_TEXT` | 输入文本 | 把文本写进聚焦输入框 | `sys.accessibility.input`（[AccessibilityExecutor.kt:105](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/AccessibilityExecutor.kt#L105)） |
| `READ_SCREEN` | 读屏状态 | 拿到当前界面控件树/内容 | `sys.accessibility.dump`（[AccessibilityExecutor.kt:43](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/AccessibilityExecutor.kt#L43)） |
| `TOGGLE_SETTING` | 开关设置 | 翻转系统/应用开关 | `sys.torch.on/off`、`sys.wallpaper.set`、`sys.wakelock.*`（[SysExecutor.kt:318-319](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/SysExecutor.kt#L318)） |

#### 3.1.2 现状证据（逐条已核对）

- **`sys.app.launch` 只有 Intent 一条路**：取不到 `getLaunchIntentForPackage` 直接失败；异常也只回 `Launch failed: <msg>`，没有下一跳。
  [AppExecutor.kt:42-53](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/AppExecutor.kt#L42)；注册处 [SysExecutor.kt:257](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/SysExecutor.kt#L257)。
- **无障碍命令全是"原子命令"，没有任何编排调用点**：全仓库除注册表与执行器自身外，没有第二处调用 `AccessibilityExecutor.*` 或 `MengPawAccessibilityService.*`。
  注册 [SysExecutor.kt:287-294](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/SysExecutor.kt#L287)。
- **风险级不是"全 HIGH"**：`status` = LOW、`dump` = MID、`click/swipe/input/back/home/recents` = HIGH。
  [CommandRiskLevels.kt:61](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L61)、[:104](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L104)、[:119-124](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L119)。
- **内置 16 个插件没有视觉**：`settings.gradle.kts` 列出的插件模块共 16 个，无 vision/图像理解插件。
  [settings.gradle.kts:43-58](../settings.gradle.kts#L43)。视觉只作为**模型属性**存在（库存 `agents[].vision`，[memory-git-sync-design.md:335](memory-git-sync-design.md#L335)；读取处 [TwinCapabilityCommands.kt:170](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinCapabilityCommands.kt#L170)）。
- **没有"通用 shell 等价命令"**：`plugin-termux` 只注册 3 条命令（status/python/ubuntu），不是任意 Android 命令桥（[TermuxPlugin.kt:37](../plugins/plugin-termux/src/main/kotlin/com/mengpaw/plugin/termux/TermuxPlugin.kt#L37)）；`root.shell` 需 root 插件且 root 设备（[CommandRiskLevels.kt:130](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L130)）；`sys.intent.open` 只接受 URL 或包名，**不能深链 `android.settings.*`**（[ClipboardIntentExecutor.kt:40-72](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/ClipboardIntentExecutor.kt#L40)）。

#### 3.1.3 五级定义

| 级 | 名称 | 含义 | 存在条件 |
|---|---|---|---|
| `L0_COMMAND` | 原生命令 | 专用 API/Intent 直达（如 `sys.app.launch`） | 命令已注册且前置满足 |
| `L1_PRIVILEGED` | 特权桥 | `root.shell`（am/monkey 等）；**仅 root 设备** | root 插件已装 + 已授权 root |
| `L2_ACCESSIBILITY` | 无障碍 | dump 定位 + click/input/global action | 无障碍已授权 **且** 服务已连接（[AccessibilityExecutor.kt:27-28](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/AccessibilityExecutor.kt#L27)） |
| `L3_VISION` | 视觉 | 截图 + 视觉插件给出坐标/描述 | 安装了外部视觉插件 且 当前模型支持视觉 |
| `L4_FAILED` | 结构化失败 | 不执行动作，只回可执行指引 + 沉淀 | 恒存在 |

#### 3.1.4 阶梯表（按操作类型）

**A. `LAUNCH_APP` 启动应用**

| 级 | 动作 | 前置条件 | 判"成功"的判据 | 失败后进入 |
|---|---|---|---|---|
| L0 | `sys.app.launch <pkg>` | 包已安装且有 launchIntent | `startActivity` 未抛异常；**且** 800ms 后 `dump` 根节点 `pkg == <pkg>`（dump 被 MID 门禁拒绝时记为"未验证成功"） | L1（若 root）→ L2 |
| L1 | `root.shell am start -n <pkg>/<launcher>`（launcher 由 `cmd package resolve-activity --brief` 取得） | root 插件 + root 授权 | 输出含 `Starting:`（无 `Error:`）且前台 `pkg == <pkg>` | L2 |
| L2 | `sys.accessibility.home` → `dump` 找图标 → `click --text <应用名>` | 无障碍已授权 + 已连接 | 前台 `pkg == <pkg>`（dump 根节点） | L3 |
| L3 | 外部视觉插件：`sys.screenshot` → 定位图标坐标 → `click x y` | 视觉插件 + 模型视觉 | 同 L2 | L4 |
| L4 | 返回失败文案 `LADDER_EXHAUSTED` + 沉淀经验 | — | — | 终止 |

**B. `CLICK_CONTROL` 点击控件**

| 级 | 动作 | 前置条件 | 判"成功"的判据 | 失败后进入 |
|---|---|---|---|---|
| L0 | `sys.accessibility.click --text <t>` 或 `--id <viewId>` | 无障碍连接 + dump 已确认目标存在 | 返回"点击完成"；且 200ms 后 dump 中目标消失/选中态变化 | L1 |
| L1 | dump 取目标 `bounds` 中心 → `click x y` | 同上 | 同上（坐标级，允许 ±1 次重试） | L2 |
| L2 | 截图 + 视觉定位坐标 | 视觉插件 | 同 L0 的界面变化判据 | L3 |
| L3 | 结构化失败：请用户点击，或给出坐标让用户确认 | — | — | 终止 |

**C. `INPUT_TEXT` 输入文本**

| 级 | 动作 | 前置条件 | 判"成功"的判据 | 失败后进入 |
|---|---|---|---|---|
| L0 | `sys.accessibility.input <text>` | 无障碍连接 + 已有聚焦输入框 | 返回"输入完成"；且 dump 中该文本出现在可编辑控件 | L1 |
| L1 | dump 找 `class=EditText` → `click --id` → `input` | 同上 | 同上 | L2 |
| L2 | 用户辅助：`sys.dialog.text` 让用户确认/提供文本，或 `sys.clipboard.set` + 指引长按粘贴 | 有前台 UI | 用户确认 **且** dump 中文本出现 | L3 |
| L3 | 结构化失败（请用户手动输入） | — | — | 终止 |

> L2 是"半自动"级，必须如实标 `verified=false`，不能把用户确认当作机器验证。

**D. `READ_SCREEN` 读屏**

| 级 | 动作 | 前置条件 | 判"成功"的判据 | 失败后进入 |
|---|---|---|---|---|
| L0 | `sys.accessibility.dump [--max N] [--depth N]` | 无障碍连接 + **Agent 权限 TRUSTED**（dump 是 MID） | 返回 JSON 且 `count > 0` | L1 |
| L1 | `sys.screenshot`（MID）+ 视觉插件转述 | 视觉插件 + 模型视觉 | 拿到结构化转述（只回事实，不回原文） | L2 |
| L2 | `sys.dialog.text` 请用户描述/确认屏幕 | 有前台 UI | 用户确认 | L3 |
| L3 | 结构化失败（说明"读不到屏幕"的具体原因） | — | — | 终止 |

**E. `TOGGLE_SETTING` 开关设置**

| 级 | 动作 | 前置条件 | 判"成功"的判据 | 失败后进入 |
|---|---|---|---|---|
| L0 | 专有命令：`sys.torch.on/off`、`sys.wallpaper.set`、`sys.wakelock.acquire/release` | 命令存在 + 对应权限 | 命令返回成功；有查询口的再查一次状态 | L1 |
| L1 | 无障碍进入设置：`home` → 打开设置 → dump 定位开关 → `click` | 无障碍连接 | dump 中该开关 `checked` 翻转 | L2 |
| L2 | 截图 + 视觉定位设置项 | 视觉插件 | `checked` 翻转 | L3 |
| L3 | 结构化失败：给出**精确设置路径**（如"设置 → 显示 → 深色模式"） | — | — | 终止 |

> 当前**没有**"Intent 深链设置页"的命令（`sys.intent.open` 只吃 URL/包名，见 3.1.2）；若将来要补 `sys.settings.open <action>`，单列命令并单独定风险级，不许塞进 `sys.intent.open` 偷渡。

#### 3.1.5 Kotlin 接口草案

```kotlin
// 落码时两个文件都要带 SPDX 双许可头（AGPL-3.0-or-later OR LicenseRef-Commercial）
package com.mengpaw.kernel.capability

/** 操作类型 —— 每个值对应一张阶梯表（§3.1.4）。 */
enum class LadderOp(val command: String) {
    LAUNCH_APP("sys.app.launch"),
    CLICK_CONTROL("sys.accessibility.click"),
    INPUT_TEXT("sys.accessibility.input"),
    READ_SCREEN("sys.accessibility.dump"),
    TOGGLE_SETTING("sys.torch.on")      // 代表命令；实际按设置项映射，专有命令优先（见阶梯表 E）
}

enum class LadderLevel { L0_COMMAND, L1_PRIVILEGED, L2_ACCESSIBILITY, L3_VISION, L4_FAILED }

/** 失败原因码 —— 与 §3.1.7 文案表一一对应。 */
enum class LadderReason {
    NOT_INSTALLED, NO_LAUNCH_INTENT, ROM_BLOCKED_INTENT,
    ACCESSIBILITY_DISABLED, ACCESSIBILITY_DISCONNECTED,
    TARGET_NOT_FOUND, NO_FOCUSED_INPUT, GESTURE_CANCELLED,
    VISION_UNAVAILABLE, RISK_DENIED, USER_DENIED, TIMEOUT, UNKNOWN
}

/** 单级尝试记录。 */
data class LadderStep(
    val level: LadderLevel,
    val ok: Boolean,
    /** 成功是否被独立证据确认（前台包名 / dump 变化 / checked 翻转）；false = 未验证成功。 */
    val verified: Boolean,
    val elapsedMs: Long,
    val reason: LadderReason? = null,
    /** 可直接进 Observation 的文案（§3.1.7 常量渲染）。 */
    val message: String = "",
    /** 证据串：`pkg=com.x` / `viewId=...` / `checked=false→true`。 */
    val evidence: String = ""
)

/** 一次阶梯执行的最终结果。 */
data class LadderResult(
    val op: LadderOp,
    val level: LadderLevel,     // 最终停留级（成功级或 L4_FAILED）
    val ok: Boolean,
    val elapsedMs: Long,        // 所有尝试累计耗时
    val reason: LadderReason?,
    val message: String,
    val steps: List<LadderStep> = emptyList()
)

interface CapabilityLadder {
    /** 本机当前可用的级别（按顺序）；L4 永远在末尾。 */
    fun availableLevels(op: LadderOp): List<LadderLevel>
    suspend fun run(op: LadderOp, args: Map<String, String>): LadderResult
}

/** 注入点 —— 照 FleetPlatform 的写法（kernel 只留 SPI，实现由各端注入）。 */
object CapabilityPlatform {
    @Volatile var ladder: CapabilityLadder? = null
}
```

**放哪个包 & 理由**：

| 归属 | 内容 | 理由 |
|---|---|---|
| **kernel** `com.mengpaw.kernel.capability` | 契约 + `cap.run` 命令 + 风险门禁编排 + 进化沉淀 | 命令注册表、`RiskGate`、`ErrorCollector`/`EvolutionStore` 都在 kernel；桌面/无 UI 宿主也要复用同一套阶梯语义。kernel 目前零 Android import（`grep '^import android\.'` 无命中），SPI 化可保持这条红线 |
| **core** `com.mengpaw.core.capability` | `AndroidCapabilityLadder` 实现（Intent/Accessibility/root） | `Intent`/`AccessibilityService`/`RootShell` 全是平台类型，只能留在 core |

**命令面**：新增 `cap.run <op> [args...] [--max-level L2] [--no-verify]`，输出 = `LadderResult` 的可读渲染（成功级/耗时/是否验证/尝试轨迹）。不改造 `sys.app.launch`（保持 L0 语义原子性），避免"低危命令偷偷执行高危动作"。新增命令必须四源同步（[memory-git-sync-design.md:438-449](memory-git-sync-design.md#L438)）。

#### 3.1.6 风险门禁（硬约束，先于任何实现）

- **每一级执行前都必须用自己的真实命令名过一次门禁**：`HighRiskCommandGate.evaluate(ToolCall(cmd, params))` → `RiskGate.evaluate(gate, agent, allowUserConfirm)`（[RiskGate.kt:29-70](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/RiskGate.kt#L29)）。worker/后台 `allowUserConfirm=false`，HIGH 一律拒绝。
- **用户拒绝 = 终止降级**（`LadderReason.USER_DENIED`），不得换一条更隐蔽的级再试。
- `cap.run` 自身注册为 **LOW**，但内部逐级求值；必须补一条守护测试 `LadderRiskCoverageTest`：断言每个 `LadderLevel` 映射到的命令名都在 `CommandRiskLevels`，且 HIGH 命令都在 `HighRiskCommandGate.HIGH_RISK` 模板内。
- **已知缺口（施工前必修）**：`sys.accessibility.click/swipe/input/back/home/recents` 是 HIGH（[CommandRiskLevels.kt:119-124](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L119)），但**不在** `HighRiskCommandGate.HIGH_RISK` 模板里（[HighRiskCommandGate.kt:34-76](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/HighRiskCommandGate.kt#L34)）。后果：以 JSON + `reason` 形态调用时会被 `paramFormatError` 直接拒（[ReActTypes.kt:30-40](../harness/src/main/kotlin/com/mengpaw/kernel/llm/ReActTypes.kt#L30)）。**E1 落地第一步：把这 6 条补进模板**，否则阶梯在"JSON 意图声明"路径下不可达。

#### 3.1.7 失败文案表（可直接落成常量）

| 场景 | 原因码（常量名） | 给 Agent 看的文案（必须可执行） |
|---|---|---|
| 包名错误/未安装 | `NOT_INSTALLED` | 未安装或不认识包名 `<pkg>`。用 `sys.apps <关键词>` 查正确包名；确认已安装后再试。不要连续猜第二个包名重试。 |
| 无启动入口 | `NO_LAUNCH_INTENT` | `<pkg>` 没有可启动入口（系统组件/被禁用/仅服务）。改用 `sys.intent.open <pkg>` 或请用户手动打开；若是设置项，走无障碍路径。 |
| ROM 拦截 Intent | `ROM_BLOCKED_INTENT` | 此 ROM 拦截了 `<pkg>` 的启动 Intent（后台启动限制/SecurityException）。可选：① 请用户手动打开；② 已开无障碍则重试 `cap.run launch_app <pkg> --fallback`。不要第三次重复原命令。 |
| 无障碍未授权 | `ACCESSIBILITY_DISABLED` | 无障碍未授权（系统设置类权限，Agent 无法代开）。请引导用户：设置 → 无障碍 → MengPaw → 打开；完成后 `sys.accessibility.status` 确认。 |
| 无障碍未连接 | `ACCESSIBILITY_DISCONNECTED` | 无障碍已授权但服务未连接。请用户把 MengPaw 切到前台或重启应用，然后 `sys.accessibility.status`；`连接=true` 再重试。 |
| 控件找不到 | `TARGET_NOT_FOUND` | 当前界面没有文本/id 匹配 `<target>` 的控件。先 `sys.accessibility.dump --max 200` 看实际文本；目标可能在列表下方，先 `sys.accessibility.swipe` 滚一屏再找。 |
| 输入框无焦点 | `NO_FOCUSED_INPUT` | 没有聚焦的输入框。先 dump 找 `class=EditText` 的控件并 `click --id`，确认光标出现后再 `input`。 |
| 手势被取消 | `GESTURE_CANCELLED` | 手势被系统取消（锁屏/系统弹窗/服务断开）。确认未锁屏、`sys.accessibility.status` 连接正常；改用 `--text/--id` 定位，别重复坐标点击。 |
| 无视觉级 | `VISION_UNAVAILABLE` | 本机没有视觉插件（16 个内置插件中无 vision），无法视觉兜底。请用户描述屏幕或手动点击；不要反复截图。 |
| 风险门禁拒绝 | `RISK_DENIED` | 该级操作被风险门禁拒绝（`<cmd>` 属 `<level>`）。把 Agent 权限提升到「信任」后可读屏；或请用户手动完成。 |
| 用户拒绝确认 | `USER_DENIED` | 用户拒绝了 `<cmd>` 的高危确认。停止降级并如实告知用户；不要换一条更隐蔽的路径重试。 |
| 阶梯耗尽 | `LADDER_EXHAUSTED` | 这台设备上 `<op>` 已试完 `<levels>` 全不通。请用户手动完成；失败已记为经验（`command=<cmd>`, `code=LADDER_EXHAUSTED_<OP>`），下次先走可用级。 |

#### 3.1.8 与 evolution 的接口（只用现有挂点，不新造机制）

**失败沉淀链路（已存在，直接复用）**：

```kotlin
// 阶梯耗尽 / 某级异常时（kernel 内，无需新机制）
ErrorCollector.report(
    type = ErrorType.TOOL_CALL_FAILED,
    source = "CapabilityLadder.${op.name}",
    message = "[device:${deviceId.take(8)}] ${op.command} 全级失败: " +
        result.steps.joinToString("/") { "${it.level}:${it.reason}" },
    agentName = ctx.agentName,
    sessionId = ctx.sessionId,
    metadata = mapOf(
        "command" to result.op.command,                       // EvolutionStore 按命令名去重
        "errorCode" to "LADDER_EXHAUSTED_${op.name}"
    )
)
```

链路：`ErrorCollector.report` 末尾触发 `onReport`（[ErrorCollector.kt:151-184](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/error/ErrorCollector.kt#L151)）→ `EvolutionHook.install()` 把 `onReport` 挂到 provider（[EvolutionHook.kt:27-33](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/evolution/EvolutionHook.kt#L27)）→ `EvolutionStore.recordFailure` 按 `(agent, 命令名, errorCode)` 去重并累计 `repeatCount`（[EvolutionStore.kt:186-273](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/evolution/EvolutionStore.kt#L186)，去重键在 [:205](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/evolution/EvolutionStore.kt#L205)）。

**阶梯整体耗尽的第二种写法**（语义更贴"任务被截断"）：`EvolutionStore.recordTermination(agentName, reason="ladder_exhausted", command=op.command, errorCode="LADDER_EXHAUSTED_${op.name}", contextSnippet=...)`（[EvolutionStore.kt:283-303](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/evolution/EvolutionStore.kt#L283)）。二选一，不要两处都记（会各算一次 repeatCount）。

**同级重试干预**：同一级同一原因连续失败 ≥3 次时，直接复用 `EvolutionStore.retryLoopDirective(...)`（[EvolutionStore.kt:338-354](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/evolution/EvolutionStore.kt#L338)）——主循环与 worker 都已消费该指令（[AgentReActStepProcessor.kt:300-307](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/AgentReActStepProcessor.kt#L300)），阶梯只负责生成。

**成功怎么沉淀**：
- **不写失败模式库**（`EvolutionStore` 只收失败，写成功会污染统计）。
- 进程内 warm-start：`LadderHintStore`（内存 Map<op, lastOkLevel + expiry 24h>），下次同 op 从上次成功级起步，失败仍可回退。
- 跨进程的"能力证据"留给定稿中的 P4 字段 `services[].evidence: measured`（§3.3.2），**本阶段不新增 schema**。

#### 3.1.9 最小验收

| # | 断言 |
|---|---|
| 1 | `cap.run launch_app <已安装包>` 在无 root/无无障碍时返回 `L0_COMMAND ok=true`，且 `verified` 取值与 dump 权限一致 |
| 2 | 模拟 L0 抛 `SecurityException` → 结果含 `ROM_BLOCKED_INTENT` 且 `steps.size>=2`（真的继续爬了） |
| 3 | 无障碍未授权时 L2 步骤 `reason=ACCESSIBILITY_DISABLED`，且文案含"设置 → 无障碍" |
| 4 | 用户拒绝 HIGH 确认 → `USER_DENIED` 且**不再**尝试更高层 |
| 5 | `LadderRiskCoverageTest`：5 个 op × 全部可用级映射的命令名都在风险表；HIGH 都在 JSON 模板表 |

---

### 3.2 E2 不可信内容隔离分析（Taint Quarantine）

**一句话**：把提示词注入的"爆炸半径"从主上下文移到一个**一次性、无工具、无记忆**的隔离器；主上下文只收结构化事实 + 原文哈希。

#### 3.2.1 现状与缺口（三层里缺第三层）

- **硬层已存在**：`stripInjection`（剥离指令形态）+ `wrap`（`<untrusted_data>` 标记），组合入口 `forModel` —— [UntrustedContent.kt:35-45](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/UntrustedContent.kt#L35)。
- **内容仍进主上下文**：工具结果只做 strip + wrap 后拼进 Observation —— [AgentReActStepProcessor.kt:319-321](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/AgentReActStepProcessor.kt#L319)；命中攻击时多一条提醒 + banner，但正文仍在 —— [:330-348](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/AgentReActStepProcessor.kt#L330)。
- **软层已存在**：系统提示词「信任边界」小节 —— [PromptEngine.kt:205](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/llm/PromptEngine.kt#L205)。
- **来源黑名单已存在**：命中即整段阻断，连上下文都不进 —— [SourceBlocklist.kt:40](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/SourceBlocklist.kt#L40)、来源解析 [:125](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/SourceBlocklist.kt#L125)。
- **信任域已存在**：`PromptFirewall.isTrusted`（[PromptFirewall.kt:98](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/PromptFirewall.kt#L98)），GUEST 逐命令过滤（[:75](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/PromptFirewall.kt#L75)），ACP 入口已用它判定（[AcpServer.kt:264-270](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/acp/AcpServer.kt#L264)、[DelegateHandler.kt:43-44](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/acp/DelegateHandler.kt#L43)）。
- **缺的正是第三层**：没有任何"先把原文交给一次性分析器、只把结论回注"的代码。

#### 3.2.2 触发判定表（什么内容走隔离器）

判定顺序固定：**黑名单 → 信任域 → 形态阈值**（先便宜后昂贵，拉黑内容不浪费一次 LLM 调用）。

| 来源 | 判定依据 | 形态阈值 | 动作 |
|---|---|---|---|
| 网页/搜索/HTTP 工具结果（`net.curl`、`net.post`、`tavily.search`、`tavily.extract`、`page.*`） | `UntrustedContent` 已声明为外部数据 | 长度 > 2048 字符 **或** 命中 `InjectionPatterns` | **走隔离器**；否则只 strip+wrap |
| 本地文件/Office 读取（`cat`、`office.read`） | 用户所有，但内容可能被外部污染 | 长度 > 8192 字符 **或** 命中注入模式 | 走隔离器；否则 strip+wrap |
| ACP GUEST 消息（`isTrusted=false`） | `PromptFirewall.isTrusted` 为假 | 一律 | **走隔离器**（整段任务/消息） |
| ACP TRUSTED 消息（自己设备） | `isTrusted=true` | — | 不隔离，只 strip+wrap |
| 用户本地输入 | 设备主人 | — | 不处理 |
| 系统提示词/框架提醒/审计文本 | 框架控制 | — | 不处理 |
| 已在黑名单的来源 | `SourceBlocklist.isBlocked=true` | — | **直接阻断**，不调隔离器（现有分支保留） |
| 隔离器自身输出 | 防递归 | — | 不再隔离；自由文本字段再过一次 `stripInjection` |

**形态风险特征（三个具体判据，避免"拍脑袋"）**：
1. 命中 `InjectionPatterns.INJECTION_PATTERNS` 任一正则；
2. 含角色标记冒充（行首 `system:` / `assistant:` / `<|im_start|>`）；
3. 单段 > 8KB 且含 ≥3 个指令动词（"执行/运行/忽略/删除/上传"等）。

#### 3.2.3 隔离器接口 + 固定输出 schema

```kotlin
package com.mengpaw.kernel.security   // 与 UntrustedContent / PromptFirewall 同包

enum class QuarantineSource { WEB, SEARCH, HTTP_TOOL, FILE, ACP_GUEST, OTHER_UNTRUSTED }
enum class QuarantineFailure { DISABLED, TIMEOUT, PROVIDER_ERROR, SCHEMA_INVALID, TOO_LARGE }
enum class Confidence { HIGH, MEDIUM, LOW }

data class QuarantineRequest(
    val text: String,
    val sourceKey: String?,          // SourceBlocklist.extractSource 的返回值（可空）
    val kind: QuarantineSource,
    val maxChars: Int = 32_000,      // 超出截断并置 truncated
    val timeoutMs: Long = 8_000
)

@kotlinx.serialization.Serializable
data class QuarantineFact(val claim: String, val quote: String, val confidence: Confidence)

@kotlinx.serialization.Serializable
data class QuarantineFacts(
    val schemaVersion: Int = 1,
    val sourceHash: String,          // "sha256:<64hex>"，原文哈希（去重/审计键）
    val sourceKind: QuarantineSource,
    val summary: String,             // ≤400 字，只写事实
    val facts: List<QuarantineFact>, // ≤20 条
    val instructionsSeen: List<String>, // 命中的"指令形态"摘录（≤120 字/条），一律 blocked
    val risks: List<String> = emptyList(),
    val truncated: Boolean = false,
    val analyzerModel: String = "",
    val elapsedMs: Long = 0
)

sealed interface QuarantineResult {
    data class Ok(val facts: QuarantineFacts) : QuarantineResult
    data class Degraded(val failure: QuarantineFailure, val fallbackText: String) : QuarantineResult
}

interface UntrustedAnalyzer {
    suspend fun analyze(req: QuarantineRequest): QuarantineResult
}

object UntrustedAnalyzerHolder {
    @Volatile var impl: UntrustedAnalyzer? = null   // shell 启动时注入（照 FleetPlatform 的 SPI 写法）
}
```

**schema 字段逐个说明（JSON 形态）**：

| 字段 | 类型 | 必填 | 含义 / 约束 |
|---|---|---|---|
| `schemaVersion` | int | ✅ | 固定 `1`；不兼容变更才加 |
| `sourceHash` | string | ✅ | `sha256:` + 64 hex；原文不落盘，只留哈希做去重/审计关联 |
| `sourceKind` | enum | ✅ | `WEB/SEARCH/HTTP_TOOL/FILE/ACP_GUEST/OTHER_UNTRUSTED` |
| `summary` | string | ✅ | ≤400 字；只允许事实陈述，不含第二人称指令 |
| `facts` | array | ✅ | ≤20 条；每条形如 `{claim, quote, confidence}`；`quote` ≤200 字且已 `stripInjection` |
| `instructionsSeen` | array<string> | ✅（可空数组） | 命中的指令形态摘录，≤120 字/条，≤20 条；**永不作为可执行建议**进主上下文 |
| `risks` | array<string> | ⬜ | 风险标签（`prompt_injection`/`data_exfil`/`credential_phishing`…），≤10 |
| `truncated` | bool | ✅ | 原文超 `maxChars` 时为 true |
| `analyzerModel` | string | ⬜ | 分析模型名（审计用） |
| `elapsedMs` | long | ⬜ | 分析耗时（观测用） |

**为什么必须结构化（四条）**：
1. **注入的本质是"自由文本里混指令"**。只要主上下文收到的是自由文本，strip 只能挡已知形态；固定 schema 让未识别的新变体也**没有位置**放（字段是 claim/quote/risk，不是"待执行文本"）。
2. **可校验**：`Json.decodeFromString<QuarantineFacts>` 失败即降级，不存在"半结构化"漏网。
3. **可审计/可回查**：`sourceHash` 把结论与原始工具结果绑定；重复同源内容直接命中缓存，不再烧 token。
4. **下游可用**：`facts`/`risks` 可被记忆、路由、审计消费；自由文本做不到稳定消费。

**输出进主上下文时的二次处理**：所有自由文本字段（`summary`/`claim`/`quote`/`instructionsSeen`）在拼接前**再跑一次 `stripInjection` 并按字段长度截断**；整体仍用 `<untrusted_data>` 包裹。schema 不能替代 strip，两者是叠加关系。

#### 3.2.4 失败降级路径（隔离调用失败 → 退回 strip+wrap）

```text
analyze(req)
 ├ impl == null            → Degraded(DISABLED)        → UntrustedContent.forModel(text)
 ├ 超时 timeoutMs=8000     → Degraded(TIMEOUT)         → 同上
 ├ provider 抛异常          → Degraded(PROVIDER_ERROR)  → 同上
 ├ JSON 解析/schema 校验失败 → Degraded(SCHEMA_INVALID)  → 同上
 └ text > maxChars         → 截断后分析；仍失败 → Degraded(TOO_LARGE) → 同上
```

- 降级**不阻塞主流程**，不弹 banner（与现有"命中静默"原则一致，[UntrustedContent.kt:24](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/UntrustedContent.kt#L24)）。
- **不要**把隔离器失败丢进 `ErrorCollector`：它会经 `EvolutionHook` 流进失败模式库，把"模型/网络抖动"写成"命令用法错误"的教训。用 `KernelLog.w("Quarantine", ...)` + 进程内降级率计数（>50%/1h 才在 `self.stats` 里告警）。
- 降级时**仍然调用** `stripInjection` + `wrap`，即"最差也等于今天"。

#### 3.2.5 与 Swarm worker 会话机制的复用点（已核实）

| 需要的能力 | 复用什么 | 不要复制什么 |
|---|---|---|
| 独立会话、不抢主会话 | `SessionManager.createSession(scope="swarm", activate=false)`（[History.kt:74-81](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/session/History.kt#L74)） | 不写 `conversationSessionId`/`_state`/`_output`（Swarm 的并发纪律，[SwarmWorkerRunner.kt:33-37](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/SwarmWorkerRunner.kt#L33)） |
| 单轮 LLM 调用 | `provider.completeWithMessages(messages)`（[LlmProvider.kt:26](../harness/src/main/kotlin/com/mengpaw/kernel/llm/LlmProvider.kt#L26)，用法见 [SwarmWorkerRunner.kt:88-93](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/SwarmWorkerRunner.kt#L88)） | 不要复制其 ReAct 多轮循环（[:77-195](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/SwarmWorkerRunner.kt#L77)） |
| 用完即销毁 | `SessionManager.deleteSession(id)` 放 `finally`（[History.kt:336](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/session/History.kt#L336)；[SwarmWorkerRunner.kt:198-201](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/SwarmWorkerRunner.kt#L198)） | 不要复制其 `recordWorkerTermination` 进化沉淀（[:209-226](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/SwarmWorkerRunner.kt#L209)） |
| 无工具执行 | 隔离器**不**接 `PipelineManager`；请求里只放文本 + schema 说明 | 不复制 Swarm 的工具执行段（[:108-193](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/SwarmWorkerRunner.kt#L108)） |

> 与 `EvolutionBranchRunner` 的区别：分支进化会话会**保留**（供用户复盘，[EvolutionBranchRunner.kt:36-42](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/evolution/EvolutionBranchRunner.kt#L36)）；隔离器必须**销毁**，否则注入文本会留在历史里。

#### 3.2.6 单测清单（每条一句断言）

| # | 断言 |
|---|---|
| 1 | `WEB` 来源 + 3000 字干净文本 → 走隔离器（analyzer 被调用 1 次） |
| 2 | `isTrusted=true` 的 ACP 消息 → 不走隔离器，只 strip+wrap |
| 3 | 黑名单来源 → 隔离器调用 0 次，Observation 里只有阻断提示、无原文 |
| 4 | analyzer 超时（mock 睡 9s）→ 返回 `Degraded(TIMEOUT)` 且主流程继续 |
| 5 | 输出缺 `sourceHash`/`facts` 类型错误 → `SCHEMA_INVALID` 降级，不抛异常 |
| 6 | `facts[].quote` 含 "ignore previous instructions" → 进上下文前被剥离为空 |
| 7 | 同 `sourceHash` 第二次请求 → analyzer 调用 0 次（命中缓存） |
| 8 | 40KB 输入 → `truncated=true`，实际送分析器的字符数 ≤ `maxChars` |

---

### 3.3 E3 服务级注册（Capability as Service）

**一句话**：让 Agent 问"谁能干这件事"，而不是"哪台机器有什么"；服务登记在**同一份** `devices/<deviceId>.md` 里，不另造库存。

#### 3.3.1 与 P4 库存的关系（不造第二套 schema）

- 文件、路径、单写者、`observedAt`、`schemaVersion`、`agents[]` 全部沿用 [memory-git-sync-design.md §7.2](memory-git-sync-design.md#L313)。
- 本文件只新增一个**可选顶层键** `services[]`；不改任何既有字段语义。
- **`online` 绝不进库存**：它属运行时（[memory-git-sync-design.md §7.3](memory-git-sync-design.md#L349)），由心跳/TwinPeerInfo 在查询时 join。骨架里"services[] 含 online"的写法作废（见 §8）。

#### 3.3.2 `services[]` 字段定义

```yaml
---
deviceId: 7f3a-…                 # 既有，不变
# …既有硬件/agents 字段全部不变…
services:                        # 新增，可选；无服务可省略
  - name: browser.autopilot      # 必填，命名规范见 §3.3.4
    cost: metered                # 必填: free | metered | paid
    requires: [plugin:browser, net:lan]   # 可选，令牌词表见 §3.3.3
    evidence: declared           # 可选: declared | measured（默认 declared）
    observedAt: 2026-10-22T22:41+08:00   # 可选；缺省继承文件级 observedAt
---
```

| 字段 | 类型 | 必填 | 含义 | 与既有字段的关系 |
|---|---|---|---|---|
| `name` | string | ✅ | 服务名（`<namespace>.<verb>`） | 与 `agents[].plugins` 的命名空间同源（`pluginNamespaceFor`） |
| `cost` | enum | ✅ | `free` / `metered` / `paid`（流量/额度成本，非金钱报价） | 不覆盖既有字段；供 `device.find` 排序与 `fleet.delegate --service` 选端 |
| `requires` | string[] | ⬜ | 前置条件令牌（§3.3.3） | 与 `agents[].vision/tools/plugins` 是"总-分"关系：后者是库存原始项，本字段是可路由的门槛 |
| `evidence` | enum | ⬜ | `declared`（自报）/ `measured`（实测过） | 与 `agents[].sources`（`measured/declared`）同一套词汇，不新造 |
| `observedAt` | ISO 8601 | ⬜ | 该行服务声明的时间 | 缺省用文件级 `observedAt`；索引显示"X 天前" |

**约束**：
- `schemaVersion` **不 bump**（新增可选键，向后兼容）；解析器必须"未知键忽略"，否则旧端读到新库存会整份拒收。
- `services[]` 不得与 `agents[].plugins` 矛盾：`device.refresh` 以插件/权限实测派生为权威，手写只允许 `evidence: declared`；矛盾项在 `device.refresh` 输出里告警，不静默覆盖。
- 单写者铁律不变：工具层拒绝写 `devices/<非本机id>.md`（[memory-git-sync-design.md §7.2](memory-git-sync-design.md#L347)）。

#### 3.3.3 `requires` 令牌词表（与 TwinRouter 共用词根）

`TwinRouter.inferRequirements` 已经在用 `model:* / software:* / hardware:*`（[TwinRouter.kt:84-125](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinRouter.kt#L84)），服务门槛直接复用同一词根，再加两个必要前缀：

| 令牌 | 含义 | 判定来源 |
|---|---|---|
| `plugin:<ns>` | 该命名空间的插件已启用 | `pluginNamespaceFor(id)`（[PluginManager.kt:22-29](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/plugin/PluginManager.kt#L22)） |
| `perm:<android.permission.X>` | 已授予该权限 | 运行时 `checkSelfPermission` |
| `service:accessibility` | 无障碍服务已授权且已连接 | `MengPawAccessibilityService.isAuthorized/isConnected`（[AccessibilityExecutor.kt:27-28](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/AccessibilityExecutor.kt#L27)） |
| `root` | 已获 root | root 插件探测 |
| `model:vision` | 当前模型支持视觉 | `ModelProfile.vision`（模型属性，不是插件能力） |
| `software:browser` | 浏览器能力可用 | 与 TwinRouter 同义 |
| `hardware:<camera / audio / gps / telephony / bigscreen>` | 硬件存在 | 库存既有字段 |
| `net:internet` / `net:lan` | 外网 / 同局域网可达 | 运行时网络判定 |

未知前缀 = **解析告警并忽略该条**（fail-closed：不会被错误路由到不满足条件的端）。

#### 3.3.4 服务命名规范与取舍

**定案：复用插件命令命名空间 + 一个动作段**，形如 `^[a-z][a-z0-9]*\.[a-z][a-z0-9-]*$`。

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 复用命令前缀（`browser.autopilot` / `office.docx` / `sys.accessibility`） | Agent 已有词汇表；`device.find browser` 与 `self.tools browser` 同形；可由注册表自动校验拼写 | 服务 = 能力簇，不等于单个命令；需 `requires` 补权限差异 | ✅ 采用 |
| 另立业务词表（`文档处理` / `视觉识别`） | 更贴用户语言 | 双套词汇 + 映射表必然漂移；无法自动校验 | ❌ 不取 |
| 直接用命令全名（`office.write`） | 精确 | 同一能力多命令会碎片化路由；服务名会随命令增删抖动 | ❌ 不取 |

补充规则：
- 内置系统能力用 `sys.<area>`（`sys.accessibility`、`sys.camera`、`sys.screenshot`）；
- **`vision` 不作为服务名**：视觉是模型属性（`model:vision`），把模型能力登记成设备服务会让"换模型"后库存失真；
- 服务名大小写敏感、禁止中文、禁止第三段（`a.b.c` 不接受）。

#### 3.3.5 `device.find <service>` 的行为

> 注意：该命令在 P4 已规划为 `device.find <capability>`（[memory-git-sync-design.md:433](memory-git-sync-design.md#L433)）。E3 是**扩展其语义到服务**，不是新增第二条命令 —— 避免 `device.find`/`device.capability` 双入口。

**输出（有结果）**：

```markdown
## 能提供 browser.autopilot 的设备 (2)

| 设备 | 形态 | 成本 | 状态 | 库存更新 | 前置 |
|---|---|---|---|---|---|
| 台式机 | DESKTOP | free | 在线 | 3 天前 | plugin:browser, net:lan |
| 荣耀平板 | TABLET | metered | 离线（12 分钟前） | 12 小时前 | plugin:browser |

排序: 在线 > 成本(free<metered<paid) > 库存更新(新→旧) > deviceId 字典序
下一步: fleet.delegate --service browser.autopilot --task "…"  或  twin.delegate <deviceId> "…"
```

**排序键（全序，保证可复现）**：
1. `online`（运行时心跳，90s 判离线）降序；
2. `cost`：`free` < `metered` < `paid`；
3. `observedAt` 新→旧；
4. `deviceId` 字典序升序（最终 tie-break）。

**时效标注**：每行 `observedAt` 渲染为"X 天前"；>30 天追加"可能过时"（[memory-git-sync-design.md §7.6](memory-git-sync-design.md#L375)）。>30 天不排除，只降权。

**无结果时怎么答**（必须给下一步，不许只说"没有"）：

```text
没有设备登记服务 `foo.bar`。
- 相近服务: browser.autopilot / office.docx（同前缀或编辑距离 ≤2）
- 看全部: device.list；看单端: device.info <名称>
- 本机刚装插件？device.refresh 后重试（只重测本机，不写别人的行）
- 该服务可能来自外置插件：在目标端 plugin.list 确认命名空间后重试
```

#### 3.3.6 `fleet.delegate --service <name>` 的寻址规则

现状：`fleet.delegate <peer-name> <task>` 只按通讯录名字选端，且先过信任门（[FleetExecutor.kt:70-92](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/agent/FleetExecutor.kt#L70)）。新增**同命令的 flag 形态**，位置参数形态保持不变（向后兼容）：

```text
fleet.delegate --service browser.autopilot --task "打开 X 并抓取标题"
```

寻址顺序（硬规则）：

1. **信任先于库存**：候选 = `members().filter { trusted }`（复用 `trustGate`，[FleetExecutor.kt:41-49](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/agent/FleetExecutor.kt#L41)）。库存里有但未配对 → **不选**，提示先配对。
2. **门槛过滤**：`requires` 必须全部满足（静态项查库存；`online`/网络查运行时心跳）。不满足的端不进候选。
3. **排序**：与 `device.find` 同一全序（在线 > 成本 > 时效 > deviceId）。
4. **失败回退**：发送失败（`sendDirect=false`）→ 换下一候选，**最多回退 2 次**；每次在输出里写明"首选 X 失败（不可达），改投 Y"。
5. **无候选**：返回 `device.find` 的完整指引；**绝不**退化成"随便找一台在线设备"。
6. **过期库存**：`observedAt>30d` 的端排在最后；`--allow-stale` 未给时不排除但输出标"库存过时，先 device.refresh"。
7. **委派报文**带上 `service` 与 `requires` 快照，接收方不满足时可直接 `fleet.reply --fail` 并说明原因。

#### 3.3.7 与现有三套能力卡/路由的收敛

| 现有 | E3 后 |
|---|---|
| `twin.route <任务>` 按自然语言推需求并打分（[TwinCapabilityCommands.kt:108-127](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinCapabilityCommands.kt#L108)） | **保留**：它是"任务→需求→打分"的模糊推荐；`device.find` 是"服务→精确候选"的确定性查询，两者互补 |
| `fleet.scan` 广播上报写 Notes（[FleetExecutor.kt:181-212](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/agent/FleetExecutor.kt#L181)） | 改为**从库存渲染**，不再 scan 覆盖写（P4 §7.5 已定） |
| `CapabilityCard` 内存卡 | 降级为库存传输信封（P4 §7.5 已定） |

---

### 3.4 E4 跨 Agent 增量交接（Delta Context）——只定启动条件

- **启动条件**：P3 记忆账本落地后，且出现"同一份长文档被 ≥2 端/≥2 个 Agent 连续编辑"或单次委派正文 >8KB 的真实案例（现在 `fleet.delegate` 全文传、`fleet.reply` 全文回：[FleetExecutor.kt:70-92](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/agent/FleetExecutor.kt#L70)、[:114-128](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/agent/FleetExecutor.kt#L114)）。
- **前置依赖**：P1 的 `TextDiff` 可用（设计与行数见 [memory-git-sync-design.md:486](memory-git-sync-design.md#L486)）+ 账本条目能给出"基版本"。
- **不做原因**：没有基版本就没有可信 diff；先做只会把"全文传输"换成"错基 diff"。

### 3.5 E5 记忆实验分支（Branch & Rollback）——只定启动条件

- **启动条件**：P3 账本落地、且梦境归纳出现"需要试错重跑"的真实需求（现在 `DreamEngine.refine` 直接写当日产物：[DreamEngine.kt:52](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/agent/DreamEngine.kt#L52)、`writeDreamMd` [:100](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/agent/DreamEngine.kt#L100)）。
- **前置依赖**：P1/P2 的多 head ref（`refs/ws-<agent>/heads` 已是集合形态）+ 账本 `supersedes`。
- **不做原因**：分支命令面没有多 head 支撑就是空壳；而进化侧已有分支能力（[EvolutionBranchRunner.kt:36-42](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/evolution/EvolutionBranchRunner.kt#L36)），先把记忆侧的地基铺平。

### 3.6 E6 同步熔断器（Circuit Breaker）

**一句话**：连续失败时退避 + 降级手动，避免每 60s 无意义重试；冲突率只观测、不触发熔断；与 QoS 的"主动暂停"在状态与文案上必须能一眼区分。

#### 3.6.1 现状证据

- 自动同步循环：`try { syncWithAllPeers() } catch { ErrorCollector.report }` → 无条件 `delay(syncIntervalMs())`，**没有任何退避**（[TwinSyncEngine.kt:77-91](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncEngine.kt#L77)）。
- 间隔由 QoS 决定：WIFI 60s / MOBILE 300s / METERED `Long.MAX_VALUE`（[TwinSyncEngine.kt:105-109](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncEngine.kt#L105)）。
- 只有**配对**冷却，没有同步熔断：3 次/10 分钟 → 锁 30 分钟（[TwinPairingCooldown.kt:12-19](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinPairingCooldown.kt#L12)、[:43-47](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinPairingCooldown.kt#L43)）。
- 同步状态只有 7 个字段（[TwinSyncTypes.kt:26-34](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncTypes.kt#L26)）；`SyncPhase` 定义了 `ERROR/MERGING/DISCOVERING`（[:23](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncTypes.kt#L23)），但**全仓库从未写入这三个值**（只写 SYNCING/IDLE）。→ 熔断不能只靠 `phase` 表达。

#### 3.6.2 计数口径（先定义"什么算一次失败"）

| 一轮结果 | 算失败？ | 依据 |
|---|---|---|
| `syncWithAllPeers()` 返回空列表（无在线节点） | ❌ 中性（不计数、不复位） | 无对端不是故障（[TwinSyncProtocol.kt:146-165](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncProtocol.kt#L146)） |
| 有在线节点，全部 `error != null` | ✅ 失败 +1 | `TwinSyncResult.error`（[TwinSyncTypes.kt:37-43](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncTypes.kt#L37)） |
| 至少一个节点成功 | ✅ 成功（失败计数归零） | 同上 |
| `error` 文本为"按流量计费模式下已暂停自动同步" | ❌ 中性（这是主动暂停） | [TwinSyncProtocol.kt:143-145](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncProtocol.kt#L143) |
| 有冲突但 `error == null` | ❌ 不算失败（冲突是正常业务） | `conflicts > 0` 与 `error` 分离 |
| 某节点写盘失败（`error="有 N 个文件写入失败"`） | ✅ 算失败（IO 类） | [TwinSyncProtocol.kt:132](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncProtocol.kt#L132) |

失败类型（用于文案与区分"网络问题 vs 协议问题"）：`UNREACHABLE` / `TIMEOUT` / `PROTOCOL` / `IO` / `UNKNOWN`，由 `error` 文本前缀归类，归不了类落 `UNKNOWN`。

#### 3.6.3 阈值与退避序列（具体数字）

| 参数 | 值 | 说明 |
|---|---|---|
| `FAILURE_THRESHOLD` | **3** | 连续 3 轮失败才 OPEN（单轮抖动不熔断） |
| `OPEN_BASE_MS` | **5 分钟** | 首次熔断冷却 |
| `OPEN_MAX_MS` | **30 分钟** | 冷却上限（不无限翻倍，避免"网好了还要等半天"） |
| `OPEN_TIERS` | **5m → 30m → 30m …** | 第 1 次 OPEN=5m；探测再失败=30m；之后恒 30m |
| `PROBE_COUNT` | **1** | 冷却到期后只放 1 轮探测 |
| `RESET_SUCCESSES` | **1** | 探测成功即 CLOSED（不强求连续 2 次，避免故障恢复后长时间空转） |
| 正常轮询 | 60s(WIFI)/300s(MOBILE) | 不变；熔断只接管"失败后的等待" |

状态机：

```text
CLOSED --(连续失败=3)--> OPEN(5m) --(到期)--> HALF_OPEN
HALF_OPEN --(探测成功)--> CLOSED（失败计数=0）
HALF_OPEN --(探测失败)--> OPEN(30m) --(到期)--> HALF_OPEN …（恒 30m）
METERED（主动暂停）与状态机正交，不参与计数
```

#### 3.6.4 状态字段：加在 `TwinSyncState`，逻辑放独立纯类

**定案：字段进 `TwinSyncState`，判定逻辑进新类 `SyncCircuitBreaker`。**

```kotlin
// TwinSyncTypes.kt —— 只加字段，不改既有字段
enum class BreakerState { CLOSED, OPEN, HALF_OPEN }
enum class SyncErrorKind { UNREACHABLE, TIMEOUT, PROTOCOL, IO, UNKNOWN }
enum class PauseSource { NONE, QOS_METERED, BREAKER }

data class TwinSyncState(
    val phase: SyncPhase = SyncPhase.IDLE,          // 既有
    // …既有其它 6 个字段不动…
    val consecutiveFailures: Int = 0,
    val successStreak: Int = 0,
    val breaker: BreakerState = BreakerState.CLOSED,
    val breakerOpenedAt: Long = 0L,
    val nextRetryAt: Long = 0L,
    val lastErrorKind: SyncErrorKind? = null,
    val lastErrorText: String = "",
    val pausedBy: PauseSource = PauseSource.NONE
)
```

**为什么不是"全放独立状态"**：
- `twin.status` 只读 `syncState.value`（[TwinLifecycleCommands.kt:105-122](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinLifecycleCommands.kt#L105)），字段放进同一个 StateFlow 才能**零额外管道**展示；
- **判定逻辑**必须独立成 `SyncCircuitBreaker`（纯 JVM、无 Android、可单测），因为它是"计数器 + 时钟 + 状态机"，塞进 data class 会变成不可测的隐式状态；
- 熔断状态**不落盘**：进程重启即复位（持久化会让"上次失败"跨天粘住，且与 `SyncPhase.ERROR` 死值一样不可维护）。

#### 3.6.5 与 QoS `METERED` 的区分（主动暂停 vs 被动熔断）

| 维度 | QoS `METERED` | 熔断 `OPEN` |
|---|---|---|
| 触发 | 计费网络/用户选择 | 连续 3 轮失败 |
| 谁决定 | 用户或网络类型（`autoDetectQos`） | 系统自动 |
| 自动同步 | 不自动同步（`Long.MAX_VALUE`；`twin.sync.qos metered` 还会直接 `stopAutoSync`） | 到 `nextRetryAt` 自动单探测 |
| 手动 `twin.sync` | 可用（正常全量） | 可用；成功即 CLOSED |
| 状态字段 | `qosLevel=METERED`，`pausedBy=QOS_METERED` | `breaker=OPEN`，`pausedBy=BREAKER`，`nextRetryAt` 有值 |
| 恢复 | 换回 WiFi 或 `twin.sync.qos wifi` | 探测成功 / 手动成功 / 进程重启 |

**`twin.status` 文案（必须能一眼区分）**：

```text
# 主动暂停
- 自动同步: 暂停（主动）— QoS=METERED（按流量计费网络）
- 恢复: 接入 WiFi 自动恢复，或 twin.sync.qos wifi

# 被动熔断
- 自动同步: 熔断（被动）— 连续 3 轮失败（原因: TIMEOUT）
- 下次自动重试: 21:35（5 分钟后）；twin.sync 可立即手动重试
- 上次错误: peer 7f3a… 同步超时（20s）
```

实现落点：`TwinLifecycleCommands.cmdStatus` 增两行；`phase` 保持现状不动（`ERROR` 死值不要复活，改用 `breaker` 表达）。

#### 3.6.6 复位条件

| 条件 | 结果 |
|---|---|
| 任意一轮全成功（≥1 个在线 peer 且全部 `error == null`） | `consecutiveFailures=0`、`breaker=CLOSED`、`nextRetryAt=0` |
| HALF_OPEN 探测成功 | 同上 |
| `twin.sync` 手动成功 | 同上（管理员显式操作可越过冷却） |
| `twin.stop` → `twin.start`（进程内重建引擎） | 状态复位（内存态） |
| 用户切到 `twin.sync.qos metered` | 不改变 breaker，但 `pausedBy=QOS_METERED` 优先展示 |
| 失败累计未达 3 | 保持 CLOSED，仅记录 `lastErrorKind/Text` |

#### 3.6.7 单测清单

| # | 断言 |
|---|---|
| 1 | 空 peer 轮次 ×10 → `consecutiveFailures=0` 且永不 OPEN |
| 2 | 连续 3 轮失败 → `breaker=OPEN` 且 `nextRetryAt = openedAt+5min` |
| 3 | OPEN 冷却内 `allowNow=false`；到期后 `HALF_OPEN` 且只放 1 轮 |
| 4 | HALF_OPEN 再失败 → 下次冷却 30min；第 3 次仍 30min（封顶） |
| 5 | 一轮成功 → 计数归零、CLOSED；METERED 轮次不计失败也不复位 |

### 3.7 E7 能效可观测——只定启动条件

- **启动条件**：需要对外给"低功耗"数字（发布说明/对比稿），或用户报"耗电/发热"且现有指标无法定位。
- **前置依赖**：shell 侧按子系统采 CPU 时间/唤醒次数（`BatteryStats`/`/proc`），经 SPI 注入；kernel 侧只需在 `self.stats` 增一节。
- **现状边界**：`Telemetry`/`self.stats` 已有 LLM token/耗时 + 命令执行事件（[Telemetry.kt:38-59](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/Telemetry.kt#L38)、[SelfExecutor.kt:108-140](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/namespace/SelfExecutor.kt#L108)），**缺的是 CPU/唤醒维度**，不是"只有命令"。
- **不做原因**：能效数字一旦对外就是承诺，没有按子系统归因前不发布。

### 3.8 E8 静默优先的跨端 UX——只定启动条件

- **启动条件**：E6 熔断与 E1 失败文案先落地（否则"静默"只是把错误藏起来）；出现"用户被非决策类提示打断"的具体投诉。
- **前置依赖**：`NotifyBus` 已分 INFO/SUCCESS/WARN/ERROR 四级（[NotifyBus.kt:32](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/namespace/NotifyBus.kt#L32)）；配对/同步已有独立对话框（[TwinPairingDialogs.kt:44](../mengpaw-shell/src/main/kotlin/com/mengpaw/shell/ui/screens/sidebar-dialogs/TwinPairingDialogs.kt#L44)）。
- **不做原因**：静默的前提是"有事可报且报得准"；现在同步失败连原因都没区分（见 E6）。

---

## §4 不做清单（三条，已论证）

| 概念 | 理由 |
|---|---|
| 虚拟管道 / 虚拟 FD / 自造背压 | 常驻 `sh` 池已给真管道，自造只会在沙箱里引入 OOM 与语义偏差 |
| packfile / delta 链存储 | 工作区 <1MB，收益约 400KB，代价是 delta 链与损坏恢复（详见 [memory-git-sync-design.md §2.4](memory-git-sync-design.md#L79)） |
| 用 diff 替代系统提示词整段注入 | 击穿 prefix cache，省下的 token 不如缓存失效赔得多 |

---

## §5 沟通层定案：三分表述法

外部 Agent 把 `Kotlin + Compose` 读成 `Tauri + Rust`、把设计稿读成已实现 —— 根因是描述里没有边界词。定案：

**任何对外技术描述必须三分标注**：`已实现` / `设计中` / `明确不支持`。

- 反面例子：「创新性地引入 GitDiff 作为内部通信方案」（把设计写成已实现）
- 正确写法：「已实现 X；演进中 Y（设计见 …）；不支持 Z（原因：…）」

> 本文件即示范：§3 每一小节开头先写"现状证据"，再写"设计"。`docs/lessons.md` 的追加暂缓（该文件没有文末"最后更新"行且章节编号与顶部 §0.x 混排，强行插入会破坏编号），本段可直接搬过去。

---

## §6 与 P0-P4 的合并

```
P0 止血 ✓ 已完成（哈希统一 + 内容相同不落盘）
 ├── P1 对象库 + 快照（+ E6 熔断器）
 │     └── P2 协议 v0.3 + 合并
 │           └── P3 记忆账本
 │                 ├── E4 增量交接（只定启动条件）
 │                 └── E5 记忆分支（只定启动条件）
 ├── P4 设备库存（+ E3 服务注册）
 │     └── P5 能力阶梯（E1）
 └── P6 隔离分析（E2，独立安全线）
E7 能效可观测 / E8 静默 UX —— 随时可插
```

**P0 已完成的三条证据**（本文件所有与库存/同步相关的前提都建立在此之上）：
- 收发同一哈希形态：发送端 `TwinWorkspace.manifestHash`（[TwinSyncProtocol.kt:209](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncProtocol.kt#L209)），接收端同函数比对（[TwinAcpHandler.kt:97](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinAcpHandler.kt#L97)）。
- 内容相同不落盘：`if (localContent == content) return "same"`（[TwinWorkspace.kt:124](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinWorkspace.kt#L124)）。

---

## §7 交付清单（§7 原待办，全部完成）

| # | 产出 | 落点 | 验收 |
|---|---|---|---|
| 1 | E1 接口签名 + 判定矩阵 + 失败文案表 | §3.1.4 / §3.1.5 / §3.1.7 | 每类操作有阶梯表；文案可直接落常量；风险门禁有硬约束 |
| 2 | E2 隔离器接口 + 输出 schema + 与 PromptFirewall 判定关系 | §3.2.2 / §3.2.3 | 能回答"什么内容走隔离器"；schema 字段逐一定义；降级与复用点已核实 |
| 3 | E3 服务字段 + 命名规范 + `device.find` 行为 | §3.3.2 / §3.3.4 / §3.3.5 | 与 P4 库存字段不冲突（`online` 归运行时） |
| 4 | E6 阈值/退避/状态字段 | §3.6.3 / §3.6.4 / §3.6.5 | 与 METERED 暂停语义可区分；文案可直接进 `twin.status` |
| 5 | E4/E5/E7/E8 启动条件 | §3.4-§3.5 / §3.7-§3.8 | 只有触发点与依赖，无方案 |
| 6 | 全文 file:line 核对 | §3 全文 / §8 | 每条现状证据可点开；纠错单列 |
| 7 | `docs/INDEX.md` 增加本文件条目 | [INDEX.md](INDEX.md) | 索引一致 |

---

## §8 证据核对与骨架纠错

核对方式：逐条打开源码/文档核对路径、行号、断言；链接统一改成从 `docs/` 出发可点开的 `../` 相对路径。**纠错 7 条、措辞收紧 1 条**。

> 行号漂移说明：核对期间有并行开发在改 `CommandRiskLevels.kt`、`TwinSyncProtocol.kt`、`TwinAcpHandler.kt`、`TwinWorkspace.kt`，本文行号已在写稿时重算过一轮。若日后再有 ±10 行漂移，**以引用的函数名/命令名/字符串为准**，不要以行号为准。

| # | 骨架原文 | 实际 | 证据 |
|---|---|---|---|
| 1 | 无障碍命令「`dump/click/swipe/input/back/home/recents`，均为 HIGH 级」 | `status`=LOW、`dump`=MID，只有 `click/swipe/input/back/home/recents` 是 HIGH | [CommandRiskLevels.kt:61](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L61)、[:104](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L104)、[:119-124](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L119) |
| 2 | 证据链接写 `mengpaw-core/...` | 文档在 `docs/` 下，缺 `../` 时链接指向 `docs/mengpaw-core/`（打不开） | 本文件所有链接已改为 `../` 前缀 |
| 3 | 阶梯「注册表命令 → shell 等价命令 → 无障碍…」 | Android 侧没有通用 shell 桥：`termux` 仅 3 条命令；`root.shell` 需 root；`sys.intent.open` 不吃 settings action。第二级只对 root 设备存在 | [TermuxPlugin.kt:37](../plugins/plugin-termux/src/main/kotlin/com/mengpaw/plugin/termux/TermuxPlugin.kt#L37)、[CommandRiskLevels.kt:130](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/CommandRiskLevels.kt#L130)、[ClipboardIntentExecutor.kt:40-72](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/ClipboardIntentExecutor.kt#L40) |
| 4 | `Telemetry`「只统计命令执行」 | 还统计 LLM 请求（prompt/completion token + 耗时）；缺的是 CPU 时间/唤醒次数等能效维度 | [Telemetry.kt:38-59](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/Telemetry.kt#L38)、[SelfExecutor.kt:137-138](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/namespace/SelfExecutor.kt#L137) |
| 5 | E3「`services[]`（name / cost / online / requires）」 | `online` 属运行时，不能进库存（与 P4 §7.3 冲突）；改由心跳在查询时 join | [memory-git-sync-design.md §7.3](memory-git-sync-design.md#L349) |
| 6 | E3「命令面加 `device.find <service>`」 | P4 已规划 `device.find <capability>`（同一条命令）；E3 是扩展语义，不是新增第二条 | [memory-git-sync-design.md:433](memory-git-sync-design.md#L433) |
| 7 | E6「指数退避（60s→5m→30m）」 | 60s 是正常轮询间隔（QoS WIFI），不是退避档位；退避档位是 5m、30m（封顶） | [TwinSyncEngine.kt:105-109](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncEngine.kt#L105) |
| 8 | 「无障碍…只用作用户显式命令」 | 措辞收紧为：**全部是原子命令，全仓库无任何编排调用点**（"只用于"不可证，调用点可证） | [SysExecutor.kt:287-294](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/SysExecutor.kt#L287)（全仓库仅此注册表 + 执行器自身引用） |

**核对通过、未改动的断言**：
- `sys.app.launch` 单路 Intent（[AppExecutor.kt:42-53](../mengpaw-core/src/main/kotlin/com/mengpaw/core/namespace/sys/AppExecutor.kt#L42)）。
- `UntrustedContent` 只做 strip+wrap、内容仍进主上下文（[UntrustedContent.kt:35-45](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/security/UntrustedContent.kt#L35)、[AgentReActStepProcessor.kt:319-333](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/AgentReActStepProcessor.kt#L319)）。
- 16 个内置插件没有 vision（[settings.gradle.kts:43-58](../settings.gradle.kts#L43)）。
- 同步循环无退避、只有配对冷却（[TwinSyncEngine.kt:77-91](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncEngine.kt#L77)、[TwinPairingCooldown.kt:12-19](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinPairingCooldown.kt#L12)）。
- P0 已完成（§6 末尾两条链接）。

---

## §9 待拍板（3 项，影响施工顺序）

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| 1 | `sys.accessibility.dump` 是 MID，STANDARD Agent 直接拒 → 阶梯 L2 只对 TRUSTED 可用 | A. 维持 MID（安全优先，要求设备控制类 Agent 升 TRUSTED）；B. 降为 LOW 或单开"读屏"权限 scope | **A**（读屏是隐私读取，降级等于绕过权限模型；在文案里明确"需 TRUSTED"） |
| 2 | `cap.run` 的风险归属：它可执行 HIGH 子动作，但自身注册 LOW | A. LOW + 每级内部 `RiskGate`（+守护测试）；B. 注册 HIGH（每次 `cap.run` 都弹窗，连启动应用也要确认） | **A**（B 会让最常用的 L0 也背确认成本）；前提是 `LadderRiskCoverageTest` 必须存在 |
| 3 | E3 `services[]` 是否允许 Agent 手写 | A. 只允许 `evidence: declared`，实测项由 `device.refresh` 派生；B. 完全禁止手写 | **A**（库存要能表达"人知道但工具测不到"的服务，但路由优先信 `measured`） |
