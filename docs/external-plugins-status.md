# 外置插件现状与建议（2026-09-10 快照）

> 范围：**全部外置插件** —— 即源码位于独立仓库 `WowBlueStudio/mengpaw-connectors`（MIT 许可）、经插件市场 `plugins.json` 下发、由宿主 DexClassLoader 运行时加载的插件。
> 数据来源：`plugins.json`（v9，28 条 = 16 builtin + 12 remote）、`D:\MengPaw-connectors` 全仓静态清点（13 模块 / 4251 行 src/main / 158 例单测）、宿主内核加载链路源码、两个平台发行资产实测。
> 本文档只描述现状与建议，**不含任何发版动作**；所有"建议"均需用户拍板后执行。

---

## 一、摘要（TL;DR）

| 维度 | 现状 |
|---|---|
| 在售外置插件 | **12 个**（6 个普通插件 + 6 个连接器），市场 `status=remote` |
| 已退役外置插件 | **2 个**（`browser-mcp`、外部 `plugin-update`）—— 模块/构建/清单/发行资产均已移除，git 历史可溯 |
| 仓库健康度 | 13 模块、4251 行 src/main、2193 行 src/test、**158 例单测全绿**；零 TODO/FIXME、零 `!!`、无文件 >400 行（最大 375 行） |
| 最严重问题 | ①**命令名双源不一致**（2 个插件声明名 ≠ 实际注册名，宿主市场/开发指南/内核指引都在教错名字）；②`browser-push` 的 ACP 句柄永远为 null，**宣称能力实际不可用**；③**市场产物落后源码：10/12**，其中 4 个含未下发的真实缺陷修复 |
| 结论 | 结构性干净（命名/构建/测试纪律良好），但**契约层与下发层有硬伤**：装到用户手上的 jar 既有旧 bug，又带着错的命令名。建议按 §五 的 P0 清单先做一次插件级发布 |

---

## 二、在售外置插件总表

> 命名空间 = 运行期实际命名空间，由内核唯一权威函数 `pluginNamespaceFor(id)` 推导（`mengpaw-kernel/.../plugin/PluginManager.kt:22-29`），命令注册式 = `"$ns.$key"`（同文件 `:299-305`）。

| # | 插件 id | 模块 | 版本 | 命令（实际注册名） | 单测 | 市场 tag | 源码是否有未下发变更 |
|---|---|---|---|---|---|---|---|
| 1 | `translate-plugin` | plugin-translate | 0.3.0 | `translate.text/auto/langs/setup` | 14 | v0.4.0 | 有（测试） |
| 2 | `error-report-plugin` | plugin-error-report | 0.3.0 | **`error-report.*`（声明为 `error.*`）** | 9 | v0.4.0 | 有（测试） |
| 3 | `render-plugin` | plugin-render | 0.3.0 | `render.models/generate/status/preview` | 9 | v0.4.0 | 有（测试+文案） |
| 4 | `comfy-plugin` | plugin-comfy | 0.3.0 | `comfy.nodes/workflow/run/preview/export` | 12 | v0.4.0 | **有（2 处缺陷修复）** |
| 5 | `browser-push-plugin` | plugin-browser-push | 0.3.0 | **`browser-push.push*`（声明为 `browser.push*`）** | 10 | v0.5.0 | **有（顺序缺陷修复）** |
| 6 | `browser-search-plugin` | plugin-browser-search | 0.3.0 | `search.extract/summary/engines/clean/md/outputs/clear` | 27 | v0.5.0 | 无 |
| 7 | `connector-openclaw-plugin` | plugin-connector-openclaw | 0.1.0 | `connector-openclaw.info` | 5 | v0.4.0 | 有（测试） |
| 8 | `connector-qwenpaw-plugin` | plugin-connector-qwenpaw | 0.2.0 | `connector-qwenpaw.config/info` | 12 | v0.4.0 | 有（测试） |
| 9 | `connector-claude-code-plugin` | plugin-connector-claude-code | 0.1.0 | `connector-claude-code.config/info` | 6 | v0.4.0 | 有（测试） |
| 10 | `connector-reasonix-plugin` | plugin-connector-reasonix | 0.1.0 | `connector-reasonix.config/info` | 6 | v0.4.0 | 有（测试） |
| 11 | `connector-trae-plugin` | plugin-connector-trae | 0.1.0 | `connector-trae.config/info` | 7 | v0.4.0 | **有（正名修复）** |
| 12 | `connector-yinxiang-plugin` | plugin-connector-yinxiang | 0.1.0 | `connector-yinxiang.*`（9 条） | 31 | v0.6.0 | 无 |

