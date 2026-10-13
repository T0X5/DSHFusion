# 只读审计报告：DSHA / DSHBox 及其上游

- **审计日期**：2026-10-13
- **审计方式**：本地克隆 + 只读检查；未修改、未推送任何上游仓库
- **审计对象**：

| 仓库 | 锁定提交 | 日期 | 语言 | 许可 | Star |
|---|---|---|---|---|---|
| `DSH-APP/DSHA` | `467c7eb09da871a64a1aaa44e82428c30455ecc3` | 2026-10-13 | Java | MIT | 1114 |
| `WSK-build/DSHBox` | `266290a6d16fa1ea08b3b734cbfe726437a75fb2` | 2026-10-11 | Kotlin | GPL-3.0 | 117 |

**两个仓库都不是 fork**（`fork: false`、无 parent/source），因此"原始上游"指它们的
**依赖上游**：`deepseek-ai/deepseek-harness`（DSH 本体，MIT）、
`WSK-build/interlock-relay`（Apache-2.0）、`termux/termux-app`（模块级 Apache-2.0 例外）、
`termux/proot`（GPL-2.0）、`coderredlab/proroot`（专有）。

---

## 0. 结论

**以 DSHBox 为第一阶段稳定骨干，DSHA 作为特性与优化的来源。**

决定性判据不是功能多少，而是三条可验证的工程事实：

1. **DSHBox 的主干是可验证的**（CI 全绿）；**DSHA 的主干当前是坏的**（CI 失败，且已复现）。
2. **DSHBox 的运行环境可以从公开来源重建**；**DSHA 需要一份不公开的私有资产**。
3. **DSHBox 不修改 DSH 源码**（运行期垫片 + 原生桥）；**DSHA 维护大量上游源码补丁**，
   每次 DSH 上游重构都要跟着修。

第 3 条对"长期吸收上游"这个目标尤其关键：漂移成本最低的项目才适合当骨干。

---

## 1. 实际构建状态

### 1.1 DSHBox：主干 CI 全绿，可从零复现

`DSHBox:.github/workflows/android.yml` 在每次 push / PR 上执行，实测**最近 8 次全部 success**：

```
Android CI   main   push          success   2026-10-11T08:23:55Z
Android CI   main   push          success   2026-10-11T08:20:34Z
Android CI   WSK-build-patch-2  pull_request  success  2026-10-11T08:14:51Z
Android CI   WSK-build-patch-1  pull_request  success  2026-10-11T08:13:36Z
```

CI 步骤：i18n 一致性 → 官网资源版本串校验 → 隐私审计（阻断本机路径/凭据入库）
→ `testDebugUnitTest` → `lintDebug` → `assembleDebug` → 上传 debug APK。

工具链：JDK 21、AGP 8.9.2、Gradle 8.11.1（wrapper jar 已提交）、Kotlin 2.0.21、
compileSdk 36、minSdk 29、targetSdk 36、arm64-v8a。

**唯一的构建前置**：`DSHBox:app/build.gradle.kts` 声明
`assets.srcDirs("../../runtime/android-assets")`，即仓库外需要一个同级
`runtime/android-assets`（base / node / android-side 三层）。该资产**公开可得**，
两条途径：

- **在线获取**：应用内 `OnlineRuntimeImportManager` 从 Debian 官方归档与 npm 镜像
  在线装配，并用 Bouncy Castle 验 `Release.gpg`（`sandbox-manager/.../online/DebianReleaseVerifier.kt`）；
- **离线导入**：公开 Release 资产 `dshapp-runtime-debian-arm64-0.1.0.zip`（192.7 MB）。

因此 DSHBox 的运行环境是**可复现的公开来源**，不依赖任何个人上传。

### 1.2 DSHA：主干 CI 失败，且需要私有资产

实测 `main` 最近两次 `Source checks` 均为 **failure**（2026-10-13T15:34、08:22）。
失败步骤是 `Verify checked-in contracts`。我在本地逐条复现了这一步的检查：

