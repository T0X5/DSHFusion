#!/usr/bin/env python3
"""核验从 DSHA 导入的文件：来源头在位，且**正文**逐字节与登记一致。

为什么需要它：`:backup` 里的 7 个文件是 DSHA（MIT）的实现。硬约束 2 允许直接
复用成熟实现，但「原样」必须是**可验证的**，不能只是自我声称 —— 否则某天有人
为了让某个断言变绿而顺手改一行，事后没人看得出来。

**验证模型：每个文件 = 固定的 3 行来源头 + DSHA 原文。** 两条断言互相独立：

1. 文件必须**正好**以 `provenance_header` 开头（多一个字符都不行）——
   这是 `governance/ATTRIBUTION.md` 第三节对移植文件的署名要求；
2. 去掉头之后的正文 sha256 必须等于 `upstream_sha256`。

这样「署名在位」与「正文未改」各自都会红，改任何一边都藏不住。

与 `check-import-fidelity.py`（DSHBox 骨干保真度）的两点关键区别：

1. **期望值内嵌在 `governance/dsha-imports.json` 里，不需要仓库外的审计副本。**
   这是刻意的：骨干那个检查器依赖 `../audit/DSHBox`，在 CI 里走「跳过」分支，
   于是它实际上很久没有真正跑过（见 docs/INCIDENTS.md 第 13 条）。
   本脚本在 CI 里是真跑。
2. **两个方向都查。** 只查「登记过的文件有没有被改」会漏掉「有人往导入目录里
   塞了新文件」；只查「目录里有什么」会漏掉「登记的文件被删了」。

用法：
    python3 tools/check-dsha-imports.py
    python3 tools/check-dsha-imports.py --update   # 仅在复核过 DSHA 上游改动后使用
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "governance" / "dsha-imports.json"

# 导入副本所在的源码根。这里出现的 `.java` 一律视为 DSHA 的副本，
# DSHFusion 自己写的代码是 Kotlin（com.dshbox.app.*），不在这些目录里。
IMPORT_ROOT = ROOT / "backup" / "src"


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="核验 DSHA 导入副本的来源头与正文一致性")
    parser.add_argument("--update", action="store_true",
                        help="重新生成正文哈希（仅在复核过上游改动并重新审计后使用）")
    args = parser.parse_args()

    if not REGISTRY.is_file():
        print(f"ERROR: 找不到 {REGISTRY.relative_to(ROOT)}", file=sys.stderr)
        return 1
    if not IMPORT_ROOT.is_dir():
        print(f"ERROR: 找不到导入副本目录 {IMPORT_ROOT.relative_to(ROOT)}", file=sys.stderr)
        return 1

    doc = json.loads(REGISTRY.read_text(encoding="utf-8"))
    files = doc.get("files") or []
    if not files:
        print("ERROR: 导入清单为空", file=sys.stderr)
        return 1

    header_lines = doc.get("provenance_header")
    if not isinstance(header_lines, list) or not header_lines:
        print("ERROR: 清单缺少 provenance_header", file=sys.stderr)
        return 1
    prefix = ("\n".join(header_lines) + "\n").encode("utf-8")

    registered = {entry["local_path"]: entry for entry in files}

    if args.update:
        changed = []
        for relative, entry in sorted(registered.items()):
            path = ROOT / relative
            if not path.is_file():
                print(f"ERROR: 文件不存在 {relative}", file=sys.stderr)
                return 1
            payload = path.read_bytes()
            if not payload.startswith(prefix):
                print(f"ERROR: {relative} 缺少登记的来源头，无法判定正文起点", file=sys.stderr)
                return 1
            body = payload[len(prefix):]
            new_hash = sha256_bytes(body)
            if new_hash != entry.get("upstream_sha256"):
                changed.append(relative)
                entry["upstream_sha256"] = new_hash
                entry["upstream_bytes"] = len(body)
        REGISTRY.write_text(
            json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if changed:
            print(f"已更新 {len(changed)} 个正文哈希：")
            for item in changed:
                print(f"  · {item}")
            print("\n⚠️  更新正文哈希等于承认这些文件的正文**不再与 DSHA 一致**。")
            print("    请确认这是有意的，并在 PR 说明里写清改了哪一行、为什么。")
        else:
            print("正文哈希无变化。")
        return 0

    missing: list[str] = []
    bad_header: list[str] = []
    mismatched: list[tuple[str, str, str]] = []
    for relative, entry in sorted(registered.items()):
        path = ROOT / relative
        if not path.is_file():
            missing.append(relative)
            continue
        payload = path.read_bytes()
        if not payload.startswith(prefix):
            bad_header.append(relative)
            continue
        body = payload[len(prefix):]
        actual = sha256_bytes(body)
        if actual != entry.get("upstream_sha256"):
            mismatched.append((relative, entry.get("upstream_sha256"), actual))
        elif len(body) != entry.get("upstream_bytes"):
            mismatched.append((relative, str(entry.get("upstream_bytes")),
                               f"{len(body)} 字节（与登记不符）"))

    # 反向：导入目录里不允许出现未登记的 .java
    on_disk = {
        path.relative_to(ROOT).as_posix()
        for path in IMPORT_ROOT.rglob("*.java")
    }
    unregistered = sorted(on_disk - set(registered))
    # 登记了但不在导入目录里的（写错路径）也一并报出来
    stray = sorted(set(registered) - on_disk - set(missing))

    if missing or bad_header or mismatched or unregistered or stray:
        print("DSHA 导入副本核验失败：", file=sys.stderr)
        for item in missing:
            print(f"  ✗ 缺失: {item}", file=sys.stderr)
        for item in bad_header:
            print(f"  ✗ 来源头不对（必须正好是清单里那 {len(header_lines)} 行）: {item}",
                  file=sys.stderr)
        for relative, want, got in mismatched:
            print(f"  ✗ 正文与登记不符: {relative}", file=sys.stderr)
            print(f"      期望 {want}", file=sys.stderr)
            print(f"      实际 {got}", file=sys.stderr)
        for item in unregistered:
            print(f"  ✗ 导入目录里有未登记的文件: {item}", file=sys.stderr)
        for item in stray:
            print(f"  ✗ 登记了但不在导入目录里: {item}", file=sys.stderr)
        print(
            "\n这些文件的**正文**按设计是 DSHA 的逐字节副本（只有来源头是本项目加的）。"
            "若确实需要改动，请先说明理由 —— 改动会同时失去「可原样比对上游」这个性质。",
            file=sys.stderr)
        return 1

    total_bytes = sum((ROOT / r).stat().st_size for r in registered)
    upstream = doc.get("upstream", {})
    print(
        f"DSHA 导入副本核验通过：{len(registered)} 个文件、{total_bytes} 字节（含来源头），"
        f"正文逐字节与 {upstream.get('id', '?')}@{str(upstream.get('locked_commit', '?'))[:12]} 一致；"
        f"导入目录内无未登记文件。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