另：`plugin-connector-common` 是共享库（SSH 传输 + 凭据存储 + config/info 复用），**无主类、不出插件产物**，被 fat 合并进 6 个连接器 —— 属预期，不是"清单缺条目"。

**市场 tag 分布**：`plugins-v0.4.0` ×9、`plugins-v0.5.0` ×2、`plugins-v0.6.0` ×1（yinxiang）。三个 tag 的资产名与打包脚本命名规则完全吻合（普通 `plugin-<name>-release.jar`、连接器 `plugin-<name>-plugin.jar`）。

---

## 三、宿主 ↔ 外置插件衔接机制（现状）

1. **索引下发**：`PluginMarketplaceClient` 从**主仓库 master 分支的 `plugins.json`** 拉取 —— 中国走 Gitee raw、海外走 GitHub raw，失败依次回退「另一源 → ghproxy.com → 磁盘快照」，5 分钟内存 TTL。
2. **条目语义**：`status` ∈ `{builtin, remote, embedded, deprecated}`（`validate-plugins.ps1` 强制枚举）。
   - `isDownloadable = status != "deprecated" && downloadUrl 非空`
   - `isBuiltin = status != "deprecated" && downloadUrl 为空`
   - ⚠️ 注意：内核/壳按**字符串 `"deprecated"`** 判定，历史 CHANGELOG 里的 `depr` 写法**不被识别**（当前清单无此值，属潜在雷区）。
3. **安装链路**：下载 → **SHA256 与清单 checksum 比对**（缺失则记 `UNTRUSTED` 并跳过校验）→ 校验产物含 `classes.dex`（AAR 会明确报错）→ 主类定位优先 `META-INF/plugin-class` 清单、回退 PascalCase 约定 → `DexClassLoader` 加载 → install + activate。
4. **校验能力现状**：`scripts/validate-plugins.ps1` 校验结构/SemVer/URL 形态/checksum 格式 + 本地 AAR 名匹配；**远端 jar 的 checksum 与 size 无法核验**（jar 在 connectors 仓库），`mirrorUrl` 也未校验。
5. **发版链路**：`mengpaw-connectors/scripts/package-plugins.ps1` → `releases/plugins/` → `plugins-v<ver>` tag + 双平台 Release → 回写 `plugins.json` 的 `downloadUrl/mirrorUrl/checksum/size/version`。
6. **本轮实测（2026-09-10）**：12 条 remote 条目逐个经 **Gitee 镜像下载**并计算 SHA256 —— **12/12 的 `size` 与 `checksum` 与清单完全一致**，即当前市场条目与可下载产物**是对得上的**（不会出现"下载完校验失败导致装不上"）。同批测试中 `github.com:443` 在本机被阻断（12/12 直连失败），Gitee 镜像 12/12 可达 —— 印证"国内走 Gitee + 海外走 GitHub"的双源设计是必要的。

---

## 四、逐插件现状与建议

### 4.1 普通插件（6 个）

**① translate-plugin（翻译引擎）** — 成熟度：可用
- 双模式：Google Free 公开端点 / Cloud API v2；`--from/--to` 参数解析、语种自动检测降级均已测。
- 问题：HTTP 客户端只有 `connectTimeout(10s)`，**无 read/socket 超时**（`TranslatePlugin.kt:53-55`），慢响应会挂；`translate.text/auto` 的联网分支无测试。
- 建议：补 read/socket 超时（P1）；联网分支保持"不硬测"原则，无需强补。