| 检查 | 本地结果 | 失败原因 |
|---|---|---|
| `prepare-runtime-tools.py --check` | ✅ | |
| `verify-runtime-descriptor-source.py` | ❌ | `DESCRIPTOR_SOURCE_BYTES: pc-migration/node_modules/yaml/bin.mjs` |
| `generate-third-party-notices.py --check` | ✅ | |
| `check-third-party-notices.py` | ❌ | `PORTABLE_YAML_LICENSE_CHANGED` |
| `generate-builtin-plugins.py --verify` | ✅ | |
| `generate-credential-paths.py --check` | ✅ | |
| `prepare-semver-reference.py` | ✅ | |
| `format-java-tree.py --check` | ❌ | Java 树格式检查不通过 |
| `run-host-tests.py --check-manifest` | ✅ | |
| `check-source-hygiene.py` | ❌ | `UNPINNED_ACTION: .github/workflows/star-history.yml` |
| `check-ui-i18n.py` | ✅ | |

用 `git worktree` 检出上一提交 `7bcae595d1`（"fix: integrate build171 source and
dynamic Web ports"）复核，**同样的 3 项契约失败已经存在**，而 HEAD `467c7eb`
只改了 `README.md`。结论：**回归由 `7bcae595d1` 引入，HEAD 未修复**。

其余构建前置：`offline-rootfs.bin` / `dsh-runtime.bin` / `ubuntu-tools.bin` /
`pnpm-runtime.bin` / `python-support.bin` / `glibc-python.tar.gz` / `adb-wheels.tar.gz`
等 **9 个大型生成物均不提交**（`.gitignore` 明确列出）。`ci-package.yml` 是
`workflow_dispatch`，需要人工提供 `asset_bundle_url` + `asset_bundle_sha256`——
即一份**外部不可变输入包**。仓库内没有该包的公开地址。

同时 `main` 上有 **20 个未合并的开放 PR**，近 8 周提交分布为
`[343, 143, 10, 4, 4, 21, 16, 18]`——前期高速迭代后进入低活跃 + 积压状态。

> 这不否定 DSHA 的工程质量：它的 `ci-fast` 设计得很聪明（不需要大资产就能跑
> 契约与单测）。问题在于**它的主干当前处于红灯**，而一个要当"长期稳定骨干"的
> 项目不能以红灯状态起步。

---

## 2. Android 应用架构与依赖

| | DSHBox | DSHA |
|---|---|---|
| 语言 | Kotlin 2.0.21（+ 少量 Java） | **纯 Java 17，无 Kotlin** |
| 模块 | **10 个**：`app`、`common`、`bridge`、`sandbox-manager`、`plugin-manager`、`terminal-emulator`、`terminal-view`、`terminal-session`、`pilot/interlock-relay-core`、`pilot/dshbox-adapter` | **单模块 `:app`** |
| 主源码规模 | 97,726 行（kt+java） | 94,639 行（509 个 Java 文件） |
| 测试文件 | **156** | 320 |
| 文档 | 3 篇（`docs/`） | **213 篇（`docs/`）** |
| UI | Jetpack Compose + Material3 | Material3 + View |
| compileSdk / targetSdk | 36 | **37**（SDK 包 `android-37.0`） |
| minSdk | 29 | 30（Standard）/ 23（Low） |
| AGP / Gradle | 8.9.2 / 8.11.1 | **9.1.1 / 9.3.1** |
| ABI | arm64-v8a | arm64-v8a |
| 产物 | 单 APK（Release 约 110.7 MB） | 双 APK：Standard 273.10 MiB / Low 345.27 MiB |

**架构判读**：DSHBox 的多模块划分对融合更友好——终端、插件、沙箱、手机助手
各自成模块，可以单独替换或保留。DSHA 的单模块意味着任何改动都落在同一个大
模块里，与 DSHBox 的模块边界无法对齐。

**工具链判读**：DSHA 要求 API 37 + AGP 9.1.1 + Gradle 9.3.1，且其 `build.sh`
需要为 aarch64 主机安装 `aidl` shim（因为 Google 的 Linux build-tools 只有
x86_64）——这些都是额外的构建脆弱点。DSHBox 的 API 36 + AGP 8.9.2 是当前
更容易满足的组合。

---

## 3. DSH 启动、Linux 运行时与环境管理

