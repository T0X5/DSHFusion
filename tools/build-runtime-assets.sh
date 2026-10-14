#!/usr/bin/env bash
#
# build-runtime-assets.sh — 构建「可嵌入 APK 的运行环境资产」。
#
# ── 为什么需要这个脚本 ────────────────────────────────────────────────
#
# 上游 DSHBox **不把 `runtime/` 提交进仓库**。它的发布流程在仓库外把层构建好，
# 再放到 `../../runtime/android-assets`（`DSHBox:app/build.gradle.kts:78` 从这里读）。
#
# DSHFusion 的 CI 照搬了上游的 `android.yml`，**但没搬这一步**，
# 于是打出来的 APK 一层运行环境都没有。这件事比它听起来严重：
#
#   `OnlineRuntimeImportManager.prepareAndroidSide()`（第 458 行）在
#   `assets/runtime/android-side.tar.*` 缺失时**硬失败**：
#       AppError("ONLINE_ASIDE", "cannot prepare android-side layer")
#   而它在整个流程的**下载之后**才执行 —— 用户会先等几百 MB 下载完，再看到失败。
#
#   也就是说：没有这两层时，「在线获取运行环境」这条**本来用于补救**的路也走不通。
#   2026-10-14 真机安装成功后发现的，详见 AGENTS.md。
#
# ── 产出什么 ──────────────────────────────────────────────────────────
#
# 只需两层进 APK（`DSHBox:README.md:231-233`）：`base` 与 `node` 由在线获取现场组装。
#
#   runtime/android-side.tar.zst  ← app/src/main/jniLibs/arm64-v8a/ 的 4 个 .so
#   dsh/<version>.tar.zst         ← npm @deepseek-ai/dsh（委托给 build-dsh-layer.sh）
#   （各自带 <层文件>.sha256 侧车，内容是裸哈希）
#
# ── 为什么必须是 zstd，gzip 不行 ──────────────────────────────────────
#
# 这里曾经写的是「gzip 也是一等格式，因为应用侧按魔数嗅探」。**那句话只对了一半，
# 而且是致命的那一半。** 应用侧确实按魔数嗅探（`BundleManager.extractTarGz` 依次试
# zstd / bzip2 / xz / plain-tar，gzip 兜底），但**层不是直接交给应用的 ——
# 它要先过 AGP 的资源打包**，而 AGP 会：
#
#   1. 把 `.gz` 资源**解压**，并
#   2. **把 `.gz` 扩展名去掉**
#
# 实测（CI run 38513950774 的产物，`tools/check-apk-identity.py` 之外另行核对）：
#
#   本地产出  runtime/android-side.tar.gz   125930 B  （gzip 魔数 1f 8b 08）
#   APK 内    assets/runtime/android-side.tar  337920 B （**未压缩的裸 tar**）
#
# 而应用侧的查找条件是 `OnlineRuntimeImportManager.kt:769`
#   `names.firstOrNull { it.startsWith("android-side.tar.") }`
# `"android-side.tar"` **不以 `"android-side.tar."` 开头**（少那个点），所以匹配不上。
# 结论：`.tar.gz` 打出来的层对应用**完全不可见**，等于没建。
#
# 上游实际发布的就是 zstd —— 从真机上装的官方 DSHBox 包直接读出来的：
#   assets/runtime/android-side.tar.zst   113245 B
#   assets/dsh/0.2.0-rc.2.tar.zst       85893395 B
#   lib/arm64-v8a/libzstd-jni-1.5.7-15.so        （zstd-jni 的 arm64 实现）
#
# 注意上游 `tools/pack_runtime.sh` 里默认 `COMPRESSION=gzip`，注释说「app 的 zstd
# 解压需要 zstd-jni，当前离线构建解析不到」—— **那段注释已经过时**：官方包里
# 明明带着 `libzstd-jni`，而 DSHFusion 也把 `libs/zstd-jni-1.5.7-15-classes.jar`
# 接进了 `app` 与 `sandbox-manager` 两个模块。**以发布产物为准，不以脚本默认值为准。**
#
# 所以本脚本**要求 zstd，缺了就失败退出**（退出码 2）。不静默降级到 gzip：
# 一个必然被 AGP 剥坏的产物，比一个明确的报错危险得多。
#
# 用法：
#   tools/build-runtime-assets.sh [输出目录]
#
# 环境变量：
#   DSHFUSION_RUNTIME_ASSETS  输出目录（默认 <repo>/runtime/android-assets）
#   ANDROID_SIDE_SRC          android-side 源目录（默认 app/src/main/jniLibs/arm64-v8a）
#   DSH_VERSION               DSH 版本（默认取 governance/upstreams.json，见 build-dsh-layer.sh）
#   SKIP_DSH=1                只构建 android-side（用于快速验证布局）
#
# 退出码：0 成功；1 构建失败；2 前置条件不满足（缺文件、工具不可用）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-${DSHFUSION_RUNTIME_ASSETS:-$ROOT/runtime/android-assets}}"
JNI_SRC="${ANDROID_SIDE_SRC:-$ROOT/app/src/main/jniLibs/arm64-v8a}"

