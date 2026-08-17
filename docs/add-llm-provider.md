# 新增 LLM 供应商接入指南

> 适用版本: v0.41.0 起（全厂商思维链兼容基线, 2026-08-17）
> 面向: 维护者 / Codex 后续会话。新增供应商 = 改动 6 个代码点 + 补官方格式测试 + 同步文档。

## 0. 总原则（违反 = 返工）

1. **以该厂商官方 API 文档为唯一准则**（用户定案 2026-08-17）：请求/响应字段、思考字段名、
   认证方式、流式事件格式一律以官方文档为准，禁止用第三方博客/issue 的说法当依据。
2. **仅响应侧解析**（用户定案）：本应用不注入任何"开启思考"请求参数（如 `thinking` /
   `enable_thinking`），也不把思维链回传进后续请求历史。预置中标注"思维链"的型号本身
   默认输出思考内容，接入时如实标注能力即可。
3. **思维链绝不混入正文**：新厂商的思考字段必须走独立 `onReasoning` 通道，否则会污染
   UI 流式缓冲并误判 `Final Answer:` / `Action:`（v0.40.1/0.40.2 三症状根因）。

## 1. 前置调研（先读官方文档，再动代码）

对照官方文档确认以下五件事，结论写进 PR/提交说明：

| 事项 | 说明 |
|------|------|
| 端点与认证 | 请求 URL；`Authorization` 头是 `Bearer <key>` 还是裸 key（GLM 是裸 key 特例） |
| 请求格式 | OpenAI 兼容 `chat/completions`（绝大多数）/ Anthropic Messages / 自有格式 |
| 流式事件 | `data: {choices:[{delta:{...}}]}` 还是 `content_block_delta` 等语义化事件 |
| 思考字段 | 流式 delta 与 message 上的思维链字段名；官方未记载 = 不声明兼容 |
| usage 字段 | `prompt_tokens` / `completion_tokens` 等（缺失按 0 计, 不影响接入） |

已支持厂商的官方字段参照（v0.40.4 核对）:

- OpenAI 兼容系 `delta.reasoning_content` / `message.reasoning_content`: DeepSeek、
  OpenAI(gpt-5/o 系)、Kimi、GLM(Z.AI)、Qwen(DashScope)、Grok、豆包(火山方舟)
- Anthropic 兼容 `content_block_delta` 内 `delta.type=="thinking_delta"` + `delta.thinking`
- 兜底键（官方 /v1 文档未记载, 仅兼容服务兜底, 不当作官方口径）: `reasoning`(Ollama 新版 /v1)、
  `thought` / `thinking`

## 2. 代码改动清单（按此顺序）

### 2.1 预置表 — `mengpaw-shell/.../ui/screens/SettingsModels.kt`

在 `enum class LlmProviderPreset` 中新增一项，构造参数顺序:
`(label, enLabel, endpoint, defaultModel, apiKeyPrefix, models)`:

```kotlin
XXX("厂商名", "English Name", "https://api.example.com/v1/chat/completions",
    "厂商-默认型号", "sk-",
    listOf(ModelInfo("型号A", "旗舰"), ModelInfo("型号B", "思维链"))),
```

- 型号与"思维链/快速/多模态"标注必须与官方文档一致，禁止口头声称。
- 超过 5 个型号时 UI 只展示前 5 个（`modelListDisplay`），完整列表靠 API 返回。

### 2.2 端点识别 — `mengpaw-kernel/.../llm/AdaptiveLlmProvider.kt`

在 `detectProviderType(endpoint)` 的 `when` 里加一行（顺序无要求，`else -> "openai"` 兜底）：

```kotlin
endpoint.contains("example.com") -> "xxx"
```

`providerType` 是内部标识，会被 `buildAuthHeader` 与日志消费。

### 2.3 认证头 — `mengpaw-kernel/.../llm/LlmPayload.kt`

`buildAuthHeader(providerType, apiKey)` 默认返回 `Bearer $apiKey`，只有官方要求裸 key
的厂商才加分支（当前仅 GLM）：

```kotlin
"xxx" -> apiKey  // 官方文档要求裸 key 时才加
```

### 2.4 缓存策略 — `mengpaw-kernel/.../llm/LlmRequestBuilder.kt`

`CacheStrategy.forProvider(endpoint)` 决定注入 `cache_control` 断点（OpenAI 兼容缓存）
还是纯前缀稳定（DeepSeek/Grok 系）。按官方缓存文档选择：

```kotlin
"example.com" in endpoint -> CACHE_CONTROL   // 或 PREFIX_STABLE
```

### 2.5 思考字段 — `mengpaw-kernel/.../llm/ReasoningExtractor.kt`

仅当官方文档记载了**新字段名**时才在 `OPENAI_COMPAT_KEYS` 追加，并附官方文档 URL 注释：

```kotlin
private val OPENAI_COMPAT_KEYS = listOf("reasoning_content", "reasoning", "thought", "thinking")
```

- 键序即优先级：同包多键只取首个（定案: 网关重复下发同一思考 ~99%，拼接会显示两遍）。
- Anthropic 兼容端点无需改这里（`thinking_delta` 已在 `SseStreamParser` 处理）。

### 2.6 会话显示名 — `mengpaw-shell/.../ui/screens/model/AgentSession.kt`

`providerLabel(strings)` 的 `when` 里按 endpoint 加显示名（无匹配回退 "Custom"）：

```kotlin
endpoint.contains("example.com") -> "厂商名"
```

### 2.7 语音输入（可选）— `mengpaw-shell/.../ui/screens/model/VoiceCapability.kt`

厂商型号支持原生音频输入时，在 `KNOWN_PREFIXES` 加精确前缀（刻意排除 gemini 的教训见
文件头注释）；不建议依赖 `KEYWORDS` 兜底做正式声明。

## 3. 测试（必做）

1. `mengpaw-kernel/.../llm/SseStreamParserTest.kt`：按**官方文档夹具**加用例 —
   `delta.reasoning_content` / Anthropic `thinking_delta` / 新厂商专属字段，断言
   思维链只进 `onReasoning`、不进 `onToken`/返回值。
2. `RemoteApiTest.kt`：若新厂商走 fallback 链，补流式分流/HTTP 错误/非流式 `lastReasoning`
   用例（MockEngine 客户端注入）。
3. 跑 `:mengpaw-kernel:test`（llm 包）→ 全量 `./gradlew test`，把实测数字更新到
   开发指南 §3.7。

## 4. 文档同步

- 新供应商的能力矩阵/字段名称追加到本文件 §1 参照表（保持"官方文档为准"）。
- 更新 `docs/INDEX.md`（本文件已登记时无需改）；开发指南 §3.7 测试快照、CHANGELOG。
- 若出现新坑（如某厂商流式事件特殊、认证特例），按项目惯例记入 `docs/lessons.md`。

## 5. 验收清单

- [ ] 官方文档核对五件事已记录（端点/认证/请求格式/流式事件/思考字段）
- [ ] 6 个代码点已改（预置/识别/认证/缓存/思考字段/显示名），语音能力按需
- [ ] 官方格式测试已加，kernel + 全量测试全绿
- [ ] 未注入请求侧参数、未回传思维链
- [ ] 新 .kt 带 SPDX 双许可头（如需新建）
- [ ] 开发指南 §3.7 / CHANGELOG 已同步
