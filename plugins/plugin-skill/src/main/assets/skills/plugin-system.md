---
name: plugin-system
description: 插件管理命令参考 — 发现/安装/启用插件来扩展Agent能力
enabled: true
category: system
---
# plugin — 插件管理 (11命令)

## 命令
| 命令 | 说明 |
|------|------|
| `plugin.marketplace [--refresh]` | 拉取市场索引 |
| `plugin.search <query>` | 搜索插件 |
| `plugin.install <id>` | 下载+验证+安装+激活 |
| `plugin.uninstall <id>` | 卸载 |
| `plugin.list` | 已安装列表 |
| `plugin.info <id>` | 插件详情(版本/命令/权限) |
| `plugin.enable <id>` | 启用 |
| `plugin.disable <id>` | 停用 |
| `plugin.update <id>` | 检查更新 |
| `plugin.upgrade --all` | 检查全部更新 |
| `plugin.auto <wake|sleep|status|sleep-idle>` | 电源管理 |

## 典型安装流程
```
plugin.marketplace --refresh   → 拉市场
plugin.search 搜索              → 找插件
plugin.install <插件ID>         → 下载安装
plugin.list                    → 确认激活
self.tools <新命名空间>          → 看新命令
```

## 学了插件怎么用
```
skill.run plugin-index   → 找对应说明书
skill.run <说明书名>       → 阅读用法
```
