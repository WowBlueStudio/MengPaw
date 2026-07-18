# 版权声明凭证

## 软件作品信息

| 项目 | 内容 |
|------|------|
| **软件名称（中文）** | 檬爪 |
| **软件名称（英文）** | MengPaw |
| **版本号** | v0.2.0-alpha |
| **作品类型** | 计算机软件 — Android 操作系统框架（AI Agent 平台） |
| **开发语言** | Kotlin |
| **源代码行数** | 14,688 行 |
| **源代码文件数** | 106 个 |
| **首次发布日期** | 2026 年 7 月 12 日 |
| **最近更新日期** | 2026 年 7 月 18 日 |
| **开源许可证** | GNU Affero General Public License v3.0 (AGPL-3.0) |
| **托管平台** | GitHub（待注册） |

## 著作权人信息

| 项目 | 内容 |
|------|------|
| **著作权人（中文）** | 深圳哇蓝文化科技有限公司 |
| **著作权人（英文）** | ShenZhen wowblue culture and technology CO.,LTD. |
| **著作权取得方式** | 原始取得（独立开发） |
| **著作权起始年份** | 2026 年 |
| **联系邮箱** | 1138018324@qq.com |

## 著作权声明

根据《中华人民共和国著作权法》第二条、第十一条，本软件作品「檬爪（MengPaw）」由深圳哇蓝文化科技有限公司独立开发，自创作完成之日起依法享有著作权。

本软件以 GNU Affero General Public License v3.0（AGPL-3.0）授权发布。该许可证要求：任何使用者若修改本软件并作为网络服务运行，必须公开其修改版本的完整源代码。

本软件所有源代码文件（共 106 个 .kt/.kts 文件）均包含以下 SPDX 标识符：

```
// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later
```

完整许可证文本见项目根目录 `LICENSE` 文件（GNU 官方 AGPL-3.0 英文原版，674 行全文，未经删改）。

## 项目模块清单

| 模块 | 类型 | 说明 |
|------|------|------|
| mengpaw-core | Library AAR | 微内核：CLI 引擎、安全沙箱、LLM 接口、插件框架、MCP/ACP 协议、Agent 引擎 |
| mengpaw-shell | APK | 主应用：聊天 UI、设置、插件市场、自适应布局 |
| mengpaw-browser | APK | 独立浏览器：标签管理、广告拦截、页面翻译 |
| mengpaw-design-system | Library AAR | Arco Design + Material3 主题系统 |
| plugin-fs | 插件 | 文件系统操作（8 命令） |
| plugin-net | 插件 | HTTP 网络请求（3 命令） |
| plugin-memory | 插件 | 记忆系统（6 命令） |
| plugin-skill | 插件 | 技能系统（4 命令） |
| plugin-self | 插件 | Agent 自省（4 命令） |
| plugin-clipboard | 插件 | 剪贴板操作（3 命令） |
| plugin-notification | 插件 | 通知管理（3 命令） |
| plugin-pad | 插件 | 悬浮窗（Compose UI） |
| plugin-tavily | 插件 | AI 搜索 |
| plugin-hermes | 插件 | 多智能体协作 |
| plugin-workflow | 插件 | DAG 工作流引擎 |
| plugin-incubator | 插件 | 子 Agent 孵化器 |
| plugin-render | 插件 | 图像生成（Replicate/Stability/DALL-E） |
| plugin-comfy | 插件 | ComfyUI 集成 |
| plugin-translate | 插件 | Google 翻译（130+ 语言） |

## 开发工具声明

本项目由 AI 辅助开发，使用的工具链如下：

| 阶段 | 时间 | 编排工具 | 主力模型 |
|------|------|---------|---------|
| 早期 | 2026-07-12 ~ 2026-07-15 | Reasonix | DeepSeek Flash |
| 中期 | 2026-07-16 ~ 至今 | Claude Code | DeepSeek Pro |

模型推理通过 DeepSeek API（api.deepseek.com），配置文件见 `reasonix.toml`。

## 签章

著作权人（盖章）：____________________________

法定代表人/授权代表（签字）：_________________

日期：________ 年 ________ 月 ________ 日

---

*本凭证由 Claude Code 根据项目实际情况生成，供著作权人留存备案。*