| | DSHBox | DSHA |
|---|---|---|
| 发行版 | Debian 13 (trixie) 精简 | Ubuntu 24.04 (noble) 完整 |
| 容器 | PRoot（用户态，免 root） | proot（默认）/ **proroot**（可选，LD_PRELOAD + 二进制补丁） |
| 分层 | **base / node / android-side 三层独立装配** | Ubuntu 与 dsh-runtime 分包（`split-runtime-v1`） |
| 资产获取 | **在线多镜像源检测** 或离线导入 | 随 APK 内嵌离线 rootfs（不提交，需私有包） |
| 数据/系统隔离 | 运行环境与 `user-data/` 互不写入 | 受管更新 + 隔离 `RuntimeTrial` 试运行后提交 |
| 更新模型 | `runtime-current/` 与 `runtime-new/` 双 Runtime，成功才切换 | `ManagedRuntimeTransaction` / `HostDataTransaction`，保留健康前代 |
| DSH 启动 | `127.0.0.1:3080`，自动解析 launchToken | 动态端口（`--port 0`）或手动 3080 |
| 端口 | 3080 固定 | **动态分配**并复用上次可用端口 |
| Node | 24.19.0（独立层） | Node 24（随 rootfs） |
| 性能 | 标准 proot | proroot 实测关键项合计 **+58%**（vivo V2352A / Android 14） |

**关键差异 —— 对上游 DSH 的处理方式**：

- **DSHBox：不改 DSH 源码一个字节。** 安卓应用数据分区（FBE/FUSE）拒绝 `link()`，
  而 DSH 用 `link()` 作为"不可覆盖地发布文件"的原语。DSHBox 的做法是启动时
  以 `node --import` 预加载垫片，在运行期替换 `node:fs/promises` 的 `link`，
  被平台拒绝时退化为语义等价的内容拷贝。README 明确写：
  "**DSH 源码一个字节都不改**，因此不受上游重构影响，也不存在补丁锚点漂移。"
- **DSHA：维护受管源码补丁。** 例如 `DSHA:tools/apply-mobile-client-patches.mjs` 管理
  `dsh-web-mobile` 的本地差异（固定到上游 main 的特定提交
  `9b16223e6c5ee8209c25034b24fb790990967b3f`），还有
  `client-combo-patch.json`、`deepseek-messages-compat-patch.json` 等。
  AGENTS.md 里大量条目是"不要恢复上游已撤回的改动""不要把 rc1 的补丁重新套回
  新版本"——**这正是补丁锚点漂移的日常成本**。

**这是本次审计里对"长期同步"目标权重最高的一条。**

---

## 4. 终端、WebView、插件与手机助手

| 能力 | DSHBox | DSHA |
|---|---|---|
| 终端 | `terminal-emulator` / `terminal-view` / `terminal-session` 三模块，源自 Termux v0.118.0 | Termux terminal-emulator JNI，PTY 出生身份握手 |
| 代码编辑器 | Sora Editor 0.23.5 + Markwon | — |
| WebView | 系统 WebView；跨内核版本兼容层（`dsh-resource://` scheme 探测） | Standard 用系统 WebView；Low 内置 **GeckoView 143** |
| 插件框架 | `plugin-manager` 模块 + **插件市场**（两个实时数据源） | 插件市场 + `dsha-plugin` CLI + 内置插件保护 |
| 插件安全 | 「绝对安全模式」（不加载第三方插件启动） | 安全模式 + 事务化安装 + 回滚 |
| 手机助手 | **`pilot/` 模块，33,481 行**：39 项操控能力、信箱通道（文件投递，无网络无共享内存）、三类执行通路（无障碍 / Shizuku 特权 / 平台直连）、两种执行模式（前台 / 后台虚拟屏）、审批闸门 | 无障碍 + ADB 无线直连（TLS1.3-PSK + SPAKE2）+ Shizuku/Root + 独立虚拟屏 |
| 助手内核许可 | `interlock-relay-core` **Apache-2.0**，可独立发布 | 自有实现（MIT） |
| 设备桥 | 信箱通道（沙箱 ↔ 应用层） | `127.0.0.1:3090` HTTP 桥（token 门控，`/app/help` 端点清单） |
| 备份 | — | **v5 备份体系**（范围备份、自动计划、换机恢复、凭据剔除） |
| 局域网 | — | LAN 代理 3081，token fail-closed |

**判读**：两者在这一层各有强项且**互补**。

- DSHBox 的手机助手是**独立的 Apache-2.0 内核 + 适配层**，结构上最适合被复用，
  且它走"信箱文件投递"而不是 HTTP 端口，与沙箱的边界更清晰。
