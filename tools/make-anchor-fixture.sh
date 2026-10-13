#!/usr/bin/env bash
# 为 check-divergence-anchors.py 构造一个最小的「DSHBox 形状」上游夹具。
#
# 为什么需要它：锚点预检的正路径需要一份锚点全部成立的上游树，
# 负路径需要制造各类漂移。真实上游检出要么不在（干净克隆的 CI 里就没有），
# 要么不该被改动。这个夹具用最小文件复现这些情形。
#
# 用法：
#   tools/make-anchor-fixture.sh <目标目录> [场景]
#
# 场景：
#   ok            全部锚点成立（默认）
#   drift-appid   applicationId 写法改变 -> DIV-0001 失败
#   drift-url     AppVersion.kt 里的上游地址被删掉 -> DIV-0004 失败
#   drift-brand   品牌图形内容被改 -> DIV-0002 失败
#   no-website    website/ 不存在 -> DIV-0005 失败
#
# 夹具会同时生成一份自有的 divergences.json（通过 --divergences 注入），
# 其品牌哈希按夹具自己的图形文件计算，因此不依赖真实上游资产。

set -euo pipefail

TARGET="${1:?用法: make-anchor-fixture.sh <目标目录> [场景]}"
SCENARIO="${2:-ok}"
[ -e "$TARGET" ] && rm -rf "$TARGET"
mkdir -p "$TARGET"

UP="$TARGET/upstream"
mkdir -p "$UP/app/src/main/res/values" \
         "$UP/app/src/main/res/values-zh" \
         "$UP/app/src/main/res/mipmap-xxxhdpi" \
         "$UP/app/src/main/res/drawable-hdpi" \
         "$UP/app/src/main/java/com/dshbox/app/util" \
         "$UP/website"
: > "$UP/website/index.html"

cat > "$UP/app/build.gradle.kts" <<'EOF'
android {
    namespace = "com.dshbox.app"
    defaultConfig {
        applicationId = "com.dshbox.app"
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

for loc in values values-zh; do
    cat > "$UP/app/src/main/res/$loc/strings.xml" <<'EOF'
<resources>
    <string name="app_name">DSHBox</string>
</resources>
EOF
done

cat > "$UP/app/src/main/java/com/dshbox/app/util/AppVersion.kt" <<'EOF'
object AppVersion {
    const val SITE_URL = "https://wsk-build.github.io/DSHBox/"
    const val REPO_URL = "https://github.com/WSK-build/DSHBox"
    private const val API_URL = "https://api.github.com/repos/WSK-build/DSHBox/releases/latest"
}
EOF

# 夹具用的两个品牌图形（真实上游有 8 个，这里取 2 个代表即可）
printf 'FIXTURE-BRAND-ICON' > "$UP/app/src/main/res/mipmap-xxxhdpi/brand_icon.png"
printf 'FIXTURE-PILOT-BRAND' > "$UP/app/src/main/res/drawable-hdpi/ic_pilot_brand.png"

# ---------- 夹具自有的 divergences.json ----------
# 注意顺序：必须在制造漂移**之前**登记哈希。
# 反过来的话，drift-brand 场景会把改动后的内容登记成「上游原样」，
# 检查自然通过 —— 夹具就成了摆设。
python3 - "$TARGET" "$UP" <<'PY'
import hashlib, json, sys
from pathlib import Path

target, up = Path(sys.argv[1]), Path(sys.argv[2])

brand = {
    p.relative_to(up).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
    for p in sorted((up / "app/src/main/res").rglob("*.png"))
}

doc = {
    "schema": 1,
    "applied": [],
    "planned": [{
        "id": "DIV-0002",
        "title": "夹具：独立应用名与自有品牌图形",
        "upstream": "dshbox",
        "type": "identity",
        "phase": 1,
        "target_files": ["app/src/main/res"],
        "change": "fixture",
        "rationale": "fixture",
        "reapply": "fixture",
        "upstream_sha256": brand,
    }],
}
(target / "divergences.json").write_text(
    json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

# ---------- 按场景制造漂移（在登记哈希之后）----------
case "$SCENARIO" in
    drift-appid)
        sed -i 's/applicationId = "com.dshbox.app"/applicationId("com.dshbox.app")/' \
            "$UP/app/build.gradle.kts"
        ;;
    drift-url)
        sed -i '/REPO_URL/d' "$UP/app/src/main/java/com/dshbox/app/util/AppVersion.kt"
        ;;
    drift-brand)
        printf 'CHANGED-BY-UPSTREAM' > "$UP/app/src/main/res/mipmap-xxxhdpi/brand_icon.png"
        ;;
    no-website)
        rm -rf "$UP/website"
        ;;
esac

echo "锚点夹具已建立：$TARGET（场景：$SCENARIO）"
