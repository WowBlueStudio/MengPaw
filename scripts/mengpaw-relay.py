#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
MengPaw Relay — 自建大模型 API 中转服务

在 PC/服务器上运行此脚本，MengPaw 即可通过局域网调用你本地的
Ollama / vLLM / llama.cpp / LM Studio 等后端的大模型。

用法:
  pip install flask requests
  python mengpaw-relay.py                          # 默认 Ollama (localhost:11434)
  python mengpaw-relay.py --backend http://10.0.0.5:8000/v1  # 自定义后端
  python mengpaw-relay.py --port 9877 --host 0.0.0.0           # 允许局域网访问

MengPaw 端配置:
  提供商: Self-Hosted
  API 地址: http://<PC-IP>:9877/v1/chat/completions
  模型: 填写后端实际模型名 (如 qwen2.5:7b, llama3.1:8b)
"""

import argparse, json, sys, time, logging
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.request import Request, urlopen
from urllib.error import URLError

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("mengpaw-relay")

# ── Config ───────────────────────────────────────────────────────────────

class Config:
    def __init__(self, backend: str, host: str, port: int, announce: bool):
        self.backend = backend.rstrip("/")
        self.host = host
        self.port = port
        self.announce = announce
        self.start_time = time.time()

# ── HTTP Handler ─────────────────────────────────────────────────────────

class RelayHandler(BaseHTTPRequestHandler):
    cfg: Config = None

    def log_message(self, fmt, *args):
        log.info(f"{self.client_address[0]} → {args[0]}" if args else fmt)

    def _send_json(self, data: dict, status: int = 200):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()

    def do_GET(self):
        if self.path == "/health":
            self._send_json({"status": "ok", "backend": self.cfg.backend,
                "uptime": int(time.time() - self.cfg.start_time)})
        elif self.path == "/v1/models":
            # Proxy model list from backend
            try:
                req = Request(f"{self.cfg.backend}/models")
                resp = urlopen(req, timeout=10)
                self._send_json(json.loads(resp.read()))
            except Exception as e:
                self._send_json({"error": str(e)}, 502)
        else:
            self._send_json({"service": "MengPaw Relay", "version": "0.1.0"})

    def do_POST(self):
        if self.path in ("/v1/chat/completions", "/chat/completions"):
            self._proxy_chat()
        else:
            self._send_json({"error": f"unknown endpoint: {self.path}"}, 404)

    def _proxy_chat(self):
        content_len = int(self.headers.get("Content-Length", 0))
        body = self.client.rfile.read(content_len) if content_len > 0 else b"{}"

        try:
            # Forward to backend
            req = Request(
                f"{self.cfg.backend}/chat/completions",
                data=body,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": self.headers.get("Authorization", ""),
                },
            )
            resp = urlopen(req, timeout=120)
            resp_body = resp.read()
            self._send_json(json.loads(resp_body))
        except URLError as e:
            log.error(f"Backend unreachable: {e.reason}")
            self._send_json({
                "error": f"后端不可达: {self.cfg.backend}\n请确认 Ollama/vLLM 已启动。\n{str(e.reason)}",
                "choices": [{"message": {"content": f"❌ 后端服务不可达\n{self.cfg.backend}\n{str(e.reason)}"}}]
            }, 502)
        except Exception as e:
            self._send_json({"error": str(e)}, 500)

# ── mDNS Announce ────────────────────────────────────────────────────────

def announce_mdns(port: int):
    """Announce MengPaw Relay via mDNS so MengPaw can discover it on LAN."""
    try:
        from zeroconf import Zeroconf, ServiceInfo
        zc = Zeroconf()
        info = ServiceInfo(
            "_mengpaw-relay._tcp.local.",
            f"MengPaw Relay._mengpaw-relay._tcp.local.",
            addresses=[], port=port,
            properties={"version": "0.1.0", "type": "llm-relay"}
        )
        zc.register_service(info)
        log.info(f"mDNS 已注册: _mengpaw-relay._tcp.local:{port}")
        return zc
    except ImportError:
        log.info("mDNS 未启用 (pip install zeroconf 以启用局域网发现)")
        return None

# ── Main ─────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser(description="MengPaw Relay — 自建大模型中转")
    p.add_argument("--backend", default="http://localhost:11434/v1",
                   help="后端 API 地址 (默认 Ollama)")
    p.add_argument("--host", default="0.0.0.0", help="监听地址")
    p.add_argument("--port", type=int, default=9877, help="监听端口")
    p.add_argument("--no-announce", action="store_true", help="禁用 mDNS 发现")
    args = p.parse_args()

    cfg = Config(args.backend, args.host, args.port, not args.no_announce)
    RelayHandler.cfg = cfg

    log.info(f"MengPaw Relay v0.1.0")
    log.info(f"后端: {cfg.backend}")
    log.info(f"监听: http://{cfg.host}:{cfg.port}")
    log.info(f"MengPaw API 地址: http://<本机IP>:{cfg.port}/v1/chat/completions")

    zc = None
    if cfg.announce:
        zc = announce_mdns(cfg.port)

    server = HTTPServer((cfg.host, cfg.port), RelayHandler)
    try:
        log.info("按 Ctrl+C 停止")
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("已停止")
    finally:
        server.server_close()
        if zc: zc.close()

if __name__ == "__main__":
    main()
