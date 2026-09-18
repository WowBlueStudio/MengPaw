---
name: filesystem
description: 文件系统操作手册 — 读写文件、目录管理、内容搜索 (Linux 命令直通)。触发词：「读写文件」「查找文件」「目录管理」「文件操作」
enabled: true
category: system
source: core
---
# 文件系统 — Linux 命令直通

> 文件读写**不需要任何插件**：非点分命令直接走 Linux 命令通道（Android mksh + toybox）。
> 工作目录 = 你的 Agent 工作区根（相对路径即工作区内路径）。

## 命令对照

| 操作 | 命令 | 示例 |
|------|------|------|
| 列出目录 | `ls` | `ls -l /path/dir` |
| 读取文件 | `cat` | `cat /path/file.md` |
| 取片段 | `head` / `tail` / `sed` | `head -50 f.md` / `sed -n '10,30p' f.md` |
| 写入/覆盖 | 重定向 `>` | `printf '内容\n' > f.md` |
| 追加 | 重定向 `>>` | `printf '补充\n' >> f.md` |
| 删除 | `rm` | `rm /path/file.md`（⚠️ 弹窗确认，不可恢复） |
| 建目录 | `mkdir` | `mkdir -p /path/newdir` |
| 复制 | `cp` | `cp /a.md /b.md` |
| 移动/重命名 | `mv` | `mv /old.md /new.md` |
| 文件信息 | `stat` / `ls -l` | `stat /file.md` |
| 搜索内容 | `grep` | `grep -n '关键词' f.md` / `grep -rn 'TODO' .` |
| 按名查找 | `find` | `find . -name '*.md'` |
| 磁盘占用 | `du` / `df` | `du -sh .` |
| 校验和 | `md5sum` | `md5sum f.md` |

## 参数写法（重要）

- 选项写在**文件参数之前**：`grep -n '关键词' f.md`（写反了结果会错）
- 表达式含 `$` / 反引号时用**单引号**包裹（双引号内 `$` 会被安全策略拦截）
- 路径含空格用双引号包裹整个路径
- 多行内容用 `printf '行1\n行2\n' > f.md`；或先写临时文件再 `cat` 合并
- 禁止：`;` `&&` `||` `&` `$()` 反引号 换行多命令（安全策略拦截）；管道 `|` 与重定向 `>` 可用

## 写后验证（框架会提示）

重定向写成功后，框架自动附「请 cat 读回验证」提示。规则：**声称写入成功必须引用 `cat` 读回的真实内容**，禁止凭记忆断言。

## 安全

- `rm` 是最高危操作：删除前确认路径正确，永远不用 `rm -rf /`（BLOCK）
- 写 `/system` `/etc` `/dev` 等系统路径被拦截；`配置/`、`插件仓库/` 为受保护目录
- 需要设备级最高权限时用 `root.fs.*`（需先安装并激活 Root 插件）

## 常用组合

```
ls                                    → 看当前目录有什么
cat notes.md                          → 读文件
grep -n 'TODO' notes.md               → 定位关键词所在行
find . -name '*.md'                   → 找所有 md 文件
printf '# 标题\n内容\n' > out.md       → 写文件
du -sh .                              → 工作区占用
```
