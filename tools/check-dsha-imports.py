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
# DSHFusion 自己写的代码是 Kotlin（com.dshfusion.app.*），不在这些目录里。
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


def check_not_imported_disjoint(doc: dict, taken_upstream: set) -> list:
    """`not_imported` 与「导入了」的两侧必须**不相交**。

    ## 为什么需要这一条（它不是防呆，是防一份**自相矛盾的权威文件**）

    实测过：这个登记文件里同时存在三条自相矛盾的记录 ——
    `BackupTree.java` 既在 `derived`（2.4a 适配导入）又在 `not_imported`；
    `NativeRestorePlan.java` 既在 `files`（2.4a 逐字节导入）又在 `not_imported`；
    `HostDataTransaction.java` 也在两侧。**而在此之前没有任何检查会红。**

    它比「少登记一个文件」更危险：`dsha-imports.json` 是「我们从上游拿了什么」的
    权威答案，下一个接手的人会**读到 `not_imported` 那一句并相信它** ——
    于是永远不去看那个其实已经在树里的文件。这正是第 33 条的成因
    （我写下一句没验证的「没搬」，就重写了一个本来就在可达范围内的实现）。

    只查「不相交」，不查「为什么没搬」写得好不好 —— 后者不可机械判定。
    但 `not_imported` 的 `reason` 非空仍然要求，因为空理由等于没有理由。

    ⚠️ **比的是 `upstream_path`，不是 `local_path`。** 第一版我拿
    `not_imported[].upstream_path` 去比一个**本机路径**的集合，于是两边永远不可能相等 ——
    **它是一条不可能变红的检查**（第 30 条）。是 5p 节的注入把它抓出来的：
    我把一条已经在 `files[]` 里的路径塞进 `not_imported`，它照样报通过。
    """
    problems = []
    # 上游路径 → 本机路径的两种身份都要比：逐字节副本与改写适配。
    imported_upstream = set(taken_upstream)
    # ⚠️ **合成的组合路径绕过了上面的集合比较。** `"A.java + B.java"` 与任何真实路径
    # 都不相等 —— 于是一条把「已导入的 A」和「没导入的 B」绑在一起的条目，
    # 能完美通过那条相交检查。实测发生过：AndroidBackupFileSystem 逐字节导入之后，
    # 那条组合条目仍然把它列在 not_imported 里，而检查器一声不吭。
    # 所以除了整串相等，还要逐个**组成部分**比一次。
    imported_names = {path.rsplit("/", 1)[-1] for path in imported_upstream}
    section = doc.get("not_imported")
    if not isinstance(section, dict):
        return problems
    items = section.get("items")
    if not isinstance(items, list):
        return ["not_imported.items 不是列表"]
    for entry in items:
        upstream_path = str(entry.get("upstream_path", ""))
        if not upstream_path:
            problems.append("not_imported 有一项没有 upstream_path")
            continue
        if upstream_path in imported_upstream:
            problems.append(
                f"{upstream_path} 同时在「已导入」（files/derived）与 not_imported 里 —— "
                "登记文件在自相矛盾：它既说拿了、又说不拿")
        else:
            # 组合路径：逐段比。`reason` 里解释「为什么这几个一起没搬」是合理的，
            # 但**不能把已经搬进来的那个也算进去**。
            for part in (piece.strip() for piece in upstream_path.split("+")):
                if part and (part in imported_upstream
                             or part.rsplit("/", 1)[-1] in imported_names):
                    problems.append(
                        f"{upstream_path} 里的 {part.rsplit('/', 1)[-1]} 其实**已经在**"
                        "「已导入」（files/derived）里了 —— 组合路径把已导入的和没导入的"
                        "绑在一条里，于是它绕过了上面那条集合比较")
        if not str(entry.get("reason", "")).strip():
            problems.append(f"{upstream_path} 的 reason 为空 —— 「没搬」也要说清为什么")
    return problems


def _texts(path: pathlib.Path):
    """把一个路径展开成 (相对路径, 文本) 列表。目录则递归取文本文件。"""
    if path.is_file():
        try:
            return [(path, path.read_text(encoding="utf-8", errors="replace"))]
        except OSError:
            return []
    out = []
    for child in sorted(path.rglob("*")):
        if child.is_file() and child.suffix in {".java", ".kt", ".kts", ".py", ".sh", ".json"}:
            try:
                out.append((child, child.read_text(encoding="utf-8", errors="replace")))
            except OSError:
                pass
    return out


