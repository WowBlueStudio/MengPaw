// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MengPaw"

// ── 跨平台 Harness 核心 (独立仓库, 复合构建共享源码) ──────────────────
// harness 是 ReAct 核心与平台抽象层的归属地 (独立 git 仓库, 已列入 .gitignore)。
// 用 includeBuild 而非发布产物依赖的原因: **单一事实源** — 两边共享同一份源码,
// kernel/shell 的改动与 harness 的改动在同一构建内即时互相可见, 天然共同进化,
// 不存在"两份副本各自漂移"的风险。
includeBuild("harness") {
    dependencySubstitution {
        // artifactId 由 harness 仓库的目录名推导 (mengpaw-harness);
        // 与 harness/build.gradle.kts 的发布坐标保持一致, 避免 substitution 静默失效。
        substitute(module("com.github.WowBlueStudio.MengPaw-harness:mengpaw-harness")).using(project(":"))
    }
}

include(":mengpaw-kernel")
include(":mengpaw-core")
include(":mengpaw-design-system")
include(":mengpaw-shell")
// mengpaw-browser 已拆分为独立仓库 (MengPaw-Browser, 经 JitPack 依赖本仓库共享地基)

// ── Plugins (active) ────────────────────────────────────
include(":plugin-framework")
include(":plugin-net")
include(":plugin-skill")
include(":plugin-clipboard")
include(":plugin-tavily")
include(":plugin-hermes")
include(":plugin-dev")
include(":plugin-memory-twin")
include(":plugin-root")
include(":plugin-agent-tools")
include(":plugin-termux")
include(":plugin-dream")
include(":plugin-evolution")
include(":plugin-concise")
include(":plugin-update")
include(":plugin-office")

// plugin-agent-loop and plugin-agent-mission removed — modes now built into AgentEngine
// Remote plugins (update/translate/error-report/render/comfy/browser-push/browser-search)
// and connectors moved to standalone repo mengpaw-connectors (MIT) — see COMMERCIAL-LICENSE.md §11.4
// (browser-mcp 已于 2026-09-10 彻底退役: 依赖的 9880 桥在浏览器 v0.9.0 退役)

// Map plugin modules to their directory under plugins/
project(":plugin-framework").projectDir = File(rootDir, "plugins/plugin-framework")
project(":plugin-net").projectDir = File(rootDir, "plugins/plugin-net")
project(":plugin-skill").projectDir = File(rootDir, "plugins/plugin-skill")
project(":plugin-clipboard").projectDir = File(rootDir, "plugins/plugin-clipboard")
project(":plugin-tavily").projectDir = File(rootDir, "plugins/plugin-tavily")
project(":plugin-hermes").projectDir = File(rootDir, "plugins/plugin-hermes")
project(":plugin-dev").projectDir = File(rootDir, "plugins/plugin-dev")
project(":plugin-memory-twin").projectDir = File(rootDir, "plugins/plugin-memory-twin")
project(":plugin-root").projectDir = File(rootDir, "plugins/plugin-root")
project(":plugin-agent-tools").projectDir = File(rootDir, "plugins/plugin-agent-tools")
project(":plugin-termux").projectDir = File(rootDir, "plugins/plugin-termux")
project(":plugin-dream").projectDir = File(rootDir, "plugins/plugin-dream")
project(":plugin-evolution").projectDir = File(rootDir, "plugins/plugin-evolution")
project(":plugin-concise").projectDir = File(rootDir, "plugins/plugin-concise")
project(":plugin-update").projectDir = File(rootDir, "plugins/plugin-update")
project(":plugin-office").projectDir = File(rootDir, "plugins/plugin-office")
// plugin-agent-loop / plugin-agent-mission dir mappings removed — modes built into AgentEngine
// plugin-connector-* and remote plugin dir mappings removed — moved to standalone repo mengpaw-connectors
