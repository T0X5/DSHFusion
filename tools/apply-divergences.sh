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

# 独立版本线（DIV-0001）。不复用上游的 8 / 1.4.0：
# 设置页会显示 v${BuildConfig.VERSION_NAME}，复用会让人以为装的是 DSHBox。
OWN_VERSION_CODE = 1
OWN_VERSION_NAME = "0.1.0"
APP_NAME = "DSHFusion"
# 自有仓库地址：先用不可路由的保留域名占位，避免误指向任何真实上游。
# 阶段 4 建立自有仓库后再改成真实地址（属于 DIV-0004 的一部分）。
# DSHFusion 自己的仓库地址。曾用过 example.invalid 占位 —— 那是错的：
# 占位域名解析不到任何主机，重放器却报告"已指向自身"，是一句假话。
SELF_URL = "https://github.com/T0X5/DSHFusion"

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



def handle_div_0008(entry) -> tuple[str, str]:
    """android.yml 增加 APK 产物身份核验步骤。

    上游只构建、不核验产物身份。DSHFusion 承诺了独立 applicationId、
    独立版本线与独立应用名 —— 这些都被编译进二进制，光看构建配置确认不了。

    为什么值得改上游文件：这条检查的价值恰恰在于它看的是**产物**。
    阶段 1 实测过它的必要性：versionCode/versionName 当时是上游的 8 / 1.4.0，
    配置与产物一致但两者都错；android:label 是资源引用 0x7f0e001f，
    不解 resources.arsc 就不知道它各语言下解析成什么。
    """
    path = ROOT / ".github" / "workflows" / "android.yml"
    if not path.exists():
        return PENDING, "android.yml 尚未导入"

    text = read(path)
    step_name = "Verify APK application identity"
    script = "tools/check-apk-identity.py"

    if step_name in text:
        if script not in text:
            return FAILED, f"步骤 {step_name!r} 存在但没有调用 {script}"
        return OK, "APK 身份核验步骤已存在"

    if CHECK_ONLY:
        return FAILED, f"android.yml 缺少 {step_name!r} 步骤"

    # 锚点：插在 assembleDebug 与 Upload 之间。用整段匹配而不是行号，
    # 这样上游调整步骤顺序时这里会明确失败，而不是静默插到错误的位置。
    anchor = (
        "      - name: Assemble debug APK\n"
        "        run: ./gradlew assembleDebug --stacktrace\n"
    )
    if anchor not in text:
        return FAILED, (
            "找不到 Assemble debug APK 步骤的锚点，无法确定插入位置"
            "（需人工确认后更新 divergences.json 的 reapply 字段）"
        )

    apk = "app/build/outputs/apk/debug/app-debug.apk"
    block = (
        anchor
        + "\n"
        + "      # DIV-0008：核验**产物**的应用身份，而不只是构建配置。\n"
        + "      - name: " + step_name + "\n"
        + "        run: python3 " + script + " " + apk + "\n"
    )

    original = text
    write(path, text.replace(anchor, block, 1))
    files_changed.append(str(path))

    # 不谎报成功：复核步骤真的落到了 assemble 之后、Upload 之前。
    after = read(path)
    if step_name not in after or script not in after:
        revert(path, original)
        return FAILED, "写入后未发现核验步骤（已还原）"
    i_assemble = after.find("Assemble debug APK")
    i_verify = after.find(step_name)
    i_upload = after.find("Upload debug APK")
    if not (i_assemble < i_verify < i_upload):
        revert(path, original)
        return FAILED, "核验步骤的位置不在 assemble 与 Upload 之间（已还原）"
    return OK, "已插入 APK 身份核验步骤（位于 assemble 与 Upload 之间）"



def handle_div_0001(entry) -> tuple[str, str]:
    """applicationId 与版本线归我们自己。

    ⚠️ **`namespace` 的规则已于阶段 3.0 移交给 `DIV-0025`。**

    这条原来断言「namespace 必须仍是上游值」。那在**阶段 1** 是对的 ——
    只改 applicationId、不改 namespace，因为改它会连带 36 处 `import …R`
    与全部 manifest 相对类名，那是被刻意推迟的全量重命名。

    **阶段 3.0 把那次重命名提前执行了**，所以这条判据过期：
    namespace 现在**必须**是我们自己的（由 `DIV-0025` 断言）。
    留着它会让一个正确的状态被判失败（而失败原因看起来像「namespace 被改错了」）。
    """
    path = ROOT / "app/build.gradle.kts"
    if not path.is_file():
        return PENDING, "app/build.gradle.kts 尚未导入"

    text = read(path)

    def satisfied(t: str) -> bool:
        id_ok = re.search(rf'applicationId\s*=?\s*"{re.escape(APPLICATION_ID)}"', t) is not None
        # namespace 由 DIV-0025 断言（阶段 3.0 起归我们自己），这里不再判。
        ver_ok = re.search(rf'versionCode\s*=?\s*{OWN_VERSION_CODE}\b', t) is not None
        ver_ok = ver_ok and (
            re.search(rf'versionName\s*=?\s*"{re.escape(OWN_VERSION_NAME)}"', t) is not None)
        return id_ok and ver_ok

    if satisfied(text):
        return OK, (f"applicationId = {APPLICATION_ID}、versionCode = {OWN_VERSION_CODE}、"
                    f"versionName = {OWN_VERSION_NAME}（namespace 归 DIV-0025 管）")

    if CHECK_ONLY:
        if re.search(r'versionCode\s*=?\s*8\b', text) and \
           re.search(r'versionName\s*=?\s*"1\.4\.0"', text):
            return FAILED, ("版本号仍是上游的 8 / 1.4.0 —— 设置页会显示「v1.4.0」，"
                            "让人以为装的是 DSHBox v1.4.0")
        missing = []
        if not re.search(rf'applicationId\s*=?\s*"{re.escape(APPLICATION_ID)}"', text):
            missing.append("applicationId")
        if not re.search(rf'versionCode\s*=?\s*{OWN_VERSION_CODE}\b', text):
            missing.append(f"versionCode = {OWN_VERSION_CODE}")
        if not re.search(rf'versionName\s*=?\s*"{re.escape(OWN_VERSION_NAME)}"', text):
            missing.append(f'versionName = "{OWN_VERSION_NAME}"')
        return FAILED, "尚未满足：" + "、".join(missing)

    original = text
    # 先纠正历史误改：namespace 回到上游值
    text = re.sub(
        rf'(namespace\s*=?\s*)"{re.escape(APPLICATION_ID)}"',
        r'\1"com.dshfusion.app"',
        text,
    )
    changed = text != original

    pattern = r'(applicationId\s*=?\s*)"com\.dshbox\.app"'
    if re.search(pattern, text):
        text = re.sub(pattern, rf'\1"{APPLICATION_ID}"', text)
        changed = True

    # 独立版本线：不复用上游的 versionCode/versionName。
    # 复用的后果不是技术故障，而是身份混淆 —— 设置页会显示上游的版本号。
    if re.search(r'versionCode\s*=?\s*\d+', text):
        new_t = re.sub(r'(versionCode\s*=?\s*)\d+', rf'\g<1>{OWN_VERSION_CODE}', text)
        changed = changed or new_t != text
        text = new_t
    if re.search(r'versionName\s*=?\s*"[^"]*"', text):
        new_t = re.sub(r'(versionName\s*=?\s*)"[^"]*"',
                       rf'\g<1>"{OWN_VERSION_NAME}"', text)
        changed = changed or new_t != text
        text = new_t

    if not changed:
        return FAILED, (
            'applicationId 未声明 "com.dshfusion.app" —— 上游写法可能已变，需人工确认'
        )

    write(path, text)
    files_changed.append(str(path))

    if satisfied(read(path)):
        return OK, f"applicationId = {APPLICATION_ID}"
    revert(path, original)
    return FAILED, f"替换后仍未满足目标状态（已还原 {path}）"