- DSHA 的备份恢复体系（v5）、LAN 访问、ADB 无线直连是 DSHBox 没有的，
  是阶段 2/3 的主要移植对象。

---

## 5. 数据目录、备份恢复与更新机制

**DSHBox**：
- 运行环境与用户数据严格分离：`runtime/`（可替换）vs `user-data/`（永不被碰）。
  `runtime-bundle/README.md` 写明"**永不删除 `user-data/` 下的 Workspace**"。
- 双 Runtime 目录 `runtime-current/` / `runtime-new/`，更新成功才切换。
- 每个 bundle 必须有 SHA-256、版本、架构、构建日期、来源。
- 分层哨兵文件 `layer-*.sha256` 用于验证解压成功。
- 设备侧路径：`/data/data/<pkg>/files/runtime/runtime-current/`、`.../user-data/`。
- 备份/恢复：**较弱**，主要靠 `user-data/` 不被打扰 + 文件管理导出。

**DSHA**：
- 宿主归档 v5：`DSHA-data-v5-<UUID>.tar.gz`，无需密码的 DSHADATA 完整性容器。
- 自动备份计划（每天 / 1–168 小时间隔 / 停止后），保留最近 3 份。
- 分范围备份（全量 / 只对话 / 只插件 / 只设置），换机恢复。
- 凭据不进备份：桥 token 整文件排除，`.credentials.yaml` 字段级剔除本机密钥。
- 受管更新用隔离 `RuntimeTrial` 真实试运行后才提交，失败自动回切。
- 覆盖更新闲时清理可再生缓存，保留至少两份健康前代。

**判读**：DSHA 的备份/更新体系明显更成熟，是**最有价值的移植对象**。
但它深度绑定 DSHA 的受管运行时模型（Ubuntu 分包 + `RuntimeTrial`），
移植到 DSHBox 的分层 Debian 模型需要**先解决模型冲突**，不是搬文件。

---

## 6. 安全模型、许可证与品牌限制

### 6.1 许可证

| | DSHBox | DSHA |
|---|---|---|
| 自有代码 | **GPL-3.0** | **MIT**（Copyright (c) 2026 qiannianhuanxiang） |
| 商标 | `TRADEMARK.md`：名称与品牌图形**全部权利保留**，依 GPL-3.0 §7(e) 附加条款 | 无商标声明 |
| 内置 proot | GPL-2.0 | GPL-2.0 |
| 内置 proroot | 无 | **上游专有许可** |
| 终端模块 | Termux 0.118.0，**Apache-2.0 例外** | 同 |

**融合的许可方向**：MIT 代码可以并入 GPL-3.0 作品，反之不行。
因此**若复用 DSHBox 的骨干代码，DSHFusion 公开分发时必须是 GPL-3.0**。
本项目当前为私有自用，不触发分发义务（详见 `LICENSE-PENDING.md`）。

### 6.2 品牌限制（必须遵守）

DSHBox 的 `TRADEMARK.md` §4 明确禁止：

- 复制、修改、再分发其自有品牌标识，或用于任何产品/服务/组织的标识；
- 制作**相同或近似**标识（含改变比例、旋转、局部元素增减替换、与他图形组合）；
- 将其**品牌标识的构造方法、生成方案**用于开发与本项目相同或近似的标识。

涉及文件：`app/src/main/res/mipmap-xxxhdpi/brand_icon.png`、
`app/src/main/res/drawable-*/ic_pilot_brand.png`（5 个密度）、
`app/src/main/res/drawable-*/ic_splash_mark.png`（2 个密度）。

→ **DSHFusion 必须自绘图标、自定名称。** 已登记为偏离 `DIV-0002`。

### 6.3 安全模型

**DSHBox**：
- `sandbox-manager` 模块负责沙箱生命周期与运行环境装配；
- 在线导入用 **Bouncy Castle 验 Debian `Release.gpg`**（预埋 Debian 官方归档钥）；
- 手机助手的每条链路受「禁止 / 询问审批 / 完全访问」约束；
- `network_security_config.xml` 存在；`allowBackup="false"`、`fullBackupContent="false"`；
- 声明权限克制：INTERNET、FOREGROUND_SERVICE(+SPECIAL_USE)、POST_NOTIFICATIONS、
  RECORD_AUDIO、CAMERA、MODIFY_AUDIO_SETTINGS（后三者按需请求，`uses-feature` 标为
  `required="false"`）。

