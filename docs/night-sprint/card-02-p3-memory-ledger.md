<!-- night-sprint
timeout: 200
rules: SPRINT-RULES.md
-->

# card-02 P3：记忆账本 + 归纳降级为视图

## 目标

中期记忆从「可被就地改写/删除的文件」变成「只增账本 + 可重建的归纳视图」，从根上消除"归纳压缩坏记忆"与不可追溯。

## 必读章节

- `docs/memory-git-sync-design.md` **§6（记忆账本）/ §8（提示词注入）/ §10.5（P3 任务表）**。
- P1 对象库：`mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/memory/`（`SnapshotStore`、`WorkspaceSnapshotSource`）。
- 现有实现：`mengpaw-kernel/.../agent/AgentDocsMemory.kt`（中期/项目记忆读写）、`AgentMemoryMutateCommands.kt`（rm/edit）、`AgentMemoryReadCommands.kt`、`DreamEngine.kt`、`llm/PromptSystemBuilder.kt`、`DataPaths.kt`。

## 交付

1. **账本**（新文件 `mengpaw-kernel/.../memory/MemoryLedger.kt`，≤400 行）
   - 存储 `{AGENTS}/twin/ledger/<agent>/entries.jsonl`，**只追加**，一行一条。
   - 条目字段严格按 §6.1：`entryId / track / file / blockHash / project / agent / device / lamport / wall / session / supersedes / kind / sources`。
   - 能力：`append(entry)`、`query(byDate|byProject|undigested)`、`unionMerge(a, b)`（按 `entryId` 去重）、损坏行跳过并记 `ErrorCollector`。
   - 三重上限（条数 / 单行长度 / 文件大小），超限拒绝追加并报错，**不静默截断**。
2. **写入挂钩**（`AgentDocsMemory.kt`）：`agent.memory.record` 与 `agent.memory.project.save` 在追加文件后生成对应条目（`blockHash` = 新增块的 SHA-256；meta 从 `ExecutionContext` 取 session/agent，device 取当前孪生 deviceId）。
3. **编辑语义**（`AgentMemoryMutateCommands.kt`）：`agent.memory.mid.edit` / `project.edit` 改为**追加 `kind="revision"` 的新条目 + `supersedes` 指向被改块**，不再物理覆盖原块；`mid.rm` 同理改为追加 tombstone 条目（`kind="revision"`，正文标注已撤回），**不删文件里的历史块**。
4. **归纳改读路径**（`DreamEngine.kt`）
   - 输入从"读全部中期分片"改为"查账本（未归纳 / 按日期区间 / 按项目）"。
   - 产物 `{date}_dream.md` 必须含 `sources: [entryId…]`；无法追溯的要点标 `unsourced`。
   - **不再删除分片**；已在账本的条目由归档视图处理。
   - 重跑不覆盖旧产物：生成 `{date}_dream.v2.md`、`v3…`，索引指向最新版本。
5. **记忆索引**（新文件 `mengpaw-kernel/.../memory/MemoryIndexWriter.kt`，≤150 行）：生成 `{agent}/memory/index.md`，内容 = 项目分组 + 每项目最近 3 条标题 + 条目计数 + 未归纳数；首行写"自动生成，勿手改"。
6. **提示词注入**（`PromptSystemBuilder.kt`）：在 `## 你的长期记忆` 之后注入索引摘要（≤200 token）；把 `memory/index.md` 加入 `AGENT_DOC_FILES` 的 mtime 快照清单，否则缓存永不失效。
7. **测试**（新增，只增不减）：`MemoryLedgerTest`（追加/去重合并/损坏行/上限）、`DreamViewTest`（带 sources、重跑版本化、不删分片）、`MemoryIndexWriterTest`（分组与计数）。

## 不做

- 不改长期记忆 `agent.memory.keep` 的写入语义（L 类靠 P2 三方合并）。
- 不改 `agent.memory.keep/read/search/stats` 的命令名与返回格式。
- 不改 `README.md` / `docs/INDEX.md` / `docs/evolution-points.md` / `docs/lessons.md`。
- 不动设备库存（P4）与协议消息（P2）。

## 验收命令

```powershell
cd D:\MengPaw
.\gradlew.bat :mengpaw-kernel:test --console=plain; echo "EXIT=$LASTEXITCODE"
.\gradlew.bat :plugin-memory-twin:testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
git diff --check
```

额外人工核对（写进报告）：
- 构造"同一天两个设备各追加一段中期记忆"的用例，账本合并后两条都在、顺序稳定；
- 连续跑两次归纳，旧产物仍在且新产物为 `.v2`；
- `memory/index.md` 在第二次写入后内容变化（证明缓存指纹生效）。

## 报告

追加到本卡末尾（≤40 行），并在最终回复里贴同样内容。
