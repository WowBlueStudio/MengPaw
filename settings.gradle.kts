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

include(":mengpaw-kernel")
include(":mengpaw-core")
include(":mengpaw-design-system")
include(":mengpaw-shell")
include(":mengpaw-browser")

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

// plugin-agent-loop and plugin-agent-mission removed — modes now built into AgentEngine
// Remote plugins (update/translate/error-report/render/comfy/browser-push/browser-search/browser-mcp)
// and connectors moved to standalone repo mengpaw-connectors (MIT) — see COMMERCIAL-LICENSE.md §11.4

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
// plugin-agent-loop / plugin-agent-mission dir mappings removed — modes built into AgentEngine
// plugin-connector-* and remote plugin dir mappings removed — moved to standalone repo mengpaw-connectors