**DSHA**：
- 设备命令白名单始终开启，Root / Shizuku / ADB 三通道同一策略；
- 桥凭据护栏：`/app/export`、`/app/readfile` 拒绝凭据区路径（规范化 + canonical path 双判定）；
- 内部破坏性命令 guard **明确声明不构成系统沙箱**；
- **诚实披露**：AGENTS.md 与 README 直接列出已知弱点
  （无内核级沙箱、历史明文备份、签名密钥待轮换等）。

### 6.4 ⚠️ DSHA 的来源风险（重要）

DSHA 自己的 `DSHA:docs/source-provenance.md` 记录了：

> Shizuku `ShellService` 的旧文件头标注 CFR 0.152；该标注本身不能证明外部反编译
> 对象、原作者身份或许可不兼容。……其更早外部来源没有本轮可核验材料，
> 不作法律定性。

即 **DSHA 仓库中存在来源不可核验的反编译代码**，DSHA 维护者自己也标注为 unknown。
→ 移植任何 DSHA 文件前必须做来源审计（已写入 `docs/UPSTREAM-SYNC.md` §三）。
这是把 DSHA 定位为"逐项移植来源"而非"整体合并"的另一个理由。

---

## 7. 哪个项目更适合作为第一阶段的稳定基础

| 判据 | 权重 | DSHBox | DSHA |
|---|---|---|---|
| 主干可构建、CI 绿 | 高 | ✅ 全绿 | ❌ **失败（已复现 4 项）** |
| 运行环境可从公开来源重建 | 高 | ✅ Debian 官方源 / 公开 Release | ❌ 私有资产包 |
| 上游漂移成本低 | **最高** | ✅ 不改 DSH 源码 | ❌ 维护大量源码补丁 |
| 模块化、可局部替换 | 中 | ✅ 10 模块 | ❌ 单模块 |
| 工具链易满足 | 中 | ✅ API 36 / AGP 8.9.2 | ⚠️ API 37 / AGP 9.1.1 + aidl shim |
| 来源可核验 | 中 | ✅ | ⚠️ 存在 unknown 来源 |
| 功能广度 | 中 | 较窄 | ✅ 更宽 |
| 运行时性能 | 低 | 标准 proot | ✅ proroot +58% |
| 备份/更新成熟度 | 中 | 较弱 | ✅ v5 体系完整 |
| 许可宽松度 | 低 | GPL-3.0 | ✅ MIT |

**DSHBox 在权重最高的三项上全部胜出**（可构建、资产可复现、漂移成本低），
而它在功能广度、性能、备份成熟度上的劣势**都可以通过逐项移植 DSHA 来补**。
反之，若以 DSHA 为骨干，则它在前三项上的劣势**无法通过移植 DSHBox 来补**——
你补不了"主干是红的"和"资产不公开"。

→ **决定：DSHBox 为第一阶段稳定骨干。**

---

## 8. 审计未覆盖的范围（诚实声明）

- **未执行任何 Android 构建**：本环境无 JDK、无 Android SDK、无 NDK。所有构建
  结论来自仓库内的构建配置、CI 定义与 CI 运行记录，**不是本地构建验证**。
- **未做真机验证**：没有在 Android 设备上安装或运行过任一 APK。
- **未验证两个 APK 的实际运行行为**：功能清单来自源码与 README，README 声称
  未逐条在源码中验证的项已在下文标注。
- **未做完整传递依赖许可审查**：仅核对了两个仓库的 `THIRD_PARTY_NOTICES.md`
  与 Gradle 直接依赖；完整传递依赖树需实际解析。
- **DSHA 的 213 篇文档只抽读**：`AGENTS.md`、`README.md`、`BUILD.md`、
  `接手指南.md`、`source-provenance.md` 与 `DSHA:docs/releases/` 目录已读；
  其余未逐篇阅读。
- **DSHBox 的 `DSHBox:docs/L4-tools.md` 与 `TERMINAL_CLI_DETECTION_RECORD.md`
  未细读**（`BUILD_RUNBOOK.md` 已读）。
- **未验证 DSHA 的 `ci-assets.py` 输入包是否在别处公开**：仓库内没有地址。
- **性能数字（proroot +58%）来自 DSHA 自述**，未独立复测。

