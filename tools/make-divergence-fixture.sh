#!/usr/bin/env bash
# 为 apply-divergences.sh 构造一个「上游已导入」的测试夹具。
#
# 为什么需要它：偏离重放的失败路径（固定字符串未匹配、替换后未生效、
# 未实现施加逻辑、目标文件缺失）在真实仓库上要么无法制造，要么会污染主干。
# 这个夹具用最小的假 DSHBox 文件复现这些情形。
#
# 用法：
#   tools/make-divergence-fixture.sh <目标目录> [场景]
#
# 场景：
#   normal        全部偏离可正常施加（默认）
#   drift         上游把 applicationId 写法改了 -> DIV-0001 固定字符串未匹配
#   asset-missing app/build.gradle.kts 里没有 assets.srcDirs(...) -> DIV-0003 未匹配
#   strings-moved strings.xml 里没有 app_name 条目 -> DIV-0002 失败
#   unknown-div   divergences.json 里混入一个没有实现的偏离 id
#   applied-nonempty  阶段 0 场景下 applied 非空（一致性检查应失败）

set -euo pipefail

TARGET="${1:?用法: make-divergence-fixture.sh <目标目录> [场景]}"
SCENARIO="${2:-normal}"
[ -e "$TARGET" ] && rm -rf "$TARGET"
mkdir -p "$TARGET"

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- 假的上游文件 ----------
mkdir -p "$TARGET/app/src/main/res/values" \
         "$TARGET/app/src/main/res/values-zh" \
         "$TARGET/app/src/main/java/com/dshbox/app/util" \
         "$TARGET/governance"

cat > "$TARGET/app/build.gradle.kts" <<'EOF'
android {
    namespace = "com.dshbox.app"
    defaultConfig {
        applicationId = "com.dshbox.app"
        versionCode = 8
    }
}

android {
    sourceSets {
        getByName("main") {
            assets.srcDirs("../../runtime/android-assets")
        }
    }
}
EOF

cat > "$TARGET/app/src/main/res/values/strings.xml" <<'EOF'
<resources>
    <string name="app_name">DSHBox</string>
</resources>
EOF

cat > "$TARGET/app/src/main/res/values-zh/strings.xml" <<'EOF'
<resources>
    <string name="app_name">DSHBox</string>
</resources>
EOF

cat > "$TARGET/app/src/main/java/com/dshbox/app/util/AppVersion.kt" <<'EOF'
object AppVersion {
    private const val API = "https://api.github.com/repos/WSK-build/DSHBox/releases/latest"
    private const val SITE = "https://wsk-build.github.io/DSHBox/"
}
EOF

# ---------- 按场景制造偏差 ----------
case "$SCENARIO" in
    drift)
        # 上游把 applicationId 写法改成了带空格/单引号之外的形式
        sed -i 's/applicationId = "com.dshbox.app"/applicationId("com.dshbox.app")/' \
            "$TARGET/app/build.gradle.kts"
        sed -i 's/namespace = "com.dshbox.app"/namespace = "com.dshbox.app"/' \
            "$TARGET/app/build.gradle.kts"
        ;;
    asset-missing)
        sed -i '/assets.srcDirs/d' "$TARGET/app/build.gradle.kts"
        ;;
    strings-moved)
        cat > "$TARGET/app/src/main/res/values/strings.xml" <<'EOF'
<resources>
    <string name="application_name">DSHBox</string>
</resources>
EOF
        cat > "$TARGET/app/src/main/res/values-zh/strings.xml" <<'EOF'
<resources>
    <string name="application_name">DSHBox</string>
</resources>
EOF
        ;;
esac

# ---------- divergences.json ----------
python3 - "$TARGET" "$SCENARIO" <<'PY'
import json, sys
from pathlib import Path

target, scenario = Path(sys.argv[1]), sys.argv[2]

planned = [
    {"id": "DIV-0001", "type": "identity", "phase": 1,
     "title": "独立应用标识 com.dshfusion.app",
     "target_files": ["app/build.gradle.kts"],
     "change": "applicationId/namespace -> com.dshfusion.app",
     "rationale": "fixture", "reapply": "替换 applicationId/namespace"},
    {"id": "DIV-0002", "type": "identity", "phase": 1,
     "title": "独立应用名",
     "target_files": ["app/src/main/res/values/strings.xml"],
     "change": "app_name -> DSHFusion",
     "rationale": "fixture", "reapply": "替换 app_name"},
    {"id": "DIV-0003", "type": "adaptation", "phase": 1,
     "title": "运行环境资产路径可配置",
     "target_files": ["app/build.gradle.kts"],
     "change": "assets.srcDirs 可被环境变量覆盖",
     "rationale": "fixture", "reapply": "替换 assets.srcDirs"},
    {"id": "DIV-0004", "type": "config", "phase": 1,
     "title": "上游监测地址指向自身",
     "target_files": ["app/src/main/java/com/dshbox/app/util/AppVersion.kt"],
     "change": "URL 改指自身",
     "rationale": "fixture", "reapply": "替换 URL"},
    {"id": "DIV-0005", "type": "removal", "phase": 1,
     "title": "不导入 website/",
     "target_files": ["website/"],
     "change": "不导入",
     "rationale": "fixture", "reapply": "不导入"},
]

applied = []
if scenario == "unknown-div":
    planned.append({
        "id": "DIV-9999", "type": "patch", "phase": 9,
        "title": "一个没有实现施加逻辑的偏离",
        "target_files": ["some/file.kt"],
        "change": "x", "rationale": "x", "reapply": "x",
    })

if scenario == "applied-nonempty":
    applied = [planned[0]]
    planned = planned[1:]

doc = {"schema": 1, "applied": applied, "planned": planned}
(target / "governance/divergences.json").write_text(
    json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

mkdir -p "$TARGET/tools"
cp "$TOOLS_DIR/apply-divergences.sh" "$TARGET/tools/"
cp "$TOOLS_DIR/check-upstreams.py" "$TARGET/tools/" 2>/dev/null || true

echo "夹具已建立：$TARGET（场景：$SCENARIO）"
