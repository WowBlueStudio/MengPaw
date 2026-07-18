// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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

include(":mengpaw-core")
include(":mengpaw-design-system")
include(":mengpaw-shell")
include(":mengpaw-browser")

// ── Plugins (active) ────────────────────────────────────
include(":plugin-fs")
include(":plugin-net")
include(":plugin-memory")
include(":plugin-skill")
include(":plugin-self")
include(":plugin-clipboard")
include(":plugin-notification")
include(":plugin-pad")
include(":plugin-tavily")
include(":plugin-hermes")
include(":plugin-workflow")
include(":plugin-incubator")
include(":plugin-render")
include(":plugin-comfy")
include(":plugin-translate")

// Map plugin modules to their directory under plugins/
project(":plugin-fs").projectDir = File(rootDir, "plugins/plugin-fs")
project(":plugin-net").projectDir = File(rootDir, "plugins/plugin-net")
project(":plugin-memory").projectDir = File(rootDir, "plugins/plugin-memory")
project(":plugin-skill").projectDir = File(rootDir, "plugins/plugin-skill")
project(":plugin-self").projectDir = File(rootDir, "plugins/plugin-self")
project(":plugin-clipboard").projectDir = File(rootDir, "plugins/plugin-clipboard")
project(":plugin-notification").projectDir = File(rootDir, "plugins/plugin-notification")
project(":plugin-pad").projectDir = File(rootDir, "plugins/plugin-pad")
project(":plugin-tavily").projectDir = File(rootDir, "plugins/plugin-tavily")
project(":plugin-hermes").projectDir = File(rootDir, "plugins/plugin-hermes")
project(":plugin-workflow").projectDir = File(rootDir, "plugins/plugin-workflow")
project(":plugin-incubator").projectDir = File(rootDir, "plugins/plugin-incubator")
project(":plugin-render").projectDir = File(rootDir, "plugins/plugin-render")
project(":plugin-comfy").projectDir = File(rootDir, "plugins/plugin-comfy")
project(":plugin-translate").projectDir = File(rootDir, "plugins/plugin-translate")
