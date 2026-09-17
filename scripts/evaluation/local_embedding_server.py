"""Loopback OpenAI-compatible endpoint for the pinned local ONNX evaluation model."""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    model = args.model_dir / "onnx" / "model_quint8_avx2.onnx"
    tokenizer = Tokenizer.from_file(str(args.model_dir / "tokenizer.json"))
    tokenizer.enable_truncation(max_length=128)
    session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    stats = {"requests": 0, "tokens": 0, "inferenceMs": 0.0}
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path == "/health":
                self.reply(200, {"status": "UP", "dimensions": 384})
            elif self.path == "/stats":
                with lock:
                    self.reply(200, dict(stats))
            else:
                self.reply(404, {"error": "unknown path"})

        def do_POST(self) -> None:
            if self.path != "/v1/embeddings":
                self.reply(404, {"error": "unknown path"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if size < 1 or size > 200_000:
                    raise ValueError("invalid request size")
                request = json.loads(self.rfile.read(size))
                source = request["input"]
                if not isinstance(source, str) or request.get("dimensions") != 384:
                    raise ValueError("one text input and 384 dimensions required")
                start = time.perf_counter()
                tokens = tokenizer.encode(source)
                inputs = {
                    "input_ids": np.asarray([tokens.ids], dtype=np.int64),
                    "attention_mask": np.asarray([tokens.attention_mask], dtype=np.int64),
                    "token_type_ids": np.asarray([tokens.type_ids], dtype=np.int64),
                }
                hidden = session.run(None, inputs)[0][0]
                mask = np.asarray(tokens.attention_mask, dtype=np.float32)[:, None]
                embedding = (hidden * mask).sum(axis=0) / max(1, mask.sum())
                embedding /= max(1e-12, np.linalg.norm(embedding))
                elapsed = (time.perf_counter() - start) * 1000
                with lock:
                    stats["requests"] += 1
                    stats["tokens"] += len(tokens.ids)
                    stats["inferenceMs"] += elapsed
                self.reply(200, {"data": [{"index": 0, "embedding": embedding.tolist()}],
                                 "usage": {"prompt_tokens": len(tokens.ids), "total_tokens": len(tokens.ids)}})
            except (KeyError, ValueError, TypeError, json.JSONDecodeError) as error:
                self.reply(400, {"error": str(error)})
            except Exception as error:
                self.reply(500, {"error": error.__class__.__name__})

        def reply(self, status: int, value: dict) -> None:
            body = json.dumps(value).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"ready port={args.port} model={model}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
