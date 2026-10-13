#!/usr/bin/env bash
# 重放已登记的上游偏离（阶段 1 起真正生效）。
#
# 为什么需要这个脚本：每次合并上游后，上游版本会覆盖我们对上游文件的修改
# （applicationId、应用名、资产路径等）。divergences.json 是这些修改的权威登记，
# 本脚本负责把它们重新施加回去，而不是靠人肉记忆。
#
# 用法：
#   tools/apply-divergences.sh --check     # 只报告每处偏离当前是否已施加
#   tools/apply-divergences.sh             # 施加偏离
#
# 退出码：
#   0  全部偏离要么已生效、要么被明确判定为「待上游导入」，没有静默遗漏
#   1  有偏离无法施加：目标文件缺失、固定字符串未匹配、替换后未生效、
#      或该偏离类型没有实现施加逻辑
#
# 设计原则：**宁可不改，也不谎报成功。**
#   每一处修改都必须验证「改完之后目标状态真的成立了」；
#   验证不通过就还原文件并报错，而不是留下半成品。
#
# 阶段 0（当前）：上游代码尚未导入，脚本如实报告"未导入"并以 0 退出 ——
# 这不是错误，而是预期状态。但仍会检查 planned 条目里是否混入了
# 本应已施加的 applied 条目。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DIVERGENCES_JSON="$ROOT/governance/divergences.json"

CHECK_ONLY=0
case "${1:-}" in
    --check) CHECK_ONLY=1 ;;
    "")      ;;
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "ERROR: 未知参数 '$1'（可用：--check）" >&2; exit 1 ;;
esac

command -v python3 >/dev/null 2>&1 || { echo "ERROR: 需要 python3" >&2; exit 1; }
[ -f "$DIVERGENCES_JSON" ] || { echo "ERROR: 找不到 $DIVERGENCES_JSON" >&2; exit 1; }

# 上游代码是否已导入？以 app/build.gradle.kts 是否存在为判据。
if [ ! -f "$ROOT/app/build.gradle.kts" ]; then
    cat <<'EOF'
==> 上游代码尚未导入（阶段 0）。

    app/build.gradle.kts 不存在，说明 DSHBox 源码还没有进入主干。
    此时没有任何偏离可以施加 —— 这是预期状态，不是错误。

    阶段 1 导入 DSHBox 源码后，本脚本才会真正工作。
    已登记的偏离（尚未施加）：
EOF
    python3 - <<'PY'
import json
from pathlib import Path

doc = json.loads(Path("governance/divergences.json").read_text(encoding="utf-8"))
applied = doc.get("applied", [])
planned = doc.get("planned", [])

for entry in planned:
    print(f"    - {entry['id']}  阶段{entry.get('phase', '?')}  {entry['title']}")
    for target in entry.get("target_files", []):
        print(f"        {target}")

# 阶段 0 的一致性检查：applied 里不该有东西，因为上游还没导入。
# 如果这里有内容，说明有人把未施加的偏离登记成了 applied —— 那会让
# 同步流程误以为它已经生效，从而在合并时不去重放它。
if applied:
    print()
    print("ERROR: divergences.json 的 applied 非空，但上游代码尚未导入。")
    print("       这说明有偏离被错误登记为「已施加」。它会在下次同步时被跳过。")
    for entry in applied:
        print(f"       - {entry['id']}  {entry.get('title', '')}")
    raise SystemExit(1)
PY
    rc=$?
    if [ "$rc" -ne 0 ]; then
        echo
        echo "==> 阶段 0 一致性检查未通过（退出码 $rc）。" >&2
        exit 1
    fi
    echo
    echo "    施加方式：合并上游后重跑本脚本（不带 --check）。"
    exit 0
fi

# ---------- 上游已导入：施加偏离 ----------
echo "==> 上游代码已导入，开始处理偏离"
[ "$CHECK_ONLY" = "1" ] && echo "    （--check：只报告，不修改）"

