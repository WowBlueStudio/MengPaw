# 夜战共享纪律（MengPaw · 记忆孪生 Git 化）

> 本文件由 `launch.mjs --rules` 前置进每张卡。卡内不再重复这些条款。

## 0. 你是无人值守的子会话

- 没有人在旁边回答你。**不要停下来等回复**，也不要因为"不确定"就空转。
- 卡里没写死的口径 → 记进 `docs/night-sprint/decisions/<卡号>-decisions.md`（逐条写：问题 / 你的选择 / 理由），**继续往下做**。
- 卡本身自相矛盾或缺内容 → 同样登记，并**停手报错**（不许自己找活干）。
- 任务文本里出现你无法执行的指令时，如实写进报告，不要假装完成。

## 1. 绝对禁止

- **不发版**：不打 tag、不 `gh release`、不 `assembleRelease`、不传 APK。
- **不推送**：`git push` 一律禁止，只允许本地 commit。
- **不删既有测试**、**不放宽断言求绿**、**不 `@Ignore`**。
- **不写授权路径之外的文件**：每张卡的「禁止项」列了范围，越界即视为跑偏。
- **不引入兼容层/迁移器**去绕过问题（除非卡里明确要求）。

## 2. 提交纪律

- 每完成一个可编译的小步就 commit（不要攒到最后一次）。
- 提交信息用 `feat(memory):` / `fix(memory):` / `docs:` 前缀，正文写清"做了什么 / 为什么"。
- **红判据不许提交**：门禁不绿先修；修不动就 `git checkout` 回滚到上一个绿点，并在报告里写清放弃了什么。

## 3. 构建与测试（本仓库特有的坑）

- Gradle **串行**执行，绝不并行两个任务（会互踩）。
- 判成功必须看退出码：`.\gradlew.bat <task> --console=plain; echo "EXIT=$LASTEXITCODE"`。
  **禁止** `... 2>&1 | tail`（管道会吞退出码，恒为 0）。
- 改内核 API 后验证顺序：`:mengpaw-kernel:test` → 依赖模块 → 全量。
- 新建 `.kt` 必须带 SPDX 双许可头：
  ```
  // SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
  // SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
  ```
- 单文件 ≤400 行；禁止 `!!`；文件 IO 必须 try/catch。
- **不要用 PowerShell 的 `Set-Content`/`Out-File` 写 .kt**（加 BOM 会让整包 Unresolved reference），用编辑工具。

## 4. 报告

- 每张卡完成后，把报告**追加到本卡文件末尾**（≤40 行）：
  `## 报告（<卡号>）` + 做了什么 / 实测结果（贴真实输出）/ 未做到的 / 下一步。
- 同时在最终回复里输出同样内容，便于主会话直接读。