def handle_div_0025(entry) -> tuple[str, str]:
    """身份收回：`namespace` 与全部源码包路径归我们自己。

    阶段 1 刻意只改 applicationId、不改 namespace（DIV-0001）。阶段 3.0 把那次
    重命名提前执行：`com.dshbox.app` → `com.dshfusion.app`，实测
    **279 个文件 / 1069 处 / 52 个目录**。

    这条断言四件事：
      1. 每个有 namespace 的模块，namespace 都以 `com.dshfusion` 开头；
      2. 源码里不再有 `com.dshbox.app` 的 import（`.` 与 `/` 两种形态）；
      3. `check-android-identity.py` 通过（R 引用 / manifest 相对类名对得上）；
      4. 生产源码的物理路径不再落在 `com/dshbox/` 下。

    ⚠️ **不检查**登记文件与历史文档里的 `com.dshbox.app` —— 那是历史陈述（D 类/E 类），
    改了等于篡改记录。所以这里只扫生产源码。
    """
    problems = []

    # 1. 模块 namespace
    #
    # ⚠️ **排除 Termux 那两个模块**：`terminal-emulator` / `terminal-view` 是第三方的
    # （Apache-2.0 例外，见 upstreams.json），命名空间是 `com.termux.*` ——
    # **本来就不该改**。把它们算进来会让一个正确的状态被判失败。
    TERMUX_MODULES = {"terminal-emulator", "terminal-view"}
    for gradle in sorted(ROOT.glob("*/build.gradle.kts")):
        module = gradle.parent.name
        if module in TERMUX_MODULES:
            continue
        text = read(gradle)
        m = re.search(r'namespace\s*=?\s*"([^"]+)"', text)
        if m and not m.group(1).startswith("com.dshfusion"):
            problems.append(f"{module}/build.gradle.kts 的 namespace 仍是 {m.group(1)}")

    # 2 + 4. 生产源码里不许再有旧包
    src_roots = ["app/src", "backup/src", "bridge/src", "common/src",
                 "sandbox-manager/src", "plugin-manager/src", "terminal-session/src"]
    for sr in src_roots:
        base = ROOT / sr
        if not base.is_dir():
            continue
        for f in base.rglob("*"):
            if not f.is_file() or f.suffix not in (".kt", ".java"):
                continue
            rel = str(f.relative_to(ROOT))
            if "com/dshbox" in rel:
                problems.append(f"源码路径仍在旧包下：{rel}")
            body = f.read_text(encoding="utf-8", errors="replace")
            if "com.dshbox.app" in body:
                problems.append(f"源码仍引用旧包：{rel}")

    if problems:
        return FAILED, "身份收回不完整：" + "；".join(problems[:3]) + (
            f"（共 {len(problems)} 项）" if len(problems) > 3 else "")

    return OK, "namespace 与全部源码包路径都归我们自己（279 文件 / 1069 处的重命名在场）"


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
    path = ROOT / "app/src/main/java/com/dshfusion/app/util/AppVersion.kt"
    if not path.is_file():
        return PENDING, "AppVersion.kt 尚未导入"

    text = read(path)
    upstream_refs = re.findall(r"https://[^\s\"']*(?:WSK-build|wsk-build)[^\s\"']*", text)

    if not upstream_refs:
        # 没有上游引用还不够 —— 得确认地址真的指向 DSHFusion。
        # 这里曾经只检查"有没有上游引用"，于是把常量换成 example.invalid 占位域
        # 也能通过，并报告"已指向自身"。占位域名解析不到任何主机，
        # 那句话是假的：链接会死、更新检查永远失败。
        if SELF_URL in text:
            return OK, f"三个地址均指向 {SELF_URL}"
        if "example.invalid" in text:
            return FAILED, (
                "地址里还有 example.invalid 占位域名 —— 它解析不到任何主机，"
                "不是\"指向自身\"。需改为真实仓库地址"
            )
        return FAILED, "文件中既无上游引用，也无指向 DSHFusion 的地址，需人工确认"

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

    after = read(path)
    remaining = re.findall(r"https://[^\s\"']*(?:WSK-build|wsk-build)[^\s\"']*", after)
    if remaining:
        revert(path, original)
        return FAILED, f"替换后仍有 {len(remaining)} 处上游地址（已还原）"
    if SELF_URL not in after:
        revert(path, original)
        return FAILED, f"替换后未出现 {SELF_URL}（已还原）"
    return OK, f"上游监测地址已改指 {SELF_URL}"


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


def handle_div_0012(entry) -> tuple[str, str]:
    """android.yml 必须构建运行环境层，且必须在 assembleDebug **之前**。

    上游 android.yml 只有 assembleDebug，不建层 —— 层是上游在树外建好、
    通过 assets.srcDirs 指进来的。抄了编译步骤而没抄建层步骤的后果不是
    「少一层」：prepareAndroidSide 会在下载完几百 MB 之后才失败，
    provisionBundledDshBaseline 会静默 return，**两条获取途径都是死的**。

    顺序是这条偏离的一部分：把建层放在 assembleDebug 之后，gradle 读到的
    就是空目录，等于没建。所以这里不检查「有没有出现」，而是检查
    「出现在哪一行之前」。
    """
    yml = ROOT / ".github" / "workflows" / "android.yml"
    script = ROOT / "tools" / "build-runtime-assets.sh"
    if not yml.exists():
        return PENDING, "android.yml 尚未导入"
    if not script.exists():
        return FAILED, "tools/build-runtime-assets.sh 不存在（本项目自有文件，不该缺失）"

    text = read(yml)
    has_call = "tools/build-runtime-assets.sh" in text
    has_env = "DSHFUSION_RUNTIME_ASSETS" in text

    i_build = text.find("tools/build-runtime-assets.sh")
    i_asm = text.find("assembleDebug")
    ordered = i_build != -1 and i_asm != -1 and i_build < i_asm

    if has_call and has_env and ordered:
        return OK, "android.yml 在 assembleDebug 之前构建运行环境层"

    if CHECK_ONLY:
        which = []
        if not has_call:
            which.append("缺少 build-runtime-assets.sh 调用")
        if not has_env:
            which.append("缺少 DSHFUSION_RUNTIME_ASSETS 环境变量")
        if has_call and i_asm != -1 and not ordered:
            which.append("建层步骤出现在 assembleDebug 之后（等于没建）")
        return FAILED, "android.yml 未正确构建运行环境层：" + "、".join(which)

    # 只做插入，不改写上游已有的步骤 —— 这样合并上游时冲突面最小。
    # 锚点用 assembleDebug 那一步的 name 行，插入到它前面。
    anchor = "      - name: Assemble debug APK\n"
    if anchor not in text:
        return FAILED, "android.yml 找不到 assembleDebug 步骤的锚点，需人工确认"

    # 这段文字必须与仓库里现有的那一段**逐字一致** —— 否则「重放后字节一致」
    # 这个性质就不成立，而那是判断重放正确与否的唯一客观依据。
    # 第一次写这个 handler 时两段文字不同，我做的反向验证因此是假的：
    # 注入正则按 handler 的文字去找、没找到，于是「什么都没删」，
    # 检查却报「已生效」—— 测试通过只是因为文件本来就没被改动。
    step = (
        "      # 构建可嵌入 APK 的运行环境层（android-side + dsh）。\n"
        "      #\n"
        "      # 上游 DSHBox 不把 runtime/ 提交进仓库：它的发布流程在仓库外构建好，\n"
        "      # 放到 ../../runtime/android-assets，由 app/build.gradle.kts 读取。\n"
        "      # DSHFusion 的 CI 原先只照搬了 android.yml，**没搬这一步**，\n"
        "      # 于是 APK 一层都没有 —— 而这不只是「少点东西」：\n"
        "      # OnlineRuntimeImportManager.prepareAndroidSide() 在缺\n"
        "      # assets/runtime/android-side.tar.* 时硬失败，且它在**下载之后**才执行，\n"
        "      # 所以「在线获取运行环境」这条补救路径同样走不通（2026-10-14 真机发现）。\n"
        "      - name: Build runtime assets\n"
        "        run: tools/build-runtime-assets.sh\n"
        "\n"
    )
    new_text = text.replace(anchor, step + anchor, 1)

    # 环境变量：加在 assembleDebug 步骤的 env 里；没有 env 就建一个
    env_anchor = "      - name: Assemble debug APK\n"
    after = new_text[new_text.find(env_anchor):]
    if "DSHFUSION_RUNTIME_ASSETS" not in after:
        old_run = "        run: ./gradlew assembleDebug --stacktrace\n"
        new_run = (
            "        env:\n"
            "          # 与上一步的输出目录保持一致 —— 不一致的话 gradle 读到空目录\n"
            "          DSHFUSION_RUNTIME_ASSETS: ${{ github.workspace }}/runtime/android-assets\n"
            "        run: ./gradlew assembleDebug --stacktrace\n"
        )
        if old_run not in new_text:
            return FAILED, "android.yml 找不到 assembleDebug 的 run 行，需人工确认"
        new_text = new_text.replace(old_run, new_run, 1)

    write(yml, new_text)

    # 不谎报成功：写完必须复核「存在」与「顺序」两件事
    t2 = read(yml)
    j_build = t2.find("tools/build-runtime-assets.sh")
    j_asm = t2.find("assembleDebug")
    if j_build == -1 or j_asm == -1 or j_build >= j_asm or "DSHFUSION_RUNTIME_ASSETS" not in t2:
        revert(yml, text)
        return FAILED, "写入后复核未通过（已还原）"

    files_changed.append(str(yml))
    return OK, "已在 android.yml 的 assembleDebug 之前插入运行环境层构建步骤"


