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


def check_derived(doc: dict, adapted_prefix: bytes, copy_prefix: bytes) -> tuple[list[str], str]:
    """核验「改写后移植」的登记。

    与 files[] 不同，这里的正文**不是**逐字节副本，所以没法靠 sha256 证明「没改」。
    能查、也真正重要的是三件事：

    1. 声明的文件确实在磁盘上；
    2. 它**没有**同时出现在逐字节副本清单里（一处代码只能有一种身份）；
    3. 改写者必须写清**保留了哪条不变式**与**改掉了什么** —— 否则「改写」就成了
       把任意代码洗成「上游移植」的通道；这条是本节真正的治理断言。

    返回 (问题列表, 上游侧核对结论)。上游侧的 sha256 只有在仓库外的审计副本存在时
    才能核对，所以结论会如实标出「本机核对」还是「未核」——
    而不是让它悄悄走一个跳过的分支（见 docs/INCIDENTS.md 第 13 条）。
    """
    problems: list[str] = []
    items = ((doc.get("derived") or {}).get("items")) or []
    if not items:
        return problems, "无改写移植项"

    verbatim = {entry["local_path"] for entry in (doc.get("files") or [])}
    upstreams = {entry["local_path"]: entry.get("upstream_sha256") for entry in items}
    if len(upstreams) != len(items):
        problems.append("derived 里有重复的 local_path")
    overlap = sorted(verbatim & set(upstreams))
    for path in overlap:
        problems.append(f"{path} 同时登记成「逐字节副本」与「改写移植」—— 一处代码只能有一种身份")

    audit = ROOT.parent / "audit" / "DSHA"
    checked = 0
    unchecked = 0
    for entry in items:
        path = ROOT / entry["local_path"]
        if not path.is_file():
            problems.append(f"声明的改写文件不存在: {entry['local_path']}")
        for field in ("kept", "changed"):
            values = entry.get(field)
            if not isinstance(values, list) or not values or not all(
                    isinstance(v, str) and v.strip() for v in values):
                problems.append(
                    f"{entry.get('local_path')} 的 {field} 必须是非空字符串列表"
                    f" —— 不写清保留/改掉了什么，「改写」就成了洗代码的通道")
        # 来源头不能撒谎：适配副本必须带 adapted_header，而且**不能**带
        # 「原样复制、正文未做任何修改」那一份。没有这条，文件可以自己声称是逐字节副本
        # 而没有任何东西会不同意 —— 那正是「错误的前提被写进产物里」的老毛病。
        kind = entry.get("kind")
        if path.is_file():
            head = path.read_bytes()[:max(len(adapted_prefix), len(copy_prefix))]
            if kind == "adapted":
                if not head.startswith(adapted_prefix):
                    problems.append(
                        f"{entry['local_path']} 是适配副本，却没有 adapted_header"
                        f"（正文已与上游不同，头不能再声称「原样复制」）")
            elif kind == "rewritten":
                if head.startswith(copy_prefix):
                    problems.append(
                        f"{entry['local_path']} 是本仓库重写的，却带着「原样复制」的来源头")
        digest = str(entry.get("upstream_sha256", ""))
        if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            problems.append(f"{entry.get('local_path')} 的 upstream_sha256 不是小写 sha256")
        if not isinstance(entry.get("upstream_bytes"), int) or entry["upstream_bytes"] <= 0:
            problems.append(f"{entry.get('local_path')} 的 upstream_bytes 不是正数")
        if entry.get("kind") not in {"rewritten", "adapted", "reimplemented"}:
            problems.append(f"{entry.get('local_path')} 的 kind 取值不在允许集合内")
        verified_by = entry.get("verified_by")
        if verified_by and not (ROOT / verified_by).is_file():
            problems.append(f"{entry.get('local_path')} 声明的 verified_by 不存在: {verified_by}")

        source = audit / entry.get("upstream_path", "")
        if source.is_file():
            payload = source.read_bytes()
            if sha256_bytes(payload) != digest:
                problems.append(
                    f"{entry.get('local_path')} 的上游正文哈希与审计副本不符: {entry.get('upstream_path')}")
            elif len(payload) != entry["upstream_bytes"]:
                problems.append(
                    f"{entry.get('local_path')} 的上游字节数与审计副本不符: {entry.get('upstream_path')}")
            else:
                checked += 1
        else:
            unchecked += 1

    if unchecked and not checked:
        return problems, f"{len(items)} 项（本机无审计副本，上游侧未核）"
    if unchecked:
        return problems, f"{checked}/{len(items)} 项上游侧已核，{unchecked} 项无审计副本未核"
    return problems, f"{len(items)} 项上游侧已核"


