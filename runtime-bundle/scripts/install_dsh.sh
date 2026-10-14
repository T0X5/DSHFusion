#!/bin/bash
# install_dsh.sh - runs inside Debian sandbox. Installs the exact DSH version
# for offline/online bundle preparation. Do NOT rely on latest tags of
# @deepseek-ai/dsh-* subpackages; always pin the exact DSH version.

set -euo pipefail

# DIV-0010：**不再提供默认版本**。
#
# 这里原本写的是 `DSH_VERSION="${DSH_VERSION:-0.1.0-rc.6}"`，而实际适配的
# DSH 是 0.2.0-rc.2。默认值一旦与实际适配版本不一致，脚本就会在没人指定的
# 情况下静默装出一个错的版本 —— 而且装完还打印「pin exact version」，
# 让人以为一切正常。这正是 R3 记录的那个漂移。
#
# 现在改为失败即报错。理由：版本号只应有一个事实来源
# （governance/upstreams.json 的 tracked_version，由 tools/build-dsh-layer.sh 读取）。
# 本脚本被直接调用时，调用方必须自己说清要哪个版本；猜一个默认值是错的。
#
# 注：本脚本在 DSHFusion 里**没有任何地方执行它** —— app 走的是
# SandboxManager.installDshFromNpm 的 Kotlin 路径。所以这个改动不影响运行，
# 它修的是一个还没被人踩到的坑。
if [ -z "${DSH_VERSION:-}" ]; then
    cat >&2 <<'ERR'
错误：必须显式指定 DSH_VERSION。

    DSH_VERSION=0.2.0-rc.2 runtime-bundle/scripts/install_dsh.sh

版本号不要在这里猜。当前适配的版本见
governance/upstreams.json 的 transitive_upstreams[id=deepseek-harness].tracked_version，
或用 tools/build-dsh-layer.sh 直接构建该层（它自己会读这个字段）。

（本脚本原先默认 0.1.0-rc.6，与实际适配的 0.2.0-rc.2 不符，故取消默认值。）
ERR
    exit 2
fi
DSH_NPM_REGISTRY="${DSH_NPM_REGISTRY:-https://registry.npmjs.org}"
PNPM_DIR="${PNPM_DIR:-/opt/dshapp/pnpm-cache}"

echo "Installing DeepSeek Harness ${DSH_VERSION} (pin exact version; do not use latest tag)"
npm install --prefix /opt/dshapp/runtime "npm:@deepseek-ai/dsh@${DSH_VERSION}" --registry "$DSH_NPM_REGISTRY"
npm install --prefix /opt/dshapp/runtime "pnpm@latest" --registry "$DSH_NPM_REGISTRY" || true

# Android 硬链接兼容：就地改写 DSH 的 JS 文件不可行（补丁锚点会随上游漂移）。
# 1.3.1 起改为**运行期垫片**（app 启动 DSH 时以 --import 预加载 link-shim.mjs），
# 因此这里不再改动 DSH 源码，安装出的层保持上游原样。

echo "Verifying install:"
ls -la /opt/dshapp/runtime/node_modules/@deepseek-ai/dsh || true
echo "Run with: /opt/dshapp/runtime/node_modules/.bin/dsh web --host 127.0.0.1 --port 3080"