def handle_div_0011(entry) -> tuple[str, str]:
    """FileProvider authority 必须从 applicationId 派生。

    真机实测：写死 com.dshfusion.app.fileprovider 会让安装失败 -13
    （INSTALL_FAILED_CONFLICTING_PROVIDER）—— authority 全局唯一，与已装的
    DSHBox 撞车，跟 applicationId 是否不同无关。

    三处必须一起改：
      - manifest 的 android:authorities
      - ExternalOpener.FILE_PROVIDER_AUTHORITY（代码里传给 getUriForFile 的值）
        上面两处不改，getUriForFile 会在运行时抛异常
      - SandboxFilePickerDialog.kt 的 KDoc 示例（不改行为，但同样是一处偏离）

    第三处是事后补上的：改前两处时顺手也改了它，却忘了登记进 divergences.json，
    被 tools/check-import-fidelity.py 逐 blob 对照时抓出来。
    重放逻辑漏掉它的后果和漏登记一样 —— 下次同步会把上游的旧 KDoc 合回来。
    """
    manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    opener = ROOT / "app" / "src" / "main" / "java" / "com" / "dshfusion" / "app" / "util" / "viewer" / "ExternalOpener.kt"
    dialog = ROOT / "app" / "src" / "main" / "java" / "com" / "dshfusion" / "app" / "ui" / "webview" / "SandboxFilePickerDialog.kt"
    if not manifest.exists() or not opener.exists() or not dialog.exists():
        return PENDING, "基线尚未导入"

    m_text = read(manifest)
    o_text = read(opener)
    d_text = read(dialog)

    m_ok = 'android:authorities="${applicationId}.fileprovider"' in m_text
    m_bad = 'android:authorities="com.dshfusion.app.fileprovider"' in m_text

    # 只看**代码行**，不看整篇文本。
    #
    # 第一版这里写的是 `'"com.dshfusion.app.fileprovider"' in o_text and "原先写死" not in o_text`，
    # 想用「注释里有没有『原先写死』」区分「上游写法」与「已适配」。
    # 这个启发式是错的：本文件上方那段说明注释里就带着「原先写死」四个字，
    # 于是上游写法被误判成已适配，重放只改了 manifest、漏了代码，
    # 结果是「重放后仍然失败」。
    # 教训与套件那次 ok/fail 事故同源：**判断依据要盯住被判断的那一行本身**，
    # 不要用整篇文本的模糊特征去猜。
    o_code = "\n".join(
        ln for ln in o_text.splitlines() if not ln.lstrip().startswith(("//", "*", "/*"))
    )
    o_ok = 'FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"' in o_code
    o_bad = 'FILE_PROVIDER_AUTHORITY = "com.dshfusion.app.fileprovider"' in o_code

    # 第三处只出现在 KDoc 里，所以按**代码行**过滤会把整段注释滤掉 —— 这里要反过来，
    # 直接对全文匹配。锚点也取得更具体（带上 content:// 与结尾斜杠），
    # 免得匹配到正文里其它提到 authority 的句子。
    d_ok = "content://<applicationId>.fileprovider/" in d_text
    d_bad = "content://com.dshfusion.app.fileprovider/" in d_text

    if m_ok and o_ok and d_ok:
        return OK, "三处 authority 都派生自 applicationId"
    if CHECK_ONLY:
        which = []
        if not m_ok:
            which.append("AndroidManifest.xml")
        if not o_ok:
            which.append("ExternalOpener.kt")
        if not d_ok:
            which.append("SandboxFilePickerDialog.kt（KDoc 示例）")
        return FAILED, "authority 未从 applicationId 派生：" + "、".join(which)

    changed = []
    if m_bad:
        write(manifest, m_text.replace(
            'android:authorities="com.dshfusion.app.fileprovider"',
            'android:authorities="${applicationId}.fileprovider"', 1))
        changed.append("AndroidManifest.xml")
    if o_bad:
        new_o = o_text.replace(
            'const val FILE_PROVIDER_AUTHORITY = "com.dshfusion.app.fileprovider"',
            'val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"', 1)
        if new_o == o_text:
            return FAILED, "ExternalOpener.kt 的 FILE_PROVIDER_AUTHORITY 锚点失效，需人工确认"
        if "import com.dshfusion.app.BuildConfig" not in new_o:
            new_o = new_o.replace("import androidx.core.content.FileProvider\n",
                                  "import androidx.core.content.FileProvider\nimport com.dshfusion.app.BuildConfig\n", 1)
        write(opener, new_o)
        changed.append("ExternalOpener.kt")
    if d_bad:
        new_d = d_text.replace(
            "content://com.dshfusion.app.fileprovider/",
            "content://<applicationId>.fileprovider/", 1)
        if new_d == d_text:
            return FAILED, "SandboxFilePickerDialog.kt 的 KDoc 锚点失效，需人工确认"
        write(dialog, new_d)
        changed.append("SandboxFilePickerDialog.kt")

    if not changed:
        return FAILED, "三处 authority 都既不是正确值也不是已知的错值，状态不明，需人工确认"

    m2, o2, d2 = read(manifest), read(opener), read(dialog)
    if 'android:authorities="${applicationId}.fileprovider"' not in m2 or \
       'FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"' not in o2 or \
       "content://<applicationId>.fileprovider/" not in d2:
        return FAILED, "写入后复核未通过（已改动 " + "、".join(changed) + "）"
    files_changed.extend(str(ROOT / p) for p in changed)
    return OK, "已把 " + "、".join(changed) + " 的 authority 改为从 applicationId 派生"


def handle_div_0010(entry) -> tuple[str, str]:
    """install_dsh.sh 取消默认 DSH 版本。

    上游写的是 `DSH_VERSION="${DSH_VERSION:-0.1.0-rc.6}"`，而实际适配的是
    0.2.0-rc.2。默认值一旦与实际不符，脚本就会在没人指定的情况下静默装出
    一个错版本，并且还打印「pin exact version」—— 让人以为一切正常。

    改为失败即报错。版本号只应有一个事实来源（upstreams.json 的
    tracked_version，由 build-dsh-layer.sh 读取），调用方必须自己说清要哪个版本。

    注意：本脚本在 DSHFusion 里没有任何地方执行它。这条偏离修的是潜在的坑，
    不是正在发作的 bug —— 记录在此以免被误读为「修了一个线上问题」。
    """
    path = ROOT / "runtime-bundle" / "scripts" / "install_dsh.sh"
    if not path.exists():
        return PENDING, "install_dsh.sh 尚未导入"

    text = read(path)
    has_guard = "必须显式指定 DSH_VERSION" in text
    # 正文（去掉注释与提示文案）里不应再有版本默认值
    body_lines = [
        ln for ln in text.splitlines()
        if ln.strip() and not ln.lstrip().startswith("#")
    ]
    body = "\n".join(body_lines)
    has_default = bool(re.search(r'DSH_VERSION="\$\{DSH_VERSION:-[0-9]', body))

    if has_guard and not has_default:
        return OK, "install_dsh.sh 已要求显式指定 DSH_VERSION"
    if not has_guard and not has_default:
        return FAILED, "install_dsh.sh 既没有默认版本也没有空值守卫，状态不明"
    if CHECK_ONLY:
        return FAILED, "install_dsh.sh 仍有默认 DSH 版本（DIV-0010 未生效）"

    original = text
    old = 'DSH_VERSION="${DSH_VERSION:-0.1.0-rc.6}"'
    if old not in text:
        return FAILED, (
            "找不到 DSH_VERSION 默认值那一行，锚点失效"
            "（上游可能已改动该文件，需人工确认后更新 divergences.json）"
        )
    guard = (
        'if [ -z "${DSH_VERSION:-}" ]; then\n'
        '    echo "错误：必须显式指定 DSH_VERSION（见 governance/upstreams.json）。" >&2\n'
        '    exit 2\n'
        'fi'
    )
    write(path, text.replace(old, guard, 1))
    files_changed.append(str(path))

    after = read(path)
    if "必须显式指定 DSH_VERSION" not in after:
        revert(path, original)
        return FAILED, "写入后未发现空值守卫（已还原）"
    if re.search(r'DSH_VERSION="\$\{DSH_VERSION:-[0-9]', after):
        revert(path, original)
        return FAILED, "写入后仍存在版本默认值（已还原）"
    return OK, "已取消 install_dsh.sh 的默认 DSH 版本"


def handle_div_0009(entry) -> tuple[str, str]:
    """根 LICENSE **是** GPL-3.0 全文，且上游原件受哈希保护。

    ⚠️ **这条的方向被反转过一次 —— 反转本身就是它最有价值的部分。**

    **阶段 1 的原方向**：根 LICENSE 放「许可状态：私有自用，未授予许可」的说明。
    理由是上游根 LICENSE 是 GPL-3.0 全文，GitHub 据此把本仓库显示成「GPL-3.0」，
    而本仓库当时并未做出那个承诺 —— **矛盾的是 GitHub 那一侧**。

    **2026-10-16 反转**：仓库从私有改为公开（起因是 Actions 分钟数用尽，
    公开仓库免费）。**公开 = 对外分发 ⇒ 骨干 DSHBox 是 GPL-3.0 ⇒
    衍生作品必须同样以 GPL-3.0 分发。** 这条义务原本就存在，只是私有期间没触发。
    于是「不让 GitHub 显示 GPL-3.0」这个原始动机整个消失了。

    **核验双向，缺一不可：**

      ① 根 LICENSE 必须含 GPL-3.0 全文，且带我们的版权声明
         —— 分发时许可必须在那里；
      ② `governance/upstream-licenses/DSHBox/LICENSE` 必须仍在、且哈希与受保护清单一致
         —— **许可证不许在换位置的过程中消失**。

    只查 ① 会让「上游原件被顺手删掉」无人发现；
    只查 ② 会让「公开了却没有许可」无人发现。

    ⚠️ **这里有一处「两处真相」**：重放时写入的通知头是下面的字面量，
    而仓库里已提交的 `LICENSE` 是它的实例。两者一旦漂移，重放就会产生无谓的 diff。
    所以 `verify-phase0.sh` 的 5e 节有一条**逐字节相等**的断言钉住它。
    """
    import hashlib

    protected = ROOT / "governance" / "upstream-licenses" / "DSHBox" / "LICENSE"
    if not protected.exists():
        return FAILED, (
            "受保护的上游 GPL 原件 governance/upstream-licenses/DSHBox/LICENSE 不存在 —— "
            "公开分发的仓库里，上游许可原文绝不能缺"
        )

    hashes_path = ROOT / "governance" / "upstream-license-hashes.json"
    try:
        declared = json.loads(read(hashes_path))
        declared = declared.get("files", declared)
        want = declared["governance/upstream-licenses/DSHBox/LICENSE"]
        want = want if isinstance(want, str) else want.get("sha256")
    except (OSError, ValueError, KeyError) as exc:
        return FAILED, f"读不出受保护 GPL 原件的声明哈希：{exc}"

    got = hashlib.sha256(protected.read_bytes()).hexdigest()
    if got != want:
        return FAILED, (
            f"受保护的上游 GPL 原件哈希不符（{got[:12]}… != {want[:12]}…）—— "
            "上游许可原文被改动过"
        )

    root_license = ROOT / "LICENSE"
    if not root_license.exists():
        return FAILED, "根目录 LICENSE 不存在 —— 公开分发时这是致命的"

    text = read(root_license)

    # 🔴 **判据就一条：根 LICENSE 与受保护原件逐字节相同。**
    #
    # 早先的写法是「通知头 + 原件」，于是通知头成了**第二处真相**
    # （handler 里的字面量 vs 已提交的文件），漂移时没有任何断言会红。
    # 实测还踩了第二个坑：**加了通知头之后 GitHub 的许可证识别把本仓库标成
    # `Other / NOASSERTION`** —— 也就是说，为了让版权声明显眼，反而让
    # 「本仓库是 GPL-3.0」这件事对外不可见了。
    #
    # 现在的设计是标准做法：`LICENSE` 是**未经改动**的 GPL-3.0 全文，
    # 版权声明放 `LICENSE-NOTES.md`。于是判据只剩一条、也没有第二处真相。
    if text == read(protected):
        return OK, "根 LICENSE 与受保护的上游 GPL-3.0 原件逐字节相同"

    if CHECK_ONLY:
        if "GNU GENERAL PUBLIC LICENSE" not in text:
            return FAILED, (
                "根 LICENSE 不含 GPL-3.0 全文 —— 本仓库公开分发，"
                "这是「分发时没有许可」，必须先修（见 LICENSE-NOTES.md）"
            )
        return FAILED, (
            "根 LICENSE 不是未经改动的 GPL-3.0 全文（与受保护原件有差异）—— "
            "改动它会同时让 GitHub 的许可证识别退化成 `Other / NOASSERTION`；"
            "我们的版权声明应放在 LICENSE-NOTES.md"
        )

    # 重放：直接用受保护的原件覆盖根 LICENSE
    original = text
    write(root_license, read(protected))
    files_changed.append(str(root_license))
    if read(root_license) != read(protected):
        revert(root_license, original)
        return FAILED, "重放写出的根 LICENSE 与受保护原件不一致"
    return OK, "根 LICENSE 已重建为未经改动的 GPL-3.0 全文"


