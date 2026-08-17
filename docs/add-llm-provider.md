# 新增 LLM 供应商接入指南

> 适用版本: v0.41.0 起（全厂商思维链兼容基线）
> 最后核对: 2026-08-17（官方文档原文）
> 面向: 维护者 / Codex 后续会话。新增供应商 = 核对官方文档原文 + 登记名单 + 改动 6 个代码点 + 补官方格式测试。

## 0. 总原则（违反 = 返工）

1. **以该厂商官方文档原文为唯一准则**（用户定案 2026-08-17）：
   - 核对 = 打开官方文档**原文页面**逐条确认（请求/响应字段、思考字段名、认证方式、流式事件格式）；
   - 禁止用搜索引擎摘要、第三方博客/issue、SDK 封装源码或中转站的转述当依据；
   - 官方文档未记载的能力 = 不声明兼容（例：OpenAI chat/completions 官方未记载
     `reasoning_content`，即如实标注"官方仅 Responses API 文档化思维输出"）。
2. **仅响应侧解析**（用户定案）：本应用不注入任何"开启思考"请求参数（`thinking` /
   `enable_thinking` 等），也不把思维链回传进后续请求历史。预置中标注"思维链"的型号
   本身默认输出思考内容，接入时如实标注能力即可。
3. **思维链绝不混入正文**：新厂商的思考字段必须走独立 `onReasoning` 通道，否则会污染
   UI 流式缓冲并误判 `Final Answer:` / `Action:`（v0.40.1/0.40.2 三症状根因）。

## 1. 官方文档原文核对表（已支持厂商, 2026-08-17 核对）

> 每家厂商只认官方原文链接；新增/复核供应商时先点开原文页，链接失效即重新检索官方域。

