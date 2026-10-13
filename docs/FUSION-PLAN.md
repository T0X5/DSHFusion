# 融合方案与实施优先级

前提结论（见 [AUDIT.md](AUDIT.md)）：**DSHBox 为骨干，DSHA 为特性来源。**
本文件定义阶段划分、每阶段的验收判据，以及必须先解决的冲突。

**核心纪律：先建立可运行的最小版本，再逐步融合。不一开始就大规模重写。**

---

## 阶段总览

| 阶段 | 目标 | 上游动作 | 判据 |
|---|---|---|---|
| **0** | 骨架：规范、双上游清单、CI | 无代码合并 | 契约检查通过 |
| **1** | 骨干落地：可构建、可装、可跑的最小版本 | 导入 DSHBox | **真机跑通** |
| **2** | 数据安全：备份恢复 + 受管更新 | 移植 DSHA | 真机恢复验证 |
| **3** | 设备能力：设备桥 / LAN / ADB 无线 | 移植 DSHA | 真机能力验证 |
| **4** | 深度优化：proroot、完整包名重构、高级功能 | 选择性 | 逐项验证 |

**每个阶段都必须以"真机跑通"结束，才允许进入下一阶段。**
没有实际执行的构建、测试和真机验证，不得标记为成功。

---

## 阶段 0 — 骨架（本次 PR）

**产出**：`AGENTS.md`、`governance/`、`docs/`、`tools/`（6 个工具）、`.github/workflows/`（2 个工作流）。

**验收**：
- `python3 tools/check-upstreams.py` 通过；
- GitHub Actions `contracts` 工作流在 PR 上通过。

**本阶段不做**：不导入任何上游代码。仓库此时**不是可构建的 Android 项目**。

---

## 阶段 1 — 骨干落地（下一个 PR）

**目标**：一个能装到手机上、能启动 DSH、能用终端和 WebView 的 DSHFusion。

### 步骤

1. **导入 DSHBox 源码作为主干基线**（在锁定提交 `266290a6d1` 上）。
   保留完整 git 历史，采用 `git merge` 上游分支的方式（理由见
   [UPSTREAM-SYNC.md](UPSTREAM-SYNC.md) §二）。
   不导入 `website/`（偏离 `DIV-0005`）。

2. **施加身份偏离**（登记在 `divergences.json`）：

   | 偏离 | 内容 |
   |---|---|
   | `DIV-0001` | `applicationId` / `namespace` → `com.dshfusion.app`；独立 versionCode/versionName |
   | `DIV-0002` | `app_name` → DSHFusion；自绘图标替换 `brand_icon` |
   | `DIV-0003` | 运行环境资产路径改为可由 `DSHFUSION_RUNTIME_ASSETS` 覆盖 |
   | `DIV-0004` | `AppVersion.kt` 的 SITE_URL / REPO_URL / 更新 API_URL 指向自身或关闭 |
   | `DIV-0005` | 不导入 `website/` |

   **刻意不做**：全量 `com.dshbox.app` → `com.dshfusion.app` 包名重构。
   721 处引用会产生巨大 diff 并让每次同步都冲突。完整重构放到阶段 4。

3. **解决运行环境资产前置**：
   - 保留 DSHBox 的在线获取路径（Debian 官方源 + npm 镜像 + `Release.gpg` 验签）；
   - 保留离线导入整包的入口；
   - 让 `assets.srcDirs` 可配置，使 CI 与本地都能在资产缺失时**只跑单测与 Lint**。

4. **建立 CI**（在 DSHBox 的 `android.yml` 基础上扩展，见下）。

5. **真机验收**：装到目标手机上，逐项确认。

### 阶段 1 验收清单（必须逐项在真机上确认）

| # | 项目 | 判据 |
|---|---|---|
| 1 | 应用安装 | 与 `com.dshbox.app`、`com.dsh.client` **可并存** |
| 2 | 运行环境初始化 | 在线获取或离线导入成功，三层哨兵 `layer-*.sha256` 齐备 |
| 3 | DSH 启动 | WebView 打开 `127.0.0.1:3080`，launchToken 自动解析，会话可用 |
| 4 | 终端 | PTY 可用，`node -v` / `npm -v` 有输出 |
| 5 | WebView | 移动端适配生效，附件上传可用 |
| 6 | 数据保留 | `user-data/` 在应用重启、运行环境更新后**完好** |
| 7 | 升级恢复 | 覆盖安装新版本后对话与配置不丢 |
| 8 | 单元测试 | `./gradlew testDebugUnitTest` 全绿，**`@Test` 方法数 ≥ 1058** |
| 9 | Lint | `./gradlew lintDebug` 通过 |
| 10 | i18n / 隐私门禁 | `DSHBox:tools/i18n_check.sh`、`DSHBox:tools/privacy_audit.py` 通过 |

