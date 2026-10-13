#!/usr/bin/env bash
# 重放已登记的上游偏离（阶段 1 起生效）。
#
# 为什么需要这个脚本：每次合并上游后，上游版本会覆盖我们对上游文件的修改
# （applicationId、应用名、资产路径等）。divergences.json 是这些修改的权威登记，
# 本脚本负责把它们重新施加回去，而不是靠人肉记忆。
#
# 用法：
#   tools/apply-divergences.sh --check     # 只报告每处偏离当前是否已施加
#   tools/apply-divergences.sh             # 施加所有 type=identity/config 的偏离
#
# 阶段 0（当前）：上游代码尚未导入，本脚本只做 --check 并如实报告"未导入"。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DIVERGENCES_JSON="$ROOT/governance/divergences.json"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

command -v python3 >/dev/null 2>&1 || { echo "ERROR: 需要 python3" >&2; exit 1; }

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
for entry in doc.get("planned", []):
    print(f"    - {entry['id']}  阶段{entry.get('phase','?')}  {entry['title']}")
    for target in entry.get("target_files", []):
        print(f"        {target}")
PY
    echo
    echo "    施加方式：合并上游后重跑本脚本（不带 --check）。"
    exit 0
fi

# ---------- 上游已导入：施加偏离 ----------
echo "==> 上游代码已导入，开始处理偏离"

python3 - "$CHECK_ONLY" <<'PY'
import json, re, sys
from pathlib import Path

check_only = sys.argv[1] == "1"
root = Path(".")
doc = json.loads((root / "governance/divergences.json").read_text(encoding="utf-8"))

APPLICATION_ID = "com.dshfusion.app"
APP_NAME = "DSHFusion"

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")

def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")

def report(div_id: str, ok: bool, detail: str) -> None:
    mark = "已施加" if ok else "未施加"
    print(f"  [{mark}] {div_id}: {detail}")

applied_count = 0
for entry in doc.get("applied", []) + doc.get("planned", []):
    div_id = entry["id"]
    kind = entry.get("type")

    if div_id == "DIV-0001":
        path = root / "app/build.gradle.kts"
        if not path.is_file():
            report(div_id, False, "app/build.gradle.kts 不存在")
            continue
        text = read(path)
        ok = f'applicationId = "{APPLICATION_ID}"' in text or f'applicationId "{APPLICATION_ID}"' in text
        report(div_id, ok, f"applicationId = {APPLICATION_ID}")
        if ok or check_only:
            continue
        text = re.sub(r'(applicationId\s*=?\s*)"com\.dshbox\.app"', rf'\1"{APPLICATION_ID}"', text)
        text = re.sub(r'(namespace\s*=?\s*)"com\.dshbox\.app"', rf'\1"{APPLICATION_ID}"', text)
        write(path, text)
        applied_count += 1

    elif div_id == "DIV-0002":
        hits = []
        for strings in (root / "app/src/main/res").glob("values*/strings.xml"):
            if '<string name="app_name">DSHBox</string>' in read(strings):
                hits.append(strings)
        ok = not hits
        report(div_id, ok, f"app_name = {APP_NAME}（{len(hits)} 个文件仍为 DSHBox）")
        if ok or check_only:
            continue
        for strings in hits:
            write(strings, read(strings).replace(
                '<string name="app_name">DSHBox</string>',
                f'<string name="app_name">{APP_NAME}</string>'))
            applied_count += 1

    elif div_id == "DIV-0003":
        path = root / "app/build.gradle.kts"
        if not path.is_file():
            report(div_id, False, "app/build.gradle.kts 不存在")
            continue
        text = read(path)
        ok = "DSHFUSION_RUNTIME_ASSETS" in text
        report(div_id, ok, "运行环境资产路径可由 DSHFUSION_RUNTIME_ASSETS 覆盖")
        if ok or check_only:
            continue
        text = text.replace(
            'assets.srcDirs("../../runtime/android-assets")',
            'assets.srcDirs(System.getenv("DSHFUSION_RUNTIME_ASSETS") ?: "../../runtime/android-assets")')
        write(path, text)
        applied_count += 1

    elif div_id == "DIV-0004":
        path = root / "app/src/main/java/com/dshbox/app/util/AppVersion.kt"
        if not path.is_file():
            report(div_id, False, f"{path} 不存在")
            continue
        text = read(path)
        ok = "dshfusion" in text.lower()
        report(div_id, ok, "上游监测地址已改指自身")
        if ok or check_only:
            continue
        text = re.sub(r'https://wsk-build\.github\.io/DSHBox/', 'https://example.invalid/DSHFusion/', text)
        text = re.sub(r'https://github\.com/WSK-build/DSHBox', 'https://example.invalid/DSHFusion', text)
        text = re.sub(r'https://api\.github\.com/repos/WSK-build/DSHBox/releases/latest',
                      'https://example.invalid/DSHFusion/releases/latest', text)
        write(path, text)
        applied_count += 1

    elif div_id == "DIV-0005":
        path = root / "website"
        ok = not path.exists()
        report(div_id, ok, "website/ 未导入主干")
        # 目录型偏离不做自动删除：删除是破坏性动作，交给人确认。
        if not ok and not check_only:
            print("        → website/ 存在但未删除。删除是破坏性操作，请人工确认后执行：")
            print("          git rm -r website/")

    else:
        report(div_id, False, f"没有为 {div_id}（type={kind}）实现施加逻辑，需人工处理")

if not check_only:
    print(f"\n==> 本次自动施加了 {applied_count} 处改动")
    print("==> 请复核 git diff，并跑 ./gradlew testDebugUnitTest lintDebug 后提交")
PY