def check_original(doc: dict, taken: set) -> list:
    """本仓库自写、不对应上游文件的测试。只查「还存不存在」与「身份有没有重叠」——
    它没有上游对应物，所以没有任何哈希可核，这一点必须如实承认。"""
    problems = []
    section = doc.get("original")
    if not isinstance(section, dict):
        return problems
    items = section.get("items")
    if not isinstance(items, list):
        return [f"original.items 不是列表"]
    for entry in items:
        local = str(entry.get("local_path", ""))
        path = ROOT / local
        if not local or not path.is_file():
            problems.append(f"original 声明的 {local or '（空）'} 不存在")
            continue
        if local in taken:
            problems.append(f"{local} 同时出现在 files/derived 与 original（一处代码一种身份）")
        if not str(entry.get("why", "")).strip():
            problems.append(f"{local} 的 why 为空 —— 自写文件也要说清为什么必须有它")
        if not str(entry.get("verified_by", "")).strip():
            problems.append(f"{local} 没有 verified_by")
        taken.add(local)
    return problems


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

    adapted_lines = doc.get("adapted_header")
    if not isinstance(adapted_lines, list) or not adapted_lines:
        print("ERROR: 清单缺少 adapted_header（适配副本的来源头与逐字节副本不同，必须分开声明）",
              file=sys.stderr)
        return 1
    adapted_prefix = ("\n".join(adapted_lines) + "\n").encode("utf-8")

    registered = {entry["local_path"]: entry for entry in files}
    # derived（改写/适配后移植）没有可比的正文哈希，但它们同样占用导入目录里的
    # 文件路径，所以反向检查必须把它们算作「已登记」—— 否则把一份文件从
    # files[] 移到 derived[] 会被误判成「导入目录里有未登记的文件」。
    derived_paths = {
        entry["local_path"] for entry in ((doc.get("derived") or {}).get("items") or [])
    }

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

    # 反向：导入目录里不允许出现未登记的 .java。
    #
    # 三类身份都算「已登记」：逐字节副本（files）、改写适配（derived）、
    # 以及**本仓库自写**（original）。第三类必须减掉 —— 否则我们自己写的
    # 映射/测试会被误报成「上游文件漏登记」，而那正好是这套检查最怕的
    # 那种误报（它逼人把自写文件塞进 files[] 或干脆不写）。
    #
    # ⚠️ 这里放宽的是**「谁是上游」这个集合**，不是「要不要登记」：
    # 一个既不在 files 也不在 derived、也不在 original 里的 .java 仍然会红。
    # verify-phase0.sh 的 5o 节用一个真实注入证明这一点（只放宽、没变瞎）。
    original_paths = {
        str(entry.get("local_path", ""))
        for entry in ((doc.get("original") or {}).get("items") or [])
    }
    on_disk = {
        path.relative_to(ROOT).as_posix()
        for path in IMPORT_ROOT.rglob("*.java")
    }
    unregistered = sorted(on_disk - set(registered) - derived_paths - original_paths)
    # 登记了但不在导入目录里的（写错路径）也一并报出来
    stray = sorted(set(registered) - on_disk - set(missing))

    derived_problems, derived_note = check_derived(doc, adapted_prefix, prefix)
    # 一处代码只能有一种身份：逐字节副本 / 改写适配 / 本仓库自写。
    taken = set(registered) | set(derived_paths)
    original_problems = check_original(doc, taken)

    if (missing or bad_header or mismatched or unregistered or stray
            or derived_problems or original_problems):
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
        for item in derived_problems:
            print(f"  ✗ 改写移植登记有问题: {item}", file=sys.stderr)
        for item in original_problems:
            print(f"  ✗ 自写文件登记有问题: {item}", file=sys.stderr)
        # 提示只在真的与逐字节副本有关时出现 —— 错在 derived 段时还讲「正文按设计是
        # 逐字节副本」会把人引到错的地方。
        if missing or bad_header or mismatched or unregistered or stray:
            print(
                "\n这些文件的**正文**按设计是 DSHA 的逐字节副本（只有来源头是本项目加的）。"
                "若确实需要改动，请先把该文件从 files[] 移到 derived.items[] 并写清改了什么。",
                file=sys.stderr)
        if derived_problems:
            print(
                "\nderived 段是「改写/适配后移植」的登记：正文本来就与上游不同，"
                "所以这里查的不是哈希，而是**改写者有没有说清保留与改动**。",
                file=sys.stderr)
        return 1

    total_bytes = sum((ROOT / r).stat().st_size for r in registered)
    upstream = doc.get("upstream", {})
    print(
        f"DSHA 导入副本核验通过：{len(registered)} 个文件、{total_bytes} 字节（含来源头），"
        f"正文逐字节与 {upstream.get('id', '?')}@{str(upstream.get('locked_commit', '?'))[:12]} 一致；"
        f"导入目录内无未登记文件；改写/适配移植 {derived_note}。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
