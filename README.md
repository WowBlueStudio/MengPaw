# MengPaw 🐾

面向 Agent 的自举式 Android 操作系统框架。

> **Agent 通过内置 CLI 操控自身，API Key 是唯一安全禁区。**

## 快速开始

```bash
# 克隆并构建
git clone https://github.com/mengpaw/mengpaw
cd mengpaw
./gradlew :mengpaw-shell:assembleDebug

# APK 位于:
# mengpaw-shell/build/outputs/apk/debug/mengpaw-shell-debug.apk
```

## 项目结构

```
mengpaw/
├── mengpaw-core/           # 微内核核心 (Library AAR)
│   ├── cli/                # CLI 引擎 (解析→安全→执行)
│   ├── namespace/          # 内置命令 (fs/ui/proc/net/self)
│   ├── security/           # 安全沙箱 (Vault/Sanitizer/Policy)
│   ├── session/            # 会话管理 (历史/断点)
│   ├── llm/                # LLM 接口 (本地/远程/多模型)
│   ├── memory/             # 记忆系统 (Markdown CRUD)
│   ├── skill/              # Skill 系统 (可复用剧本)
│   ├── transport/          # 传输层 (AIDL/HTTP/Socket)
│   └── extension/          # 扩展框架 (加载/版本兼容)
│
├── mengpaw-design-system/  # Arco.design 适配 Compose
│
├── mengpaw-shell/          # 主应用 APK
│   └── ui/                 # Chat UI + 设置 + 记忆 + Skills
│
└── mengpaw-browser/        # 浏览器插件 APK
```

## 核心概念

### Agent ReAct 循环

```
Thought（思考） → Action（行动） → Observation（观察） → ... → Final Answer
```

Agent 通过 CLI 命令操控设备：
- `fs.cat /path/to/file` — 读取文件
- `ui.click 540 1200` — 点击屏幕
- `net.curl https://api.example.com` — HTTP 请求
- `memory write note.md "内容"` — 写入记忆
- `skill.run 搜索` — 运行 Skill

### 支持的 LLM 提供商

| 服务商 | Endpoint | 模型 |
|--------|----------|------|
| OpenAI | api.openai.com | gpt-4o |
| DeepSeek | api.deepseek.com | deepseek-chat |
| Kimi (月之暗面) | api.moonshot.cn | moonshot-v1-8k |
| GLM (智谱) | open.bigmodel.cn | glm-4-plus |
| Qwen (通义千问) | dashscope.aliyuncs.com | qwen-plus |
| Custom | 自定义 | 自定义 |

## 构建要求

- Android SDK 35+
- JDK 17+
- Gradle 8.12

## 许可证

Apache 2.0