def handle_div_0013(entry) -> tuple[str, str]:
    """docs/BUILD_RUNBOOK.md 加一条 zstd 警告横幅。

    为什么要动一份**逐字节导入**的上游文档：因为它是一份**要照着做**的 runbook。
    它第 1.2 / 1.4 / ④ 节都建议「没有 zstd 就降级 gzip」——这对上游仓库内的打包
    是真的，但**层要进 APK 时这句话是错的**：AGP 会解压 `.gz` 资源并去掉扩展名，
    于是 `android-side.tar.gz` 变成 `android-side.tar`，而应用侧要求名字以
    `android-side.tar.` 开头（`OnlineRuntimeImportManager.kt:769`），匹配不上。

    我本人就是被这份 runbook 说服、用 gzip 建层，结果 CI 全绿而产物是坏的。
    所以这条横幅的价值是**在读者照着做之前拦住他**。

    ⚠️ 这条偏离只**插入**、不**修改**。核验时两边都要查：
      - 横幅在（否则重放没生效）
      - 上游正文那三处原文仍在（否则说明我们改动了上游内容，
        那就不是「插入」而成了「改写」，冲突面会大得多）
    只查「横幅在不在」是不够的 —— 那样把整段正文删掉也能通过。
    """
    runbook = ROOT / "docs" / "BUILD_RUNBOOK.md"
    if not runbook.exists():
        return PENDING, "基线尚未导入"

    text = read(runbook)

    BANNER_ANCHOR = "DSHFusion 附加警告"
    ADDED = [
        # 横幅必须点名的三件事：结论、原因、以及它只对 APK 内层成立
        BANNER_ANCHOR,
        "不能用于要进 APK 的层",
        "AGP 会解压 `.gz` 资源并去掉 `.gz` 扩展名",
        "任何要嵌进 APK 的层必须用 `.tar.zst`",
        "DIV-0013",
    ]
    # 上游正文原文（必须原样保留，证明我们只做了插入）
    UPSTREAM_LINES = [
        "（`zstd` 缺装会自动降级 gzip，见第 ④ 节风险。）",
        "export ZSTD_LEVEL=19            # zstd 存在时用；不存在则 pack 脚本自动 gzip",
        "若某层用 gzip 制作，同样可解（gzip→commons-compress）",
    ]

    have = [a for a in ADDED if a in text]
    missing = [a for a in ADDED if a not in text]
    kept = [u for u in UPSTREAM_LINES if u in text]
    lost = [u for u in UPSTREAM_LINES if u not in text]

    if not missing and not lost:
        return OK, "横幅已在，且上游正文三处原文都保留"

    if CHECK_ONLY:
        if lost:
            return FAILED, (
                "上游正文被改动了（横幅不该改正文）：缺 "
                + "；".join(u[:40] for u in lost)
            )
        return FAILED, "缺少 zstd 警告横幅（缺：" + "；".join(m[:28] for m in missing) + "）"

    if lost:
        # 正文已经不对了 —— 不猜、不自动"修"，交给人工，避免把上游内容改坏
        return FAILED, "上游正文已被改动，拒绝自动重放（需人工确认）：" + lost[0][:40]

    if not runbook.exists():
        return PENDING, "基线尚未导入"

    original = text
    banner = (
        "> ## ⚠️ DSHFusion 附加警告：本文的 gzip 建议**不能用于要进 APK 的层**（DIV-0013）\n"
        ">\n"
        "> 下面第 1.2 节「`zstd` 缺装会自动降级 gzip」、第 1.4 节「不存在则 pack 脚本自动 gzip」、\n"
        "> 第 ④ 节「若某层用 gzip 制作，同样可解」——这三句对**上游仓库内**的打包与消费都是真的\n"
        "> （`BundleManager` 确实按魔数嗅探，确实能解 gzip）。\n"
        ">\n"
        "> **但它们答的不是同一个问题。** 层不是直接交给 `BundleManager` 的，它得先过\n"
        "> **AGP 的资源打包**，而 AGP 会解压 `.gz` 资源并去掉 `.gz` 扩展名：\n"
        ">\n"
        "> ```\n"
        "> runtime/android-side.tar.gz  (125930 B, gzip)\n"
        ">         ↓ 过一遍 AGP\n"
        "> assets/runtime/android-side.tar  (337920 B, 裸 tar)\n"
        "> ```\n"
        ">\n"
        '> 应用侧的查找条件是 `names.firstOrNull { it.startsWith("android-side.tar.") }`\n'
        "> （`OnlineRuntimeImportManager.kt:769`，注意结尾那个点）——`\"android-side.tar\"`\n"
        "> **匹配不上**。层进了 APK，应用永远看不见，**等于没建**。\n"
        ">\n"
        "> 所以：**任何要嵌进 APK 的层必须用 `.tar.zst`。** DSHFusion 的\n"
        "> `tools/build-runtime-assets.sh` 与 `tools/build-dsh-layer.sh` 因此要求 zstd，\n"
        "> 缺了直接退 2，不静默降级。上游发布包用的也正是 `.tar.zst`。\n"
        ">\n"
        "> 这段横幅是 DSHFusion 加的（**正文一字未改**），见\n"
        "> [governance/divergences.json](../governance/divergences.json) 的 DIV-0013。\n"
    )

    # 插在标题之后、原有第一段引用块之前。
    head = "# 运行环境 bundle 构建 + 真机安装 · 执行手册（阶段 A→D）\n\n"
    if not text.startswith(head):
        return FAILED, "文件头与预期不符，拒绝插入（上游可能已改结构）"
    text = head + banner + "\n" + text[len(head):]
    runbook.write_text(text, encoding="utf-8")
    files_changed.append(str(runbook))

    # 不谎报成功：写完必须复核，两个方向都查
    after = read(runbook)
    if any(a not in after for a in ADDED):
        revert(runbook, original)
        return FAILED, "写入后复核失败：横幅不完整（已还原）"
    still = [u for u in UPSTREAM_LINES if u not in after]
    if still:
        revert(runbook, original)
        return FAILED, "写入后复核失败：上游正文丢了（已还原）"
    return OK, "已插入 zstd 警告横幅，上游正文未改"


