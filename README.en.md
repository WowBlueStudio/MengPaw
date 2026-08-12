# MengPaw 🐾

A self-bootstrapping Android OS framework for Agents.

> **The Agent controls itself through built-in CLI commands; the API key is the only forbidden zone.**

## Why MengPaw

China's digital ecosystem is fragmented — WeChat can message but not manage files, Mi Home controls Xiaomi devices but ignores Huawei, and various AIs can chat but cannot truly "live". Users don't lack capabilities; they lack a **hub**.

MengPaw takes a microkernel orchestration route: instead of reinventing wheels, it builds the hub — bridging existing fragments into a whole with plugins.

```
App layer:  WeChat / DingTalk / Mi Home / Feishu / WPS / ...   ← fragments, not replaced
Agent layer: MengPaw microkernel + plugin mesh + Memory Twin   ← the hub, and only this
Device layer: phone / PC / tablet / car / ...                  ← nodes, more is stronger
```

> **Beat fragmentation with fragments.** — Not a better app, but the thing *between* apps.
> **Users are developers.** — Anyone facing a fragmentation problem can build and share a plugin with `plugin.create`.
> **Never replace an ecosystem on a platform — bridge its existing fragments on every platform.**
>
> LAN peer-to-peer is a **feature**, not a compromise: data never leaving the LAN means privacy is guaranteed by physics; zero server cost means it's for ordinary people.

## Quick Start

```bash
git clone https://github.com/WowBlueStudio/MengPaw.git
cd mengpaw
./gradlew :mengpaw-shell:assembleDebug

# APK at:
# mengpaw-shell/build/outputs/apk/debug/mengpaw-shell-debug.apk
```

## Project Structure

```
mengpaw/
├── mengpaw-kernel/             # Microkernel (pure Kotlin/JVM, zero Android)
│   ├── cli/                    # CLI engine (parse→security→execute→audit)
│   ├── security/               # Security layer (Sanitizer/Policy/IntegrityProvider/Firewall)
│   ├── session/                # Session management (history compaction/checkpoints)
│   ├── llm/                    # LLM interfaces (multi-model adaptive/exponential backoff/Prefix Cache)
│   ├── plugin/                 # Plugin framework (lifecycle/market/version compatibility)
│   ├── agent/                  # Agent document management + Dream engine
│   ├── mcp/                    # Model Context Protocol (JSON-RPC)
│   ├── acp/                    # Agent Communication Protocol
│   ├── trigger/                # Cron + human-touch triggers
│   ├── namespace/              # Built-in namespaces (self)
│   ├── AgentEngine.kt          # ReAct + Plan-Execute engine
│   └── DataPaths.kt            # Platform-independent path constants
│
├── mengpaw-core/               # Android adapter layer (20 files)
│   ├── security/               # Vault (Keystore) / IntegrityGuard (APK signature)
│   └── namespace/              # SysExecutor (Android system info)
│
├── mengpaw-design-system/      # Arco Design + Material3 theme
│
├── mengpaw-shell/              # Main app APK
│   ├── ui/screens/             # Chat/Settings/Plugin Market/Sidebar
│   └── service/                # Foreground service/event listener/wakeups
│
├── mengpaw-browser/            # Standalone browser APK
│   ├── bridge/                 # BrowserBridge (Java↔JS bidirectional bridge)
│   └── plugin/                 # In-browser plugins (22 commands)
│
└── plugins/                    # 13 built-in plugins (siblings, all depend only on kernel, bundled in APK)
    ├── plugin-net/             # HTTP network (4 commands)
    ├── plugin-skill/           # Two-tier skill system (10 commands) ⭐💎
    ├── plugin-clipboard/       # Clipboard (3 commands)
    ├── plugin-framework/       # Framework communication protocol (15 commands) ⭐💎
    ├── plugin-memory-twin/     # Memory Twin (16 commands) ⭐💎
    ├── plugin-agent-tools/     # Agent toolset import (4 commands) ⭐💎
    ├── plugin-root/            # Root access (19 commands)
    ├── plugin-hermes/          # Tribe collaboration 💎
    ├── plugin-dream/           # Dream mode (built-in, SPI-replaceable) ⭐
    ├── plugin-dev/             # Plugin dev toolchain ⭐💎
    ├── plugin-tavily/          # AI search
    └── plugin-concise/         # Concise mode (disable to restore original prompt) ⭐
```

> ⭐ = bundled in the Shell APK · 💎 = WowBlue original (leading similar frameworks, see below)
>
> The 13 external plugins (update / translate / error-report / render / comfy / browser-push /
> browser-search / browser-mcp + 5 connectors) live in the standalone repo
> [mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors) (MIT) and are
> installed from the plugin marketplace via `plugin.install`.

## Architecture

```
┌────────────────────────────────┐
│  Shell APK     Browser APK     │  ← UI layer
├────────────────────────────────┤
│  mengpaw-core (20 files)       │  ← Android adapter
├────────────────────────────────┤
│  mengpaw-kernel (124 files)    │  ← Microkernel (pure Kotlin/JVM)
│  zero Android deps · JVM-tested │
├────────────────────────────────┤
│  14 built-in plugins (siblings · kernel only) │  ← Plugin layer
└────────────────────────────────┘
```

## Core Concepts

### Agent ReAct Loop

```
Thought → Action → Observation → ... → Final Answer
```

The Agent controls the device through CLI commands:

