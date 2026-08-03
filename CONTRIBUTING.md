# 参与 MengPaw（反馈与贡献指南）

> **主仓库开放 Pull Request**（插件 / 文档类优先；内核严格评审）。提交 PR 即代表同意版权让渡（见下文 [版权与许可](#版权与许可)）。

## 反馈渠道

| 场景 | 渠道 |
|------|------|
| **Bug 报告** | GitHub [Issues](https://github.com/WowBlueStudio/MengPaw/issues) — 选择 Bug 模板，附设备/版本/复现步骤/日志 |
| **功能请求** | GitHub [Issues](https://github.com/WowBlueStudio/MengPaw/issues) — 选择功能请求模板 |
| **代码贡献** | GitHub [Pull Requests](https://github.com/WowBlueStudio/MengPaw/pulls) — 插件/文档优先；内核建议先在 issue 讨论 |
| **商用授权咨询** | 1138018324@qq.com |
| **连接器插件（MIT 社区仓库）** | [mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors) — 该仓库开放 PR |

> 提交 Issue 前请先搜索是否已有同类问题；Bug 报告请提供可复现的最简步骤。

## 版权与许可

MengPaw 采用双许可（社区版 AGPL-3.0 + 商业授权，见 [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)）。**提交 PR 即表示你同意将贡献代码的版权让渡给深圳哇蓝文化科技有限公司**（你是代码原创作者，无第三方权利冲突），贡献按双许可分发。你仍然可以按 AGPL-3.0 自由 fork、修改与分发（遵守 AGPL 义务即可）。

## 贡献范围与评审流程

| 范围 | 建议 | 评审严格度 |
|------|------|-----------|
| **插件类**（`plugins/` 新插件 / 改进） | 推荐首选 — 独立模块、只依赖 kernel、不改核心 | 常规 |
| **文档 / 翻译 / 脚本** | 随时欢迎 | 常规 |
| **内核 / 核心**（kernel / core） | 先开 issue 讨论方案再动手 | 严格（双许可合规 + 安全 + 兼容性） |

流程：**Fork → 提交 PR（CI 自动跑编译与 JVM 测试）→ 维护者按九维清单评审 → 通过后 squash 合并**。PR 模板含检查清单，提交前对照一遍能显著加快合并。

## 构建（从源码自行构建）

## 构建（从源码自行构建）

### 环境搭建

- JDK 17 (Amazon Corretto 17 推荐)
- `JAVA_HOME` + `ANDROID_HOME` 环境变量
- Android SDK 35 (platforms, build-tools, platform-tools, emulator)
- 克隆 → `./gradlew :mengpaw-kernel:test` → `./gradlew :mengpaw-shell:assembleDebug`

### 构建命令

```bash
# 微内核测试 (JVM, 秒级)
./gradlew :mengpaw-kernel:test

# 插件开发工具链测试 (JVM)
./gradlew :plugin-dev:testDebugUnitTest

# 全部编译
./gradlew :mengpaw-shell:assembleDebug     # Shell APK
./gradlew :mengpaw-browser:assembleDebug   # Browser APK
./gradlew :mengpaw-shell:assembleRelease   # Shell Release (R8)

# 插件批量构建 (AAR + plugins.json 回写)
powershell -File scripts/build-plugins.ps1

# plugins.json 校验 (只读)
powershell -File scripts/validate-plugins.ps1
```

### 模块结构

| 模块 | 职责 |
|------|------|
| mengpaw-kernel | 纯 Kotlin/JVM 微内核，零 Android 依赖，JVM 可测试 |
| mengpaw-core | Android 适配层：Vault 加密存储 / IntegrityGuard / SysExecutor |
| mengpaw-shell | Compose UI + 前台服务 |
| mengpaw-design-system | Arco 设计令牌 + 基础组件 |
| plugins/ | 21 个功能插件（均只依赖 kernel） |

## 代码规范（提交 PR 前必读）

- 包命名：`com.mengpaw.{模块}.{功能}`
- 类大驼峰，函数小驼峰
- UI 文字全部中文（Strings.kt 本地化）
- 注释中文
- **禁止 `!!` 强制解包** — 用 `?.let {}` 或 `?: return` 替代
- **所有文件 IO 必须 try/catch**
- SPDX 版权头（双许可）：所有 `.kt` / `.kts` 文件
  ```
  // SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
  // SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
  ```
- 每个文件不超过 400 行
- 单元测试覆盖核心逻辑