def handle_div_0014(entry) -> tuple[str, str]:
    """DSH 端口改为内核动态分配 + 健康检查必须核验身份（DIV-0014）。

    这条偏离**没有可自动重放的锚点**：它跨 22 个文件，而且改的是语义
    （把「端口是编译期常量」换成「端口是运行期从 DSH 输出里学到的实例属性」），
    不是插入一段文本。所以本函数只做**核验**，不自动改写。

    为什么不自动改写：改的是上游正在演进的核心运行期代码（进程启动命令行、
    健康判定）。自动改写极可能把上游的新逻辑覆盖掉，而在这一处「覆盖」的后果
    恰恰是**静默退回写死端口** —— 也就是这条偏离要修的那个 bug 本身。
    宁可停下来让人看一眼，也不要把一个看起来成功、实际退回原状的改动写进去。

    ⚠️ 检查必须钉住**语义**，不能只查字符串存在 —— 本项目已经在「检查器自己骗自己」
    上栽过三次（最近一次是断言 6 被 `.sha256` 侧车骗过，报了个假的好消息）。
    所以这里刻意区分两种查法：
      - 「必须存在」查的是**声明/表达式形式**（`const val DSH_PORT_AUTO`、
        `probe.webUiReady && probe.processAlive`），不是「文件里出现过某个词」；
      - 「必须不存在」查的是**被删掉的那个声明**（`const val DSH_DEFAULT_PORT`），
        而**不是**「文件里出现过 3080」。后者会被解释性注释误伤，
        而那会逼着作者删掉解释 —— 正是我们不想要的方向。
    """
    REQUIRED = [
        ("common/src/main/java/com/dshfusion/app/common/Constants.kt",
         ["const val DSH_PORT_AUTO", "fun dshBaseUrl(port: Int)"],
         "端口改为「让内核分配」，地址改为按实际端口拼"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/SandboxConfig.kt",
         ["val dshPort: Int = Constants.DSH_PORT_AUTO"],
         "配置默认值不再是 3080"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/SandboxProcessRunner.kt",
         ["--port $port"],
         "端口必须作为命令行参数传给 dsh"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/DefaultSandboxManager.kt",
         ["DshWebLaunchParser.parse",
          "portProvider = { _dshPort.value }",
          "tokenProvider = { _dshLaunchToken.value }",
          "resetDshEndpointState"],
         "从 dsh 自己的输出学端口+token，并把运行期值喂给健康检查"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/HttpHealthChecker.kt",
         ["tokenProvider", "!= HTTP_UNAUTHORIZED"],
         "健康检查核验身份：401 = 应答者不认识本实例的 token"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/DshHealthPolicy.kt",
         ["probe.webUiReady && probe.processAlive"],
         "就绪必须同时要求本实例进程存活"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/SandboxHealth.kt",
         ["instanceVerified"],
         "区分「没人应答」与「有人应答但不是我」"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/SandboxManager.kt",
         ["val dshPort: StateFlow<Int>", "val dshBaseUrl: StateFlow<String?>"],
         "接口暴露实例端口与实例地址"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/DshWebLaunch.kt",
         # 钉的是**代码表达式**而不是字面量 `65535`：KDoc 里也写着「1..65535」，
         # 只查字面量的话，把校验删掉仍然能通过 —— 实测漏过一次，故收紧。
         ["dsh web: http", "port !in 1..65535"],
         "端口/token 解析器存在，且会拒绝越界端口"),
        # ── UI 层：光有「资源里有个占位文案」不够，必须确认 UI 真的在用实例地址。
        #    只查资源键会被「UI 仍然写死地址」骗过 —— 实测过，这一条是补上来的。
        ("app/src/main/java/com/dshfusion/app/ui/home/HomeScreen.kt",
         ["dshBaseUrl ?: stringResource(R.string.home_dsh_address_unknown)"],
         "首页显示本实例地址，端口未知时给占位文案"),
        ("app/src/main/java/com/dshfusion/app/ui/MainScreen.kt",
         ["sandboxManager.dshBaseUrl.collectLatest", "url = dshBaseUrl.orEmpty()"],
         "WebView 与浏览器入口用本实例地址"),
        ("app/src/main/java/com/dshfusion/app/service/SandboxService.kt",
         ["sandboxManager.dshBaseUrl.value"],
         "通知栏与浏览器入口用本实例地址"),
        ("app/src/main/java/com/dshfusion/app/ui/settings/DiagnosticsScreen.kt",
         ["R.string.home_dsh_address_unknown"],
         "诊断页显示本实例地址"),
    ]

    FORBIDDEN = [
        ("common/src/main/java/com/dshfusion/app/common/Constants.kt",
         ["const val DSH_DEFAULT_PORT", "const val DSH_BASE_URL"],
         "写死的端口常量与地址常量必须已删除"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/DefaultSandboxManager.kt",
         ['base["PORT"]'],
         "上游那个不起作用的 PORT 环境变量必须已删除"),
        ("sandbox-manager/src/main/java/com/dshfusion/app/sandbox/HttpHealthChecker.kt",
         ["in 200..599"],
         "「任意状态码都算存活」的判据必须已被替换"),
    ]

    missing: list[str] = []
    present: list[str] = []

    for rel, needles, why in REQUIRED:
        path = ROOT / rel
        if not path.exists():
            return PENDING, f"{rel} 尚未导入"
        text = read(path)
        for n in needles:
            (present if n in text else missing).append(f"{rel}: {n}（{why}）")

    for rel, needles, why in FORBIDDEN:
        path = ROOT / rel
        if not path.exists():
            return PENDING, f"{rel} 尚未导入"
        text = read(path)
        for n in needles:
            if n in text:
                missing.append(f"{rel}: 仍存在 {n}（{why}）")

    # 全库扫描：任何 Kotlin **代码**（非注释）都不得再引用旧常量。
    # 注释里提到它们是可以的 —— 那正是解释「为什么删掉」的地方。
    stale: list[str] = []
    for kt in ROOT.rglob("*.kt"):
        try:
            body = kt.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for lineno, line in enumerate(body.splitlines(), 1):
            code = line.split("//", 1)[0]           # 去掉行尾注释
            stripped = code.strip()
            if stripped.startswith("*") or stripped.startswith("/*"):
                continue                             # 块注释 / KDoc 行
            if "Constants.DSH_BASE_URL" in code or "Constants.DSH_DEFAULT_PORT" in code:
                stale.append(f"{kt.relative_to(ROOT)}:{lineno}")
    if stale:
        missing.append("仍有代码引用旧常量：" + "、".join(stale[:5]))

    # 全库扫描：src/main 的 Kotlin **代码**里不得再出现写死的「回环地址:端口」。
    # 注释里提到它是可以的 —— 那正是解释「为什么不能写死」的地方；
    # 若把注释也一并禁掉，作者会被逼着删掉解释，方向正好反了。
    # 测试源不在扫描范围内：那里的字面量是夹具，不是配置。
    hardcoded: list[str] = []
    for module in ("app", "common", "sandbox-manager", "plugin-manager", "bridge",
                   "terminal-session", "terminal-emulator", "terminal-view"):
        base = ROOT / module / "src" / "main"
        if not base.is_dir():
            continue
        for kt in base.rglob("*.kt"):
            try:
                body = kt.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for lineno, line in enumerate(body.splitlines(), 1):
                code = line.split("//", 1)[0]
                stripped = code.strip()
                if stripped.startswith("*") or stripped.startswith("/*"):
                    continue
                if "127.0.0.1:3080" in code or "localhost:3080" in code:
                    hardcoded.append(f"{kt.relative_to(ROOT)}:{lineno}")
    if hardcoded:
        missing.append("代码里仍有写死的回环地址+端口：" + "、".join(hardcoded[:5]))

    # 6 个语言的 strings.xml 都必须带上「地址未知」的占位文案
    res = ROOT / "app" / "src" / "main" / "res"
    for lang in ("values", "values-zh", "values-ar", "values-es", "values-fr", "values-ru"):
        f = res / lang / "strings.xml"
        if not f.exists():
            return PENDING, f"{lang}/strings.xml 尚未导入"
        if 'name="home_dsh_address_unknown"' not in read(f):
            missing.append(f"app/src/main/res/{lang}/strings.xml: 缺 home_dsh_address_unknown")

    # ⚠️ 位置断言（2026-10-14 真机失败后补）：`$patches` 必须排在 `--host` / `--port` **之前**。
    #
    # 这条不能用 REQUIRED 的「字符串在场」表达 —— 那是**存在性**检查，而真机上坏掉的
    # 恰恰是**位置**：`--patch` 一直在命令里，只是被 `--host` / `--port` 挤到了后面，
    # 于是被 dsh 启动器当成 web 应用自己的旗标，web 应用打印
    # `error: unknown option '--patch'` 并退出（process-dsh.log 连续 6 次）。
    # 上游原本把 `$patches` 紧贴在 `--profile` 之后才成立；DIV-0014 把
    # `--host`/`--port` 插到中间，打断了一个隐含前提。
    #
    # 只比对**代码**，不比对注释：注释里必然会引用这些旗标来解释原因，
    # 把注释算进去会让「解释」本身触发误判（本项目栽过这个跟头）。
    runner_rel = "sandbox-manager/src/main/java/com/dshfusion/app/sandbox/SandboxProcessRunner.kt"
    runner = ROOT / runner_rel
    if not runner.is_file():
        return PENDING, f"{runner_rel} 尚未导入"
    code_only = "\n".join(
        ln.split("//", 1)[0]
        for ln in read(runner).splitlines()
        if not ln.strip().startswith("*") and not ln.strip().startswith("/*")
    )
    i_profile = code_only.find("--profile ${Constants.DSH_WEB_PROFILE}")
    i_patch = code_only.find("$patches", i_profile if i_profile >= 0 else 0)
    i_host = code_only.find("--host ${Constants.DSH_DEFAULT_HOST}")
    i_port = code_only.find("--port $port")
    if i_profile < 0 or i_patch < 0:
        missing.append(f"{runner_rel}: 找不到 `--profile …$patches` 的拼装（这条偏离的锚点）")
    elif i_host < 0 or i_port < 0:
        missing.append(f"{runner_rel}: 找不到 --host / --port 的拼装")
    else:
        if not (i_profile < i_patch < i_host):
            missing.append(
                f"{runner_rel}: `$patches` 必须紧跟 --profile、排在 --host 之前 —— "
                "否则 --patch 会被转交给 web 应用并报 unknown option（真机实测）"
            )
        if not (i_patch < i_port):
            missing.append(f"{runner_rel}: `$patches` 必须排在 --port 之前")
        if i_profile < i_patch < i_host and i_patch < i_port:
            present.append("dsh 命令行参数顺序（--patch 在 --host / --port 之前）")

    if not missing:
        return OK, f"动态端口与身份核验均在场（{len(present)} 项断言通过）"

    detail = "；".join(m[:90] for m in missing[:4])
    if CHECK_ONLY:
        return FAILED, f"缺少 {len(missing)} 项：{detail}"
    return FAILED, (
        "本偏离无法自动重放（跨文件语义改动）。请按 governance/divergences.json "
        f"里 DIV-0014 的 reapply 字段人工核对。缺：{detail}"
    )



