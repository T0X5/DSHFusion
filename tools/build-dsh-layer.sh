#!/usr/bin/env bash
# build-dsh-layer.sh — 构建 DSH 运行环境层（补运行环境风险 R1）。
#
# ## 为什么需要这个脚本
#
# 上游 DSHBox 把 DSH 层当作**独立的发布产物**：`runtime-bundle/bundle.yaml`
# 里明确写着「bundle 不得包含 DSH」，层是 `dsh/<version>.tar.zst` 单独分发的。
# 但上游仓库里**没有**生成这一层的脚本 —— `runtime-bundle/scripts/install_dsh.sh`
# 是设计成在 Debian 沙箱**内部**跑的片段，没有任何调用方，也无法在仓库外直接执行。
# 结果是：新克隆一份代码，构建出的 APK 不含 DSH 层，装上也起不了 DSH。
# 这就是审计里的运行环境风险 R1。
#
# 本脚本补上这一环，并且可以在**任何有 node + npm 的机器上**运行：
# `@deepseek-ai/dsh` 是纯 JavaScript 的 npm 包（无原生扩展、无 os/cpu 约束），
# 所以不需要 Debian 沙箱、不需要 PRoot、不需要 arm64 主机。
# 层内布局与 `DefaultSandboxManager.dshLayerDir()` 的期望一致：
# 解包后得到 `node_modules/@deepseek-ai/dsh/lib/bin.js`，
# 运行时整层被 bind 到 guest 的 `/opt/dshapp/runtime`。
#
# ## 版本从哪来
#
# 从 governance/upstreams.json 的 tracked_version 读，而不是在这里写死一个默认值。
# 上游的 install_dsh.sh 默认 0.1.0-rc.6，而实际适配的是 0.2.0-rc.2 ——
# 这个漂移就是审计里的风险 R3。单一事实来源能从结构上消灭它：
# 想让层换版本，就去改 upstreams.json，两处不会再有分歧。
#
# ## 用法
#
#   tools/build-dsh-layer.sh [输出目录]
#
# 环境变量：
#   DSH_VERSION          覆盖版本（默认取 upstreams.json 的 tracked_version）
#   DSH_NPM_REGISTRY     默认 https://registry.npmjs.org
#   DSH_SKIP_SMOKE=1     跳过 `node lib/bin.js --version` 冒烟测试
#   DSH_KEEP_STAGE=1     保留中间暂存目录（排错用）
#
# 产出（输出目录默认 ./runtime/android-assets）：
#   dsh/<version>.tar.zst
#   dsh/<version>.tar.zst.sha256
#
# 退出码：0 成功；1 失败；2 用法/环境错误。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT/runtime/android-assets}"

DSH_NPM_REGISTRY="${DSH_NPM_REGISTRY:-https://registry.npmjs.org}"
DSH_SKIP_SMOKE="${DSH_SKIP_SMOKE:-0}"
DSH_KEEP_STAGE="${DSH_KEEP_STAGE:-0}"

die() { printf '错误：%s\n' "$1" >&2; exit "${2:-1}"; }
info() { printf '==> %s\n' "$1"; }

# ---------- 1. 解析版本 ----------
if [ -n "${DSH_VERSION:-}" ]; then
    VERSION="$DSH_VERSION"
    info "版本来自环境变量 DSH_VERSION=$VERSION"
else
    UPSTREAMS="$ROOT/governance/upstreams.json"
    [ -f "$UPSTREAMS" ] || die "找不到 $UPSTREAMS，无法确定 DSH 版本" 2
    VERSION="$(python3 - "$UPSTREAMS" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
entries = doc.get("transitive_upstreams", [])
# 主路径：按 id 精确定位。
for entry in entries:
    if entry.get("id") == "deepseek-harness":
        print(entry.get("tracked_version", ""))
        break
