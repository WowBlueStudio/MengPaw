#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
"""
提示词优化前后真实 LLM 对比 — 验证「言简意赅」middleware 变换不干扰生成。

用法:
  python scripts/compare_prompts.py --before scripts/prompt_compare/zh_before.txt --after scripts/prompt_compare/zh_after.txt
  (API key 从环境变量 MENGPAW_API_KEY 或 --api-key 提供)

指标:
  - Action: 出现率 (工具必需类核心指标, 闸门 80% — 低于则提示回退)
  - Thought: / Final Answer: 出现率 (去样板化是否生效)
  - Markdown 标记密度 (** / # 标题 / - 列表)
  - 平均生成长度 + 简化 parse 判定 (哪些输出会被内核当最终答案)
"""
import argparse
import json
import os
import re
import sys
import urllib.request

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions"

# 3 工具必需 + 2 纯回答 (Markdown 倾向探测)
MESSAGES = [
    ("工具必需", "查看设备状态"),
    ("工具必需", "帮我安排明天下午 3 点的会议"),
    ("工具必需", "列出下载目录的文件"),
    ("纯回答", "MengPaw 是什么？"),
    ("纯回答", "写一首关于猫的短诗"),
]

RE_ACTION = re.compile(r"(?i)action\s*[:：]")
RE_THOUGHT = re.compile(r"(?i)thought\s*[:：]")
RE_FINAL = re.compile(r"(?i)final answer\s*[:：]")
RE_HEADING = re.compile(r"^\s*#{1,6}\s", re.MULTILINE)
RE_BULLET = re.compile(r"^\s*[-*]\s", re.MULTILINE)


def call_llm(api_key, system_prompt, user_msg, model, endpoint):
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_msg},
        ],
        "temperature": 0.7,
    }
    req = urllib.request.Request(
        endpoint,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def parse_verdict(text):
    """简化内核 parse: Action 优先, Final Answer 其次, 纯文本当最终答案。"""
    if RE_ACTION.search(text):
        return "action"
    if RE_FINAL.search(text):
        return "final"
    return "plain-final"


def analyze(api_key, prompt_file, model, endpoint, samples):
    with open(prompt_file, encoding="utf-8") as f:
        system_prompt = f.read()
    rows = []
    for kind, msg in MESSAGES:
        outputs = []
        for _ in range(samples):
            outputs.append(call_llm(api_key, system_prompt, msg, model, endpoint))
        n = len(outputs)
        action_rate = sum(1 for o in outputs if RE_ACTION.search(o)) / n
        thought_rate = sum(1 for o in outputs if RE_THOUGHT.search(o)) / n
        final_rate = sum(1 for o in outputs if RE_FINAL.search(o)) / n
        bold = sum(o.count("**") for o in outputs)
        headings = sum(len(RE_HEADING.findall(o)) for o in outputs)
        bullets = sum(len(RE_BULLET.findall(o)) for o in outputs)
        avg_len = sum(len(o) for o in outputs) / n
        verdicts = [parse_verdict(o) for o in outputs]
        rows.append({
            "kind": kind, "msg": msg,
            "action_rate": action_rate, "thought_rate": thought_rate, "final_rate": final_rate,
            "bold": bold / n, "headings": headings / n, "bullets": bullets / n,
            "avg_len": avg_len, "verdicts": verdicts,
            "sample": outputs[0].replace("\n", " ")[:120],
        })
    return rows


def print_table(title, rows):
    print(f"\n=== {title} ===")
    print(f"{'类型':<6}{'消息':<14}{'Action%':>8}{'Thought%':>9}{'Final%':>8}{'**/轮':>7}{'#/轮':>6}{'-/轮':>6}{'长度':>7}  判定")
    for r in rows:
        v = "/".join({"action": "A", "final": "F", "plain-final": "P"}.get(x, x) for x in r["verdicts"])
        print(f"{r['kind']:<6}{r['msg']:<14}{r['action_rate']*100:>7.0f}%{r['thought_rate']*100:>8.0f}%"
              f"{r['final_rate']*100:>7.0f}%{r['bold']:>7.1f}{r['headings']:>6.1f}{r['bullets']:>6.1f}"
              f"{r['avg_len']:>7.0f}  {v}")
    print("\n抽样输出:")
    for r in rows:
        print(f"  [{r['kind']}] {r['msg']}: {r['sample']}")


def main():
    ap = argparse.ArgumentParser(description="提示词优化前后 LLM 生成对比")
    ap.add_argument("--before", required=True, help="优化前提示词文件")
    ap.add_argument("--after", required=True, help="优化后提示词文件")
    ap.add_argument("--api-key", default=os.environ.get("MENGPAW_API_KEY", ""), help="API key (或环境变量 MENGPAW_API_KEY)")
    ap.add_argument("--model", default="deepseek-chat")
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    ap.add_argument("--samples", type=int, default=3, help="每条消息采样次数 (默认 3)")
    args = ap.parse_args()

    if not args.api_key:
        print("缺少 API key: 请设置环境变量 MENGPAW_API_KEY 或传 --api-key")
        sys.exit(1)

    before = analyze(args.api_key, args.before, args.model, args.endpoint, args.samples)
    after = analyze(args.api_key, args.after, args.model, args.endpoint, args.samples)

    print_table(f"BEFORE: {args.before}", before)
    print_table(f"AFTER:  {args.after}", after)

    # ── 判定 ──
    tool_needed = [b for b in before if b["kind"] == "工具必需"]
    after_tool = [a for a in after if a["kind"] == "工具必需"]
    action_before = sum(r["action_rate"] for r in tool_needed) / len(tool_needed)
    action_after = sum(r["action_rate"] for r in after_tool) / len(after_tool)

    print("\n=== 判定 ===")
    print(f"工具必需类 Action: 出现率  before={action_before*100:.0f}%  after={action_after*100:.0f}%")
    if action_after < 0.80:
        print("❌ FAIL: 优化后 Action 出现率 < 80% — 模型不再稳定输出工具调用标记。"
              "建议回退强要求句删除（保留完整 Thought → Action → Action Input 要求）后重测。")
        sys.exit(2)
    print("✅ PASS: Action 出现率 ≥ 80%，工具调用未受干扰。")

    md_before = sum(r["bold"] + r["headings"] + r["bullets"] for r in before)
    md_after = sum(r["bold"] + r["headings"] + r["bullets"] for r in after)
    print(f"Markdown 标记密度 (每轮 **/#/- 合计)  before={md_before/len(before):.1f}  after={md_after/len(after):.1f}")
    if md_after < md_before:
        print(f"✅ Markdown 装饰减少 ({md_before/len(before):.1f} → {md_after/len(after):.1f}/轮)，反 Markdown 约束生效。")
    else:
        print(f"⚠️ Markdown 密度未下降 — 反 Markdown 约束可能不够，可考虑强化措辞。")


if __name__ == "__main__":
    main()