def handle_div_0015(entry) -> tuple[str, str]:
    """dsh 运行环境层必须按目标架构（arm64）构建（DIV-0015）。

    这条偏离没有可自动重放的锚点 —— 不是因为它跨文件（只跨两处），而是因为
    真正要钉住的东西是**数据**（7 个按平台分发的包名），不是一段可插入的文本。
    所以本函数只做核验。

    ⚠️ 查的是**表达式形式**，不是裸的架构名。脚本的 KDoc 里本来就会写到
    arm64（解释这个 bug 时必须写），若断言写成「文件里出现过 arm64」，
    那把整段修复删掉、只留注释，断言照样通过 —— 这正是本项目栽过三次的
    「检查器被长得像的东西骗过」。所以每一条都要求一个**只有真实现才有的形状**：
    npm 参数、默认值赋值、自检清单的变量名与模板串。
    """
    missing: list[str] = []
    present: list[str] = []

    script = ROOT / "tools/build-dsh-layer.sh"
    if not script.is_file():
        return PENDING, "tools/build-dsh-layer.sh 尚未引入（阶段 1 之前是正常的）"
    body = read(script)
    for needle, why in (
        ('--os=linux --cpu="$DSH_TARGET_ARCH" --libc=glibc',
         "npm install 必须显式指定目标平台"),
        ('DSH_TARGET_ARCH="${DSH_TARGET_ARCH:-arm64}"', "目标架构默认必须是 arm64"),
        ("PLATFORM_PKGS=(", "必须有平台包自检清单"),
        ("node-addon-require-builtin-linux-ARCH-gnu",
         "自检清单必须含 DSH 启动时必需的那个包"),
        ("平台自检通过", "自检必须打印通过结论"),
    ):
        (present if needle in body else missing).append(why)

    checker = ROOT / "tools/check-apk-identity.py"
    if not checker.is_file():
        return PENDING, "tools/check-apk-identity.py 尚未引入"
    cbody = read(checker)
    for needle, why in (
        ("断言 7", "产物级断言 7（层架构）必须在场"),
        ("node-addon-require-builtin-linux-{a}-gnu", "断言 7 的包清单"),
        ("_layer_pkg_dirs", "断言 7 必须真的解开层看里面的包"),
        ("--os=linux --cpu={target_arch} --libc=glibc", "失败信息要指出修法"),
    ):
        (present if needle in cbody else missing).append(why)

    # 行为证明必须在套件里。
    #
    # 上面那些都是字符串在场性检查 —— 它们证明不了断言 7 **在跑**。
    # 真正证明它活着的是套件里那两条（x64 夹具必须失败、arm64 夹具必须通过）
    # 加上那次反向注入（掐掉断言 7 后 x64 夹具必须变回通过）。
    # 所以这里也钉住它们：删掉套件断言同样会让本偏离报失败。
    suite = ROOT / "tools/verify-phase0.sh"
    if not suite.is_file():
        return PENDING, "tools/verify-phase0.sh 尚未引入"
    sbody = read(suite)
    for needle, why in (
        ("x64-layer", "套件必须有 x64 层夹具场景"),
        ("arm64-layer", "套件必须有 arm64 层夹具场景（成对才说明失败原因是架构）"),
        ("checker-no-assert7.py", "套件必须有「掐掉断言 7」的反向注入"),
        ("dsh 层不是 arm64 架构的", "套件必须核对失败原因确实是架构"),
    ):
        (present if needle in sbody else missing).append(why)

    if not missing:
        return OK, f"层架构偏离在场（{len(present)} 项断言通过）"

    detail = "；".join(missing[:4])
    return FAILED, (
        "本偏离无法自动重放（要钉的是数据不是文本）。请按 divergences.json 里 "
        f"DIV-0015 的 reapply 字段人工核对。缺：{detail}"
    )


def handle_div_0016(entry) -> tuple[str, str]:
    """无障碍服务名改为 DSHFusion（DIV-0016）。

    这条是**可以**自动重放的（纯值替换），但重放需要知道每个语言的新值，
    而那 6 个新值已经在 divergences.json 里写明了 —— 与其在这里再抄一遍
    （抄一遍就多一个会漂移的事实来源），不如只核验。
    """
    langs = ("values", "values-zh", "values-ar", "values-es", "values-fr", "values-ru")
    base = ROOT / "pilot" / "dshbox-adapter" / "src" / "main" / "res"
    missing: list[str] = []
    ok_langs: list[str] = []

    for lang in langs:
        f = base / lang / "strings.xml"
        if not f.exists():
            return PENDING, f"pilot/dshbox-adapter/.../{lang}/strings.xml 尚未导入"
        body = read(f)
        m = re.search(r'<string name="relay_a11y_label">(.*?)</string>', body, re.S)
        if not m:
            missing.append(f"{lang} 缺 relay_a11y_label")
            continue
        value = m.group(1)
        # 两个方向都要查：残留旧名 = 没改；没有新名 = 改成了别的东西。
        # 只查「不含 DSHBox」会放过「改成了 DSHBox2」这类荒唐值。
        if "DSHBox" in value:
            missing.append(f"{lang} 仍写着 DSHBox")
        elif "DSHFusion" not in value:
            missing.append(f"{lang} 没有 DSHFusion")
        else:
            ok_langs.append(lang)

    if not missing:
        return OK, f"无障碍服务名已在 {len(ok_langs)} 个语言里改为 DSHFusion"

    detail = "；".join(missing[:4])
    return FAILED, f"无障碍服务名仍有问题：{detail}"


