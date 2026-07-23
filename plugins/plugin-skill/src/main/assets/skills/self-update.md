---
name: self-update
description: MengPaw自更新 — 检查/下载/安装新版本
enabled: true
category: system
---
# update — 自更新 (4命令)

| 命令 | 说明 |
|------|------|
| `update.check` | 检查GitHub是否有新版本 |
| `update.download` | 下载最新APK |
| `update.install` | 安装已下载的APK |
| `update.auto` | 自动检查+下载+安装（仅WiFi，每小时一次） |

## 典型流程
```
update.check     → 比较版本
update.download  → 下载APK
update.install   → 安装（需REQUEST_INSTALL_PACKAGES权限）
```
