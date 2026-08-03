## ⚖️ 版权让渡声明（提交即同意）

> 提交本 PR 即表示：**你是所提交代码的原创作者，无第三方权利冲突；你同意将贡献代码的版权让渡给深圳哇蓝文化科技有限公司，并按仓库双许可（[AGPL-3.0](LICENSE) 或 [商业许可](COMMERCIAL-LICENSE.md)）分发。**

## 贡献范围（建议先看）

| 范围 | 说明 |
|------|------|
| **插件类（推荐）** | `plugins/` 下新插件或插件改进 — 独立模块、只依赖 kernel、风险低 |
| **文档 / 翻译 / 脚本** | docs、README、scripts |
| **内核 / 核心（严格评审）** | `mengpaw-kernel`、`mengpaw-core` — 维护者评审最严格，建议先在 [Issues](https://github.com/WowBlueStudio/MengPaw/issues) 讨论方案再动手 |

## 变更说明

<!-- 简述这个 PR 改了什么、为什么、怎么验证的 -->

## 检查清单

- [ ] 编译测试通过：`./gradlew :mengpaw-kernel:test`
- [ ] 所有 `.kt` / `.kts` 带 SPDX 版权头（格式见 [CONTRIBUTING.md](CONTRIBUTING.md)）
- [ ] 无 `!!` 强制解包；文件 IO 均已 try/catch
- [ ] 新命令已声明 `metadata.commands`；新端口已核对 `Ports.kt`（9876 内核保留、9881 MCP 网关）
- [ ] UI 文案走 `Strings.kt` 本地化，未硬编码
- [ ] 影响发布的功能变更已写 `CHANGELOG.md`
