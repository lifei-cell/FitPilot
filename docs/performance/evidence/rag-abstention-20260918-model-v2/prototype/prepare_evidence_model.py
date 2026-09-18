"""Download a public pinned evidence model; no FitPilot data leaves the machine."""

import hashlib
from pathlib import Path

from huggingface_hub import hf_hub_download

REPO = "Qwen/Qwen2.5-1.5B-Instruct"
REVISION = "989aa7980e4cf806f80c7fef2b1adb7bc71aa306"
FILES = ("model.safetensors", "config.json", "generation_config.json",
         "tokenizer.json", "tokenizer_config.json", "vocab.json", "LICENSE")
MODEL_HASH = "dd924a11b4c220f385b51ffa522daea7c9f3d850e31b162bb5661df483c6d3ee"


def main() -> None:
    destination = Path("target/models/Qwen2.5-1.5B-Instruct")
    for name in FILES:
        hf_hub_download(REPO, name, revision=REVISION, local_dir=destination)
    actual = hashlib.sha256((destination / FILES[0]).read_bytes()).hexdigest()
    if actual != MODEL_HASH:
        raise RuntimeError("Evidence model hash mismatch")
    print(f"EVIDENCE_MODEL_READY={destination} SHA256={actual}")


if __name__ == "__main__":
    main()
