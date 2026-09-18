"""Loopback OpenAI-compatible evidence judge for reproducible retrieval evaluation."""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    torch.set_num_threads(8)
    tokenizer = AutoTokenizer.from_pretrained(args.model_dir)
    model = AutoModelForCausalLM.from_pretrained(args.model_dir, torch_dtype=torch.float32).eval()
    lock = threading.Lock()
    cache = {}
    stats = {"requests": 0, "cacheHits": 0, "inputTokens": 0,
             "outputTokens": 0, "inferenceMs": 0.0}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path == "/health":
                self.reply(200, {"status": "UP", "model": args.model_dir.name})
            elif self.path == "/stats":
                with lock:
                    self.reply(200, dict(stats))
            else:
                self.reply(404, {"error": "unknown path"})

        def do_POST(self) -> None:
            if self.path != "/v1/chat/completions":
                self.reply(404, {"error": "unknown path"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if size < 1 or size > 50_000:
                    raise ValueError("invalid request size")
                request = json.loads(self.rfile.read(size))
                messages = request["messages"]
                if not isinstance(messages, list) or not messages:
                    raise ValueError("messages required")
                prompt = tokenizer.apply_chat_template(messages, tokenize=False,
                                                       add_generation_prompt=True)
                encoded = tokenizer(prompt, return_tensors="pt")
                key = prompt
                with lock:
                    cached = key in cache
                    if cached:
                        answer, output_tokens = cache[key]
                        elapsed = 0.0
                    else:
                        started = time.perf_counter()
                        with torch.no_grad():
                            generated = model.generate(**encoded, max_new_tokens=4, do_sample=False,
                                                       pad_token_id=tokenizer.eos_token_id)
                        tokens = generated[0][encoded.input_ids.shape[1]:]
                        output_tokens = len(tokens)
                        answer = tokenizer.decode(tokens, skip_special_tokens=True).strip()
                        elapsed = (time.perf_counter() - started) * 1000
                        cache[key] = (answer, output_tokens)
                    stats["requests"] += 1
                    stats["cacheHits"] += int(cached)
                    stats["inputTokens"] += int(encoded.input_ids.shape[1])
                    stats["outputTokens"] += output_tokens
                    stats["inferenceMs"] += elapsed
                self.reply(200, {"choices": [{"message": {"content": answer}}],
                                 "usage": {"prompt_tokens": int(encoded.input_ids.shape[1]),
                                           "completion_tokens": output_tokens}})
            except (KeyError, ValueError, TypeError, json.JSONDecodeError) as error:
                self.reply(400, {"error": str(error)})
            except Exception as error:
                self.reply(500, {"error": error.__class__.__name__})

        def reply(self, status: int, value: dict) -> None:
            body = json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            return

    print(f"ready port={args.port} model={args.model_dir.name}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
