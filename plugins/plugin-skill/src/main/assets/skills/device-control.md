---
name: device-control
description: 设备操控能力 — 悬浮窗/日历/Root/跨应用/脚本执行。触发词：「设备操控」「悬浮窗」「日历操作」「root 操作」
enabled: true
category: system
source: core
---

# 设备操控

## 悬浮窗
```
sys.overlay.show <文本> [--x 100] [--y 200] [--size 14] [--color #FFF]
sys.overlay.update <文本>
sys.overlay.hide
```
需要 SYSTEM_ALERT_WINDOW 权限（设置→应用→在其他应用上层显示）。
可指定位置、大小、颜色。单例管理。

## 日历
```
sys.calendar.calendars                 # 列出可用日历
sys.calendar.add "标题" "时间" [--end] [--desc] [--cal ID]
sys.calendar.list [--days 7]
sys.calendar.delete <ID>
```
需要 READ/WRITE_CALENDAR 权限。自动检测可写入日历。
时间格式: yyyy-MM-dd HH:mm 或 Unix 毫秒。

## Root 权限
```
root.status                          # 检测 su/Magisk/SELinux
root.exec <命令>                      # su -c 执行（审计日志）
root.apps.list/freeze/unfreeze/uninstall/data
root.fs.ls/cat/write/stat            # 完整文件系统
root.system.props/setprop/hosts      # 系统修改
root.backup.list/save/restore        # 备份恢复
root.audit [--last 20]               # 审计日志
```
⚠️ 最高权限。危险命令自动拦截（rm -rf /, dd, mkfs）。
所有命令记录到 root_audit.log。

## 跨应用唤醒
```
sys.app.launch <pkg>                  # 启动应用
sys.intent.open <url|pkg>            # 打开网页/跳转设置
sys.intent.share <text>               # 弹出分享面板
sys.intent.view <file>                # 用关联应用打开文件
```

## 脚本执行
```
skill.run termux   # Termux 桥接指南
```
