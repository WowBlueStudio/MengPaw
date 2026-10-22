<!-- night-sprint
timeout: 150
rules: SPRINT-RULES.md
-->

# card-04 终审：十二问闭环 + 九维代码审查

## 目标

P2/P3/P4 落地后做一次独立复核：机器门禁全绿的前提下，用两套方法论找缺口，产出可执行的审计报告；只修 P0（数据丢失 / 安全 / 崩溃），其余只报告。

## 必读

- `C:\Users\a1138\.agents\skills\closure-audit-12q\SKILL.md` —— 三层二十一问全文与输出格式。
- `C:\Users\a1138\.agents\skills\code-review-9d\SKILL.md` —— 九维清单与输出格式。
- `docs/memory-git-sync-design.md` §0/§5/§6/§7/§9/§13（验收标准）。
- 本次改动范围：`git log --oneline 965e5cfe..HEAD`（P1/P2/P3/P4 的提交）。逐条看 `git show`。

## 第一步：机器门禁（不过就停）

```powershell
cd D:\MengPaw
.\gradlew.bat :mengpaw-kernel:test --console=plain; echo "EXIT=$LASTEXITCODE"
.\gradlew.bat :plugin-memory-twin:testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
.\gradlew.bat :mengpaw-shell:compileDebugKotlin --console=plain; echo "EXIT=$LASTEXITCODE"
```

任一红 → 在报告里写清失败用例与原因，**不要为了变绿而删测试/放宽断言**；本卡到此为止（除 P0 级修复外）。

## 第二步：闭环审计（二十一问）

对「记忆孪生 Git 化」子系统逐条过 1.1–1.7 / 2.1–2.7 / 3.1–3.7，每条给 `file:line` 证据与 ✅/⚠️/❌/N/A。重点核对：

- **1.7 幽灵引用**：`README.md` 与提示词里出现的 `memory.*` / `device.*` 命令，是否真的在 `BuiltinCommandIndex` + `PipelineManager` 注册？技能手册 `plugin-skill/.../twin-guide.md` 是否需要更新？
- **2.7 双通道一致**：`memory.restore` 这类写操作在 Linux 通道与注册表通道下的路径保护是否一致？
- **3.5 数据完整性**：对象库/账本/库存的原子写、损坏恢复、指标持久化（重启后 `twin.status` 不丢）。
- **3.7 保护链命中**：新命令是否都进了 `CommandRiskLevels`；新 ACP 消息是否都进了 `SENSITIVE_ACP_TYPES` 与信任门。

## 第三步：九维审查

对 `965e5cfe..HEAD` 的代码变更逐维过（可维护性/可读性/可扩展性/灵活性/简洁性/可复用性/可测试性/健壮性/兼容性）。特别查：

- 单文件 >400 行；新 `.kt` 缺 SPDX；`!!`；空 catch 吞错。
- **假测试**：新用例是否能真的抓住它声称防住的回归（尤其合并冲突、账本去重、库存单写者）。
- 并发：对象库/账本是否有多协程同时写的风险；`ConcurrentHashMap` 用对没。
- 兼容：`protocolVersion` 双栈是否真的能回退；旧 `CapabilityCard` 字段是否仍可解析。

## 第四步：只修 P0

- 只允许修 **P0**（数据丢失、安全绕过、必崩），每修一处必须同时补回归测试。
- P1/P2 一律只写进报告（含建议改法），不要动。
- 修完重跑第一步三条命令，必须全绿后再提交。

## 第五步：产出

1. 新文件 `docs/audit/memory-git-2026-10-22.md`，结构：
   ```
   # 记忆孪生 Git 化 · 终审报告（2026-10-22）
   ## 门禁结果（三条命令 + EXIT 码 + 用例数）
   ## 十二问结论表（21 行）
   ## 九维结论（P0/P1/P2 + 亮点）
   ## 已修 P0（若无写"无"）
   ## 遗留缺口与建议顺序
   ```
2. **README 状态行翻绿**：仅当三条门禁全绿**且** P1–P4 的产物都存在（`kernel/memory/SnapshotStore.kt`、`TwinSyncV3.kt`、`kernel/memory/MemoryLedger.kt`、`kernel/device/DeviceInventory.kt`）时，把 `README.md` 里那句
   `> 实施状态：P0（…）已落地；P1–P4（…）按设计文档推进，本节即其终态。`
   改为 `> 实施状态：P0–P4 已全部落地（对象库 / 协议 v0.3 / 记忆账本 / 设备库存），本节即当前实现。`
   条件不满足则**保持原样**并在报告里说明。
3. 提交：`docs(audit): 记忆孪生 Git 化终审（十二问 + 九维）`（若修了 P0，另起一个 `fix(memory):` 提交）。

## 禁止

- 不 `git push`、不打 tag、不 `assembleRelease`。
- 不重构、不"顺手优化"、不改 P1/P2 的既定语义。
- 不动 `docs/INDEX.md`、`docs/evolution-points.md`、`docs/lessons.md`（README 仅按第五步第 2 条改那一行）。

## 报告

追加到本卡末尾（≤40 行），并在最终回复里贴：门禁用例数、P0 数量、是否翻绿 README、报告文件路径。