else:
    # 退路：id 若改名，不要静默失效 —— 按特征字符串再找一遍。
    for entry in entries:
        blob = " ".join(str(entry.get(k, "")) for k in ("id", "full_name", "note"))
        if "deepseek-harness" in blob or "DSH 本体" in blob:
            print(entry.get("tracked_version", ""))
            break
PY
)"
    [ -n "$VERSION" ] || die "upstreams.json 里读不到 @deepseek-ai/dsh 的 tracked_version" 2
    info "版本来自 governance/upstreams.json：$VERSION"
fi

# ---------- 2. 工具链检查 ----------
command -v node >/dev/null 2>&1 || die "找不到 node（DSH 层需要 node 与 npm 来安装）" 2
command -v npm  >/dev/null 2>&1 || die "找不到 npm" 2
command -v python3 >/dev/null 2>&1 || die "找不到 python3" 2

# 取 node 主版本。必须严格校验是纯数字再比较：
# `[ "$X" -lt 20 ]` 在 X 不是整数时会报 "integer expression expected"
# 并**返回假**，于是低版本 node 会被当成通过、一路走到冒烟测试才失败 ——
# 报错位置离真正原因很远。这里显式判断，失败信息直接说清。
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || true)"
info "node $(node --version 2>/dev/null || echo '?') / npm $(npm --version 2>/dev/null || echo '?')"
case "$NODE_MAJOR" in
    ''|*[!0-9]*)
        die "读不出 node 主版本（得到「$NODE_MAJOR」）—— node 可能不是真的 node" 2 ;;
esac
if [ "$NODE_MAJOR" -lt 20 ]; then
    die "node 主版本 $NODE_MAJOR 过低；DSH 需要 node >= 20（运行环境层用的是 24.x）" 2
fi

# ---------- 3. 暂存目录 ----------
STAGE="$(mktemp -d)"
cleanup() {
    if [ "$DSH_KEEP_STAGE" = "1" ]; then
        printf '暂存目录保留在：%s\n' "$STAGE"
    else
        rm -rf "$STAGE"
    fi
}
trap cleanup EXIT INT TERM

# 层根 = 解包后即为 /opt/dshapp/runtime 的内容，因此直接装到 node_modules/
LAYER_ROOT="$STAGE/layer"
mkdir -p "$LAYER_ROOT"

# ---------- 4. 安装 ----------
info "npm install @deepseek-ai/dsh@$VERSION（约 500 MB，可能需要几分钟）"
npm install \
    --prefix "$LAYER_ROOT" \
    "npm:@deepseek-ai/dsh@$VERSION" \
    --registry "$DSH_NPM_REGISTRY" \
    --no-audit --no-fund --loglevel=error

PKG_JSON="$LAYER_ROOT/node_modules/@deepseek-ai/dsh/package.json"
[ -f "$PKG_JSON" ] || die "安装后找不到 $PKG_JSON —— npm 可能改变了布局"

# ---------- 5. 版本核对（fail closed）----------
INSTALLED="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1],encoding='utf-8'))['version'])" "$PKG_JSON")"
if [ "$INSTALLED" != "$VERSION" ]; then
    # npm 的 "npm:" 别名语法在版本不可得时可能解析到别的版本。
    # 这里必须硬失败：一个版本对不上的层，会让「适配 0.2.0-rc.2」这句话变成假的。
    die "装出来的版本是 $INSTALLED，期望 $VERSION —— 层与登记不一致，拒绝产出"
fi
info "版本核对通过：$INSTALLED"

# ---------- 6. 关键文件存在性 ----------
BIN_JS="$LAYER_ROOT/node_modules/@deepseek-ai/dsh/lib/bin.js"
[ -f "$BIN_JS" ] || die "缺少入口 lib/bin.js —— 层布局与应用期望不符（dshLayerDir 绑定到 /opt/dshapp/runtime）"

