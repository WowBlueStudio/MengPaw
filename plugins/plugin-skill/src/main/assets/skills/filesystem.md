---
name: filesystem
description: 文件系统操作命令参考 — 读写文件、目录管理、内容搜索
enabled: true
category: system
---
# fs — 文件系统 (10命令)

| 命令 | 说明 | 示例 |
|------|------|------|
| `fs.ls [path]` | 列出目录 | `fs.ls /path/dir` |
| `fs.cat <path>` | 读取文件 | `fs.cat /path/file.md` |
| `fs.write <path> <content>` | 覆盖写入 | `fs.write /f.md 新内容` |
| `fs.rm <path>` | 删除文件 | `fs.rm /path/file.md` |
| `fs.mkdir <path>` | 创建目录 | `fs.mkdir /newdir` |
| `fs.cp <src> <dst>` | 复制 | `fs.cp /a.md /b.md` |
| `fs.mv <src> <dst>` | 移动/重命名 | `fs.mv /old.md /new.md` |
| `fs.stat <path>` | 文件信息 | `fs.stat /file.md` |
| `fs.grep <pattern> [--regex] [-i] [--context N]` | 搜索内容 | `fs.grep TODO --regex -i` |
| `fs.glob <pattern>` | 匹配文件 | `fs.glob **/*.kt` |

## 常用
```
fs.ls                    → 看当前目录有什么
fs.glob **/*.md         → 找所有MD文件
fs.grep 关键词 --regex    → 搜索文件内容
```

⚠ `fs.rm`不可恢复，删除前确认。