---

## 9. 审计产物

| 文件 | 内容 |
|---|---|
| `governance/upstreams.json` | 双上游锁定清单（提交、许可、工具链、模块、同步策略） |
| `governance/ATTRIBUTION.md` | 署名与许可义务清单 |
| `governance/upstream-licenses/` | 上游许可原文（按原样保存，含 SHA-256 记录） |
| `docs/FUSION-PLAN.md` | 融合方案与实施优先级 |
| `docs/UPSTREAM-SYNC.md` | 双上游长期同步流程 |

---

## 附录 A：DSHBox 深度审计补充（子代理独立复核）

以下由一次独立的、只读的 DSHBox 深度审计补充，用于交叉验证本报告的结论。
**两处修正了本报告正文的数字，两处新增了必须纳入方案的风险。**

### A.1 对正文的修正

| 项 | 正文 | 修正 |
|---|---|---|
| 测试规模 | "156 个测试文件" | 文件数按目录计为 158（含 `src/test` 全部）；**`@Test` 方法 1058 个**。README 自述"v1.4.0 共 1203 例"，与实测差 145 例（疑似 `terminal-emulator` 的 JUnit-3 用例，未确认）。 |
| 主源码规模 | 97,726 行 | 按 `git ls-files` 统计为 **397 `.kt` + 41 `.java` ≈ 103,726 行**（正文数字只覆盖了部分目录）。 |

→ 阶段 1 验收清单第 8 项的基线应记为 **`@Test` ≥ 1058**，而不是"文件数 ≥ 156"。
   文件数容易被重构（拆分/合并测试类）改变，`@Test` 计数才是稳定的护栏。

### A.2 新增的关键事实

**1. 模块许可边界是清晰的 —— 这直接改变融合策略**

| 模块 | 许可 | 可否放进非 GPL 产品 |
|---|---|---|
| `:pilot:interlock-relay-core` | **Apache-2.0** | ✅ 可以 |
| `:terminal-emulator` / `:terminal-view` | **Apache-2.0**（Termux 例外） | ✅ 可以 |
| `:app`、`:sandbox-manager`、`:plugin-manager`、`:terminal-session`、`:pilot:dshbox-adapter` | **GPL-3.0** | ❌ 不可以 |

`interlock-relay-core` 确实是**真正独立**的：自带 `settings.gradle.kts`、`gradlew`、
`sample/`、`LICENSE`/`NOTICE`；所有部署相关细节通过 **7 个 SPI 接口**注入
（`spi/RelayText`、`RelaySurfaces`、`RelayCapabilities`、`RelayPathPolicy`、
`RelayExecutor`、`RelayPrefs`、`RelayRedactor`）；**模块内没有任何
`com.dshbox.app` 引用**；Shizuku authority 已是 `${applicationId}.shizuku`（改包名安全）。

**2. `:bridge` 是空壳**

`ServiceLocator.kt` 接线的是 `BridgeRouter(delegate = noopBridge, expectedDshToken = "")`，
no-op 返回 `"bridge not implemented"`；`OriginVerifier.isTrustedDshWebUi()` 自我描述为
"a placeholder for the complete policy"。WebView 刻意**没有 `addJavascriptInterface`**。
→ 融合时**不要指望 DSHBox 的 bridge**；设备桥能力要从 DSHA 移植（阶段 3），
   或基于 `interlock-relay-core` 的信箱通道扩展。

**3. 构建确实不自包含（已确认）**

`DSHBox:app/build.gradle.kts:78` 的 `assets.srcDirs("../../runtime/android-assets")` 指向的目录
**在检出中不存在**。`app/src/main/assets/` 只有 `runtime-online/` 与 `terminal-packages/`，
**没有 `assets/dsh/`、没有 `assets/runtime/`**。
→ 全新克隆产出的 APK **必须由用户先在线获取 base + node 层与 DSH 层**才能启动 DSH。
   PRoot 本身没问题（`libproot.so` 等已在 `jniLibs`，走 `nativeLibraryDir`）。

**4. 运行环境的在线获取是有真实信任链的（优于正文估计）**