**② error-report-plugin（错误上报）** — 成熟度：可用但契约错误
- 6 条命令：list/show/clear/export/status/upload；上传地址 `gitee.com/api/v5/repos/.../issues` 硬编码但可被 `config.properties` 覆盖。
- 问题：**P0 命令名漂移**（见 §五 P0-1）；`plugins.json` 描述写「`error.bridge` JSON 桥接」但源码**根本没有 bridge 命令**；6 条命令 + `ErrorReportUploader`（84 行）+ `ErrorReportWifiMonitor`（81 行）**全部零测试**。
- 建议：先修 P0-1 名称不一致；补命令层测试（依赖 `SharedPreferences` → 需 Robolectric，或把可测逻辑抽成 `internal object`）；修正描述。

**③ render-plugin（API 生图引擎）** — 成熟度：可用
- 三后端（Replicate / Stability / GPT Image），模型表更新至 2026.07；API Key 全部来自环境变量，**无密钥字面量**（符合红线）。
- 问题：`HttpClient(OkHttp)` **无任何超时**（`RenderPlugin.kt:45`），轮询可长达 600s；`render.preview` 只返回固定文案不落地；三后端真实提交与轮询零测试；`jobs` 用非线程安全 `mutableMapOf`。
- 建议：补超时（P1）+ `jobs` 改并发安全容器（P2）；`render.preview` 要么落地要么在描述里去掉"预览"承诺。

**④ comfy-plugin（ComfyUI 工作流）** — 成熟度：**半成品（链路不通）**
- 5 条命令；本轮已修两处真实缺陷（`comfy.workflow add` 的 `ClassCastException`、`comfy.run` 单参数被拒）。
- 问题：**工作流文件格式对 ComfyUI 无效** —— nodes 存成「数组套 `{id:{...}}`」、link id 为字符串，测试**主动钉住**了这两处偏差并注释"构建→run 的核心链路当前对 ComfyUI 无效"；`HttpClient` 无超时；`comfy.preview` 固定文案。
- 建议：**决策项**（§六 决策 1）—— 按 ComfyUI API 格式重写工作流模型（估 1 个模块级重写）或直接退役。

**⑤ browser-push-plugin（跨设备推送）** — 成熟度：**不可用（能力缺失）**
- 4 条命令；本轮已修 `accept` 先删文件后校验 URL 的顺序缺陷。
- 问题：**P0-1 名称漂移** + **P0-2 `acpServer`/`acpTransport` 全仓无任何赋值**（`BrowserPushPlugin.kt:50-51`），`push` 永远走"ACP 未启动，请先执行 `self.acp start`"失败分支（该指引指向的命令也不存在）；`onInstall` 只有一行日志，注释说"由 shell service 处理"但**没有对应代码**；广播循环吞异常后仍报"已推送到 N 个设备"。
- 建议：见 §五 P0-2 —— 接线（壳注入 ACP server/transport）或下线/标实验。

**⑥ browser-search-plugin（网页转档）** — 成熟度：较好（测试最全之一，27 例）
- 7 条命令；命名空间是内核特例 `search`；含 SSRF 防护（拦回环/私网）与重定向逐跳复检。
- 问题：出口路径前缀校验用无分隔符 `startsWith`（`BrowserSearchPlugin.kt:275`），**同前缀兄弟目录可能绕过**；**重定向跟随 + 每跳 SSRF 复检这条安全关键路径零测试**；客户端仅设 `requestTimeoutMillis`。
- 建议：改为 `File.canonicalPath` + 分隔符边界判断，并补该分支测试（P1）。

### 4.2 连接器（6 个）

**共性**：都实现内核 `FrameworkAdapter` SPI，装完即出现在 `framework.adapters`，经 `framework.connect <peer>` / `framework.call <peer> <tool>` 使用；都是 **fat dex JAR（约 580–600 KB）**，各自内嵌 jsch/okhttp/okio；`connect`/`callTool` 的成功路径**全部零测试**（只测命令构造与转义）。