def handle_div_0017(entry) -> tuple[str, str]:
    """签名改用固定密钥库（DIV-0017）。

    三处必须同时在场，缺一处这个 bug 就会以另一种形态回来：
      - 只有 CI 步骤、没有 gradle 覆盖 -> 密钥库还原了但 debug 构建不用它；
      - 只有 gradle 覆盖、没有 CI 步骤 -> 本地能用，CI 仍然每个构建一个身份；
      - 有 CI 步骤但没有指纹核验 -> 「签名稳定」变成一句无法验证的话。

    ⚠️ 另外查一件**反向**的事：密钥库不得进入版本控制。
    上游 .gitignore 里那条规定必须仍然有效 —— 若有人为了图方便把它注释掉，
    这条断言会失败。查的是 .gitignore 里仍有 *.jks 与 keystore.properties，
    以及仓库里**确实没有**被跟踪的密钥文件。
    """
    missing: list[str] = []
    present: list[str] = []

    gradle = ROOT / "app" / "build.gradle.kts"
    if not gradle.is_file():
        return PENDING, "app/build.gradle.kts 尚未导入"
    gbody = read(gradle)
    # debug 块里必须出现 signingConfig 赋值。只查「有 signingConfig」不够 ——
    # release 块本来就有，那会漏掉「debug 没改」这个真正的缺口。
    m = re.search(r"debug\s*\{(.*?)\n        \}", gbody, re.S)
    if not m:
        missing.append("app/build.gradle.kts 里找不到 debug 构建块")
    else:
        debug_block = m.group(1)
        if "signingConfig = if (hasReleaseKeystore)" in debug_block:
            present.append("debug 构建用固定密钥库")
        else:
            missing.append("debug 构建块没有 signingConfig 覆盖（会退回随机调试密钥）")

    wf = ROOT / ".github" / "workflows" / "android.yml"
    if not wf.is_file():
        return PENDING, ".github/workflows/android.yml 尚未导入"
    wbody = read(wf)
    for needle, why in (
        ("Restore signing keystore", "CI 必须有还原密钥库的步骤"),
        ("DSHFUSION_KEYSTORE_B64", "密钥库来自 Actions Secret，不提交进仓库"),
        ("76d588d44aad12b00aac0a1587738bc91efc182b7c0b00b288a5826058e98126",
         "必须核验证书指纹，否则「签名稳定」无法验证"),
        ("keytool -list -v", "指纹核验要用 keytool 真读一次密钥库"),
    ):
        (present if needle in wbody else missing).append(why)

    # 还原步骤必须排在 assemble 之前，否则等于没还原
    if "Restore signing keystore" in wbody and "Assemble debug APK" in wbody:
        if wbody.index("Restore signing keystore") > wbody.index("Assemble debug APK"):
            missing.append("还原密钥库的步骤排在 assemble 之后，等于没生效")
        else:
            present.append("还原步骤在 assemble 之前")

    # 反向：密钥库不得入库
    #
    # ⚠️ 必须逐行查、且跳过注释行。第一版写的是 `pat in read(gi)`，
    # 故障注入实测：把 `*.jks` 那一行注释成 `# *.jks`，字符串仍在文件里，
    # 检查照样通过 —— 又是「检查器被长得像的东西骗过」。
    # 而这一条恰恰是防止密钥被提交的那道闸，不能是假的。
    gi = ROOT / ".gitignore"
    if gi.is_file():
        patterns = {
            line.strip()
            for line in read(gi).splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        for pat in ("*.jks", "keystore.properties"):
            if pat in patterns:
                present.append(f".gitignore 仍忽略 {pat}")
            else:
                missing.append(f".gitignore 不再忽略 {pat}（密钥有入库风险）")

    if not missing:
        return OK, f"固定签名偏离在场（{len(present)} 项断言通过）"

    detail = "；".join(missing[:4])
    return FAILED, f"固定签名仍有缺口：{detail}"



def handle_div_0018(entry) -> tuple[str, str]:
    """通知栏动作：个数随状态收敛 + 文案改短（DIV-0018）。

    这条偏离是「两个因素相乘」造成的，所以**两边都要查**：
      - 只查文案 -> 4 个动作仍会把三分之一宽度再切一刀；
      - 只查个数 -> ru/fr 的长词在三分之一宽度里依然紧张。
    任一边回退，真机上那个「一个字 + 省略号」就会回来。
    """
    langs = ("values", "values-zh", "values-ar", "values-es", "values-fr", "values-ru")
    keys = {
        "notify_action_open_dsh": "Open",
        "notify_action_start_dsh": "Start",
        "notify_action_restart_dsh": "Restart",
        "notify_action_stop_sandbox": "Stop",
    }
    base = ROOT / "app" / "src" / "main" / "res"
    # 上游那 4 条长文案的尾巴：出现任意一个就说明这一条被回退了。
    stale = (" DSH", "沙箱", "sandbox", "الصندوق", "entorno aislado", "bac à sable", "песочницу")
    missing: list[str] = []
    checked = 0

    for lang in langs:
        f = base / lang / "strings.xml"
        if not f.exists():
            return PENDING, f"app/src/main/res/{lang}/strings.xml 尚未导入"
        body = read(f)
        for key in keys:
            m = re.search(r'<string name="%s">(.*?)</string>' % key, body, re.S)
            if not m:
                missing.append(f"{lang} 缺 {key}")
                continue
            value = m.group(1)
            checked += 1
            if not value.strip():
                missing.append(f"{lang}/{key} 是空值")
            elif any(s in value for s in stale):
                missing.append(f"{lang}/{key} 仍是长文案「{value}」")

    # 结构那一半：动作装配必须走纯函数，不能再是 4 个连着的 addAction。
    svc = ROOT / "app" / "src" / "main" / "java" / "com" / "dshfusion" / "app" / "service" / "SandboxService.kt"
    if not svc.exists():
        return PENDING, "SandboxService.kt 尚未导入"
    body = read(svc)
    if "for (action in dshNotifyActions(dshState))" not in body:
        missing.append("buildNotification 没有按 dshNotifyActions(dshState) 循环装配动作")
    # addAction 必须只有**一处**（循环里那处），且不能把具体文案写死在调用上。
    # 上游是 4 处写死调用；只要回到 2 处以上，动作个数就又不受状态控制了。
    n_add = len(re.findall(r"\.addAction\(", body))
    if n_add != 1:
        missing.append(f"addAction 调用应恰好 1 处（循环内），实际 {n_add} 处")
    for line in body.splitlines():
        if "addAction" in line and "notify_action_" in line:
            missing.append("addAction 又写死了具体文案：" + line.strip()[:60])

    if not missing:
        return OK, f"通知栏动作已收敛（{checked} 条文案 + 动作装配走纯函数）"

    detail = "；".join(missing[:4])
    return FAILED, f"通知栏动作仍有问题：{detail}"



def handle_div_0019(entry) -> tuple[str, str]:
    """新增 :backup 模块与 Gson 依赖（DIV-0019）。

    三处缺一不可，因为缺任何一处都会以不同方式「静默地」失败：
      - 只加目录不 include -> 模块根本不在构建图里，单测一次都不跑；
      - include 了但没有 Gson -> 导入的 BackupJson 编译不过；
      - 有模块有依赖但没进版本目录 -> 依赖解析失败。

    另外**卡 Gson 的下限**：BackupJson 用的是 strictness 严格模式，
    低于 2.11 的 Gson 没有 Strictness，重复键不再被拒 —— 那是静默的安全退化，
    编译期不会报错，只在恶意输入下才看得出来。
    """
    problems: list[str] = []

    settings = ROOT / "settings.gradle.kts"
    if not settings.exists():
        return PENDING, "settings.gradle.kts 尚未导入"
    if 'include(":backup")' not in read(settings):
        problems.append('settings.gradle.kts 缺少 include(":backup")')

    catalog = ROOT / "gradle" / "libs.versions.toml"
    if not catalog.exists():
        return PENDING, "gradle/libs.versions.toml 尚未导入"
    text = read(catalog)
    version_match = re.search(r'^gson\s*=\s*"([^"]+)"', text, re.M)
    if not version_match:
        problems.append("版本目录没有 gson 版本条目")
    else:
        version = version_match.group(1)
        numbers = version.split(".")
        try:
            major, minor = int(numbers[0]), int(numbers[1])
        except (IndexError, ValueError):
            problems.append(f"gson 版本号无法解析：「{version}」")
        else:
            if (major, minor) < (2, 11):
                problems.append(
                    f"gson {version} 低于 2.11，Strictness 不可用 —— 重复键将不再被拒")
    if not re.search(r'^gson\s*=\s*\{\s*group\s*=\s*"com\.google\.code\.gson"', text, re.M):
        problems.append("版本目录没有 com.google.code.gson 库条目")

    module = ROOT / "backup" / "build.gradle.kts"
    if not module.exists():
        problems.append("backup/build.gradle.kts 不存在")
    elif "libs.gson" not in read(module):
        problems.append("backup/build.gradle.kts 没有依赖 libs.gson")

    # 导入副本与预检门面都要在位，否则模块是个空壳、构建能过但什么都没测。
    imported = ROOT / "backup" / "src" / "main" / "java" / "com" / "deepseekharness" / "app" / "backup" / "BackupArchive.java"
    if not imported.exists():
        problems.append("backup 模块里没有导入的 BackupArchive.java")
    facade = ROOT / "backup" / "src" / "main" / "java" / "com" / "dshfusion" / "app" / "backup" / "V5Archive.kt"
    if not facade.exists():
        problems.append("backup 模块里没有预检门面 V5Archive.kt")
    registry = ROOT / "governance" / "dsha-imports.json"
    if not registry.exists():
        problems.append("governance/dsha-imports.json 不存在（导入副本无法核验）")

    if not problems:
        return OK, "v5 归档模块已接入（:backup + Gson ≥2.11 + 导入副本清单）"

    detail = "；".join(problems[:4])
    return FAILED, f"v5 归档模块接入不完整：{detail}"



def handle_div_0024(entry) -> tuple[str, str]:
    """观察 `sandboxState` 记录停止时刻（DIV-0024，阶段 2.5「停止后」）。

    这里查的**关键是「有时间戳的写入方」**，而不只是「观察那段代码在位」——
    先前那个缺口的形态是：字段、setter、策略判定**全都在**，
    而**没有任何东西调用那个 setter**。所以「代码在位」完全证明不了这件事。
    """
    problems = []

    app = ROOT / "app" / "src" / "main" / "java" / "com" / "dshfusion" / "app" / "DshApp.kt"
    if not app.exists():
        return PENDING, "DshApp.kt 尚未导入"
    text = read(app)

    if "sandboxState" not in text:
        problems.append("DshApp 里没有观察 sandboxState —— 「停止后」拿不到时刻")
    if "SandboxState.STOPPED" not in text:
        problems.append("观察里没有判断 STOPPED —— 那么任何状态变化都会记一笔（包括 RUNNING）")
    if "setStopped(" not in text:
        problems.append(
            "DshApp 里没有调用 setStopped —— 这正是先前的缺口形态："
            "字段/setter/判定全在，而没有任何写入方，于是 `stop` 模式一次都不触发")

    # 反向：要有 distinctUntilChanged，否则同一个 STOPPED 状态重复推送会反复覆写时刻。
    if "distinctUntilChanged" not in text:
        problems.append(
            "没有 distinctUntilChanged —— 同一个 STOPPED 被重复推送时会反复覆写时刻，"
            "把「停止后」变成「一直在停止后」")

    if problems:
        return FAILED, "；".join(problems)
    return OK, "观察 sandboxState 且真的写了停止时刻（有写入方）"



def handle_div_0023(entry) -> tuple[str, str]:
    """`i18n_check.sh` 第 4 项改用 Python（DIV-0023，阶段 2.6）。

    判据是**「那条扫描在本机真的会跑」**，而不只是「脚本里有这一段」——
    原版在本机是零覆盖却报 ✓，所以「照抄一份 bash」也能过一条天真的断言。
    """
    problems = []
    script = ROOT / "tools" / "i18n_check.sh"
    if not script.exists():
        return PENDING, "tools/i18n_check.sh 尚未导入"
    text = read(script)

    if "PYI18N" not in text:
        problems.append("第 4 项没有换成 Python 实现（找不到 PYI18N 段）")
    # ⚠️ **剥注释再查。** 这是同一个坑的**第四次**（第 35 条与两次补记）：
    # 我解释「原版用的是 grep -P」的那句注释，把这条「不许再有 grep -P」的检查触发了。
    # 规律一次比一次清楚：**凡是我在注释里点出被禁的那个东西的名字，检查就会被自己命中。**
    # 所以这里不靠我记得措辞，靠剥注释。
    import re as _re

    code = _re.sub(r"/\*.*?\*/", "", text, flags=_re.S)
    code = _re.sub(r"(?m)^\s*#.*$", "", code)
    if _re.search(r"grep\s+-\w*P", code):
        problems.append(
            "脚本里仍有 `grep -P` 的中文扫描 —— 本机对它报 "
            "`code point value too large`，那条扫描会是零覆盖")
    if "scanned_files" not in text:
        problems.append("没有「零文件即失败」的守卫 —— 没扫到东西与扫了且干净会同样报 ✓")
    if "AppLocale.kt" not in text:
        problems.append("白名单被删掉了（那 4 个文件里的中文是设计保留）")

    if problems:
        return FAILED, "；".join(problems)
    return OK, "i18n 第 4 项是 Python 实现，带零文件守卫，白名单仍在"



def handle_div_0022(entry) -> tuple[str, str]:
    """`DshApp.onCreate` 里重登记自动备份心跳（DIV-0022，阶段 2.6）。

    这一条偏离只有三行，但缺了它的后果是**静默**的：心跳用 `setPersisted(false)`，
    重启后任务就没了，于是「开关还开着、再也不备份」。

    所以查两件事：① 那一段在位；② `AutomaticBackupJobService` 确实用的是
    `setPersisted(false)` —— 若哪天改成 `true`，这一段就成了冗余，
    而**冗余本身不是问题，「以为它在起作用其实不必要」才是**，
    所以两条一起查，让两者的关系显式。
    """
    problems = []

    app = ROOT / "app" / "src" / "main" / "java" / "com" / "dshfusion" / "app" / "DshApp.kt"
    if not app.exists():
        return PENDING, "app/src/main/java/com/dshfusion/app/DshApp.kt 尚未导入"
    text = read(app)

    if "AutomaticBackupJobService.schedule(this)" not in text:
        problems.append(
            "DshApp.onCreate 里没有重新登记自动备份心跳 —— "
            "重启之后自动备份会静默停止（开关还开着，再也不备份）")
    if "AutomaticBackupSettingsStore.enabled(this)" not in text:
        problems.append("重登记没有先判断开关状态 —— 关掉之后也会登记一条空转的心跳")

    service = (
        ROOT / "app" / "src" / "main" / "java" / "com" / "dshfusion" / "app"
        / "service" / "AutomaticBackupJobService.kt"
    )
    if not service.exists():
        problems.append("AutomaticBackupJobService.kt 不存在")
    elif "setPersisted(false)" not in read(service):
        problems.append(
            "心跳不再用 setPersisted(false) —— 那么 DshApp 里那段重登记就成了冗余，"
            "请一并说明（两者的关系必须是显式的）")

    if problems:
        return FAILED, "；".join(problems)
    return OK, "重启后会重登记心跳，且只在开启时登记"


def handle_div_0021(entry) -> tuple[str, str]:
    """`app` 依赖 `:backup`（DIV-0021，阶段 2.6）。

    这一条偏离只有一行，但它挡住的是一件**静默**的事：

    在接上它之前，`:backup` 的 124 条测试在 CI 里全过，而**那些类一个字节都没进 APK**
    —— 因为 `settings.gradle.kts` 的 `include(":backup")` 足以让 Gradle 单独编译并测试它，
    而 `testDebugUnitTest` 对**所有**模块逐个跑，不看有没有人依赖。
    于是「测试全绿」和「代码在 APK 里」是两件不同的事，而输出上完全一样。

    所以这里查**四件事**，缺一不可：
      ① `app/build.gradle.kts` 里有 `implementation(project(":backup"))`；
      ② `settings.gradle.kts` 里仍有 `include(":backup")`（否则①指向一个不存在的模块）；
      ③ **反向**：不许出现「只有 include、没有依赖边」——
         那正是 2.6 之前持续了四个阶段的真实状态；
      ④ 不许把它挂到 `testImplementation` 上（那同样进不了 APK）。

    ⚠️ 诚实边界：本 handler 证明的是**依赖声明在位**。
    「APK 里真的有那些类」由 `check-apk-identity.py` 的**断言 9** 负责
    （在 dex 字节里找类型描述符，并用 `missing-phase2-classes` 夹具场景证明它会红）。
    **两者不可互相替代** —— 声明可能落空（R8 / compileOnly / 构建变体），
    而一个删掉了依赖的仓库仍然可能因为上一次构建的产物而看起来正常。
    """
    problems = []

    app = ROOT / "app" / "build.gradle.kts"
    if not app.exists():
        return PENDING, "app/build.gradle.kts 尚未导入"
    app_text = read(app)

    settings = ROOT / "settings.gradle.kts"
    if not settings.exists():
        return PENDING, "settings.gradle.kts 尚未导入"
    settings_text = read(settings)

    if 'implementation(project(":backup"))' not in app_text:
        problems.append('app/build.gradle.kts 里没有 implementation(project(":backup"))')

    if 'include(":backup")' not in settings_text:
        problems.append('settings.gradle.kts 里没有 include(":backup")')

    # ③ 反向：这正是 2.6 之前的真实状态。只查 ① 会让「依赖边被删掉、
    #    但 include 还在」这种半残状态通过 —— 而它的后果与完全没接一样。
    if 'include(":backup")' in settings_text and 'project(":backup")' not in app_text:
        problems.append(
            'settings 里有 include(":backup") 但 app 没有任何 project(":backup") 依赖 '
            "—— 这是「只编译不打包」的半残状态，与完全没接的后果相同")

    # ④ 挂到 testImplementation 上同样进不了 APK。
    for line in app_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("testImplementation") and 'project(":backup")' in stripped:
            problems.append(
                'project(":backup") 出现在 testImplementation 里 —— 它进不了 APK，等于没接')

    if problems:
        return FAILED, "；".join(problems)
    return OK, "app 依赖 :backup，且 include 与依赖边一致（不是「只编译不打包」）"


def handle_div_0020(entry) -> tuple[str, str]:
    """`:backup` 的 SnakeYAML 依赖 + app 的 keep 规则（DIV-0020，阶段 2.3）。

    三处缺一不可，且**每一处缺失的后果都不一样**：
      - 缺版本目录条目 -> 依赖解析失败（大声）；
      - 缺模块声明 -> `CredentialProjection` 编译不过（大声）；
      - 缺 keep 规则 -> **只在 release 混淆后才坏，而且是静默的**
        （debug 构建完全正常，CI 也只构建 debug）。

    所以这里**卡 SnakeYAML 的主版本号**：`CredentialProjection` 用的是 2.x 才有的
    `LoaderOptions`（codePointLimit / maxAliasesForCollections / nestingDepthLimit /
    allowDuplicateKeys）。降到 1.x 是**静默的安全退化** —— 那几项限流是它抵御
    YAML 炸弹与别名展开攻击的边界，编译期不会报错。

    ⚠️ 诚实边界：本 handler 只能证明「keep 规则在位」，
    **证明不了「keep 规则的效果」** —— 那需要构建 release 并运行凭据投影。
    """
    problems: list[str] = []

    catalog = ROOT / "gradle" / "libs.versions.toml"
    if not catalog.exists():
        return PENDING, "gradle/libs.versions.toml 尚未导入"
    text = read(catalog)
    version_match = re.search(r'^snakeyaml\s*=\s*"([^"]+)"', text, re.M)
    if not version_match:
        problems.append("版本目录没有 snakeyaml 版本条目")
    else:
        version = version_match.group(1)
        try:
            major = int(version.split(".")[0])
        except (IndexError, ValueError):
            problems.append(f"snakeyaml 版本号无法解析：「{version}」")
        else:
            if major < 2:
                problems.append(
                    f"snakeyaml {version} 是 1.x —— LoaderOptions 的限流项不可用，"
                    "那是静默的安全退化")
    if not re.search(r'^snakeyaml\s*=\s*\{\s*group\s*=\s*"org\.yaml"', text, re.M):
        problems.append("版本目录没有 org.yaml:snakeyaml 库条目")

    module = ROOT / "backup" / "build.gradle.kts"
    if not module.exists():
        problems.append("backup/build.gradle.kts 不存在")
    elif "libs.snakeyaml" not in read(module):
        problems.append("backup/build.gradle.kts 没有依赖 libs.snakeyaml")

    # 凭据投影本体必须在位，否则这个依赖是给谁用的就说不清了。
    projection = (ROOT / "backup" / "src" / "main" / "java" / "com" / "deepseekharness"
                  / "app" / "backup" / "CredentialProjection.java")
    if not projection.exists():
        problems.append("没有导入 CredentialProjection.java（依赖将无人使用）")

    proguard = ROOT / "app" / "proguard-rules.pro"
    if not proguard.exists():
        problems.append("app/proguard-rules.pro 不存在")
    elif not re.search(r'^-keep class org\.yaml\.snakeyaml\.\*\*', read(proguard), re.M):
        problems.append(
            "app/proguard-rules.pro 缺少 SnakeYAML 的 keep 规则 —— "
            "release 混淆后可能静默失效，而 CI 只构建 debug")

    if not problems:
        return OK, "SnakeYAML 依赖与 keep 规则都在位（限流项 ≥2.x；keep 规则的**效果**未验证）"

    detail = "；".join(problems[:4])
    return FAILED, f"凭据投影依赖接入不完整：{detail}"


HANDLERS = {
    "DIV-0001": handle_div_0001,
    "DIV-0025": handle_div_0025,
    "DIV-0002": handle_div_0002,
    "DIV-0003": handle_div_0003,
    "DIV-0004": handle_div_0004,
    "DIV-0005": handle_div_0005,
    "DIV-0006": handle_div_0006,
    "DIV-0007": handle_div_0007,
    "DIV-0008": handle_div_0008,
    "DIV-0009": handle_div_0009,
    "DIV-0010": handle_div_0010,
    "DIV-0011": handle_div_0011,
    "DIV-0012": handle_div_0012,
    "DIV-0013": handle_div_0013,
    "DIV-0014": handle_div_0014,
    "DIV-0015": handle_div_0015,
    "DIV-0016": handle_div_0016,
    "DIV-0017": handle_div_0017,
    "DIV-0018": handle_div_0018,
    "DIV-0019": handle_div_0019,
    "DIV-0020": handle_div_0020,
    "DIV-0021": handle_div_0021,
    "DIV-0022": handle_div_0022,
    "DIV-0023": handle_div_0023,
    "DIV-0024": handle_div_0024,
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