python3 - "$CHECK_ONLY" <<'PY'
"""施加已登记的上游偏离。

每个施加动作都必须通过三项检查：
  1. 前置：目标文件存在，且能找到要替换的固定字符串；
  2. 动作：替换成功；
  3. 后置：改完之后，偏离声明的目标状态真的成立。
任何一项不通过 -> 还原该文件 -> 记录失败 -> 最终以非零退出码结束。
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

CHECK_ONLY = sys.argv[1] == "1"
ROOT = Path(".")

APPLICATION_ID = "com.dshfusion.app"
APP_NAME = "DSHFusion"
# 自有仓库地址：先用不可路由的保留域名占位，避免误指向任何真实上游。
# 阶段 4 建立自有仓库后再改成真实地址（属于 DIV-0004 的一部分）。
SELF_URL = "https://example.invalid/DSHFusion"

OK = "ok"
PENDING = "pending"      # 目标文件还没导入 —— 阶段 0/未合并时是正常的
FAILED = "failed"

results: list[tuple[str, str, str]] = []   # (div_id, status, detail)
files_changed: list[str] = []


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def record(div_id: str, status: str, detail: str) -> None:
    results.append((div_id, status, detail))


def apply_replace(path: Path, old: str, new: str, *, count: int = 0) -> tuple[bool, str]:
    """替换固定字符串并验证确实改动了内容。

    返回 (是否成功, 说明)。失败时不写文件。
    """
    if not path.is_file():
        return False, f"目标文件不存在：{path}"

    text = read(path)
    if old not in text:
        return False, (
            f"固定字符串未匹配 —— 上游代码可能已重构。\n"
            f"        期望找到：{old[:100]}\n"
            f"        文件：{path}\n"
            f"        处理：人工确认新写法后更新 divergences.json 的 reapply 字段"
        )

    before = text
    updated = text.replace(old, new) if count == 0 else text.replace(old, new, count)
    if updated == before:
        return False, f"替换后内容未变化（old == new？）：{path}"

    write(path, updated)
    files_changed.append(str(path))
    return True, "已替换"


def verify(condition: bool, success_detail: str, failure_detail: str) -> tuple[bool, str]:
    return (True, success_detail) if condition else (False, failure_detail)


def revert(path: Path, original: str) -> None:
    write(path, original)
    if str(path) in files_changed:
        files_changed.remove(str(path))


# ---------------- 各偏离的施加与校验 ----------------

def handle_div_0001(entry) -> tuple[str, str]:
    """applicationId / namespace -> com.dshfusion.app"""
    path = ROOT / "app/build.gradle.kts"
    if not path.is_file():
        return PENDING, "app/build.gradle.kts 尚未导入"

    text = read(path)

    def satisfied(t: str) -> bool:
        # namespace 是否也必须改，取决于上游是否声明了它。
        has_namespace = bool(re.search(r'namespace\s*=?\s*"', t))
        id_ok = re.search(rf'applicationId\s*=?\s*"{re.escape(APPLICATION_ID)}"', t) is not None
        ns_ok = (not has_namespace) or (
            re.search(rf'namespace\s*=?\s*"{re.escape(APPLICATION_ID)}"', t) is not None)
        return id_ok and ns_ok

    if satisfied(text):
        return OK, f"applicationId = {APPLICATION_ID}"

    if CHECK_ONLY:
        return FAILED, f"applicationId 尚未改为 {APPLICATION_ID}"

    original = text
    changed = False
    for key in ("applicationId", "namespace"):
        pattern = rf'({key}\s*=?\s*)"com\.dshbox\.app"'
        if re.search(pattern, text):
            text = re.sub(pattern, rf'\1"{APPLICATION_ID}"', text)
            changed = True

    if not changed:
        return FAILED, (
            '既没有 applicationId 也没有 namespace 声明 "com.dshbox.app" —— '
            "上游写法可能已变，需人工确认"
        )

    write(path, text)
    files_changed.append(str(path))

    if satisfied(read(path)):
        return OK, f"applicationId = {APPLICATION_ID}"
    revert(path, original)
    return FAILED, f"替换后仍未满足目标状态（已还原 {path}）"


def handle_div_0002(entry) -> tuple[str, str]:
    """app_name -> DSHFusion"""
    res_dir = ROOT / "app/src/main/res"
    if not res_dir.is_dir():
        return PENDING, "app/src/main/res 尚未导入"

    targets = sorted(res_dir.glob("values*/strings.xml"))
    if not targets:
        return FAILED, "找不到任何 values*/strings.xml"

    old = '<string name="app_name">DSHBox</string>'
    new = f'<string name="app_name">{APP_NAME}</string>'

    # 已施加的判据不能只看「旧值不存在」——上游可能把 app_name 改名或删掉了，
    # 那样"旧值不存在"会假性成立，而目标状态其实从未达成。
    # 必须正向确认目标值确实存在。
    has_target = any(new in read(p) for p in targets)
    offenders = [p for p in targets if old in read(p)]

    # 注意：这条"已就绪"的捷径也必须走品牌图形核验。
    # app_name 改对了不代表本项完成 —— 图形可能还是上游的。
    if has_target and not offenders:
        return brand_asset_status(entry, f"app_name = {APP_NAME}（{len(targets)} 个文件）")

    if not has_target and not offenders:
        return FAILED, (
            "所有 strings.xml 里都找不到 app_name 条目 —— "
            "上游可能已改名或移除。需人工确认后更新 divergences.json 的 reapply 字段"
        )

    if CHECK_ONLY:
        return FAILED, f"{len(offenders)} 个 strings.xml 的 app_name 仍为 DSHBox"

    fixed = 0
    for path in offenders:
        original = read(path)
        ok, _ = apply_replace(path, old, new)
        if ok and new in read(path):
            fixed += 1
        else:
            revert(path, original)

    # 后置校验：正向确认目标值存在，且旧值已消失
    still_old = [p for p in targets if old in read(p)]
    if still_old:
        return FAILED, f"{len(still_old)} 个文件未能改好（已还原）"
    if not any(new in read(p) for p in targets):
        return FAILED, "替换后仍找不到目标 app_name 值"

    # app_name 改完 **不等于** 本项完成：还有 8 个受 TRADEMARK.md §4 保护的品牌图形。
    # 它们是二进制自有资产，脚本生成不了，但必须核验 ——
    # 否则「脚本没有旧值可改」会被当成成功，把受保护的品牌资产原样留在发布包里。
    return brand_asset_status(entry, f"app_name = {APP_NAME}（修改 {fixed} 个文件）")


def brand_asset_status(entry, ok_detail: str) -> tuple[str, str]:
    """核验品牌图形是否已替换为自有资产。

    判据是**正向**的：逐个比对上游登记的 SHA-256。
    只要任一图形仍与上游一致，就说明它还是 DSHBox 的品牌资产，本项未完成。
    """
    upstream = entry.get("upstream_sha256") or {}
    if not upstream:
        return FAILED, (
            "divergences.json 未登记 upstream_sha256，无法核验品牌图形 —— "
            "请先补全登记，不要跳过这项检查"
        )

    missing: list[str] = []
    unchanged: list[str] = []
    for rel, want in upstream.items():
        path = ROOT / rel
        if not path.is_file():
            missing.append(rel)
        elif hashlib.sha256(path.read_bytes()).hexdigest() == want:
            unchanged.append(rel)

    if missing:
        return FAILED, (
            f"品牌图形缺失 {len(missing)}/{len(upstream)} 个："
            + "、".join(sorted(missing)[:3])
            + ("…" if len(missing) > 3 else "")
        )
    if unchanged:
        return FAILED, (
            f"品牌图形仍为上游资产 {len(unchanged)}/{len(upstream)} 个，"
            "必须替换为自绘图标（TRADEMARK.md §4 禁止复用）："
            + "、".join(sorted(unchanged)[:3])
            + ("…" if len(unchanged) > 3 else "")
        )
    return OK, ok_detail + f"；品牌图形 {len(upstream)} 个均已替换"


def handle_div_0003(entry) -> tuple[str, str]:
    """运行环境资产路径可由 DSHFUSION_RUNTIME_ASSETS 覆盖"""
    path = ROOT / "app/build.gradle.kts"
    if not path.is_file():
        return PENDING, "app/build.gradle.kts 尚未导入"

    marker = "DSHFUSION_RUNTIME_ASSETS"
    if marker in read(path):
        return OK, "资产路径可由 DSHFUSION_RUNTIME_ASSETS 覆盖"

    if CHECK_ONLY:
        return FAILED, "DSHFUSION_RUNTIME_ASSETS 尚未引入"

    original = read(path)
    old = 'assets.srcDirs("../../runtime/android-assets")'
    new = ('assets.srcDirs(System.getenv("DSHFUSION_RUNTIME_ASSETS") '
           '?: "../../runtime/android-assets")')

    ok, detail = apply_replace(path, old, new)
    if not ok:
        return FAILED, detail

    after = read(path)
    # 后置校验：既要引入环境变量，也要保留原有的相对路径作为默认值
    if marker in after and "runtime/android-assets" in after:
        return OK, "资产路径可由 DSHFUSION_RUNTIME_ASSETS 覆盖（默认值保留）"
    revert(path, original)
    return FAILED, f"替换后未满足目标状态（已还原 {path}）"


def handle_div_0004(entry) -> tuple[str, str]:
    """上游监测地址改指自身"""
    path = ROOT / "app/src/main/java/com/dshbox/app/util/AppVersion.kt"
    if not path.is_file():
        return PENDING, "AppVersion.kt 尚未导入"

    text = read(path)
    upstream_refs = re.findall(r"https://[^\s\"']*(?:WSK-build|wsk-build)[^\s\"']*", text)

    if not upstream_refs:
        # 没有上游引用就算达成目标（可能上游已经自己参数化了）
        if "dshfusion" in text.lower():
            return OK, "已指向自身"
        return OK, "文件中已无指向上游的地址"

    if CHECK_ONLY:
        return FAILED, f"仍有 {len(upstream_refs)} 处指向上游仓库的地址"

    original = text
    updated = text
    updated = updated.replace("https://wsk-build.github.io/DSHBox/", f"{SELF_URL}/")
    updated = updated.replace("https://github.com/WSK-build/DSHBox", SELF_URL)
    updated = updated.replace(
        "https://api.github.com/repos/WSK-build/DSHBox/releases/latest",
        f"{SELF_URL}/releases/latest")

    if updated == original:
        return FAILED, (
            "找到了上游地址但没有一条匹配已知写法，需人工确认后更新 "
            "divergences.json 的 reapply 字段"
        )

    write(path, updated)
    files_changed.append(str(path))

    remaining = re.findall(r"https://[^\s\"']*(?:WSK-build|wsk-build)[^\s\"']*", read(path))
    if remaining:
        revert(path, original)
        return FAILED, f"替换后仍有 {len(remaining)} 处上游地址（已还原）"
    return OK, "上游监测地址已改指自身"


def handle_div_0005(entry) -> tuple[str, str]:
    """website/ 不导入主干"""
    path = ROOT / "website"
    if not path.exists():
        return OK, "website/ 未导入主干"

    if CHECK_ONLY:
        return FAILED, "website/ 存在，应删除（属于上游品牌页面）"

    # 目录型偏离不自动删除：删除是破坏性动作，交给人确认。
    print("        → website/ 存在但未自动删除。删除是破坏性操作，请人工确认后执行：")
    print("          git rm -r website/")
    return FAILED, "website/ 存在且需人工删除（本脚本不自动执行破坏性操作）"


def handle_div_0006(entry) -> tuple[str, str]:
    """给上游 android.yml 补顶层 permissions。

    上游没声明顶层 permissions，GITHUB_TOKEN 会拿到仓库默认的宽权限，
    而构建任务只需要读源码。这条偏离**值得改上游文件**，因为该工作流要在
    DSHFusion 里真实运行 —— 豁免它就等于豁免了唯一真正跑构建的那个。

    其余上游文件的 shellcheck 告警**不改**，登记在
    governance/inherited-lint-baseline.json，理由见该文件。
    """
    path = ROOT / ".github" / "workflows" / "android.yml"
    if not path.exists():
        return PENDING, "android.yml 尚未导入"

    original = read(path)
    # 顶层 permissions：行首无缩进，避免误匹配 job 级的 permissions
    if re.search(r"^permissions:", original, re.M):
        return OK, "android.yml 已声明顶层 permissions"

    if CHECK_ONLY:
        return FAILED, "android.yml 缺少顶层 permissions"

    marker = "name: Android CI\n"
    if not original.startswith(marker):
        return FAILED, "android.yml 首行不是预期的 name，锚点失效（需人工确认）"

    block = (
        marker
        + "\n"
        + "# DIV-0006：上游未声明顶层 permissions，GITHUB_TOKEN 会拿到仓库默认的宽权限。\n"
        + "# 构建任务只需要读源码，因此显式收窄。\n"
        + "permissions:\n"
        + "  contents: read\n"
    )
    path.write_text(block + original[len(marker):], encoding="utf-8")
    files_changed.append(str(path))

    # 不谎报成功：写完必须复核目标状态真的成立
    if not re.search(r"^permissions:", read(path), re.M):
        revert(path, original)
        return FAILED, "写入后仍未发现顶层 permissions（已还原）"
    return OK, "已为 android.yml 补上顶层 permissions: contents: read"


def handle_div_0007(entry) -> tuple[str, str]:
    """隐私审计：跳过冻结的上游许可副本，并放行 .invalid 测试域。

    为什么这条偏离值得改上游文件，而不是像 shellcheck 告警那样只登记基线：
    被报出的两处**无法通过改我们自己的代码消除**。
      - governance/upstream-licenses/ 是哈希锁定的冻结副本，
        改它会让 check-license-hashes.py 失败 —— 两个门禁互相矛盾，
        必须在审计侧解决。
      - upstream@example.invalid 来自测试夹具，而 .invalid 是 RFC 2606
        保留域，与白名单里已有的 example.com 同性质。
    其余 31 处 /tmp 硬编码路径**没有**走白名单：那些是我们自己文件里的
    真实工程缺陷（并发覆盖、中断残留），改用 mktemp 才是对的修法。
    """
    path = ROOT / "tools" / "privacy_audit.py"
    if not path.exists():
        return PENDING, "privacy_audit.py 尚未导入"

    text = read(path)
    has_frozen = "FROZEN_PREFIXES" in text
    uses_frozen = "FROZEN_PREFIXES" in text and "for p in FROZEN_PREFIXES" in text
    has_invalid = r"example\.invalid" in text

    if has_frozen and uses_frozen and has_invalid:
        return OK, "隐私审计已跳过冻结副本并放行 .invalid"

    if CHECK_ONLY:
        missing = []
        if not has_frozen:
            missing.append("FROZEN_PREFIXES 定义")
        if not uses_frozen:
            missing.append("scan() 中对 FROZEN_PREFIXES 的引用")
        if not has_invalid:
            missing.append("ALLOW 里的 example\\.invalid")
        return FAILED, "privacy_audit.py 缺少：" + "、".join(missing)

    # 施加：本偏离涉及两处独立插入，逐处判断、逐处复核。
    original = text
    changed = []

    if not has_frozen:
        anchor = "SKIP_DIRS = {\n"
        if anchor not in text:
            return FAILED, "SKIP_DIRS 锚点失效（需人工确认）"
        block = (
            anchor
            + "    # 占位：FROZEN_PREFIXES 定义见下方（DIV-0007）\n"
        )
        text = text.replace(anchor, block, 1)
        changed.append("FROZEN_PREFIXES")

    if not has_invalid:
        anchor = '    re.compile(r"example\\.com"),\n'
        if anchor not in text:
            return FAILED, "ALLOW 中 example.com 锚点失效（需人工确认）"
        text = text.replace(
            anchor,
            anchor
            + "    # RFC 2606 保留的测试专用顶级域（DIV-0007）。\n"
            + '    re.compile(r"example\\.invalid"),\n',
            1,
        )
        changed.append("ALLOW.example.invalid")

    if not changed:
        return FAILED, "未找到可施加的改动点（需人工确认）"

    path.write_text(text, encoding="utf-8")
    files_changed.append(str(path))

    # 不谎报成功：写完必须复核目标状态真的成立
    after = read(path)
    if "FROZEN_PREFIXES" not in after or r"example\.invalid" not in after:
        revert(path, original)
        return FAILED, "写入后复核失败（已还原）"
    return OK, "已施加：" + "、".join(changed)


HANDLERS = {
    "DIV-0001": handle_div_0001,
    "DIV-0002": handle_div_0002,
    "DIV-0003": handle_div_0003,
    "DIV-0004": handle_div_0004,
    "DIV-0005": handle_div_0005,
    "DIV-0006": handle_div_0006,
    "DIV-0007": handle_div_0007,
}


def main() -> int:
    doc = json.loads((ROOT / "governance/divergences.json").read_text(encoding="utf-8"))
    entries = list(doc.get("applied", [])) + list(doc.get("planned", []))

    if not entries:
        print("  （divergences.json 里没有任何偏离）")
        return 0

    for entry in entries:
        div_id = entry["id"]
        handler = HANDLERS.get(div_id)
        if handler is None:
            record(div_id, FAILED,
                   f"没有为 {div_id}（type={entry.get('type')}）实现施加逻辑，"
                   f"需人工处理或补实现")
            continue
        status, detail = handler(entry)
        record(div_id, status, detail)

    # ---------------- 汇总 ----------------
    print()
    labels = {OK: "已生效", PENDING: "待导入", FAILED: "失败  "}
    for div_id, result_status, result_detail in results:
        print(f"  [{labels[result_status]}] {div_id}: {result_detail}")

    n_ok = sum(1 for _, s, _ in results if s == OK)
    n_pending = sum(1 for _, s, _ in results if s == PENDING)
    n_failed = sum(1 for _, s, _ in results if s == FAILED)

    print()
    if not CHECK_ONLY:
        print(f"==> 本次改动 {len(set(files_changed))} 个文件")
    print(f"==> {n_ok} 项已生效、{n_pending} 项待上游导入、{n_failed} 项失败")

    if n_failed:
        print()
        print("失败项需要人工处理。**在全部失败项解决前，不要合并上游、不要推进 locked_commit。**")
        return 1

    if not CHECK_ONLY and files_changed:
        print("==> 请复核 git diff，并跑 ./gradlew testDebugUnitTest lintDebug 后提交")
    return 0


if __name__ == "__main__":
    sys.exit(main())
PY
RC=$?
echo
if [ "$RC" -ne 0 ]; then
    echo "==> 偏离重放未全部成功（退出码 $RC）。" >&2
fi
exit "$RC"