| 连接器 | 通道 | 工具面 | 特有风险 |
|---|---|---|---|
| `connector-openclaw` | MCP over **`ws://`**（无 wss 选项） | `callTool` 透传任意 tool 名 | WebSocket 握手/回包零测试；握手 15s/响应 30s 超时已配 ✓ |
| `connector-claude-code` | SSH → `claude -p`（headless） | `run` / `version` | 凭据缺失、CLI 探测失败分支零测试 |
| `connector-reasonix` | SSH → `reasonix run` | `run` / `version` | 同上；依赖上游 `esengine/DeepSeek-Reasonix`（MIT） |
| `connector-trae` | SSH → `trae-cli run` | `run` / `show-config` | **四处拼写两套**：模块/id/ns 用 `trae`，README 与 `frameworkName` 用 `trae-ide`（内核保留 `trea-ide`→`trae-ide` 历史别名） |
| `connector-qwenpaw` | REST **`http://`**（无 https 选项）+ **实验性 SSH ACP** | `chat` / `acp-prompt` | ACP 通道源码自标"实验性"（5 处标注）；`restChat`（SSE 读循环 + token 头）与 `AcpOverSsh` **全链路零测试** |
| `connector-yinxiang` | EDAM 云 API over https（**不是** FrameworkAdapter） | 9 条命令：search/get/create/update/delete/notebooks/tags/config/info | 覆盖最好（31 例）；真实 API 调用零测试；token 7 天短效；434 KB（含 evernote-api + jsoup） |

**共性建议**：① 明文 `ws://`/`http://` 至少加"内网专用"提示或可选 TLS（P2）；② 为 `connect`/`callTool` 抽纯逻辑（帧构造、回包解析、错误映射）后补测（P1）；③ 6 个连接器各带一份 jsch/okhttp（合计 ≈3.5 MB）—— 若宿主已有 okhttp，可考虑瘦身（P2，收益有限）。

### 4.3 已退役（2 个，仅存档说明）

| 插件 | 退役原因 | 现状 |
|---|---|---|
| `browser-mcp`（`browser-mcp-plugin`） | 9880 HTTP 桥退役，浏览器控制改为 am 桥单通道（Browser v0.9.0/v0.10.1） | 模块/构建清单/打包清单/内核端口常量/命名空间特例/市场条目/发行资产全部移除；源码在 git 历史 |
| 外部 `plugin-update` | 宿主自 v0.37.3 起已内置同功能插件，connectors 侧属重复实现 | 模块退役，13 模块构建图；`update-plugin` 在清单中为 `builtin` |

> 两个插件的发行资产已从 GitHub + Gitee 的 6 个历史 release 中摘除（共 34 个附件），tag/release 条目/CHANGELOG 历史原样保留。

---

## 五、横向问题清单与建议（P0 / P1 / P2）

### P0-1 命令名双源不一致 —— 市场、开发指南、内核指引三处都在教错名字

**事实（文件级证据）**

| 插件 | 声明名（`metadata.commands` + `plugins.json`） | 实际注册名（`pluginNamespaceFor` 推导） |
|---|---|---|
| `browser-push-plugin` | `browser.push` / `.pending` / `.accept` / `.reject` | **`browser-push.push` / `browser-push.push.pending` / `.accept` / `.reject`** |
| `error-report-plugin` | `error.list` / `.show` / `.clear` / `.export` / `.status` / `.upload` | **`error-report.list` / … / `error-report.upload`** |

- 推导权威：`pluginNamespaceFor("browser-push-plugin") = "browser-push"`（无特例）、`= "error-report"`；注册式 `registry.register("$ns.$name", …)`（`PluginManager.kt:22-29` / `:299-305`）。
- 错误名字的扩散面：`plugins.json:273-278` 与 `:385-388`、`MengPaw-Development-Guide.md:342`、`:411`、`:1129`，以及**内核自己生成的收件箱指引** `mengpaw-kernel/.../acp/AcpServer.kt:372-373`（输出"接受: `browser.push.accept …`"）。
- 连带效应：市场安装成功提示用 `entry.commands.joinToString { it.removePrefix("$ns.") }` 构造（`PluginExecutor.kt:200-201`），前缀不匹配时**原样输出错误命令名**给用户/Agent。

