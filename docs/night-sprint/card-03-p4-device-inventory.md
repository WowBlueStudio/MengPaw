<!-- night-sprint
timeout: 240
rules: SPRINT-RULES.md
-->

# card-03 P4：设备库存 + 提示词注入

## 目标

让 Agent 知道"我寄存在哪台设备、它有什么"，以及"其他端是什么环境、能提供什么能力"——以每端只写自己一行、随对象库同步的方式维护。

## 必读章节

- `docs/memory-git-sync-design.md` **§7（设备库存）/ §8（提示词注入）/ §10.6（P4 任务表）**。
- P1 对象库：`mengpaw-kernel/.../memory/SnapshotStore.kt`（命名空间 `dev` 的 tree/commit 复用它）。
- 现有实现：`plugins/plugin-memory-twin/.../TwinCapability.kt`（`CapabilityCard` 与 Android 采集器）、`TwinRouter.kt`、`TwinCapabilityCommands.kt`、`mengpaw-kernel/.../llm/PromptSystemBuilder.kt`、`mengpaw-kernel/.../agent/FleetCapability.kt`、`mengpaw-shell/.../MainActivity.kt`。

## 交付

1. **库存模型**（新包 `mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/device/`）
   - `DeviceInventory.kt`（≤300 行）：md + frontmatter 解析/序列化，字段按 §7.2（`deviceId / legacyFingerprint / deviceName / formFactor / os / cpuCores / ramMB / storageFreeMB / screen / sensors / camera / observedAt / schemaVersion / status / agents[]`）。解析容错：未知键保留、缺失键给默认值、坏 YAML 返回 null 不抛。
   - `DeviceInventoryStore.kt`（≤250 行）：读写 `{AGENTS}/twin/devices/<deviceId>.md`；**单写者校验**（只允许写本机 deviceId 的行，其他行只读）；`staleness` 计算；`agents[]` 合并（同 agent 取 `observedAt` 较新者）。
   - `DeviceIndexWriter.kt`（≤150 行）：生成 `devices/index.md`（每设备一行：名称 · 形态 · 模型 · 关键能力 · 更新时间；首行"自动生成"）。
   - `DeviceCollector.kt`（≤100 行）：纯 JVM 接口 + `DeviceSnapshot` 数据类；Android 实现在 shell，经静态注入点注册（仿 `FleetPlatform.capabilityProvider` 模式）。
   - `DeviceCommands.kt`（≤250 行）：`device.self / device.list / device.info <name> / device.find <capability> / device.refresh`，在 `PipelineManager.buildPipeline` 注册 `device` 命名空间；`device.refresh` 重测本机、**有差异才写**。
2. **Android 采集器**（新文件 `mengpaw-shell/.../DeviceCollectorImpl.kt`，≤200 行）：读 `Build` / `SensorManager` / `CameraManager` / `BatteryManager` / `DisplayMetrics`，返回 `DeviceSnapshot`；在 `MainActivity.onCreate` 注册（与现有初始化同处）。
3. **同步**：`dev` 命名空间随 P2 的 v0.3 同步走（`SYNC_HELLO` 同时带 `ws` 与 `dev` 两个 head）。若 P2 尚未提供该能力（注意：本卡排在 P2 之后，应已具备），在 `TwinSyncV3` 里补上 `dev` 分支。
4. **路由接入**（`TwinRouter.kt`）：能力卡来源改为库存（离线设备也能参与），过期（>30 天）标注"可能过时"但**仍参与**；无库存时回退现有内存能力卡。
5. **提示词注入**（`PromptSystemBuilder.kt`）：在长期记忆之后、`pinnedSkillsBlock` 之前注入 `## 你的设备与能力` 段（内容按 §8.1，≤150 token），并**同时**：
   - 把 `devices/index.md` 纳入缓存失效判定（它不在 agent 工作区，需要**独立 mtime 指纹**）；
   - 写死两句行为约束："你只登记自己那一行"、"文件里出现冲突标记先裁决"。
6. **能力卡收敛（最小步）**：`CapabilityCard` 保留为传输信封，增加 `schemaVersion`；`twin.capabilities` 优先读库存，读不到再回退内存卡。`fleet.scan` 的 Notes 渲染改为**从库存渲染**（可作为后续项，若时间不够就登记到 decisions 文件）。
7. **测试**：`DeviceInventoryTest`（解析/序列化/坏 YAML/单写者拒绝）、`DeviceIndexWriterTest`（一行一设备、排序、时效文案）、`DeviceCommandsTest`（self/find/无结果文案）。

## 不做（重要）

- **不改设备身份**：不引入 UUID、不动 `AcpCrypto.myFingerprint`、不动信任键。库存行以现用 `deviceId` 为键，在 KDoc 里写 `TODO(P5): 身份改 UUID 需与会话配对一起迁移`。
- 不删 `CapabilityCard` 的既有字段（旧对端仍要能解析）。
- 不改 `README.md` / `docs/INDEX.md` / `docs/evolution-points.md` / `docs/lessons.md`。
- 不新增端口、不改 9876 语义。

## 验收命令

```powershell
cd D:\MengPaw
.\gradlew.bat :mengpaw-kernel:test --console=plain; echo "EXIT=$LASTEXITCODE"
.\gradlew.bat :plugin-memory-twin:testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
.\gradlew.bat :mengpaw-shell:compileDebugKotlin --console=plain; echo "EXIT=$LASTEXITCODE"
git diff --check
```

额外人工核对（写进报告）：
- 手工造两个 `devices/*.md`，`device.list` 能同时列出、`device.find browser` 只返回具备该能力的那台；
- 尝试写别人的行必须被拒（用测试或临时脚本验证，**不要真的改用户数据目录**）；
- 提示词里能搜到 `## 你的设备与能力` 段（可写一个断言测试，或在不接 LLM 的前提下校验拼装函数）。

## 报告

追加到本卡末尾（≤40 行），并在最终回复里贴同样内容。