# ---------- 7. 冒烟测试 ----------
if [ "$DSH_SKIP_SMOKE" != "1" ]; then
    info "冒烟测试：node lib/bin.js --version"
    SMOKE_OUT="$(node "$BIN_JS" --version 2>&1 || true)"
    case "$SMOKE_OUT" in
        *"$VERSION"*) info "冒烟测试通过：$SMOKE_OUT" ;;
        *) die "冒烟测试失败：期望输出含 $VERSION，实际为「$SMOKE_OUT」" ;;
    esac
else
    info "已按 DSH_SKIP_SMOKE=1 跳过冒烟测试"
fi

# ---------- 8. 打包 ----------
mkdir -p "$OUT_DIR/dsh"
TARBALL="$OUT_DIR/dsh/$VERSION.tar.zst"

# 压缩器：**必须** zstd。
#
# 这里曾经在找不到 zstd 时退回 gzip 并只打印一条提示。那个兜底是错的，
# 而且错得不容易发现：AGP 会把 `.gz` 资源解压并**去掉 `.gz` 扩展名**，
# 于是 dsh 层在 APK 里变成 `assets/dsh/<version>.tar`（裸 tar），
# 而应用侧的查找条件是 `it.startsWith("$layer.tar.")` —— 匹配不上，
# 层对应用不可见。产物看着「建好了」，实际上等于没建。
#
# 上游实际发布的就是 zstd（从真机装的官方 DSHBox 包读出：
# `assets/dsh/0.2.0-rc.2.tar.zst`）。上游 `tools/pack_runtime.sh` 里那句
# 「默认 gzip，因为 zstd-jni 解析不到」是过时注释 —— 官方包里带着
# `lib/arm64-v8a/libzstd-jni-1.5.7-15.so`，DSHFusion 也把
# `libs/zstd-jni-1.5.7-15-classes.jar` 接进了 app 与 sandbox-manager。
# **以发布产物为准，不以脚本默认值为准。**
if ! command -v zstd >/dev/null 2>&1; then
    cat >&2 <<'EOF'

错误：找不到 zstd，拒绝继续。

  不是「压缩率更好」的问题，而是**没有别的可行格式**：
  AGP 会把 `.gz` 资源解压并去掉扩展名，于是
      dsh/<version>.tar.gz  →  assets/dsh/<version>.tar
  而应用侧要求名字以 `<layer>.tar.` 开头（带那个点），匹配不上 ——
  层对应用完全不可见。gzip 产物在这个仓库里没有可用场景。

  安装：Debian/Ubuntu  apt-get install -y zstd
        macOS          brew install zstd
  CI（ubuntu-latest runner）自带 zstd。

EOF
    exit 2
fi
info "打包（zstd -19）：$TARBALL"
tar -C "$LAYER_ROOT" -I 'zstd -19 -T0' -cf "$TARBALL" .

# 魔数自检：写出来的必须是 zstd 帧头，而不是别的什么。
MAGIC="$(head -c 4 "$TARBALL" | od -An -tx1 | tr -d ' \n')"
if [ "$MAGIC" != "28b52ffd" ]; then
    die "dsh 层魔数不是 zstd 帧头（实测 $MAGIC）" 1
fi

# ---------- 9. 校验和 ----------
# 与上游约定一致：同目录放 <层文件>.sha256，内容是裸哈希（应用侧按此解析）。
SHA="$(sha256sum "$TARBALL" | cut -d' ' -f1)"
printf '%s' "$SHA" > "$TARBALL.sha256"

SIZE="$(stat -c %s "$TARBALL" 2>/dev/null || stat -f %z "$TARBALL")"

cat <<EOF

==> 完成
    层文件   : $TARBALL
    校验和   : $TARBALL.sha256
    sha256   : $SHA
    大小     : $SIZE 字节（$(python3 -c "print(f'{$SIZE/1048576:.1f}')") MB）
    DSH 版本 : $VERSION

放置到应用读取的位置：
    cp "$TARBALL" "$TARBALL.sha256" <runtime/android-assets>/dsh/

或在构建 APK 前把整个 runtime/android-assets 通过 DSHFUSION_RUNTIME_ASSETS 指过去。
EOF