**建议（二选一，推荐 A）**
- **方案 A（推荐，零代码风险）**：以**运行期实际名**为准，改 `plugins.json` + 开发指南 §CLI 表 + `AcpServer.kt` 指引文案；`error-report.*` 直观无歧义，`browser-push.push*` 略啰嗦但可接受。
- 方案 B：内核为 `browser-push`/`error-report` 加命名空间特例恢复短名 —— 需四源同步（BuiltinCommandIndex/CLI.md/提示词/开发指南）+ 测试，且 `browser.*` 与浏览器仓库命令面语义重叠，**不推荐**。
- 无论哪个方案，都要在 `validate-plugins.ps1` 加一条校验：**清单 `commands` 的命名空间前缀必须等于 `pluginNamespaceFor(id)`**（当前无此校验，才让漂移存活至今）。

### P0-2 `browser-push` 宣称能力实际不可用

- `acpServer` / `acpTransport` 两个字段**全仓无赋值**（`BrowserPushPlugin.kt:50-51`），`push` 恒返回"ACP 未启动"（`:72-76`）；`onInstall` 注释称"由 shell service 处理"，但宿主没有对应接线代码（本轮已核查 `PluginRegistrar`/`AppRoot`/`PluginClassRegistry`）。
- **建议**：① 若保留 → 由壳在 ACP 服务就绪时注入（给 `Plugin` 增加可选 `onAcpReady(server, transport)` 之类的 SPI 钩子），并补"句柄已注入"的测试；② 若暂不投入 → 市场条目降级（`status=deprecated` 或从清单移除）+ 文档标注"暂不可用"，**不要让用户装了之后拿到一条死命令**。

### P0-3 市场产物落后源码：10/12，其中 4 个含未下发的缺陷修复

- 逐模块比对（模块末次提交 vs 市场 jar 所在 tag）：**10 个模块**在 tag 之后有新提交；`browser-search`、`connector-yinxiang` 无变更。
- 其中含**真实缺陷修复**未下发：`plugin-comfy`（2 处缺陷 + 12 例测试）、`plugin-browser-push`（accept 顺序缺陷）、`plugin-connector-trae`（trea→trae 正名）、`plugin-render`（文案去已退役 `fs.cp`）。
- **建议**：尽快做一次**插件级发布 `plugins-v0.7.0`**（`package-plugins.ps1` 全量重打包 12 个产物 → tag → 双平台 Release → 回写 `plugins.json` 的 `downloadUrl/mirrorUrl/checksum/size/version`，并把顶层 `version`/`updated` 递增）。验收标准：`validate-plugins.ps1` PASS + 12 条 remote 的 checksum/size 与本地产物逐一相符。

### P0-4 索引元数据与校验盲区

- `plugins.json` 顶层 `version: 9` / `updated: "2026-09-01"`**在本轮条目变更后未递增**（文件 mtime 已是 2026-09-10）；壳侧记录 `lastRemoteUpdated` 用于识别市场变化 → 元数据不动会削弱"是否变更"的判断。
- `checksum` 只做格式校验，`size`、`mirrorUrl` 完全不校验；远端产物无任何自动核验。
- **建议**：① 改条目即递增 `version`/`updated`（写进插件发布 SOP）；② 给 `validate-plugins.ps1` 加 `-VerifyRemote` 开关（经 Gitee 镜像下载后核 sha256 + size，12 个产物 ≈ 3 MB，可接受），至少发布前手工跑一次。本轮已用等价的一次性脚本实测通过（见 §三.6），说明"加这道闸门"是可行且低成本的。

### P1 清单

