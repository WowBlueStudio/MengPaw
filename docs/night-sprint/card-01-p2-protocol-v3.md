<!-- night-sprint
timeout: 240
rules: SPRINT-RULES.md
-->

# card-01 P2：协议 v0.3 + 三方合并

## 前置条件（先过这一关，不过就停）

P1（对象库）必须**已提交且工作区干净**。执行：

```powershell
cd D:\MengPaw
Test-Path 'mengpaw-kernel\src\main\kotlin\com\mengpaw\kernel\memory\SnapshotStore.kt'
git status --porcelain -- mengpaw-kernel mengpaw-core mengpaw-shell plugins
git log --oneline -3
```

- 文件存在 **且** 代码目录无未提交改动 → 通过，继续。
- 不满足 → **每 60 秒重试一次，最多等 30 分钟**（P1 可能还在跑）。仍不满足 → **立即停止**，报告 `PRECONDITION_FAILED`，把 `git status` 与 `git log` 原样贴进报告。
- **绝不接管未提交的半成品**：不要 `git add -A`、不要继续写 P1 留下的文件。

## 目标

把孪生同步从「全量清单 + 全文传输」换成「根 tree 比对 + 只拉缺失对象 + 三方合并」，同时保持与旧版对端的兼容。

## 必读章节

- `docs/memory-git-sync-design.md` **§2（四类数据）/ §4（协议 v0.3）/ §5（合并）/ §10.4（P2 任务表）** —— 这是权威规格，逐条对齐。
- P1 已落地的对象库 API：`mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/memory/SnapshotStore.kt`（及同目录 `SnapshotTypes.kt`、`WorkspaceSnapshotSource.kt`）。
- 现有实现：`plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/` 下 `TwinSyncProtocol.kt`、`TwinAcpHandler.kt`、`TwinWorkspace.kt`、`TwinSyncEngine.kt`；内核 `mengpaw-kernel/.../acp/AcpProtocol.kt`、`AcpServer.kt`、`AcpTransport.kt`。

> 只读上面这些，不要全量加载 648 行设计文档以外的资料。

## 交付

1. **消息类型**（`AcpProtocol.kt`）：新增 `SYNC_HELLO / SYNC_ACK / TREE_WALK / OBJ_GET` 四个枚举值与工厂方法。载荷字段严格按 §4.1。
2. **鉴权**：`AcpServer.processMessage` 把四个新类型并入"要求 `PromptFirewall.isTrusted`"的分支；`AcpTransport.SENSITIVE_ACP_TYPES` 加入四个类型（要求 IP 绑定）。
3. **合并器**（新文件 `mengpaw-kernel/.../memory/TreeMerge.kt`，≤400 行）：纯函数，输入 `(base, ours, theirs)` 文本，输出合并结果 + 冲突块列表。实现 §5.2 的策略表：
   - L 类：diff3（行级三方合并），冲突生成 `<<<<<<< ours` / `=======` / `>>>>>>> theirs`（用设备名）；
   - frontmatter（首尾 `---` 块）：键级合并，同键按 (lamport, deviceId) 取一方；
   - M 类：追加并集（两侧新块都保留，按 (lamport, deviceId) 排序）；
   - `twin-model-rules.json`：按 `rule.id` 并集；
   - 删除 vs 修改：保留修改方为 `{name}.kept-<device>.md`，返回"需 inbox 提示"标记；
   - 兜底：非 UTF-8 或 >256KB → 取较新一方 + `.conflict` 备份。
4. **v0.3 同步驱动**（插件新文件 `plugins/plugin-memory-twin/.../TwinSyncV3.kt`，≤400 行）：按 §4.2 时序实现**纯 pull**：`HELLO`（本机 ws/dev heads）→ 相同即结束；不同则 `TREE_WALK` + `OBJ_GET` 批量取缺失对象 → `TreeMerge` 落盘 → `SnapshotStore.commit` → `local` ref 前进。
5. **文件分类**（`TwinWorkspace.kt`）：把扫描结果按 §2.1 分成 L / M / D / S 四类；`S` 类保持排除；扫描逻辑改为**委托 P1 的 kernel 扫描器**，去掉插件内重复实现（保留 `sanitizeRelPath`）。
6. **删除传播**：对端树里没有、本机有的 L/M 文件 → 按 §5.2「删除 vs 修改」处理（修改方保留为 `.kept-`），并写 inbox 提示；不再保留"从不调用 `removeWorkspaceFile`"的状态。
7. **双栈**：`CapabilityCard.protocolVersion` ≥ `0.3` 走 v0.3，否则走现有 `WS_MANIFEST/WS_PULL`（该路径**冻结**，只修 P0 级缺陷）。
8. **删除死代码** `TwinDiscovery.kt`（启动后无人消费其 discoveredPeers；发现由 plugin-framework 通讯录 + 手动 IP 承担）。

## 不做（本卡明确排除）

- **不改设备身份**（不把 deviceId 换成 UUID、不动 `AcpCrypto.myFingerprint`、不动机器配对信任键）——身份变更会让既有配对失效，需用户在场单独做。
- 不动 P1 的对象格式与 refs 布局；不改 `memory.*` 命令语义。
- 不改 `README.md` / `docs/INDEX.md` / `docs/evolution-points.md` / `docs/lessons.md`。

## 验收命令（逐条粘贴执行，看 EXIT 码）

```powershell
cd D:\MengPaw
.\gradlew.bat :mengpaw-kernel:test --console=plain; echo "EXIT=$LASTEXITCODE"
.\gradlew.bat :plugin-memory-twin:testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
git diff --check
git status --short
```

必须补的测试（新增用例只增不减）：
- `TreeMergeTest`（kernel）：L 类无冲突/有冲突、frontmatter 键级、M 类并集顺序稳定、删除-修改、大文件兜底、CRLF 归一。
- `TwinSyncV3Test`（插件）：heads 相同 → 0 传输；heads 不同 → 只请求缺失对象；双栈判定（0.2 走旧路径）。
- 现有 `TwinWorkspaceTest`/`TwinAcpHandlerTest` 随分类/消息类型调整。

## 报告

追加到本卡末尾（≤40 行），并在最终回复里贴同样内容。