> 第 8 项用 **`@Test` 方法数**而不是测试文件数：文件数会被重构（拆分/合并测试类）
> 轻易改变，方法数才是稳定的护栏。基线 1058 来自对上游的实测统计。

### ⚠️ 阶段 1 必须一并修复的上游缺陷

导入 DSHBox 源码时，以下问题**不能继承**。它们已在
[AUDIT.md 附录 A.3](AUDIT.md#a3-必须纳入方案的风险新发现) 中记录：

| # | 缺陷 | 处理 | 严重度 |
|---|---|---|---|
| 1 | 层完整性哨兵缺失时**自愈写入声明哈希**，等于删掉哨兵即可绕过篡改检测 | 哨兵缺失必须判为**不完整**并触发重新获取 | 高 |
| 2 | `DebianReleaseVerifier.verify()` 的 `return signatures.size() == 0` 与自身 fail-closed 注释矛盾 | 改为 fail-closed，并补一个"可解析但空签名列表"的测试 | 高 |
| 3 | `ServiceLocator.kt` 注释声称 `.deb` "逐个 sha256 校验"，代码中不存在该检查 | 要么实现校验，要么删除误导性注释；并提交资产的校验和 | 中 |
| 4 | `terminal-emulator` / `terminal-view` 未随附 Apache-2.0 许可副本 | 补上 `LICENSE`（Apache-2.0 §4(a) 要求） | 中（合规） |
| 5 | `runtime-bundle/scripts/install_dsh.sh` 仍默认 `0.1.0-rc.6`，且无任何调用方 | 修正或移除；不要留与 README 矛盾的死代码 | 低 |

### ⚠️ 阶段 1 必须建立的验证纪律

上游的自动化验证**只到 JVM 层**：`src/androidTest` 在每个模块都是空的，
CI 从不构建 release、从不签名、从不接触设备，唯一的设备验收记录早于 v1.4.0。

因此**阶段 1 的真机验收不是形式主义 —— 它是这个架构第一次被真正验证**。
阶段 1 应同时建立：

1. **CI 分层**（本阶段即建立）：
   - **快车道**：契约检查 + `testDebugUnitTest` + `lintDebug` + i18n + 隐私门禁。
     **不需要运行环境资产**，每次 push/PR 都跑。
   - **慢车道**：`assembleDebug` / `assembleRelease` + 签名。需要资产与密钥，
     `workflow_dispatch` 手动触发。**签名密钥只走 secrets，绝不入库。**
2. **真机验收记录**：在 `docs/device-acceptance/` 下按版本留档<!-- future: docs/device-acceptance/ -->，包含实际包摘要、
   包名、版本码、证书指纹与非破坏性操作结果。**不得从旧记录移用。**
3. **`androidTest` 从零开始**：不追求覆盖率，先为"装了能启动、能连上 3080、
   终端能出 `node -v`"这三条建立 instrumented 冒烟测试。

---

## 阶段 2 — 数据安全（移植 DSHA 的备份与更新）

这是**收益最高也最难**的阶段，因为要移植的是一套完整体系，而两边的
运行时模型不同。

### 必须先解决的冲突

| 冲突 | DSHBox | DSHA | 解决方向 |
|---|---|---|---|
| 发行版 | Debian trixie | Ubuntu noble | 以 DSHBox 为准；备份逻辑不得假设发行版 |
| 运行时模型 | 三层（base/node/android-side）独立装配 | Ubuntu + dsh-runtime 两份分包 | **以 DSHBox 的分层为准**，把 DSHA 的"受管更新"改造成分层更新 |
| 回切机制 | `runtime-current/` ↔ `runtime-new/` 双目录 | `ManagedRuntimeTransaction` + `RuntimeTrial` 隔离试运行 | **取 DSHA 的试运行思路**，落在 DSHBox 的双目录上 |
| 数据契约 | `user-data/` 不被打扰 | 宿主归档 v5 + 范围/合并子目录 | 移植 v5 的**格式与预检**，宿主实现重写为 DSHBox 风格 |

**结论**：DSHA 的备份格式（v5）与"先预检、再试运行、后提交"的流程是可移植的；
它的**实现代码**深度绑定 Ubuntu 分包模型，不能直接搬。

### 移植顺序（每项一个 PR）

| 顺序 | 项目 | 来源 | 为什么这个顺序 |
|---|---|---|---|
| 2.1 | 备份归档 v5 格式与预检（不含自动计划） | DSHA `backup/` | 先有能读写的格式，才谈自动化 |
| 2.2 | 范围备份（全量 / 只对话 / 只插件 / 只设置） | DSHA `BackupScope` | 依赖 2.1；`BackupScope` 是纯逻辑，好测 |
| 2.3 | 凭据排除（桥 token 整文件、`.credentials.yaml` 字段级） | DSHA | 安全项，独立可测 |
| 2.4 | 覆盖更新的隔离试运行 + 失败回切 | DSHA `RuntimeTrial` 思路 | 落在 DSHBox 双目录上 |
| 2.5 | 自动备份计划（每天 / 1–168h / 停止后，保留 3 份） | DSHA `AutomaticBackups` | 最后做，依赖 2.1–2.3 |

### 阶段 2 验收

- 导出的备份能在**另一台设备**上恢复，且对话、附件、配置、用户插件齐全；
- 恢复前有预检，恢复后逐文件核验；
- 故意投喂损坏备份 → **明确报错且不破坏现有数据**；
- 覆盖更新失败 → 自动回切到前一版本，数据不丢；
- 备份中**不含** API key 与桥 token。

---

## 阶段 3 — 设备能力

| 顺序 | 项目 | 来源 | 冲突分析与方向 |
|---|---|---|---|
| 3.1 | 设备命令白名单 + 三通道（Root / Shizuku / ADB）选择 | DSHA `bridge/`、`DeviceShellPolicy` | DSHBox 已有信箱通道与 Shizuku 通路；**取 DSHA 的白名单策略与"结果未知不自动切换通道重放"规则**，落在 DSHBox 的执行通路上 |
| 3.2 | HTTP 设备桥 `127.0.0.1:3090`（token 门控 + `/app/help`） | DSHA `bridge/HttpShellService` | DSHBox 走信箱而非端口。**先评估是否需要引入第二个通道**；若引入，必须与信箱通道明确分工，避免两套能力表 |
| 3.3 | 凭据区路径护栏（`/app/export`、`/app/readfile` 拒绝凭据区） | DSHA | 纯逻辑，独立可测，可直接移植 |
| 3.4 | 局域网访问（LAN 代理 + token fail-closed + Cookie SameSite） | DSHA | DSHBox 无此能力，属净新增 |
| 3.5 | ADB 无线配对（TLS1.3-PSK + SPAKE2） | DSHA | 需 Android 11+；工作量较大，优先级最低 |

### ⚠️ 来源审计要求

移植 3.1 / 3.2 前**必须**审计 DSHA `ShellService` 相关文件的来源
（DSHA 自己标注为 unknown，见 AUDIT.md §6.4）。
审计结论不明确时**不要移植**，改用 DSHA 的公开协议接口（`IShellService`）
自行实现。

### 阶段 3 验收

- 每条设备能力在真机上逐项验证，**明确记录哪些通道在哪些机型可用**；
- 危险命令（格式化 / 分区 / SELinux / 系统属性）**被拒绝**，返回 `[POLICY_BLOCKED]`；
- 结果未知的命令**不被自动重放**；
- 凭据区读写被拒绝（含软链接绕过尝试）。

---

## 阶段 4 — 深度优化（按需，不急于做）

| 项目 | 说明 | 前置 |
|---|---|---|
| proroot 运行时 | 实测 +58%，但为**上游专有许可**二进制 | 必须先确认分发权利；私有自用可先本地评估 |
| 完整包名重构 | `com.dshbox.app` → `com.dshfusion.app` 全量 | 需要一次性的巨大 diff；建议等上游活跃度降低后再做，或永久不做 |
| 完整离线 Ubuntu | DSHA 的 273MB 内嵌方案 | 与 DSHBox 的分层在线模型冲突，**建议不做** |
| GeckoView 备用内核 | 兼容旧 Android 的 WebView | DSHBox minSdk 29，收益有限 |
| 虚拟屏 / PiP / 悬浮条 | DSHA 的增强交互 | 逐项评估 |
| 插件市场数据源自持 | 目前依赖上游的两个 catalog 地址 | 若要长期独立，需自建 catalog |

---

## 明确不做的事

1. **不把 DSHA 整体合并进主干。** 语言、模块结构、运行时模型全不同。
2. **不重写 DSHBox 已有的成熟实现。** 能直接保留就保留。
3. **不打 DSH 上游源码补丁。** 保持 DSHBox 的运行期垫片路线——这是
   漂移成本最低的做法，也是选它当骨干的主要理由之一。
4. **不修改两个上游仓库。** 永远不向它们推送。
5. **不为了通过构建而降低门禁。** 测试失败就保留稳定版本。
6. **不复用上游品牌资产**（DSHBox 名称/图标、DSHA 的发布证书）。
