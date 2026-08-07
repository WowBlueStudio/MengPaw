---
name: plugin-dev
description: 插件开发与发布全流程 — 创建/审计/关键词/构建/校验/市场发布。用户说"插件开发/建插件/plugin.dev/发插件"时执行。管插件级发布（AAR+plugins.json）；版本级发布（APK）见 release skill。
---

> **2026-08-07 已迁移**: 本技能已迁移至 Codex skill `mengpaw-plugin-dev`（用户级 `~/.codex/skills/mengpaw-plugin-dev/`），下文为兼容保留，新开发以 Codex skill 为准。

# MengPaw 插件开发与发布流程

> 权威文档：`PLUGIN_DEV_GUIDE.md`（开发）| `.claude/skills/release.md`（版本级发布）| 本 skill 管插件级发布
> 插件数口径：22 模块（settings.gradle.kts）| 14 捆绑（mengpaw-shell build.gradle.kts）

## 0. 前置

- [ ] 读 `PLUGIN_DEV_GUIDE.md`（唯一权威，与代码 v0.20.0 对齐）
- [ ] 核对 `gradle.properties` 的 `mengpaw.version`（插件产物命名含此版本）
- [ ] 插件 AAR 发布 = 对外动作 → 需要用户明确指示

## 1. 创建插件（二选一）

**App 内（Agent 自助，零编译）**：
```
dev.plugin.create --type script|native --name <名称> [--author <作者>] [--desc <描述>]
# 生成位置: DataPaths.PLUGIN_CACHE/<id>/
```
- `--type script` → plugin.json（JSON+shell，无需编译）
- `--type native` → build.gradle.kts + Kotlin 源（需 Android Studio/Gradle 编译）

**仓库内（人类开发者）**：
1. `plugins/plugin-<name>/` 建模块（参照 `plugin-fs` 最小结构：build.gradle.kts + src/main/kotlin/...）
2. `settings.gradle.kts` 加 `include(":plugin-<name>")`（build-plugins.ps1 自动派生，无需改脚本）
3. 若捆绑进 APK：`mengpaw-shell/build.gradle.kts` dependencies 加 implementation；plugins.json 加 builtin 条目

## 2. 开发要点

- 命令键用**短名**；运行时注册为 `<命名空间>.<短名>`（命名空间由插件 id 经 namespaceFor 派生：`dev-plugin` → `dev.plugin.*`）
- metadata 必填：id/name/version/author(非空)/description/permissions/minCoreVersion(≥0.2.0)
- `ports` 声明占用端口（冲突检测在 PluginManager.install；9876 为内核保留）
- `commandKeywords` 声明 BM25 检索同义词（提升 self.search 可发现性）
- 参考模板：`dev.plugin.examples`（文件/网络插件）

## 3. 审计

```
dev.plugin.audit --target <插件ID>     # --target 必填
dev.plugin.keywords --target <插件ID>  # 查看检索关键词
```
- 🔴 阻断项 = 阻止 `dev.plugin.share`；🟡 建议项不阻断
- 检查项与实现对照：auditScript（SCRIPT JSON）/ auditKotlin（NATIVE 源码）7+ 类

## 4. 本地验证

```bash
# 纯 JVM（无需真机）: kernel 全量 + dev-plugin 链路（create→audit→keywords→端口冲突）
./gradlew :mengpaw-kernel:test :plugin-dev:testDebugUnitTest
# 出现任何失败 → 必须修复（AcpProtocolTest 已于 v0.22.1 修复，不再是"已知预存在失败"）
# 真机: plugin.install <本地 AAR> 后 self.tools <ns> 验证命令注册
```

## 5. 构建 + 回写（仓库工具链）

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-plugins.ps1
# 动态派生 22 模块 → releases/plugins/plugin-<name>-<version>-release.aar
# Python 回写 plugins.json: checksum/size/changelog/version/updated
powershell -ExecutionPolicy Bypass -File scripts/validate-plugins.ps1
# 全绿 = 字段/命名空间/URL/checksum 与实际 AAR 比对 + 与代码交叉校验（捆绑↔builtin）
```

构建前先 `git status` 确认 plugins.json 无未提交的意外改动；脚本幂等可重跑。

## 6. 发布（插件级）—— 需要用户确认

```bash
# 1. plugins.json 改动随版本 commit（含新插件条目/状态调整）
git add plugins.json && git commit -m "chore: 更新 plugins.json（插件 AAR 发布）"
# 2. 独立 tag + 双远端 push（引用 release.md §4 惯例）
git tag plugins-vX.Y.Z && git push origin plugins-vX.Y.Z && git push gitee plugins-vX.Y.Z
# 3. 上传 AAR 到插件 tag
gh release create plugins-vX.Y.Z releases/plugins/*.aar --title "Plugin AARs vX.Y.Z"
gh release view plugins-vX.Y.Z --json tagName,assets,isDraft   # 验证
# 4. 更新 plugins.json remote 条目的 downloadUrl/mirrorUrl（指向上述 tag assets）→ 重新 commit
```

**用户确认点**：任何 `git push` / `gh release` / 外部仓库操作前必须用户明确同意（记忆 no-release-without-ask）。

## 7. 状态裁决备忘

- `tribe-plugin` = plugin-hermes 模块（TribePlugin，注册 tribe.* + hermes.* 兼容）→ **builtin**（已随 APK 打包）
- `tools-plugin` = plugin-agent-tools 模块（id 与模块名不同，正常）
- `embedded`（agent-mission/agent-loop）= UI 绑定不可卸载；`deprecated` = 下架

## 验证清单

- [ ] `:mengpaw-kernel:test` + `:plugin-dev:testDebugUnitTest` 全绿（已知失败除外）
- [ ] build-plugins.ps1 输出全部 AAR（命名含版本号），releases/plugins/ 无旧残留
- [ ] validate-plugins.ps1 全绿（含 checksum 与 AAR 实际 SHA256 一致）
- [ ] plugins.json 命令/状态/端口/checksum 字段正确
- [ ] `gh release view plugins-vX.Y.Z` assets 含全部 AAR
- [ ] 双远端 ls-remote 与本地 HEAD 一致