| 项 | 证据 | 建议 |
|---|---|---|
| 凭据可能静默丢失 | `ConnectorConfigStore.kt:79` `write()` 整体 `catch(_:Exception){}`；`:55` `read()` 失败静默回退空配置 → 上层仍回"已保存连接配置" | 写失败必须上抛并让命令返回失败；读失败与"未配置"区分 |
| 4 处 HTTP 超时缺失 | `ComfyPlugin.kt:39`（无任何超时）、`RenderPlugin.kt:45`（无超时）、`BrowserSearchPlugin.kt:71-73`（无 connect/socket）、`TranslatePlugin.kt:53-55`（无 read） | 统一补齐（仓储内其它插件已有正确范例可抄） |
| SSRF 路径校验边界 | `BrowserSearchPlugin.kt:275` 无分隔符 `startsWith` | `canonicalPath` + 分隔符边界 + 补测试 |
| 测试缺口（按断言实测） | `error-report` 6 命令 + 上传器 + WiFi 监视器 0 覆盖；`SshTransport`（183 行）0 直接测试；`qwenpaw` REST/ACP 0 覆盖；`render` 三后端提交 0 覆盖；`browser-search` 重定向复检 0 覆盖 | 按"抽纯逻辑再测"的项目惯例分批补，优先 `error-report` 与 `SshTransport` |
| 文档漂移 | connectors `README.md:80` 写"全部 14 个模块"而 `:49` 写"13 个 AAR"；`CONTRIBUTING.md:3` 写"8 个普通 + 5 个连接器"（实际 6+6+1）；`.gitignore` 注释写 `package-connectors.ps1`（实际 `package-plugins.ps1`）；`plugins.json` 描述含不存在的 `error.bridge` | 一次性订正 |
| `minCoreVersion` 双源不一致 | 清单对 6 个普通插件统一写 `0.8.0`，源码分别为 `0.2.0`/`0.2.3`；门禁只读清单值 | 明确"清单为准"，或发布时用脚本从源码回写，避免两套数字 |
| 无构建/测试 CI | connectors 仅 `.github/workflows/gitee-sync.yml`（GitHub→Gitee 镜像）；`build.gradle.kts:17` 注释里"升级内核后验证全部模块编译"的约定**无自动化承接** | 加一个最小 workflow（`testDebugUnitTest`）—— 注意依赖 JitPack 内核坐标，需评估 CI 网络可行性 |

### P2 清单

| 项 | 说明 |
|---|---|
| 内核坐标固定 `v0.35.5` | `mengpaw-connectors/build.gradle.kts:19`；需明确 bump 节奏，否则新内核 API 用不上 |
| 三方一致性校验 | `scripts/plugin-class.txt`（主类清单）、`settings.gradle.kts`（模块）、`plugins.json`（条目）目前靠人工对齐，建议脚本互校 |
| `trae` vs `trae-ide` | 模块/id/命令 ns 用 `trae`，README 与 `frameworkName` 用 `trae-ide` —— 若属有意（产品名 vs 命名空间）建议在 README 写明，否则统一 |
| 连接器 fat jar 体积 | 6 个连接器各内嵌 jsch/okhttp/okio ≈ 580–600 KB/个 |
| 明文 `ws://` / `http://` | openclaw / qwenpaw REST 无 TLS 选项，建议至少文档标注"仅限可信内网" |
| 内核 `"deprecated"` 字面量 | 历史 CHANGELOG 曾写 `depr`，而代码只认 `"deprecated"` —— 建议抽常量并在清单校验里锁死枚举 |

---

## 六、待决策项（需用户拍板）

1. **`plugin-comfy`：重写还是退役？** 重写 = 按 ComfyUI API 的 `{nodeId: {class_type, inputs}}` + 数组 link 重建工作流模型（模块级改造 + 测试钉住项全部翻转）；退役 = 从模块/清单/发行链路移除，保留 git 历史。
2. **`connector-qwenpaw` 的 SSH ACP 通道**：保留（继续标"实验性"）/ 砍掉（只留 REST）/ 投入完善（补全链路测试 + 文档）。
3. **P0-1 选方案 A 还是 B**（推荐 A：改文档与清单，零代码风险）。
4. **签发下次插件级发布版本号**（建议 `plugins-v0.7.0`，全量 12 产物重打包）。

