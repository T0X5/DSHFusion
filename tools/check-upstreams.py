#!/usr/bin/env python3
"""DSHFusion 契约检查（阶段 0）。

不构建 Android，只校验治理文件的内部一致性与真实文件的存在性。
设计原则：宁可失败也不要静默通过 —— 一个「检查了但没真查」的检查器比没有更糟。

用法：
    python3 tools/check-upstreams.py            # 全部检查
    python3 tools/check-upstreams.py --list     # 列出会检查什么
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

UPSTREAMS = ROOT / "governance" / "upstreams.json"
DIVERGENCES = ROOT / "governance" / "divergences.json"
LICENSE_HASHES = ROOT / "governance" / "upstream-license-hashes.json"

REQUIRED_DOCS = [
    "AGENTS.md",
    "LICENSE-NOTES.md",
    "governance/ATTRIBUTION.md",
    "governance/upstreams.json",
    "governance/divergences.json",
    "docs/AUDIT.md",
    "docs/FUSION-PLAN.md",
    "docs/UPSTREAM-SYNC.md",
]

VALID_ROLES = {"base", "feature_source"}
VALID_DIV_TYPES = {"identity", "adaptation", "config", "patch", "removal"}
VALID_DIV_STATES = {"applied", "planned"}

# 上游许可原文一旦被改动，就不再具有证明力。
# 这些文件是「只读原件」，检查器按 SHA-256 锁死。
PROTECTED_LICENSE_FILES = [
    "governance/upstream-licenses/DSHA/LICENSE",
    "governance/upstream-licenses/DSHA/THIRD_PARTY_NOTICES.md",
    "governance/upstream-licenses/DSHBox/LICENSE",
    "governance/upstream-licenses/DSHBox/THIRD_PARTY_NOTICES.md",
    "governance/upstream-licenses/DSHBox/TRADEMARK.md",
]

failures: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def load(path: Path, label: str):
    if not path.is_file():
        fail(f"{label}: 文件不存在 — {path.relative_to(ROOT)}")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"{label}: JSON 解析失败 — {error}")
        return None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_required_docs() -> None:
    for relative in REQUIRED_DOCS:
        if not (ROOT / relative).is_file():
            fail(f"缺少必需文档: {relative}")


def check_protected_licenses() -> None:
    """核验上游许可原文未被改动，并与哈希清单一致。"""
    recorded = load(LICENSE_HASHES, "upstream-license-hashes.json")
    if recorded is None:
        return
    entries = recorded.get("files")
    if not isinstance(entries, dict) or not entries:
        fail("upstream-license-hashes.json: files 必须是非空对象")
        return

    listed = set(entries)
    expected = set(PROTECTED_LICENSE_FILES)
    for missing in sorted(expected - listed):
        fail(f"upstream-license-hashes.json 未登记受保护文件: {missing}")
    for extra in sorted(listed - expected):
        fail(f"upstream-license-hashes.json 登记了非受保护文件: {extra}")

    for relative, record in sorted(entries.items()):
        path = ROOT / relative
        if not path.is_file():
            fail(f"受保护的许可原件缺失: {relative}")
            continue
        actual = sha256_file(path)
        want = (record or {}).get("sha256")
        if want != actual:
            fail(
                f"受保护的许可原件被改动: {relative}\n"
                f"        期望 {want}\n"
                f"        实际 {actual}\n"
                f"        这些文件必须保持上游原样；如需更新，请同步上游后重新生成哈希清单。"
            )


def check_upstreams() -> None:
    data = load(UPSTREAMS, "upstreams.json")
    if data is None:
        return

    upstreams = data.get("upstreams")
    if not isinstance(upstreams, list) or not upstreams:
        fail("upstreams.json: upstreams 必须是非空数组")
        return

    ids = []
    roles = {}
    for index, entry in enumerate(upstreams):
        label = f"upstreams.json[{index}]"
        if not isinstance(entry, dict):
            fail(f"{label}: 必须是对象")
            continue
        for field in ("id", "role", "full_name", "url", "locked_commit", "license"):
            if not entry.get(field):
                fail(f"{label}: 缺少字段 {field}")
        upstream_id = entry.get("id")
        if upstream_id:
            ids.append(upstream_id)
            roles[upstream_id] = entry.get("role")
        role = entry.get("role")
        if role and role not in VALID_ROLES:
            fail(f"{label}: role 非法 — {role}")
        commit = entry.get("locked_commit", "")
        if commit and (len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit)):
            fail(f"{label}: locked_commit 必须是 40 位小写十六进制 — {commit}")
        url = entry.get("url", "")
        if url and not url.endswith(".git"):
            notes.append(f"{label}: url 未以 .git 结尾（{url}），同步脚本需要可 clone 的地址")

        # 许可原文必须真实存在
        for field in ("license_file", "trademark_notice"):
            relative = entry.get(field)
            if relative and not (ROOT / relative).is_file():
                fail(f"{label}: {field} 指向的文件不存在 — {relative}")

        # 运行环境资产策略必须写明，这是本项目最关键的外部依赖
        if not entry.get("runtime_asset_strategy"):
            fail(f"{label}: 缺少 runtime_asset_strategy —— 运行环境资产如何获取必须显式记录")

        # 同步策略
        policy = entry.get("sync_policy") or {}
        if not policy:
            fail(f"{label}: 缺少 sync_policy")
        else:
            if "auto_publish_allowed" not in policy:
                fail(f"{label}: sync_policy 缺少 auto_publish_allowed")
            elif policy["auto_publish_allowed"] is not False:
                fail(f"{label}: auto_publish_allowed 必须为 false —— 不得默认自动发布")

    if len(ids) != len(set(ids)):
        fail(f"upstreams.json: id 重复 — {ids}")

    if roles.get("dshbox") != "base":
        fail("upstreams.json: dshbox 必须是 base（见 docs/AUDIT.md 的结论）")
    if roles.get("dsha") != "feature_source":
        fail("upstreams.json: dsha 必须是 feature_source")

    transitive = data.get("transitive_upstreams")
    if not isinstance(transitive, list) or not transitive:
        fail("upstreams.json: transitive_upstreams 必须是非空数组")
    else:
        for index, entry in enumerate(transitive):
            if not isinstance(entry, dict) or not entry.get("id") or not entry.get("role"):
                fail(f"upstreams.json.transitive_upstreams[{index}]: 缺少 id 或 role")


def check_divergences() -> None:
    data = load(DIVERGENCES, "divergences.json")
    if data is None:
        return

    seen: set[str] = set()
    for state in ("applied", "planned"):
        entries = data.get(state)
        if entries is None:
            fail(f"divergences.json: 缺少 {state} 数组（可以为空，但必须存在）")
            continue
        if not isinstance(entries, list):
            fail(f"divergences.json: {state} 必须是数组")
            continue

        for index, entry in enumerate(entries):
            label = f"divergences.json.{state}[{index}]"
            if not isinstance(entry, dict):
                fail(f"{label}: 必须是对象")
                continue

            div_id = entry.get("id")
            if not div_id:
                fail(f"{label}: 缺少 id")
            elif div_id in seen:
                fail(f"{label}: id 重复 — {div_id}")
            else:
                seen.add(div_id)

            div_type = entry.get("type")
            if div_type not in VALID_DIV_TYPES:
                fail(f"{label}: type 非法 — {div_type}（允许：{sorted(VALID_DIV_TYPES)}）")

            for field in ("title", "change", "rationale", "reapply"):
                if not entry.get(field):
                    fail(f"{label}: 缺少字段 {field}")

            targets = entry.get("target_files")
            if not isinstance(targets, list) or not targets:
                fail(f"{label}: target_files 必须是非空数组")
                targets = []

            # applied 的偏离必须指向真实存在的文件；否则「登记」是空的
            if state == "applied":
                for relative in targets:
                    if not (ROOT / relative).exists():
                        fail(
                            f"{label}: applied 的 target_files 不存在 — {relative}\n"
                            f"        要么文件确实还没导入（应放在 planned），要么路径写错了。"
                        )

            if state == "planned" and not entry.get("phase"):
                fail(f"{label}: planned 的条目必须写明 phase（哪个阶段施加）")


def main() -> int:
    parser = argparse.ArgumentParser(description="DSHFusion 治理契约检查")
    parser.add_argument("--list", action="store_true", help="只列出检查项")
    args = parser.parse_args()

    checks = [
        "必需文档齐全",
        "上游许可原文未被改动（SHA-256 锁定）",
        "upstreams.json 结构与角色正确",
        "divergences.json 结构与文件存在性正确",
    ]
    if args.list:
        for name in checks:
            print(f"  - {name}")
        return 0

    check_required_docs()
    check_protected_licenses()
    check_upstreams()
    check_divergences()

    for note in notes:
        print(f"NOTE  {note}")

    if failures:
        print(f"\n契约检查失败（{len(failures)} 项）：\n", file=sys.stderr)
        for message in failures:
            print(f"  ✗ {message}", file=sys.stderr)
        print("", file=sys.stderr)
        return 1

    print(f"契约检查通过（{len(checks)} 项）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
