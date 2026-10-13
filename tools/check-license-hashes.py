#!/usr/bin/env python3
"""独立核验上游许可原件的哈希。

为什么单独一个脚本：这些文件是「只读原件」，一旦被改动就不再具有证明力。
`check-upstreams.py` 已经检查它们，本脚本提供一个可独立运行的、
输出更直白的核验入口，便于在任何时候（包括没有完整仓库上下文时）确认
许可原件是否与锁定提交处一致。

用法：
    python3 tools/check-license-hashes.py
    python3 tools/check-license-hashes.py --update   # 同步上游后重新生成哈希
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "governance" / "upstream-license-hashes.json"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="核验上游许可原件哈希")
    parser.add_argument("--update", action="store_true",
                        help="重新生成哈希（仅在同步上游并复核许可变化后使用）")
    args = parser.parse_args()

    if not REGISTRY.is_file():
        print(f"ERROR: 找不到 {REGISTRY.relative_to(ROOT)}", file=sys.stderr)
        return 1

    doc = json.loads(REGISTRY.read_text(encoding="utf-8"))
    files = doc.get("files") or {}
    if not files:
        print("ERROR: 哈希清单为空", file=sys.stderr)
        return 1

    if args.update:
        changed = []
        for relative, record in files.items():
            path = ROOT / relative
            if not path.is_file():
                print(f"ERROR: 文件不存在 {relative}", file=sys.stderr)
                return 1
            new_hash = sha256_file(path)
            if new_hash != record.get("sha256"):
                changed.append(relative)
                record["sha256"] = new_hash
                record["bytes"] = path.stat().st_size
        REGISTRY.write_text(
            json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if changed:
            print(f"已更新 {len(changed)} 个哈希：")
            for item in changed:
                print(f"  · {item}")
            print("\n⚠️  请同步复核 governance/ATTRIBUTION.md 与上游 THIRD_PARTY_NOTICES。")
        else:
            print("哈希无变化。")
        return 0

    mismatches = []
    missing = []
    for relative, record in sorted(files.items()):
        path = ROOT / relative
        if not path.is_file():
            missing.append(relative)
            continue
        actual = sha256_file(path)
        if actual != record.get("sha256"):
            mismatches.append((relative, record.get("sha256"), actual))

    if missing or mismatches:
        print("许可原件核验失败：", file=sys.stderr)
        for item in missing:
            print(f"  ✗ 缺失: {item}", file=sys.stderr)
        for relative, want, got in mismatches:
            print(f"  ✗ 被改动: {relative}", file=sys.stderr)
            print(f"      期望 {want}", file=sys.stderr)
            print(f"      实际 {got}", file=sys.stderr)
        print(
            "\n这些文件必须保持上游原样。若确实同步了上游并复核过许可，"
            "用 --update 重新生成。", file=sys.stderr)
        return 1

    total_bytes = sum((ROOT / r).stat().st_size for r in files)
    print(f"许可原件核验通过：{len(files)} 个文件、{total_bytes} 字节，"
          f"哈希与登记一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
