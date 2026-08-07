# MengPaw 项目守则(自 v0.34.1 起由 Codex 接手维护)

> 本文件承接 2026-08-07 前 Claude Code 积累的全部项目经验(原 `.claude/projects/D--MengPaw/memory/` 40+ 条记忆)。
> 所有回复、代码注释、报错解释一律使用简体中文。

## 一、知识入口(每会话先读)

- **开发文档** `MengPaw-Development-Guide.md` 是项目唯一权威知识中心,代码变更必须同步更新对应章节及其版本/日期
- **文档索引** `docs/INDEX.md` 按场景列出技术文档,按需选读,不要一次全量加载
- 用户说"开发文档"即指 `MengPaw-Development-Guide.md`

## 二、红线(违反 = 严重事故)

1. **未经用户明确指令,禁止任何版本发布**(git tag / `gh release` / APK 上传 / 远程推送),包括修复任务完成后自动发版
2. **禁止遥控真机做 UI 测试**(ADB 点击/截图验证)。构建+提交即可,APK 交给用户自测;仅 `adb install` 交付可做
3. **不可编译的源码禁止推送**;APK 构建失败必须修复后重新构建,有问题的 Release 产物必须撤回
4. **API Key 是唯一安全禁区**:密钥不得进日志、审计输出、用户可见文本、代码仓库
5. **新建 `.kt`/`.kts` 必须带 SPDX 双许可版权头**(见下方模板),缺失直接拒
6. **禁止 `!!` 强制解包**;文件 IO 必须 try/catch;单文件 ≤400 行

```kotlin
// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
```

## 三、设计定案(勿"修正")

- **内置插件无版本号**:内置插件随 shell 更新,版本无语义,不展示不对照;版本号仅对远程插件(plugins.json + `plugins-v*` tag)有意义
- **伪人模式 / Truman Show**:SCHEDULE 触发器命名定案,严禁改回"随机对话"或"TrueMen/TrueMan/单独 Truman",不加解释说明;工作区文件 `trumanshow.md`
- **单轨记忆**:`{agent}/memory/` 三轨持有全部记忆,孪生=同步整个 `{agent}/` 工作区文档(排除 CLI.md/inbox/dialog/memory/backup),无独立账本
- **命令核对以源码注册处为准**:`BuiltinCommandIndex.kt`/`CommandRegistry.kt`/`Plugin.commands`/`McpTool(` 构造,不凭 grep 字符串印象下结论
- **端口单一事实源**:`Ports.kt`(9876 内核保留、9881 MCP 网关),新端口先查
- **插件仅依赖 kernel**:plugins/ 同级,不得依赖其他插件或 Android 模块;命令键用短名,命名空间由插件 id 经 `pluginNamespaceFor` 推导

## 四、工作方式

- **修改即确认**:改动完成并通过验证(测试+编译)后立即 commit,不等用户说"提交";按功能粒度拆 commit,message 用 `feat:`/`fix:`/`chore:`/`release:` 前缀
- **上下文精简**:塞任何东西进提示词/文档前问"真值得消耗注意力吗";不回溯历史、不双重注入、中期记忆不注入提示词
- **双许可**:主仓库 `AGPL-3.0-or-later OR LicenseRef-Commercial`;外置连接器仓库 `mengpaw-connectors` 用 MIT,其 JitPack 内核坐标是 `com.github.WowBlueStudio.MengPaw:mengpaw-kernel:<tag>`(点连接,非冒号)
- **PR 政策**:主仓库开放 PR(2026-08-03 起),插件/文档类优先,提交即版权让渡;评审走 `mengpaw-pr-review` skill,合并由用户拍板

## 五、环境与构建铁律

- Android SDK `C:\Users\a1138\Android\Sdk`;ADB `C:\platform-tools\adb.exe`(不在 PATH);JDK 17;Gradle wrapper 8.12
- **管道吞退出码**:`./gradlew ... 2>&1 | tail -N` 的退出码恒 0,判成功用 `echo EXIT=$?` 或直接查产物文件
- **BOM 铁律**:创建 .kt/.kts 禁用 PowerShell `Set-Content`(加 BOM 致整包 Unresolved reference),用 apply_patch/Git Bash
- **Gradle 任务勿并行**(clean 与编译互踩);browser 构建 ~9 分钟,shell ~2 分钟;不要手删 build 目录,用 `./gradlew clean`
- **签名验证用 apksigner**(keytool 只验 v1 会误报):`apksigner.bat verify --print-certs` 期望 `CN=MengPaw, OU=Studio, O=WowBlue`
- **ADB 无线端口每次配对都变**:用户给的通常是配对端口,连接端口用 `adb mdns services` 查;荣耀平板安装必弹 ICP 警告,由用户点"继续安装",勿再试静默方案
- **发布流程**:`mengpaw-release` skill(版本号/端口必须询问用户;browser 无变更不构建;双远端 push;gh release 必须附 APK;发布前 dropbox 巡检)
- **崩溃排查**:设备"启动即闪退"且清数据仍崩 → 先 `adb shell dumpsys dropbox --print`(crash buffer 在荣耀/vivo 上会丢),再 logcat

## 六、测试纪律

- 改核心逻辑必须补/跑测试:`:mengpaw-kernel:test` 全绿(409+ 用例)
- 改插件跑对应 `:plugin-*:testDebugUnitTest`
- 新增命令必须四源同步:BuiltinCommandIndex / CLI.md / 系统提示词常用命令 / 开发指南 §5.1(有 IndexCoverageTest / PromptGhostReferenceTest 锁死)
- 发布前全量跑一次测试拿真实数字,更新开发指南 §3.7 快照
- 遇到新坑:记入 `docs/lessons.md`(项目惯例),发布类坑同步更新 `mengpaw-release` skill