def run_not_imported_verifications(doc: dict) -> list:
    """真跑 `not_imported[].verify` 里的每一条断言。

    ## 为什么这一节必须真跑，而不是「写了就算」

    第 33 条的成因是我在登记文件里写下一句**没验证过**的「没搬」，然后自己相信了它，
    于是从零重写了一个上游本来就有、而且更严密的实现。

    登记文件的性质决定了它错得更贵：**它会终止下一个读它的人的怀疑。**
    所以每一句「为什么没搬」都配一条可执行的断言，由这里跑。

    三种断言（路径可以是文件，也可以是目录 —— 目录递归查）：
    - `absent`           ：这些路径必须不存在
    - `must_not_contain` ：这些路径里不许出现某个标识符
    - `must_contain`     ：这些路径里必须出现某个标识符

    ⚠️ 只跑声明，不判断「这条声明是否真的证明了那个理由」——
    后者不可机械判定。但**「理由」里出现过的每个标识符至少要被某条断言碰到**，
    这条可判定性很弱，所以我不假装它成立；本函数只保证：**声明不成立时它一定红。**
    """
    problems = []
    section = doc.get("not_imported")
    if not isinstance(section, dict):
        return problems
    items = section.get("items") or []
    checked = 0
    for entry in items:
        name = str(entry.get("upstream_path", "?"))
        verify = entry.get("verify")
        if not isinstance(verify, dict) or not verify:
            problems.append(f"{name} 没有 verify —— 「没搬」的理由没有可执行的验证方式（第 33 条）")
            continue
        if not str(verify.get("note", "")).strip():
            problems.append(f"{name} 的 verify 没有 note —— 说清这条检查为什么能证明上面那个理由")
            continue

        for relative in verify.get("absent") or []:
            checked += 1
            if (ROOT / relative).exists():
                problems.append(f"{name} 的 verify 说 {relative} 不存在，而它**在**")
                continue
            # ⚠️ 「不存在」有两种原因：① 它确实没被搬进来（我们想证的）；
            # ② 我写错了路径，而那个路径本来就没人有（我们什么也没证）。
            # 两者在结果上**长得一模一样** —— 所以要求父目录必须存在。
            # 实测抓到过：我把 absent 写成了上游的 `app/src/...` 布局，
            # 三条断言全绿，绿得毫无意义；而同一组里 present 那条当场红了。
            parent = (ROOT / relative).parent
            if not parent.is_dir():
                problems.append(
                    f"{name} 的 verify 说 {relative} 不存在 —— 但它的父目录 "
                    f"{parent.relative_to(ROOT).as_posix()} 也不存在，"
                    "所以这条断言什么都没证明（很可能路径写错了）")

        for relative in verify.get("present") or []:
            checked += 1
            if not (ROOT / relative).exists():
                problems.append(f"{name} 的 verify 说 {relative} 应该在，而它**不在**")

        for key, want in (("must_contain", True), ("must_not_contain", False)):
            for item in verify.get(key) or []:
                checked += 1
                relative, text = str(item.get("path", "")), str(item.get("text", ""))
                target = ROOT / relative
                if not target.exists():
                    problems.append(f"{name} 的 verify 指向不存在的路径 {relative}")
                    continue
                scanned = _texts(target)
                # ⚠️ 目录里一个可扫的文本文件都没有时，`must_not_contain` 会**静默通过** ——
                # 它是一条不可能变红的断言（第 30 条）。这里把它变成一条问题。
                # 实测抓到过：我对着只放 .so 的 jniLibs 目录写 must_contain，红了；
                # 而同一目录上的 must_not_contain 是**绿**的，绿得毫无意义。
                if target.is_dir() and not scanned:
                    problems.append(
                        f"{name} 的 verify 指向目录 {relative}，但那里一个可扫的文本文件都没有 —— "
                        "这条断言不可能变红")
                    continue
                hits = [p for p, body in scanned if text in body]
                if want and not hits:
                    problems.append(f"{name} 的 verify 要求 {relative} 里出现 {text!r}，实际没有")
                if not want and hits:
                    problems.append(
                        f"{name} 的 verify 要求 {relative} 里不出现 {text!r}，"
                        f"实际出现在 {hits[0].relative_to(ROOT).as_posix()}")
    return problems + [] if False else problems, checked


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
    # `taken` 现在含 original；not_imported 必须与**全部**「已导入」不相交。
    # ⚠️ 传的是**上游路径**集合。`taken` 里是 local_path，键空间不同，
    # 拿它比会让这条检查永远为真（第一版就是这么错的）。
    upstream_taken = (
        {str(f.get("upstream_path", "")) for f in files}
        | {str(i.get("upstream_path", "")) for i in ((doc.get("derived") or {}).get("items") or [])}
    )
    not_imported_problems = check_not_imported_disjoint(doc, upstream_taken)
    verify_problems, verify_checked = run_not_imported_verifications(doc)

    if (missing or bad_header or mismatched or unregistered or stray
            or derived_problems or original_problems or not_imported_problems or verify_problems):
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
        for item in not_imported_problems:
            print(f"  ✗ not_imported 登记有问题: {item}", file=sys.stderr)
        for item in verify_problems:
            print(f"  ✗ not_imported 的验证声明不成立: {item}", file=sys.stderr)
        # 提示只在真的与逐字节副本有关时出现 —— 错在 derived 段时还讲「正文按设计是
        # 逐字节副本」会把人引到错的地方。
        if missing or bad_header or mismatched or unregistered or stray:
            print(
                "\n这些文件的**正文**按设计是 DSHA 的逐字节副本（只有来源头是本项目加的）。"
                "若确实需要改动，请先把该文件从 files[] 移到 derived.items[] 并写清改了什么。",
                file=sys.stderr)
        if not_imported_problems:
            print(
                "\nnot_imported 是「我们**没有**从上游拿这个文件」的清单。"
                "同一路径不能既在 files/derived/original 里、又在 not_imported 里 —— "
                "那会让下一个读登记的人以为树里没有这个文件（见 docs/INCIDENTS.md 第 33 条）。",
                file=sys.stderr)
        if verify_problems:
            print(
                "\nnot_imported 的 `verify` 是**可执行的**：它由本检查器真跑。"
                "理由说「没搬」，就得有一条断言证明「确实不在 / 依赖的东西确实不在 / 替代物确实在」。"
                "第 33 条就是因为一句没验证的「没搬」而重写了一个上游本来就有的实现。",
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
        f"导入目录内无未登记文件；改写/适配移植 {derived_note}；"
        f"not_imported 的 {verify_checked} 条验证声明全部成立。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