`OnlineRuntimeImportManager.kt`（1042 行）从 Debian 镜像取 `Release` + `Release.gpg`，
用**内嵌在 APK 里的 Debian 归档公钥**验 OpenPGP 签名，再读签名内的 `Packages.gz`
SHA-256，然后下载 `.deb`、解包、写 dpkg DB。Node 层对 `SHASUMS256.txt` 校验。
离线导入路径有**逐层 SHA-256 与 `runtime-profile.json` 交叉核对** + Zip-Slip 防护。
→ 这条信任链是 DSHBox 作为骨干的重要加分项，正文的"公开可复现"判断成立。

### A.3 必须纳入方案的风险（新发现）

**风险 1：运行时完整性校验存在自愈式失效**

启动时的层完整性校验用哨兵 `runtime-current/<layer>/.dshbox/layer-<name>.sha256`
与 profile 比对；但**哨兵缺失时，代码会写入声明的哈希并把该层判为完好**
（"self-heal"）。→ 删掉哨兵即可绕过篡改检测。

**风险 2：Debian 签名校验存在疑似 fail-open**

`DebianReleaseVerifier.verify()` 的返回语句是 `return signatures.size() == 0`——
**空的但可解析的签名列表会被判为"已验签"**，与其自身 KDoc 声称的
"一律拒绝（fail-closed）"矛盾。现有测试只覆盖了 `ByteArray(0)`。
→ 审计方**未能构造出触发输入**，因此这是**潜在缺陷而非已证漏洞**。
   但移植/复用时必须先修，不能继承。

**风险 3：`.deb` 的 sha256 校验是宣称而非实现**

`ServiceLocator.kt:73-74` 的注释声称 24 个 `.deb` "逐个 sha256 校验后随 APK 分发"，
但**代码中不存在该检查**；`TerminalOverlayInstaller.prepare()` 只复制资源并判断
`length() > 0`。同时这些 `.deb`（16 MB）与全部 `.so`、`.jar` **都没有提交校验和**。

**风险 4：`DSH_PERMISSION_MODE=danger-full-access`**

`runtime-bundle/scripts/start_dsh.sh:24-26` 的论证是：Android 内核无 Landlock，
bubblewrap 在应用沙箱内无法创建 user namespace，**PRoot 沙箱本身就是边界**。
加上插件市场**无签名/校验和/发布者验证**、**无逐插件能力门控**，
任何市场插件都以完整沙箱权限运行。

**风险 5：自动化验证仅到 JVM 层**

`src/androidTest` 在**每个模块都是空的**（尽管声明了 instrumentation runner 与
Espresso/Compose-UI-test 依赖）。CI **从不构建 release、从不签名、从不接触设备**。
唯一的设备验收记录（`DSHBox:docs/TERMINAL_CLI_DETECTION_RECORD.md`，2026-08-23）
**早于 v1.4.0**，且是在旧的 236 MB 单层 APK 上做的。
→ **没有任何针对 v1.4.0 分层/在线架构的已发布设备记录。**
   因此阶段 1 的真机验收清单**不是形式主义，它是这个项目第一次真正验证该架构**。

**风险 6：许可合规缺口**

`terminal-emulator/` 与 `terminal-view/` **没有随附 Apache-2.0 许可副本**
（全仓库唯一的 Apache-2.0 正文在 `pilot/interlock-relay-core/LICENSE`）。
Apache-2.0 §4(a) 要求向接收者提供许可副本。
→ 阶段 1 导入这两个模块时**必须补上许可副本**，这是我们要修的上游缺口。

### A.4 复用建议（来自独立审计，与本项目方案一致）

- **可以整体取用**：`interlock-relay-core`（Apache-2.0，SPI 干净）、
  `terminal-emulator` + `terminal-view`（Apache-2.0）、link-shim 对、
  `DSHBox:tools/privacy_audit.py`、`DSHBox:tools/i18n_check.sh`。
- **当作参考实现重写**：`sandbox-manager` 的 PRoot/分层机制 —— 要按我们自己的
  层 schema 重新推导，不要照搬。
- **只能在 GPL-3.0 产品中复用**：`:app`、`:plugin-manager`、`:terminal-session`、
  `:pilot:dshbox-adapter`。
- **`sora-editor`（LGPL-2.1-or-later，未修改 aar）是许可绊线**：
  它静态打包进 APK。上游的合规论证是"宿主整体 GPLv3"。本项目公开分发时同样成立，
  但**若改为宽松许可分发则违反 LGPL**，必须替换该库或提供可重链接机制。
