---
name: termux
description: Termux 桥 — 通过 termux.* 命令在 Termux→ubuntu→miniconda 环境执行命令与 Python。触发词：「跑脚本」「termux」「脚本执行」「ubuntu」「conda」「python 环境」
enabled: true
category: system
source: core
---

# Termux 桥 (Termux → ubuntu → miniconda → Python)

通过 `termux.*` 插件命令在 Android 设备上执行 shell 命令和 Python 代码——无需 root。
插件自动封装"登录 ubuntu + conda 环境 + 输出回传", 一次命令完成 写→执行→读回→清理。

## 前置条件 (先运行 termux.status)

1. Termux 已安装, 且 `~/.termux/termux.properties` 含 `allow-external-apps=true`
   (写入后**完全重启 Termux**)
2. Termux 执行 `termux-setup-storage` 授予存储权限 (交换目录 /sdcard/MengPaw/termux)
3. Termux 安装 proot-distro 并装好 ubuntu:
   `pkg install proot-distro && proot-distro install ubuntu`
4. ubuntu 内安装 miniconda (可选但推荐, Python 环境所在层)
5. MengPaw 已授予「所有文件访问」权限 (设置→应用→MengPaw)

`termux.status` 会逐层探测并给出每层的安装提示。

## 命令

### termux.status [--refresh]

逐层探测 Termux → ubuntu → miniconda → python 的状态与可用 conda 环境。
环境发生变化后加 `--refresh` 强制重测 (默认 30s 缓存)。

### termux.python [--env <环境名>] <Python 代码>

在 conda 环境内执行 Python 并**直接回传输出**。默认用第一个可用环境, 可用
`--env` 指定 (先 termux.status 查看)。

示例:
```
termux.python "import sys; print(sys.version)"
termux.python --env py310 "import numpy; print(numpy.__version__)"
termux.python "import pandas as pd; print(pd.__version__)"
```

单行代码用 `;` 串联; 复杂逻辑建议先 `termux.ubuntu` 把代码写入文件再执行。

### termux.ubuntu [--env <环境名>] <命令>

登录 ubuntu (conda 环境内) 执行 shell 命令并直接回传输出。

示例:
```
termux.ubuntu "pip list"
termux.ubuntu --env py310 "python -m pip --version"
termux.ubuntu "ls /root/miniconda3/envs"
```

## 安全说明

- 代码/命令会经过**高危规则审查**: rm 删除、chmod/chown、su/sudo 提权、覆盖系统路径、
  格式化分区等会被拦截或弹窗确认 (worker 等无交互环境直接拒绝)
- **完整 shell 语法可用** (`;` `&&` `$` 变量、反引号): 内容由 ubuntu 直接执行,
  没有本地 shell 拼接注入面 — 与直接 Linux 命令通道不同
- 输出上限 100KB; 超时默认 120s (`--timeout <秒>` 可调 5-300s)

## 注意

- 不要用 `am startservice … com.termux.RUN_COMMAND_ARGUMENTS` 手拼 Termux 命令:
  Linux 通道的元字符/前缀沙箱会拦截 (如 `python3 -c`、`$`、`&&`), 且 `am --esa`
  按逗号切分参数数组, 代码里的逗号会把命令切碎
- 执行环境是多层嵌套: MengPaw → Termux → ubuntu (proot-distro) → miniconda → Python,
  首次调用可能较慢 (proot 启动 + conda 环境加载), 属正常现象