info() { printf '  %s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die()  { printf '\n[错误] %s\n' "$1" >&2; exit "${2:-1}"; }

printf '构建可嵌入 APK 的运行环境资产\n'
printf '  输出目录 : %s\n' "$OUT_DIR"
printf '  源目录   : %s\n' "$JNI_SRC"

# ══════════════════════════════════════════════════════════════════════
# 0. 前置条件：zstd 必须可用
# ══════════════════════════════════════════════════════════════════════
#
# 放在最前面：与其先花时间装出 gzip 的层、再在最后发现不能用，
# 不如一开始就说清楚。见文件头「为什么必须是 zstd」。

step "前置条件"

ZSTD_BIN="$(command -v zstd || true)"
if [ -z "$ZSTD_BIN" ]; then
    cat >&2 <<'EOF'

[错误] 找不到 zstd，拒绝继续。

  原因不是「压缩率更好」，而是**没有别的可行格式**：
  AGP 会把 `.gz` 资源解压并去掉 `.gz` 扩展名，于是
      runtime/android-side.tar.gz  →  assets/runtime/android-side.tar
  而应用侧要求名字以 `android-side.tar.` 开头（带那个点），匹配不上 ——
  层对应用完全不可见，等于没建。gzip 产物在这个仓库里**没有可用场景**。

  安装：Debian/Ubuntu  `apt-get install -y zstd`
        macOS          `brew install zstd`
  CI（ubuntu-latest runner）自带 zstd，不需要额外步骤。

EOF
    exit 2
fi
info "zstd: $ZSTD_BIN（$(zstd --version 2>&1 | head -1)）"

# ══════════════════════════════════════════════════════════════════════
# 1. android-side 层
# ══════════════════════════════════════════════════════════════════════
#
# 层的内部布局由应用侧决定，不是随便定的：
#   `DefaultSandboxManager.prootBinary()`      → android-side/bin/proot
#   `DefaultSandboxManager.prootLibDir()`      → android-side/lib
#   `DefaultSandboxManager.prootLoaderFile()`  → android-side/libexec/proot/loader
# 与上游 `runtime-bundle/build_android_side.sh` 的映射一致。

step "android-side 层"

[ -d "$JNI_SRC" ] || die "android-side 源目录不存在：$JNI_SRC（可用 ANDROID_SIDE_SRC 覆盖）" 2

# 三个必需 + 一个可选，逐个 fail closed。
# 缺任何一个都不静默跳过：一层缺文件的 android-side 会在运行时变成
# 「proot 起不来」，而那时候离这里已经很远了。
declare -A ASIDE_MAP=(
    [libproot.so]=bin/proot
    [libproot-loader.so]=libexec/proot/loader
    [libandroid-shmem.so]=lib/libandroid-shmem.so
    [libtalloc.so]=lib/libtalloc.so
)
ASIDE_REQUIRED=(libproot.so libproot-loader.so libandroid-shmem.so)

for f in "${ASIDE_REQUIRED[@]}"; do
    [ -f "$JNI_SRC/$f" ] || die "android-side 缺少必需文件：$JNI_SRC/$f" 2
done

ASIDE_STAGE="$(mktemp -d)"
cleanup_aside() { rm -rf "$ASIDE_STAGE"; }
trap cleanup_aside EXIT INT TERM

ASIDE_COUNT=0
for src_name in "${!ASIDE_MAP[@]}"; do
    dst_rel="${ASIDE_MAP[$src_name]}"
    if [ ! -f "$JNI_SRC/$src_name" ]; then
        # 只有 libtalloc.so 允许缺席（上游也把它标为 optional）。
        if [ "$src_name" = "libtalloc.so" ]; then
            info "跳过可选文件 $src_name（上游同样标为 optional）"
            continue
        fi
        die "android-side 缺少 $src_name" 2
    fi
    mkdir -p "$ASIDE_STAGE/$(dirname "$dst_rel")"
    cp "$JNI_SRC/$src_name" "$ASIDE_STAGE/$dst_rel"
    ASIDE_COUNT=$((ASIDE_COUNT + 1))
    info "$src_name → $dst_rel"
done

# 可执行位必须在打包**之前**显式设好：应用侧解包时按 tar 里的 mode 还原
# （`BundleManager.applyEntryMode` 只保留 owner 位）。漏了这一步，
# 解出来的 proot 不可执行 —— 而且是运行时才炸。
#
# 显式 chmod 而不是依赖源文件的模式：git 索引里这四个都是 100755，
# 但检出到工作区后实际模式会被 umask / 归档工具改写（本机就是 700）。
# 让产物由脚本决定，而不是由「谁的机器怎么检出的」决定。
chmod 755 "$ASIDE_STAGE/bin/proot" "$ASIDE_STAGE/libexec/proot/loader"
for so in "$ASIDE_STAGE"/lib/*.so; do
    [ -e "$so" ] && chmod 755 "$so"
done
info "已设模式：bin/proot, libexec/proot/loader, lib/*.so → 755"

mkdir -p "$OUT_DIR/runtime"
ASIDE_TAR="$OUT_DIR/runtime/android-side.tar.zst"
tar -C "$ASIDE_STAGE" -I "$ZSTD_BIN -19 -T0" -cf "$ASIDE_TAR" .
ASIDE_SHA="$(sha256sum "$ASIDE_TAR" | cut -d' ' -f1)"
printf '%s' "$ASIDE_SHA" > "$ASIDE_TAR.sha256"

ASIDE_SIZE="$(stat -c %s "$ASIDE_TAR" 2>/dev/null || stat -f %z "$ASIDE_TAR")"
info "已产出 $ASIDE_TAR（$ASIDE_SIZE 字节，sha256 ${ASIDE_SHA:0:12}…）"

# 产物自检 1：魔数必须是 zstd 帧头。AGP 会不会动它取决于格式，
# 与其在 APK 那一侧才发现，不如在这里就确认写出来的是什么东西。
MAGIC="$(head -c 4 "$ASIDE_TAR" | od -An -tx1 | tr -d ' \n')"
if [ "$MAGIC" != "28b52ffd" ]; then
    die "android-side 层魔数不是 zstd 帧头（实测 $MAGIC）—— 产物格式与预期不符" 1
fi
info "魔数自检通过（zstd 帧头 28 b5 2f fd）"

# 产物自检 2：布局。不是「文件存在」就算数，而是解包后**路径确实对**。
# 一个路径错的层比没有层更难查 —— 它在运行时表现为 proot 找不到。
# 注意最后那个 `sed '/^$/d'`：`tar -C <dir> -cf … .` 的首个条目是 `./`，
# 去掉前缀后变成空行；空行不以 `/` 结尾，会被上一句 `grep -v '/$'` 放过去。
# 少了这个过滤，自检会把**正确**的层判成错的 —— 第一次运行就是这么失败的。
LISTING="$("$ZSTD_BIN" -dc "$ASIDE_TAR" | tar -tf - | sed 's#^\./##' | grep -v '/$' | sed '/^$/d' | sort)"
EXPECTED="$(printf 'bin/proot\nlib/libandroid-shmem.so\nlibexec/proot/loader\n' | sort)"
if [ -f "$ASIDE_STAGE/lib/libtalloc.so" ]; then
    EXPECTED="$(printf 'bin/proot\nlib/libandroid-shmem.so\nlib/libtalloc.so\nlibexec/proot/loader\n' | sort)"
fi
if [ "$LISTING" != "$EXPECTED" ]; then
    printf '[错误] android-side 层布局不符\n--- 实际 ---\n%s\n--- 期望 ---\n%s\n' \
        "$LISTING" "$EXPECTED" >&2
    exit 1
fi
info "布局自检通过（$(printf '%s\n' "$LISTING" | wc -l) 个文件）"

# 产物自检 3：模式。`BundleManager.applyEntryMode` 只保留 owner 位，
# 所以 owner 的 x 位就是「能不能执行」的全部。
MODES="$("$ZSTD_BIN" -dc "$ASIDE_TAR" | tar -tvf - | awk '{print $1}' | sort -u | tr '\n' ' ')"
case "$MODES" in
    *rwxr-xr-x*) : ;;
    *) die "android-side 层里有条目的模式不是 rwxr-xr-x（实测：$MODES）—— 解包后 proot 可能不可执行" 1 ;;
esac
info "模式自检通过（$MODES）"

# ══════════════════════════════════════════════════════════════════════
# 2. dsh 层（委托给 build-dsh-layer.sh）
# ══════════════════════════════════════════════════════════════════════
#
# 不在这里重写：那一层有自己的失败模式（npm 别名解析到别的版本、布局漂移、
# 入口文件缺失），build-dsh-layer.sh 已经逐个 fail closed 处理过，
# 并且真跑通过。重复实现等于制造第二个事实来源。

if [ "${SKIP_DSH:-0}" = "1" ]; then
    step "dsh 层：已按 SKIP_DSH=1 跳过"
else
    step "dsh 层（委托 tools/build-dsh-layer.sh）"
    [ -x "$ROOT/tools/build-dsh-layer.sh" ] || die "tools/build-dsh-layer.sh 不可执行" 2
    "$ROOT/tools/build-dsh-layer.sh" "$OUT_DIR"
fi

# ══════════════════════════════════════════════════════════════════════
# 3. 汇总（也是给 CI 日志看的「到底产出了什么」）
# ══════════════════════════════════════════════════════════════════════

step "产出汇总"
find "$OUT_DIR" -type f \( -name '*.tar.gz' -o -name '*.tar.zst' -o -name '*.sha256' \) \
    | sort | while read -r f; do
    printf '  %10s  %s\n' "$(stat -c %s "$f" 2>/dev/null || stat -f %z "$f")" "${f#"$ROOT"/}"
done

# 收尾断言：确认产出的**就是**应用侧会找到的那个名字。
# 这里刻意写死完整文件名而不是 glob：这条断言的职责正是「名字必须精确」，
# 用 `android-side.tar.*` 去匹配会让 `.sha256` 侧车也能满足它 ——
# 第一版 check-apk-identity.py 的断言 6 就是这么被骗过去的。
[ -f "$OUT_DIR/runtime/android-side.tar.zst" ] \
    || die "缺少 runtime/android-side.tar.zst —— 应用侧按 'android-side.tar.' 前缀查找，名字不对就等于没层" 1

# 反向确认没有留下会被 AGP 剥坏的 gzip 产物：它不会被上面的断言发现，
# 却会一起进 APK（AGP 收整个目录），留下一个误导性的 `android-side.tar`。
if [ -f "$OUT_DIR/runtime/android-side.tar.gz" ]; then
    die "输出目录里同时存在 android-side.tar.gz —— 它会被 AGP 解压并去掉扩展名，在 APK 里变成误导性的 android-side.tar，请先删掉" 1
fi

cat <<EOF

完成。把这个目录通过 DSHFUSION_RUNTIME_ASSETS 指给 gradle：
    DSHFUSION_RUNTIME_ASSETS=$OUT_DIR ./gradlew assembleDebug
EOF
