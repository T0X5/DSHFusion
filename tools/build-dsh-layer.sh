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
# 本脚本补上这一环，并且可以在**任何有 node + npm 的机器上**运行 ——
# 但**必须显式指定目标平台**。见下面「平台」一节：这里曾经写着
# 「`@deepseek-ai/dsh` 是纯 JavaScript 的 npm 包（无原生扩展、无 os/cpu 约束）」，
# **那句话是错的**，而且它让整个层在真机上一次都跑不起来。
#
# ## 平台（2026-10-14 补，来自真机失败）
#
# `@deepseek-ai/dsh` 本身确实是纯 JS，但它的依赖树里有若干**按平台分发的可选包**
# （optionalDependencies，由 npm 按宿主平台自动挑选）：
#
#   node-addon-require-builtin-linux-<arch>-gnu     ← dsh-app-boot 启动时**必需**
#   @deepseek-ai/node-addon-system-linux-<arch>
#   @img/sharp-linux-<arch> / @img/sharp-libvips-linux-<arch>
#   sherpa-onnx-linux-<arch>
#   @koromix/koffi-linux-<arch>
#
# CI 跑在 x86_64 runner 上，于是 npm 装的全是 `-x64` 那一套。产物打进 APK、
# 装到 arm64 手机上，DSH 在 host preparation 阶段直接致命退出：
#
#   dsh: host preparation failed:
#        No usable native binding found for
#        node-addon-require-builtin-linux-arm64-gnu (auto)
#
# 所以必须显式覆盖 npm 的平台判定（npm >= 10.4 支持，CI 用的是 npm 11）：
#
#   --os=linux --cpu=arm64 --libc=glibc
#
# 这三个参数只影响**可选依赖的挑选**，不影响纯 JS 包。装完还会自检
# （见「5.5 平台自检」），不通过就拒绝产出 —— 一个架构错的层比没有层更坏：
# 它能装、能解包、能起来 node，然后在启动到一半时致命失败。
#
# 依据是**上游发布产物**（真机能跑的官方 DSHBox v1.4.0 的
# `0.2.0-rc.2.tar.zst`，sha256 4197ec52…），逐包对照后确认它装的全是 arm64。
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
#   DSH_TARGET_ARCH      目标 CPU 架构，默认 arm64（阶段 1 只支持 arm64-v8a）
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
DSH_TARGET_ARCH="${DSH_TARGET_ARCH:-arm64}"
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
# 平台参数是**必需**的，不是优化：CI 是 x86_64，不覆盖就会装出 x64 的可选包，
# 层在 arm64 真机上跑到一半致命失败。理由见文件头「平台」一节。
info "npm install @deepseek-ai/dsh@$VERSION（目标平台 linux/$DSH_TARGET_ARCH/glibc，约 500 MB，可能需要几分钟）"
npm install \
    --prefix "$LAYER_ROOT" \
    "npm:@deepseek-ai/dsh@$VERSION" \
    --registry "$DSH_NPM_REGISTRY" \
    --os=linux --cpu="$DSH_TARGET_ARCH" --libc=glibc \
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

# ---------- 6.5 平台自检（fail closed）----------
#
# 这一节是 2026-10-14 真机失败之后补的。之前这个脚本能「成功」产出一个
# 架构完全错误的层，而且它一路通过了打包、CI 断言、装到真机 —— 直到 DSH
# 在 host preparation 阶段致命退出才暴露。
#
# 断言清单不是想出来的，是**逐包对照上游发布产物**得到的：
# 官方 DSHBox v1.4.0 的 `0.2.0-rc.2.tar.zst`（sha256 4197ec52…，真机能跑）
# 与我们的层做包目录集合比较，恰好是 7 个包一一对换（arm64 ↔ x64）。
# 这 7 个就是全部按平台分发的可选包，一个不多一个不少。
#
# 为什么要「必需 + 禁止」两条都查：只查「arm64 在不在」不够 ——
# npm 完全可能同时装两套（比如 lockfile 里钉了旧的），那时层体积翻倍、
# 应用侧加载器也可能挑错。上游的形态是**只有一套**，就按上游钉。
PLATFORM_PKGS=(
    "node-addon-require-builtin-linux-ARCH-gnu"
    "@deepseek-ai/node-addon-system-linux-ARCH"
    "@img/sharp-linux-ARCH"
    "@img/sharp-libvips-linux-ARCH"
    "@koromix/koffi-linux-ARCH"
    "@vscode/ripgrep-linux-ARCH"
    "sherpa-onnx-linux-ARCH"
)

OTHER_ARCH=x64
if [ "$DSH_TARGET_ARCH" = "x64" ]; then
    OTHER_ARCH=arm64
fi

