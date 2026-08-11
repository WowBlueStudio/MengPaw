# Root 指南

Root 让 Agent 获得设备最高权限（`su`），可执行普通应用无法完成的操作：系统文件读写、应用冻结/卸载、系统属性修改、备份恢复等。MengPaw 内置 root-plugin 提供这些能力。

## 前提

- 设备已解锁并完成 Root（Magisk / KernelSU 等），`root.status` 可检测当前状态
- 安装 root-plugin：插件市场搜索 `root` → 安装 → 激活
- 所有 root 操作都会写入审计日志（`root.audit`），高风险操作每次弹窗确认

## 常用命令

- `root.status` — 检测 Root 环境与 su 可用性
- `root.exec <命令>` — 以 root 执行单条命令（输出截断 4000 字符，完整输出见 `/sdcard/root_out.txt`）
- `root.apps.list` / `root.apps.freeze <包名>` — 应用列表 / 冻结（卸载前可先冻结观察）
- `root.fs.cat/write` — 系统文件查看与写入
- `root.system.hosts` — 管理 hosts（广告/域名屏蔽）
- `root.backup.save/restore` — 应用数据备份与恢复

## 风险提示

- Root 会降低系统安全边界：恶意应用或提示词注入获得 root 权限后果严重，**必须保持安全分级拦截开启**
- 冻结系统应用可能导致设备异常，先冻结用户应用，出问题立即 `root.apps.unfreeze` 解冻
- 修改 `/system` 前先备份；部分 OEM 设备（荣耀/vivo 等）对 su 有限制，失败属正常现象
- 不 root 也能用 MengPaw 大部分功能；仅在确需系统级操作时启用
