#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
将构建产物信息 (checksum/size/changelog) 回写到 plugins.json.
由 scripts/build-plugins.ps1 调用 — 用 Python 处理 JSON, 规避 PowerShell 5.1
ConvertTo-Json 的中文 unicode-escape 转义与格式化缺陷.

用法:
    python update-plugins-json.py <repo-root> <artifacts.json> <version>

artifacts.json 由 build-plugins.ps1 生成:
    [{"id": "fs-plugin", "module": "plugin-fs", "file": "plugin-fs-0.21.0-release.aar",
      "sha256": "...", "sizeBytes": 12345}, ...]

行为:
    - 对 artifacts 中每个 id, 匹配 plugins.json 的 plugins[].id
    - remote/embedded 条目: 写 checksum (sha256: 前缀) + size + changelog
    - builtin 条目: 不写 downloadUrl (内置无需下载), 但更新 checksum/size 作参考
    - 更新顶层 updated 字段为当前日期
    - 保留未构建插件的一切字段不变
"""

import json
import re
import sys
from datetime import date


# 模块名 → plugins.json 插件 id（与 PluginManager.namespaceFor 无关；特例映射）
MODULE_TO_ID = {
    "plugin-hermes": "tribe-plugin",
    "plugin-agent-tools": "tools-plugin",
}


def module_to_id(module: str) -> str:
    if module in MODULE_TO_ID:
        return MODULE_TO_ID[module]
    return module.removeprefix("plugin-") + "-plugin"


def extract_changelog(changelog_path: str, version: str) -> str:
    """从 CHANGELOG.md 提取当前版本的 release notes (前 500 字符)."""
    try:
        with open(changelog_path, encoding="utf-8") as f:
            text = f.read()
    except OSError:
        return ""
    # 匹配 "## [x.y.z] - date" 或 "## x.y.z" 之后的段落, 到下一个 "## " 为止
    m = re.search(
        rf"^## .*?{re.escape(version)}.*?\n(.*?)(?=^## )",
        text, re.MULTILINE | re.DOTALL)
    if not m:
        return ""
    notes = m.group(1).strip()
    return notes[:500]


def main():
    if len(sys.argv) < 4:
        print("用法: python update-plugins-json.py <repo-root> <artifacts.json> <version>")
        sys.exit(1)
    root, artifacts_path, version = sys.argv[1], sys.argv[2], sys.argv[3]

    plugins_json_path = f"{root}/plugins.json"
    changelog_path = f"{root}/CHANGELOG.md"

    # PS 5.1 Set-Content -Encoding UTF8 会写 BOM → 用 utf-8-sig 容错
    with open(plugins_json_path, encoding="utf-8-sig") as f:
        data = json.load(f)
    with open(artifacts_path, encoding="utf-8-sig") as f:
        artifacts = json.load(f)

    changelog = extract_changelog(changelog_path, version)
    by_id = {p["id"]: p for p in data.get("plugins", [])}
    updated = 0

    for a in artifacts:
        # 优先用模块名映射 (PS 的 Id 派生无特例映射, 不可靠)
        pid = module_to_id(a.get("Module", "")) or a.get("id") or a.get("Id")
        entry = by_id.get(pid)
        if not entry:
            print(f"  {pid} (module={a.get('Module', '?')}): plugins.json 无此条目 (skip)")
            continue
        sha = a.get("sha256") or a.get("Sha256")
        size = a.get("sizeBytes") or a.get("SizeBytes")
        entry["checksum"] = f"sha256:{sha}"
        entry["size"] = size
        if changelog:
            entry["changelog"] = changelog
        entry["version"] = version
        status = entry.get("status", "remote")
        if status in ("remote", "embedded"):
            # downloadUrl 由发布者在 plugins.json 中手写 (含 tag), 脚本不猜测域名
            pass
        updated += 1
        print(f"  {pid}: checksum/size/changelog written (status={status})")

    data["updated"] = date.today().isoformat()

    with open(plugins_json_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"plugins.json updated ({updated} entries, updated={data['updated']})")


if __name__ == "__main__":
    main()