MISSING=()
WRONG=()
for tmpl in "${PLATFORM_PKGS[@]}"; do
    want="${tmpl//ARCH/$DSH_TARGET_ARCH}"
    bad="${tmpl//ARCH/$OTHER_ARCH}"
    [ -d "$LAYER_ROOT/node_modules/$want" ] || MISSING+=("$want")
    [ -d "$LAYER_ROOT/node_modules/$bad" ] && WRONG+=("$bad")
done

if [ "${#MISSING[@]}" -gt 0 ] || [ "${#WRONG[@]}" -gt 0 ]; then
    {
        printf '\n错误：DSH 层的目标平台不对，拒绝产出。\n\n'
        if [ "${#MISSING[@]}" -gt 0 ]; then
            printf '  缺少 %s 包（DSH 在 arm64 上启动时必需）：\n' "$DSH_TARGET_ARCH"
            printf '    %s\n' "${MISSING[@]}"
        fi
        if [ "${#WRONG[@]}" -gt 0 ]; then
            printf '  混进了 %s 包（宿主平台，不是目标平台）：\n' "$OTHER_ARCH"
            printf '    %s\n' "${WRONG[@]}"
        fi
        cat <<'EOF'

  这不是「最好有」的检查。一个架构错的层能装、能解包、能起来 node，
  然后在 host preparation 阶段才致命失败：

      dsh: host preparation failed: No usable native binding found for
           node-addon-require-builtin-linux-arm64-gnu (auto)

  真机上看到的就是这个 —— 界面报「DSH 异常」，日志里是上面那行。

  修法：确认 npm install 带了 --os=linux --cpu=<目标架构> --libc=glibc
  （npm >= 10.4 才支持这三个参数）。

EOF
    } >&2
    exit 1
fi
info "平台自检通过：$DSH_TARGET_ARCH 的 ${#PLATFORM_PKGS[@]} 个平台包齐全，且无 $OTHER_ARCH 混入"

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
# 实现上有两条路，**两条都只产出 zstd**：
#   ① 系统里的 zstd CLI —— CI 自带，压缩率最好，首选
#   ② node 的 zlib.createZstdCompress —— Node >= 22.15 / 23.8 才有
# 两条都没有才退出 2。这里没有任何一条路会产出 gzip：
# 兜底改变的是「用什么压缩」，不是「要不要压缩」。
if command -v zstd >/dev/null 2>&1; then
    ZSTD_MODE=cli
elif node -e "process.exit(require('zlib').createZstdCompress ? 0 : 1)" 2>/dev/null; then
    ZSTD_MODE=node
else
    cat >&2 <<'EOF'

错误：既没有 zstd 命令，也没有支持 zstd 的 node，拒绝继续。

  不是「压缩率更好」的问题，而是**没有别的可行格式**：
  AGP 会把 `.gz` 资源解压并去掉扩展名，于是
      dsh/<version>.tar.gz  →  assets/dsh/<version>.tar
  而应用侧要求名字以 `<layer>.tar.` 开头（带那个点），匹配不上 ——
  层对应用完全不可见。gzip 产物在这个仓库里没有可用场景。

  安装：Debian/Ubuntu  apt-get install -y zstd
        macOS          brew install zstd
  或者升级 node 到 22.15+（自带 zlib.createZstdCompress）。
  CI（ubuntu-latest runner）自带 zstd。

EOF
    exit 2
fi

if [ "$ZSTD_MODE" = "cli" ]; then
    info "打包（zstd -19 -T0，系统 CLI）：$TARBALL"
    tar -C "$LAYER_ROOT" -I 'zstd -19 -T0' -cf "$TARBALL" .
else
    info "打包（node zlib.createZstdCompress，无系统 zstd CLI）：$TARBALL"
    # ⚠️ 实测：node 的 zstd **忽略 level 参数**。
    # 同一份 40 MB 样本，level 1/3/9/19/22 输出字节数完全相同（5808545 B，
    # 各约 60 ms）—— 也就是说它固定跑在某个默认级别上，调不高。
    # 后果只是体积，实测同一层：CLI -19 = 84973267 B，node = 111809043 B（+31.6%）。
    # **格式与内容都正确**，所以这里不判失败，但必须说出来 ——
    # 一个「命令一样、产物大 30%」的静默差异，正是这个仓库反复踩的那类坑。
    printf '    注意：node 的 zstd 无法指定压缩级别，产物比 CLI -19 大约 32%%。\n' >&2
    printf '          CI 用 ubuntu-latest 自带的 zstd CLI，不会走这条路。\n' >&2
    tar -C "$LAYER_ROOT" -cf - . | node -e '
        const fs = require("fs"), z = require("zlib");
        const out = fs.createWriteStream(process.argv[1]);
        out.on("error", (e) => { console.error(e.message); process.exit(1); });
        process.stdin
            .pipe(z.createZstdCompress())
            .pipe(out)
            .on("finish", () => process.exit(0));
    ' "$TARBALL"
fi

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