| Namespace | Example commands | Responsibility |
|-----------|-----------------|----------------|
| `fs` | `cp`, `mv`, `stat`, `grep`, `glob` | File system |
| `net` | `curl`, `get`, `post`, `proxy` | HTTP network |
| `sys` | `battery`, `cpu`, `display`, `wifi` | Android system (51 commands) |
| `skill` | `ls`, `run`, `create`, `pull`, `push` | Skill system |
| `self` | `status`, `tools`, `search`, `time` | Agent self-management |
| `evolution` | `audit`, `report`, `learn.command`, `mark-corrected` | Agent evolution (learning from failure) |
| `plugin` | `marketplace`, `install`, `search` | Plugin management |
| `twin` | `peers`, `sync`, `delegate`, `route` | Memory Twin |

### Execution Modes

| Mode | Description |
|------|-------------|
| **ReAct** | Standard Thought → Action → Observation loop |
| **Plan-Execute** | LLM decomposes the task into 3-7 steps, executes step by step |
| **Goal** | Single-goal driven with LLM completion evaluation |
| **Swarm** | Decompose → parallel Workers (role-mixed models) → Verifier → synthesize |
| **Fleet** | Multi-Agent fleet collaboration across devices (combined-arms mode) |
| **Plan** | Decompose into 3-7 steps → execute → summarize |
| **Research** | Multi-round search + cross-validation + sourced structured report |
| **Silent** | Background silent execution, result pushed on completion |

## WowBlue Original Plugins

Original features leading similar Agent frameworks (shown with a pink WowBlue badge in Settings and the Plugin Market):

| Plugin | Namespace | Edge |
|--------|-----------|------|
| **Memory Twin** 💎 | `twin` | Cross-device workspace sync — syncs the entire Agent workspace (soul.md → memory/), manifest diff + delta transfer + conflict backup + ACP P2P encrypted channel + short-code pairing + heartbeat + adaptive QoS; no counterpart in similar frameworks |
| **Tribe** 💎 | `tribe` | Multi-agent formation — LAN auto-teaming + Kanban delegation (priority/timeout/nested chains) + LLM capability routing + broadcast discussion + heartbeat |
| **Agent Tools** 💎 | `tools` | Import external CLI command sets (GitHub CLI / Feishu CLI etc.) as per-agent indexes, compact summaries injected into the system prompt for fast invocation |
| **Memory System** 💎 | `agent.memory` | Three-tier memory (kernel) — long/mid/project layers + cross-tier search/stats/by-ID read-write; Dream mode reads memory → backup → distills {date}_dream.md → auto-expires; only long-term memory is injected to prevent prompt bloat |
| **Skill System** 💎 | `skill` | Two-tier skill pool — shared global pool + agent-private local pool, with skill.pull/push on demand |
| **Framework Discovery** 💎 | `framework` | mDNS LAN framework discovery — register/scan/fingerprint/trust management, multi-device auto-networking |
| **Plugin Dev Chain** 💎 | `dev` | Built-in dev toolchain — plugin.create / audit / share in three steps; users are developers |
| **Evolution** 💎 | `evolution` | The Agent learns from failure — command-mistake hooks + four-level pyramid self-inquiry + error-quadrant handling (toolset/memory/soul/framework feedback) + user-reaction twin; problems never recur |

> Don't reinvent wheels — build the hub. These capabilities don't replace ecosystems; they bridge fragments into a whole.

## Build Requirements

- Android SDK 35 + JDK 17 + Gradle 8.12 (Wrapper included)
- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01

```bash
# Microkernel tests (JVM, seconds, no emulator needed)
./gradlew :mengpaw-kernel:test

# Build
./gradlew :mengpaw-shell:assembleDebug     # Shell APK
./gradlew :mengpaw-browser:assembleDebug   # Browser APK
```

## Dev Tools

This project is developed with AI assistance. Since 2026-08-07 it is maintained by Codex:

| Phase | Period | Orchestrator | Main model |
|-------|--------|--------------|-----------|
| Early | 2026-07-12 ~ 07-15 | Reasonix | DeepSeek Flash |
| Current | 2026-08-07 ~ now | Codex | DeepSeek Pro |

> Model inference goes through the DeepSeek API (`api.deepseek.com`); see `reasonix.toml`.

## Supported LLM Providers (12)

| Provider | Endpoint | Default model |
|----------|----------|--------------|
| OpenAI | api.openai.com | gpt-4o |
| DeepSeek | api.deepseek.com | deepseek-chat |
| Kimi (Moonshot) | api.moonshot.cn | moonshot-v1-8k |
| GLM (Zhipu) | open.bigmodel.cn | glm-4-plus |
| Qwen (Tongyi Qianwen) | dashscope.aliyuncs.com | qwen-plus |
| Grok (xAI) | api.x.ai | grok-2 |
| Volcano Engine | ark.cn-beijing.volces.com | Doubao |
| OpenModel | Custom | Custom |
| Self-Hosted | Custom | Custom |
| Custom | Custom | Custom |

## License (Dual)

MengPaw is released under a **dual license**:

| | Community | Commercial |
|---|-----------|-----------|
| License | AGPL-3.0 ([LICENSE](LICENSE)) | Commercial ([COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)) |
| Cost | Free | Paid |
| Applies to | Personal use / open source / AGPL-compliant deployments | Closed-source distribution / white-label / embedded products / hosted services that don't want to publish modifications |

> Internal enterprise use (no external distribution) is unrestricted. Commercial licensing: 1138018324@qq.com

External connector plugins ([mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors)) are independently MIT-licensed, open for community contributions.

## Feedback & Contribution

- **Bug reports / feature requests**: GitHub [Issues](https://github.com/WowBlueStudio/MengPaw/issues) (with templates)
- **Code contributions**: Pull Requests are open — plugins/docs preferred (submission transfers copyright; dual-licensed, see [CONTRIBUTING.md](CONTRIBUTING.md))
