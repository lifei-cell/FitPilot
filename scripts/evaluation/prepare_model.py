"""Download only public pinned model files; no FitPilot content is uploaded."""

import hashlib
from pathlib import Path

from huggingface_hub import hf_hub_download


REPO = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
REVISION = "e8f8c211226b894fcb81acc59f3b34ba3efd5f42"
FILES = ("onnx/model_quint8_avx2.onnx", "tokenizer.json", "config.json",
         "modules.json", "sentence_bert_config.json", "1_Pooling/config.json")
MODEL_HASH = "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f"


def main() -> None:
    destination = Path("target/models/paraphrase-multilingual-MiniLM-L12-v2")
    for name in FILES:
        hf_hub_download(REPO, name, revision=REVISION, local_dir=destination)
    actual = hashlib.sha256((destination / FILES[0]).read_bytes()).hexdigest()
    if actual != MODEL_HASH:
        raise RuntimeError(f"Pinned model hash mismatch: {actual}")
    print(f"MODEL_READY={destination} SHA256={actual}")


if __name__ == "__main__":
    main()
