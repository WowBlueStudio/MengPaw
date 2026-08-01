# 参与 MengPaw（反馈与贡献指南）

> **主仓库当前只接受 Bug 报告与功能请求，暂不接受代码贡献（PR 通道关闭）。**
> 代码贡献未来可能开放；开放前请勿提交 PR，以免被直接关闭。

## 反馈渠道

| 场景 | 渠道 |
|------|------|
| **Bug 报告** | GitHub [Issues](https://github.com/WowBlueStudio/MengPaw/issues) — 选择 Bug 模板，附设备/版本/复现步骤/日志 |
| **功能请求** | GitHub [Issues](https://github.com/WowBlueStudio/MengPaw/issues) — 选择功能请求模板 |
| **商用授权咨询** | 1138018324@qq.com |
| **连接器插件（MIT 社区仓库）** | [mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors) — 该仓库开放 PR |

> 提交 Issue 前请先搜索是否已有同类问题；Bug 报告请提供可复现的最简步骤。

## 为什么暂不接受代码贡献

MengPaw 采用双许可（社区版 AGPL-3.0 + 商业授权，见 [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)）。保持版权单一归属（全部代码归著作权人所有）是双许可合法性的基础，因此主仓库暂不接受外部代码合并。你仍然可以按 AGPL-3.0 自由 fork、修改与分发（遵守 AGPL 义务即可）。

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

## 代码规范（供未来开放贡献时参考）

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
