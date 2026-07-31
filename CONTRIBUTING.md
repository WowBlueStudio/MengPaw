# Contributing to MengPaw

## 开发流程

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feat/amazing-feature`)
3. 提交改动 (`git commit -m 'feat: add amazing feature'`)
4. 推送到分支 (`git push origin feat/amazing-feature`)
5. 创建 Pull Request

## 环境搭建

- JDK 17 (Amazon Corretto 17 推荐)
- `JAVA_HOME` + `ANDROID_HOME` 环境变量
- Android SDK 35 (platforms, build-tools, platform-tools, emulator)
- 克隆 → `./gradlew :mengpaw-kernel:test` → `./gradlew :mengpaw-shell:assembleDebug`

### 关键配置文件

| 文件 | 说明 |
|------|------|
| `build.gradle.kts` (根) | AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01 |
| `settings.gradle.kts` | 4 核心模块 + 26 插件模块 |
| `mengpaw-kernel/build.gradle.kts` | JVM 模块, kotlinx-serialization, ktor, coroutines-core |
| `mengpaw-core/build.gradle.kts` | Android Library, 依赖 kernel, security-crypto |
| `mengpaw-shell/build.gradle.kts` | Compose, material-icons-extended, work-runtime, 13 捆绑插件 (framework/memory/skill/dev/fs/net/self/clipboard/notification/memory-twin/root/hermes/agent-tools) |
| `mengpaw-browser/build.gradle.kts` | material-icons-core (轻量), version follows mengpaw.version |
| `mengpaw-shell/.../AndroidManifest.xml` | 6 权限, MainActivity, ShellService (foregroundServiceType=dataSync) |
| `mengpaw-browser/.../AndroidManifest.xml` | 2 权限, BrowserActivity (3 intent-filter) |

### 主要依赖

| 依赖 | 版本 | 位置 |
|------|------|------|
| Kotlin | 2.0.21 | kernel + core |
| kotlinx-serialization-json | 1.7.3 | kernel |
| kotlinx-coroutines | 1.9.0 | kernel / shell |
| ktor-client (core+okhttp) | 3.0.3 | kernel |
| security-crypto | 1.1.0-alpha06 | core |
| Compose BOM | 2024.12.01 | shell / browser / design-system |
| work-runtime-ktx | 2.10.0 | shell |

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

# 插件批量构建 (26 模块 AAR + plugins.json 回写)
powershell -File scripts/build-plugins.ps1

# plugins.json 校验 (只读)
powershell -File scripts/validate-plugins.ps1

# 清理
./gradlew clean
```

## 代码规范

- 包命名：`com.mengpaw.{模块}.{功能}`
- 类大驼峰，函数小驼峰
- UI 文字全部中文（Strings.kt 本地化）
- 注释中文
- **禁止 `!!` 强制解包** — 用 `?.let {}` 或 `?: return` 替代
- **所有文件 IO 必须 try/catch**
- SPDX 版权头：所有 `.kt` / `.kts` 文件
- 每个文件不超过 400 行
- 单元测试覆盖核心逻辑

## AI 辅助开发

本项目全程由 AI 辅助编码。若你也使用 AI 工具贡献：

| 阶段 | 编排工具 | 模型 |
|------|---------|------|
| 早期 (US-001 ~ US-012) | Reasonix | DeepSeek Flash |
| 中期 (架构重构 ~ 至今) | Claude Code | DeepSeek Pro |

推荐配置见 `reasonix.toml`（Reasonix）或 `.claude/` 目录（Claude Code）。

## 模块职责

| 模块 | 职责 |
|------|------|
| mengpaw-kernel | 纯 Kotlin/JVM 微内核，零 Android 依赖，JVM 可测试 |
| mengpaw-core | Android 适配层：Vault 加密存储 / IntegrityGuard / SysExecutor |
| mengpaw-shell | Compose UI + 前台服务 |
| mengpaw-design-system | Arco 设计令牌 + 基础组件 |