| 厂商 | 官方文档原文 | 核对要点 |
|------|-------------|---------|
| DeepSeek | [思考模式](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode) | `reasoning_content` 与 `content` 同级，流式 delta 与 message 均含；官方要求工具调用场景必须回传 `reasoning_content` 否则 400（本项目仅响应侧解析不回传, 请求侧定案, 工具调用场景注意） |
| Kimi | [思考模型](https://platform.kimi.com/docs/guide/use-thinking-models) | `delta.reasoning_content` / `message.reasoning_content`；kimi-k3/k2.7-code 始终思考；保留式思考官方要求多轮回传（本项目不回传, 请求侧定案） |
| GLM/Z.AI | [Migrate to GLM-5.2](https://docs.z.ai/guides/overview/migrate-to-glm-new) | 流式须处理 `delta.reasoning_content` 与 `delta.content`；`thinking` 参数 |
| Qwen/DashScope | [模型大全](https://help.aliyun.com/zh/model-studio/getting-started/models) + [Responses 兼容](https://help.aliyun.com/zh/model-studio/compatibility-with-openai-responses-api) + [Thinking](https://docs.qwencloud.com/developer-guides/text-generation/thinking) | 两阶段流式：先 `reasoning_content` 后 `content`；2026-08-17 核对：qwen3.8-max 已转正为旗舰（preview 退役自动路由），均衡/快速档为 qwen3.7-plus / qwen3.7-flash |
| Grok/xAI | [Models](https://docs.x.ai/developers/models) + [Streaming](https://docs.x.ai/developers/model-capabilities/text/streaming) + [Reasoning](https://docs.x.ai/developers/model-capabilities/text/reasoning) + [退役公告](https://docs.x.ai/developers/migration/may-15-retirement) | 流式 `delta.reasoning_content`（grok-4.6 为思考摘要, 官方 reasoning 文档示例）；旧 `grok-4` 不返回；2026-08-17 核对：grok-4.6 当前旗舰，grok-4-1-fast 系列已退役（重定向 grok-4.3） |
| 豆包/火山方舟 | [套餐概览](https://docs.volcengine.com/docs/82379/2276791?lang=zh) + [OpenCode](https://docs.volcengine.com/docs/82379/2188958?lang=zh) + [LLM API 文档](https://docs.volcengine.com/docs/6492/2165111?lang=zh) | `reasoning_content` 为思维链字段（含流式 delta）；2026-08-17 核对：托管第三方为 deepseek-v4-flash/pro + glm-5.3（glm-5.2 即将下线），新增 doubao-seed-2.1-turbo |
| Anthropic 兼容 | [Models overview](https://platform.claude.com/docs/en/about-claude/models/overview) + [Streaming messages](https://platform.claude.com/docs/en/build-with-claude/streaming) + [Thinking](https://platform.claude.com/docs/en/build-with-claude/thinking) | `content_block_delta` 内 `delta.type=="thinking_delta"` + `delta.thinking`；块尾 `signature_delta`（忽略不回放）；`display:"omitted"` 时官方不发 thinking_delta 只发 signature_delta；2026-08-17 核对：Fable 5 / Opus 5 / Sonnet 5 / Haiku 4.5 |
| Ollama | [Thinking](https://docs.ollama.com/capabilities/thinking) + [OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility) | 原生 `/api/chat` 用 `message.thinking`；`/v1` 兼容端点官方未记载思维字段（只作兜底键） |
| OpenAI | [Models](https://developers.openai.com/api/docs/models) + [Reasoning 指南](https://developers.openai.com/api/docs/guides/reasoning) + [GPT-5.6 Sol](https://developers.openai.com/api/docs/models/gpt-5.6-sol) | 官方 reasoning 指南明确 "reasoning tokens are not visible via the API"，chat/completions 流式仅 `delta.content`（思维链天然为空属官方能力边界）；`reasoning_content` 仅作兼容兜底键；2026-08-17 核对：GPT-5.6 家族为当前旗舰，o4-mini Deprecated |
| MiniMax | [OpenAI SDK](https://platform.minimaxi.com/docs/api-reference/text-openai-api) + [工具使用&交错思维链](https://platform.minimaxi.com/docs/guides/text-m3-function-call.md) + [OpenAPI 规范](https://platform.minimaxi.com/docs/api-reference/text/api/openapi-chat-openai.json) | 默认 thinking 内联在 `content` 的 `<think>...</think>` 标签内（响应侧剥离）；`reasoning_split=true` 时经 `reasoning_content` + `reasoning_details` 数组返回，流式 delta 的 `text` 为累计全文（官方示例按 buffer 取增量） |

> 2026-08-17 复核记录：GLM（docs.z.ai/pricing）、Kimi（platform.kimi.com/docs/models）、
> DashScope（help.aliyun.com 模型大全/Responses 兼容）、OpenAI（developers.openai.com Models）、
> Grok（docs.x.ai Models + 退役公告）、火山（docs.volcengine.com 套餐概览/OpenCode）、
> Anthropic（platform.claude.com Models overview）官方原文均已打开核对，预置随表更新。

## 2. 当前支持厂商与模型名单（登记表, 2026-08-17）

> 数据源: `mengpaw-shell/.../ui/screens/SettingsModels.kt` 的 `LlmProviderPreset`。
> **同步铁律**: 改代码必改本表、改本表必改代码；核对日期随每次更新刷新。

| 预置 | 端点 | 默认型号 | 型号清单（type 标注） |
|------|------|---------|---------------------|
| OpenAI | https://api.openai.com/v1/chat/completions | gpt-5.6 | gpt-5.6(旗舰·1.05M上下文) / gpt-5.6-terra(均衡) / gpt-5.6-luna(轻量) / gpt-5.5(前代) / gpt-5.4(前代) |
| DeepSeek | https://api.deepseek.com/chat/completions | deepseek-v4-flash | deepseek-v4-flash(快速) / **deepseek-v4-pro(思维链)** |
| Kimi | https://api.moonshot.cn/v1/chat/completions | kimi-k3 | kimi-k3(旗舰·1M上下文) / kimi-k2.7-code(Coding) / kimi-k2.6(通用) / kimi-k2.7-code-highspeed(高速Coding) |
| GLM | https://open.bigmodel.cn/api/paas/v4/chat/completions | glm-5.2 | glm-5.2(旗舰·1M上下文) / glm-5.1(Coding) / glm-5(前代) / glm-5-turbo(高速) / glm-5v-turbo(多模态) |
| DashScope | https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions | qwen3.8-max | qwen3.8-max(旗舰·视觉+推理) / qwen3.7-max(前代) / qwen3.7-plus(均衡·视觉) / qwen3.7-flash(快速·视觉) / qwen3.6-35b-a3b(开源MoE) / qwen3-coder-plus(Coding) / **qwq-plus(思维链)** / qwen3-vl-plus(多模态) / qwen3-omni-flash(全模态) |
| Grok | https://api.x.ai/v1/chat/completions | grok-4.6 | grok-4.6(旗舰·500K上下文) / grok-4.5(前代) / grok-4.3(推荐·1M上下文) / **grok-4.20-0309-reasoning(思维链)** / grok-build-0.1(Coding) |
| 火山引擎(豆包) | https://ark.cn-beijing.volces.com/api/v3/chat/completions | doubao-seed-2.0-pro | doubao-seed-2.0-pro(旗舰) / doubao-seed-2.1-turbo(2.1系列·快速) / doubao-seed-2.0-lite(均衡) / doubao-seed-2.0-mini(轻量) / doubao-seed-1.8(前代) / doubao-seed-1.6-flash(快速) / **doubao-seed-1.6-thinking(思维链)** / deepseek-v4-flash(DeepSeek托管) / deepseek-v4-pro(DeepSeek托管·思维链) / glm-5.3(GLM托管) / (需创建接入点 ep-xxx) |
| OpenModel | https://api.openmodel.ai/v1/chat/completions | deepseek-v4-flash | **deepseek-v4-pro(思维链)** / deepseek-v4-flash(快速) / qwen3.7-max(Qwen托管) / gpt-5.4-mini(OpenAI托管) / kimi-k3(Kimi托管) / glm-5.2(GLM托管) / grok-4.5(Grok托管) / (更多模型见API返回) |
| MiniMax | https://api.minimaxi.com/v1/chat/completions | MiniMax-M3 | MiniMax-M3(旗舰·1M上下文) / MiniMax-M2.7(均衡) / MiniMax-M2.7-highspeed(极速) / MiniMax-M2.5(性价比) / MiniMax-M2.5-highspeed(极速) / MiniMax-M2.1(编程) / MiniMax-M2.1-highspeed(极速) / MiniMax-M2(编码/Agent) |
| Self-Hosted | http://192.168.1.100:{Ports.LLM_SELF}/v1/chat/completions | local-model | local-model(Chat) / qwen2.5:7b(Chat) / llama3.1:8b(Chat) |
| Custom | 用户自填 | — | 无预置（OpenAI 兼容端点） |

## 3. 代码改动清单（按此顺序）

### 3.1 预置表 — `mengpaw-shell/.../ui/screens/SettingsModels.kt`

在 `enum class LlmProviderPreset` 中新增一项，构造参数顺序:
`(label, enLabel, endpoint, defaultModel, apiKeyPrefix, models)`:

```kotlin
XXX("厂商名", "English Name", "https://api.example.com/v1/chat/completions",
    "厂商-默认型号", "sk-",
    listOf(ModelInfo("型号A", "旗舰"), ModelInfo("型号B", "思维链"))),
```

- 型号与"思维链/快速/多模态"标注必须与官方文档一致，禁止口头声称；同步更新 §2 登记表。
- 超过 5 个型号时 UI 只展示前 5 个（`modelListDisplay`），完整列表靠 API 返回。

### 3.2 端点识别 — `mengpaw-kernel/.../llm/AdaptiveLlmProvider.kt`

在 `detectProviderType(endpoint)` 的 `when` 里加一行（`else -> "openai"` 兜底）：

```kotlin
endpoint.contains("example.com") -> "xxx"
```

### 3.3 认证头 — `mengpaw-kernel/.../llm/LlmPayload.kt`

`buildAuthHeader(providerType, apiKey)` 默认 `Bearer $apiKey`，仅官方要求裸 key 的厂商
加分支（当前仅 GLM）：

```kotlin
"xxx" -> apiKey  // 官方文档要求裸 key 时才加
```

### 3.4 缓存策略 — `mengpaw-kernel/.../llm/LlmRequestBuilder.kt`

`CacheStrategy.forProvider(endpoint)` 按官方缓存文档选择 `CACHE_CONTROL`（注入断点）或
`PREFIX_STABLE`（前缀稳定）：

```kotlin
"example.com" in endpoint -> CACHE_CONTROL   // 或 PREFIX_STABLE
```

### 3.5 思考字段 — `mengpaw-kernel/.../llm/ReasoningExtractor.kt`

仅当官方文档原文记载了**新字段名**时才在 `OPENAI_COMPAT_KEYS` 追加，并附官方文档 URL：

```kotlin
private val OPENAI_COMPAT_KEYS = listOf("reasoning_content", "reasoning", "thought", "thinking")
```

- 键序即优先级：同包多键只取首个（定案: 网关重复下发同一思考 ~99%，拼接会显示两遍）。
- Anthropic 兼容端点无需改这里（`thinking_delta` 已在 `SseStreamParser` 处理）。
- **内联标签形态**（MiniMax 默认）：官方文档明确 thinking 保留在 `content` 的
  `<think>...</think>` 标签内 — 流式经 `ThinkTagSplitter` 剥离（跨 chunk 拆分标签也处理），
  非流式经 `stripThinkTags` 剥离，剥离内容都进 `onReasoning`/`ParsedLlmBody.reasoning`。
- **数组形态**（MiniMax `reasoning_split=true`）：`reasoning_details` 数组每项含
  `type/id/format/index/text`；**流式 delta 的 text 是当前块累计全文**（官方 OpenAI SDK
  示例按 `len(reasoning_buffer)` 取新增），流式侧必须按全文 buffer 做增量去重，直接拼接
  会整段重复推送。

### 3.6 会话显示名 — `mengpaw-shell/.../ui/screens/model/AgentSession.kt`

`providerLabel(strings)` 的 `when` 里按 endpoint 加显示名（无匹配回退 "Custom"）。

### 3.7 语音输入（可选）— `mengpaw-shell/.../ui/screens/model/VoiceCapability.kt`

厂商型号支持原生音频输入时，在 `KNOWN_PREFIXES` 加精确前缀（gemini 排除教训见文件头）；
不建议依赖 `KEYWORDS` 兜底做正式声明。

## 4. 测试（必做）

1. `mengpaw-kernel/.../llm/SseStreamParserTest.kt`：按**官方文档原文夹具**加用例 —
   `delta.reasoning_content` / Anthropic `thinking_delta` / 新厂商专属字段，断言
   思维链只进 `onReasoning`、不进 `onToken`/返回值。
2. `RemoteApiTest.kt`：若新厂商走 fallback 链，补流式分流/HTTP 错误/非流式 `lastReasoning`
   用例（MockEngine 客户端注入）。
3. 跑 `:mengpaw-kernel:test`（llm 包）→ 全量 `./gradlew test`，实测数字更新开发指南 §3.7。

## 5. 文档同步

- §1 核对表与 §2 登记表随新供应商同步更新（含核对日期）。
- 开发指南 §3.7 测试快照、CHANGELOG；新坑按惯例记入 `docs/lessons.md`。

## 6. 验收清单

- [ ] 官方文档**原文**已打开核对（端点/认证/请求格式/流式事件/思考字段），结论记录
- [ ] §2 登记表已同步最新厂商与型号（含"思维链"标注），核对日期已刷新
- [ ] 6 个代码点已改（预置/识别/认证/缓存/思考字段/显示名），语音能力按需
- [ ] 官方格式测试已加，kernel + 全量测试全绿
- [ ] 未注入请求侧参数、未回传思维链
- [ ] 新 .kt 带 SPDX 双许可头（如需新建）
- [ ] 开发指南 §3.7 / CHANGELOG 已同步