---

## 七、建议行动顺序

| 顺序 | 动作 | 产出/验收 |
|---|---|---|
| 1 | 修 P0-1（改清单 + 开发指南 + `AcpServer` 指引）+ 给 `validate-plugins.ps1` 加"命名空间前缀一致"校验 | 校验脚本 PASS；三处文档不再出现不存在的命令名 |
| 2 | 决策 P0-2（接线或降级 `browser-push`） | 要么有真实发送路径 + 测试，要么市场明确标注不可用 |
| 3 | 处理 P1 中的"凭据静默丢失" + 4 处超时 + SSRF 边界 | 对应单测补齐，`testDebugUnitTest` 全绿 |
| 4 | 插件级发布 `plugins-v0.7.0`（全量重打包）并回写清单 | `validate-plugins.ps1` PASS + `-VerifyRemote` 12/12 校验和相符 + 索引 `version/updated` 递增 |
| 5 | 决策 §六 的 comfy / qwenpaw ACP，再按结论重写或退役 | 各自的模块级变更 + 文档同步 |
| 6 | P1 测试缺口分批补齐（先 `error-report`、`SshTransport`） | 用例数从 158 继续上升，覆盖矩阵同步 |

---

## 附录 A：复核本文档的命令

```powershell
# 清单条目与状态分布
(Get-Content D:\MengPaw\plugins.json -Raw | ConvertFrom-Json).plugins | Group-Object status | Select-Object Name,Count

# 模块数与用例数（connectors 仓库）
(Select-String -Path D:\MengPaw-connectors\settings.gradle.kts -Pattern '^include').Count
(Get-ChildItem D:\MengPaw-connectors -Recurse -Filter *.kt | Where-Object { $_.FullName -match '\\src\\test\\' } |
  Select-String -Pattern '^\s*@Test' | Measure-Object).Count

# 命令名一致性（宿主侧）
pwsh D:\MengPaw\scripts\validate-plugins.ps1

# 市场产物可用性（GitHub 被阻断时用 Gitee 镜像）
$p = (Get-Content D:\MengPaw\plugins.json -Raw | ConvertFrom-Json).plugins | Where-Object status -eq 'remote'
$p | ForEach-Object { $r = Invoke-WebRequest $_.mirrorUrl -Method Head -TimeoutSec 60; "$($_.id) $($r.StatusCode)" }
```

## 附录 B：打包与发布约定（外置插件）

- 打包脚本：`mengpaw-connectors/scripts/package-plugins.ps1` → `releases/plugins/`
  - 普通插件：`plugin-<name>-release.jar`
  - 连接器（含 yinxiang）：`plugin-<name>-plugin.jar`（fat dex，含 `plugin-connector-common` + jsch/okhttp/okio，yinxiang 另含 evernote-api/jsoup）
- 产物必须同时含 **`classes.dex`** 与 **`META-INF/plugin-class`**（主类取自 `scripts/plugin-class.txt`），否则宿主拒绝加载
- 宿主内核坐标：`com.github.WowBlueStudio.MengPaw:mengpaw-kernel:v0.35.5`（JitPack，点连接）
- 发布后用 `plugins.json` 回写 4 个字段：`downloadUrl`（GitHub）、`mirrorUrl`（Gitee）、`checksum`（`sha256:` 前缀）、`size`
- 版本号唯一来源是 `PluginMetadata.version`；connectors 的 `gradle.properties` 与各模块 `build.gradle.kts` **均无版本字段**

---

> 快照日期：2026-09-10 ｜ 对应 connectors 提交 `0078446`、宿主提交 `d074002e` ｜ 下次更新时机：任一外置插件模块发生变更或插件级发布后
