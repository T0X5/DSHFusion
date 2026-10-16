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
2. **真机验收记录**：在 `docs/device-acceptance/` 下按版本留档，包含实际包摘要、
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
| 回切机制 | `runtime-current/` ↔ `runtime-new/` 双目录，**但它换的是运行环境层，不是用户数据** | `ManagedRuntimeTransaction` + `RuntimeTrial` 隔离试运行 | **取 DSHA 的「先试运行、后提交」思路，但落点不是那对槽** —— 见下面的 2.4b 审计。`BundleManager.promoteNewSlotToCurrent()` 换的是 base/node/dsh 三层环境，而 `user-data/` 由本表下一行承诺「不被打扰」，两者不能混为一谈 |
| 数据契约 | `user-data/` 不被打扰 | 宿主归档 v5 + 范围/合并子目录 | 移植 v5 的**格式与预检**，宿主实现重写为 DSHBox 风格 |
| **工具链** | AGP **8.9.2**（单元测试按 `android.jar` 解析 `java.nio.file`） | AGP **9.1.1**（按 JDK 解析） | 以 DSHBox 为准。后果具体而隐蔽：DSHA 的 `Files.readString`/`writeString`（Java 11+）在本仓库**编不过**。3 份测试因此做机械适配并登记为 `derived`/`adapted`。见 [INCIDENTS.md](INCIDENTS.md) 第 20 条 |

**结论**：DSHA 的备份格式（v5）与"先预检、再试运行、后提交"的流程是可移植的；
它的**实现代码**深度绑定 Ubuntu 分包模型，不能直接搬。

### 移植顺序（每项一个 PR）

| 顺序 | 项目 | 来源 | 状态 | 为什么这个顺序 |
|---|---|---|---|---|
| 2.1 | 备份归档 v5 格式与预检（不含自动计划） | DSHA `backup/` | ✅ PR #4（CI 通过 run 38627379821） | 先有能读写的格式，才谈自动化 |
| 2.2 | 范围备份（全量 / 只对话 / 只插件 / 只设置） | DSHA `BackupScope`＋枚举/写入侧 | ✅ PR #6（CI 通过 run 38633884134） | 依赖 2.1；范围是纯逻辑，好测 |
| 2.3 | 凭据排除（机器本地记录 **投影**掉，而不是整份删除） | DSHA `CredentialProjection`＋`CredentialFileBackupSource` | ✅ **已通过真实 CI**（run [38643847540](https://github.com/T0X5/DSHFusion/actions/runs/38643847540)，commit `19e95bd`，**24/24 步全绿**；`:backup` **76 条测试**、产物 128,791,472 字节）。分支 `phase-2.3/credential-exclusion`，PR #8 | 安全项，独立可测 |
| 2.4a | **恢复核心**：预检 + 候选构建 + 校验（`NativeRestorePlan`/`BackupTree`） | DSHA `NativeRestorePlan`＋`BackupTree`＋`DataFormatEvidence`＋`RuntimeDescriptor` | ✅ **已通过真实 CI**（run [38647873314](https://github.com/T0X5/DSHFusion/actions/runs/38647873314)，commit `0629409`，**24/24 步全绿**；`:backup` **85 条测试 / 12 个类**、产物 128,789,848 字节）。分支 `phase-2.4/restore-core`，PR #10 | 会毁数据的那条路径，必须先能本机真跑 |
| 2.4b-① | **恢复的落点**：逻辑根 → 本机路径映射（**落在 `user-data/` 的成员上，不是 runtime 槽**） | DSHA `NativeRestoreTargets` **不导入**（见下），另写 `HostRestoreMapping.java` | ✅ **已实现**（2026-10-14，同一分支）。✅ **已通过真实 CI**（run [38651955250](https://github.com/T0X5/DSHFusion/actions/runs/38651955250)，`0aea1dd`，**24/24 步全绿**，`:backup` **96 条 / 13 个类**）。本机真跑 **53 条** Java 测试（42 → 53），含**端到端往返**与**改动注入**；成员表与 `DshBackupScope` 的一致性由 `check-backup-contract.py` 逐项核验并做过漂移注入 | **一个成员一个 group**：`read` 指向成员自己，于是候选树不把整个 `.dsh` 卷进来 |
| 2.4b-② | **提交候选树到活动目录 + 失败回切** | **上游有**：`HostDataTransaction` 逐字节导入 + 自写适配层 `HostRestoreTransaction.java`（第一版手写了 350 行，理由经实测是错的，已删除） | ✅ **已实现**（2026-10-14，同一分支）。**手写那版**的 CI run [38655637817](https://github.com/T0X5/DSHFusion/actions/runs/38655637817) / `9d02c9f`：24/24 全绿，`:backup` 105 条 / 14 个类，产物 128,789,786 字节。**换成移植版后**本机真跑 **65 条**（`−9` 删手写的 / `+5` 上游那份 / `+7` 我们那份），新计数 108，CI 待跑。⚠️ 真机行为未观察过 | 三条前提都被断言：`CROSS_DEVICE`（**上游没有**，补在适配层）、`TARGET_CHANGED` + `CANDIDATE_CHANGED`（上游）、`OVERLAPPING_TARGETS`（上游）。⚠️ 运行约束不变：恢复期间目标必须静止，只能由调用方保证 |
| 2.5 | 自动备份计划（每天 / 1–168h / 停止后，保留 3 份） | DSHA **两个纯逻辑类可逐字节复用**，编排层与 pruner 必须自写 | 🚧 **审计完成 + 核心已实现**（`AutomaticBackupPolicy`/`ScheduledBackupOwner` 逐字节导入；`DshAutomaticBackupRetention` 自写）；调度胶水未接 | 最后做，依赖 2.1–2.4 |

**2.2 落地时校正了本表的两处假设**（都是对照真实代码后改的，不是推测）：

1. 「`BackupScope` 是纯逻辑」——对，但它的 `PLUGINS` 范围列的是 **DSHA 自建插件管理
   的目录**（`plugin-src` / `plugin-sources.json` / `plugin-history` /
   `plugin-safe-mode.json`），这四个在 DSHFusion 里**根本不存在**。照搬会备份一个
   永远为空的目录。本仓库的插件实体与清单在 `.dsh/profiles/<profile>/`
   （见 `PluginPaths`）。所以 2.2 是**改写后移植**，不是原样复制。
2. DSHA 的 `dshPaths()` 本身描述的是 **DSH 自己的布局**（`.dsh/sessions`、
   `.dsh/storages`、`.dsh/settings.yaml`），那部分是可按原样对照使用的 ——
   已用本仓库的证据复核：`Constants.kt:148` 把 DSH 的数据列为
   `profiles / sessions / settings.yaml / storages / credentials`，
   `SandboxService.kt:557` 同样把 `profiles/sessions/storages` 称作「DSH 的数据」。

**另一处只在动手前才发现、而且会造出真机故障的**：排除清单的第一直觉是
「我方资产目录 `.dsh/dshbox` 整目录排掉」。**那是错的** —— 那目录里三份 overlay
承载用户状态，其中「哪些插件被停用」是**安全机制**（丢了会把此前被隔离的坏插件
重新加载，真机上出过「每次都起不来」的事故）。排除必须按子路径逐项。
完整理由见 [INCIDENTS.md](INCIDENTS.md) 第 19 条。

### 2.4 的只读审计结论与实施决定（2026-10-14，**已实施**）

**为什么先做这一步**：2.4 是阶段 2 里唯一**必然引入 Android 耦合**的一项，
而 2.1–2.3 的全部价值建立在「`:backup` 的 Java 侧能在本机真编译真运行」之上
（`tools/run-backup-jvm-tests.sh`）。照直觉把 6 个类一起搬进来，会让那个工具**失效** ——
而它失效的方式是「编译不过」，不是在评审里看得出来的设计问题。所以先量清楚。

> ⚠️ **这一节的第一版审计有三处被后续实测推翻，原文已保留在 git 历史里。**
> 三处错都是同一类：**从「导入清单」和「行数」推断可达性，而没有去读那几处引用本身。**
> 记在这里是因为它们各自都会让一个看起来合理的方案落地成错的方案。

**实测的类、真实闭包与真正卡住的地方**：

| 类 | 行数 | 真正的阻碍（不是「有没有 android 导入」） |
|---|---|---|
| `NativeRestorePlan` | 402 | **零** —— 只依赖同包的 `BackupArchive`/`BackupControl`/`BackupFileSystem`/`BackupJson`/`BackupLimits` 与 `BackupTree` |
| `BackupTree` | 304 | 引用了 `AndroidTreeFileSystem`/`AndroidBackupFileSystem` —— 但见下，**那只是快路径** |
| `DataFormatEvidence` | 55 | 依赖 `RuntimeDescriptor`（审计第一版**完全没发现这两个类**，是编译报错逼出来的） |
| `RuntimeDescriptor` | 154 | **零**（只 `java.io`/`java.util`） |
| `AndroidBackupFileSystem` | 377 | `ParcelFileDescriptor`/`Environment` |
| `AndroidTreeFileSystem` | 600 | `Os`/`OsConstants`/`StructStat` + **`NativeStorage`(441)** |
| `NativeStorage` | 441 | 🔴 **`System.loadLibrary("termux")` + 7 个 native 方法**，符号在 DSHA 的 `libtermux.so` 里 |
| `NativeRestoreTargets` | 301 | 🔴 **`ConfigStore`(817) → `KeyVault`(145) → `CredentialRead`(73)** —— DSHA 自己的设置与凭据子系统 |
| `DataRootPolicy` | 42 | 依赖 `ManagedRuntimeLayout`(87) —— 审计第一版把它当成叶子，**错了** |

**校正一：「零 `android.*` 导入」不等于「不依赖 Android」，判据必须是可达性。**
这一条第一版就说对了，而且被证实：`BackupTree` 零 `android.*` 导入，却有 9 处引用
`AndroidTreeFileSystem`。

**校正二（推翻第一版的结论）：真正决定方案的不是 `android.*` 调用，而是两处
「应用内部子系统」耦合。**
第一版说「Android 特异性只集中在 2 个文件，其余 4 个只是类型上引用它们」——
**这个判断是错的**，因为它只看 `android.*`。按可达性追下去，两处才是决定性的：

- `AndroidTreeFileSystem` → `NativeStorage` → **`libtermux.so`**。DSHFusion 的骨干
  （DSHBox）只有 `libproot`/`libproot-loader`/`libtalloc`/`libandroid-shmem`/`libzstd-jni`，
  **没有 `libtermux.so`**。搬进来会得到「编译通过、运行必 `UnsatisfiedLinkError`」的死代码。
- `NativeRestoreTargets` → `ConfigStore` → `KeyVault` → `CredentialRead`。
  这是 **DSHA 自己的设置与凭据模型**；DSHFusion 的骨干（Kotlin + DataStore）
  有它自己的一套。搬进来等于在骨干旁边长期并行第二套设置模型，每次双上游同步都会冲突。

> **一个 `android.os` 依赖，比一个指向上游自己应用内部子系统的依赖，更容易处理。**
> 前者是平台 API（`com.android.library` 本来就有），后者是**架构承诺**。

**校正三（这是让 2.4 有解的那处）：`BackupTree` 的 Android 耦合是快路径优化，不是语义。**
它的 `digest()` 是：

```java
if (fs.getClass() == AndroidBackupFileSystem.class && fs.stat(root).type.equals("DIRECTORY")) {
  try (AndroidTreeFileSystem tree = new AndroidTreeFileSystem(root)) {
    return digestTree(tree, root, control);
  }
}
return digestTree(fs, root, control);          // ← 通用回退，功能完整
```

两条路径**共用同一个 `TreeHash`/`visit`**，分支条件只取决于 `fs` 的类型 ——
所以任何一次调用里两侧必然走同一分支，**摘掉它不会造成「一侧快、一侧通用」的不一致**。
而它要拖进来的是 1418 行（377 + 600 + 441），其中 441 行需要那个不存在的原生库。

**⇒ 实施决定：搬纯 JVM 的恢复核心，摘掉这条用不上的快路径。**

- **逐字节导入**：`NativeRestorePlan`(17,107) + `DataFormatEvidence`(2,472) + `RuntimeDescriptor`(6,258)。
- **适配导入**：`BackupTree` —— 上游 11,171 字节 → 7,330 字节，删掉的**全部是 Android 专有分支**
  （`digest` 的快路径分支、`visitChildren` 整个方法、`ReadScratch`、`visit` 的 `parent` 参数），
  **通用遍历的每一行逐字保留**。登记在 `dsha-imports.json` 的 `derived` / `kind: adapted`。
- **明确不导入**：`NativeRestoreTargets`（依赖 DSHA 自己的设置子系统，DSHFusion 侧按骨干的
  设置模型另写，阶段 2.4b）、`AndroidBackupFileSystem`+`AndroidTreeFileSystem`+`NativeStorage`
  （需要骨干没有的 `libtermux.so`）。两者都写进 `not_imported` 并点名真正的原因。
- **`DataRootPolicy` 暂不导入** —— 它只被 `NativeRestoreTargets` 使用，跟着 2.4b 一起做。

> **残留风险，如实记在 `derived` 条目里**：两条路径的目录枚举顺序是否完全一致
> **我们没有验证**（验证它需要那个原生库）。若将来有地方按摘要值**跨实现**比较，
> 结论可能不同。这不影响当前用法（每次比较两侧都走同一实现），但它是这条适配的已知缺口。

**2.3 那条被删测试的开放问题，已由真实执行回答（不是推理）：**

`oldV5ArchiveMachineRecordIsProjectedInCandidateBeforeItCanBecomeActive` 依赖
`NativeRestorePlan`，2.3 因此把它删了并如实登记为覆盖损失。摘掉快路径之后这条链变成纯 JVM，
于是**恢复回去、在本机真跑 —— 通过了**。

⇒ **那条覆盖回来了。** 本机 Java 测试从 33 条变成 **42 条**
（+`NativeRestorePlanTest` 的 8 条 + 恢复的 1 条），`NativeRestorePlanTest` 的 8 条
证明的正是 2.4 最要紧的性质：**校验失败时一个字节都没写进活动目录**。

> 这一条本来写的是「**读源码判断不了** JVM 会不会在类加载阶段就解析 `AndroidTreeFileSystem`，
> 必须真跑」。**方法是对的** —— 而答案之所以是「能跑」，不是因为 JVM 的惰性解析，
> 而是因为**我们先把那条引用删掉了**。两者要分清：前者是运气，后者是可复现的决定。

**DSHA 的 `RuntimeTrial`（739 行、1 处 `Context`）与 DSHBox 的双目录
不是同一个模型，不能照搬。** 它是 2.4 里唯一「思路可借、代码要重写」的部分 ——
DSHBox 的 `runtime-current/` ↔ `runtime-new/` 切换是它自己的机制。
具体怎么落，等它自己的审计做完再定；**不要因为 `RuntimeTrial` 长得像就搬。**

### 2.4b 的只读审计结论（2026-10-14）—— **审计已实施，落点映射已实现；提交与回切未做**

2.4b 要做的是「把候选树提交到本机真实位置，失败能回切」。审计推翻了本文件自己
先前的两处写法，也推翻了「复用 DSHA 的落点实现」这个默认假设。

#### 一、回切的落点不是 runtime 槽 —— 我先前写的方案自相矛盾

本文件的「必须先解决的冲突」表里，**同一个表的两行互相打架**：

- 「回切机制」那行原本写：落在 DSHBox 的 `runtime-current/` ↔ `runtime-new/` 上；
- 「数据契约」那行写：`user-data/` **不被打扰**。

读代码后事实是后者对、前者错：

| 证据 | 位置 | 说明 |
|---|---|---|
| 四个槽的定义 | `sandbox-manager/src/main/java/com/dshbox/app/sandbox/BundleManager.kt:26-30` | `currentSlotDir()`/`newSlotDir()`/`backupSlotDir()`/`failedSlotDir()` —— 四个**运行环境**槽 |
| 槽的切换与回滚 | `sandbox-manager/src/main/java/com/dshbox/app/sandbox/BundleManager.kt:240-284` | `promoteNewSlotToCurrent()` 换的是 base/node/dsh 三层环境与 `runtime-previous`；`rollback()` 换回来 |
| 用户数据目录 | `sandbox-manager/src/main/java/com/dshbox/app/sandbox/SandboxConfig.kt:41-46` | `userDataDir = File(appFilesDir, "user-data")`，**与 runtime 无关** |
| DSH 的 HOME | `app/src/main/java/com/dshbox/app/service/SandboxService.kt:1017` | `DSH_HOME=/root/projects/.dsh` |
| 宿主与 guest 的 bind | `app/src/main/java/com/dshbox/app/ui/files/FilesScreen.kt:170`、`app/src/main/java/com/dshbox/app/util/SandboxFiles.kt:16-21` | 宿主 `user-data` ↔ guest `/root/projects` 是 bind 关系，**同一份数据** |

**结论：恢复写的是用户数据（`user-data/.dsh`），而 `BundleManager` 那对槽换的是运行环境。
把用户的恢复提交挂在环境槽上，等于用「换整个系统」去实现「换一个目录」。**
`runtime-current` ↔ `runtime-new` 与 2.4b **不是同一个问题**；环境更新是阶段 3 的事。

#### 二、DSHA 的 `NativeRestoreTargets` 不可复用 —— 三条独立理由

第一版审计只写了「它依赖 `ConfigStore`/`KeyVault`」。读完代码后，那甚至不是最强的理由：

1. **逻辑根词汇根本不同。** 归档里的根名 = `BackupSource.id()`
   （`HostSnapshot.java:43` 与 `:220` 都写 `source.id()`），而 `BackupSource.description()`
   只给三个键：`id` / `scope` / `external`（`BackupSource.java:62-68`）。
   DSHA 的 `target()` 第一句就是 `BackupJson.string(root, "logicalKind")` ——
   **我们的根没有这个键，它会直接抛**。DSHA 的根名是 `dsh-home`/`dsh-child`/
   `native-settings`/`plugin-store`/`plugin-package`/`project`，其中 `dsh-child` 是
   「`.dsh` 的**子条目**逐个成根」；我们的方案是「一个成员一个根」
   （`DshBackupMember.id`）。**这是两套不同的归档词汇，不是同一套的两个实现。**
2. **目的地路径不同。** 它写 `runtime-current/base`、`files/user-data-v5/dsh`、
   `files/plugin-imports/<task>`；我们的是 `user-data/.dsh`。
3. **设置钩子不同。** 它的 `current()`/`apply()` 走 `ConfigStore`（SharedPreferences +
   Keystore），我们的是 DataStore。这条仍然成立，但**即使它不成立，前两条也已经够了**。

⇒ 登记为 `not_imported`，并且在 2.4b 里**另写**一个 `Mapping`。这正是硬约束 2 说的
「重复模块必须选一个权威实现」——权威实现是**我们自己的归档词汇**。

#### 三、落点有单一事实来源，而且 2.2 当时就是对的

`DshBackupScope` 的类型说明写着「`path` 相对 `user-data`」。这句话曾被我认为需要复核
（因为 DSH 的 HOME 看起来该是 `/root/.dsh`）。实测**它是对的**：

```
guest /root/projects  ←bind→  宿主 filesDir/user-data
DSH_HOME = /root/projects/.dsh  →  宿主 filesDir/user-data/.dsh
```

所以 `DshBackupMember.path`（`.dsh`、`.dsh/sessions`、…）可以直接当**宿主相对路径**用，
不需要第二套映射表。2.4b 的 `Mapping` 因此很薄。

#### 四、`Mapping` 的形状已经被 2.2 决定

按 `NativeRestorePlan.Target` 的语义（`write`=活动目标、`read`=合并来源、
`merge`=先拷后写、`group`=共享同一活动目标的组、`prefix`=组内子路径、
`memberRead`=成员写前状态）与 `DshBackupMember` 逐项对上：

| `DshBackupMember` | `Target` |
|---|---|
| 所有成员 | `group = "dsh"`（同一个活动根 `.dsh`） |
| `mergeSubdir` | `prefix`（FULL 为空串） |
| `path` 的宿主根 | `write = read = user-data/.dsh` |
| —— | `merge = true`（未恢复的文件必须活着） |
| `path` | `memberRead`（成员级写前摘要，用于 `TARGET_CHANGED` 与冲突预览） |

#### 五、提交这一步确实还不存在（这是 2.4b 的实质内容）

读完 `NativeRestorePlan` 全部 405 行后确认：`buildCandidates()` **只写
`task/candidate/<group>/…`，一次都不碰活动目录**（`:182-323`），返回「哪些组有内容」。
文件级校验（`:263-267` 重新读回落盘文件比 sha256）、链接环检测（`:290-300`）、
冲突计数（`:247-252`）都在候选阶段完成。**所以 2.4a 是安全的**，缺的正是：

1. 把 `candidate/<group>` 提交到 `write`；
2. 提交前的 `before`/`readBefore` 复核（`buildCandidates` 已经做了，`:188-192`）；
3. 失败时回切 —— 判据是 `plan.before` 里那些写前摘要。

#### 未验证（如实登记）

- **`buildCandidates` 的候选树与活动树之间的原子性没有验证过**：它是「先拷贝、
  再逐文件覆盖」，提交时若进程被杀，活动树可能半新半旧。这不是 2.4a 的缺陷
  （它不写活动目录），但它是 2.4b **必须有**的东西 —— 而且**目前没有任何测试覆盖它**。
- 真机上「恢复后 DSH 能否读回会话」未验证（还没有界面入口）。

#### 审计之后实现了什么（2.4b-①）

`backup/src/main/java/com/dshbox/app/backup/HostRestoreMapping.java`，**自写**（登记在
`governance/dsha-imports.json` 的 `original` 段）。

| 决定 | 内容 |
|---|---|
| 语言 | **Java**（不是 Kotlin）。本机有 JDK 17、**没有 kotlinc**，而这是会毁数据的那条路径 —— 只让 CI 编译它太贵。代价是破了语言约定，因此显式登记 |
| group 粒度 | **一个成员一个 group**。DSHA 把 `.dsh` 的子条目并进一个 group，那要求 `read` 指向 group 根，于是**只恢复 sessions 也会把整个 `.dsh` 拷进候选**（含凭据、插件实体）。改成成员粒度后：`before`/`readBefore` 是成员级摘要（变更检测更准）、提交半径缩到一个成员、不再需要 `memberRead` |
| `mergeSubdir` | **由 `path` 推导**，不再是构造参数。少一个来源就少一类分叉（事故表第 17/20 条） |
| 认不出的根 | 跳过（`ROOT_NOT_IN_MEMBER_TABLE`），**不猜落点**；但认得出根、范围对不上就**报错**（`ROOT_SCOPE`） |
| 落点越界 | 构造时就炸（`TARGET_OUTSIDE_APP`）。成员表是编译期常量，但它是**数据** |

**验证方式（本机真跑，53 条）**：最重要的是两条，都不是字段断言 ——

1. **端到端往返**：用我们**自己的** `HostSnapshot` 打归档 → 用**我们自己的** mapping
   预检 → 构建候选 → **直接在磁盘上核对候选树的字节与位置**。
   这是 2.1 → 2.4a → 2.4b 第一次串起来跑。「两个模块各自的单测都过」证明不了它们能对接。
2. **注入**：预检之后改动活动目录，`buildCandidates` 必须报 `TARGET_CHANGED`
   **且不留下候选树**。

成员表在 Kotlin（权威）与 Java（本机镜像）各有一份，所以
`tools/check-backup-contract.py` 逐项核验 6 个成员，并且**做过漂移注入自证**。
为容纳自写文件而放宽 `check-dsha-imports.py` 的反向扫描时，同时加了一条注入证明
**放宽是有界的**（未登记的 `.java` 仍须变红）—— 见 `docs/INCIDENTS.md` 第 30 条。

#### 2.4b-② 的设计与实现（2026-10-14；**同日推倒重来一次**）

> 🔴 **这一节的实现换了。** 第一版是**我从零手写的 350 行** `HostRestoreCommit.java`，
> 理由写在类注释与 `dsha-imports.json` 里：「上游把它放在 `HostDataTransaction` 里，
> **那个类依赖它自己的 `ConfigStore`/`KeyVault`**，没搬。」
>
> **那个理由是错的 —— 我写下它的时候没有读那个文件。** 实测 `HostDataTransaction`
> 只有 `java.io`/`java.util` 两个 import，一次都没提到 `ConfigStore`/`KeyVault`/`CredentialRead`，
> 同包依赖的前五个我们都已经有了。**它就在可达范围内**，而且更严密。已删除手写的那份，
> 改为**逐字节导入 + 一层适配**。见 `docs/INCIDENTS.md` 第 33 条。

先更正本文件前面一处说法。我先前写「提交若被进程杀死，活动树会半新半旧」——
**读了 `BackupFileSystem` 之后，这句话对「基于 rename 的提交」是不准确的**：

```java
void move(File source, File target) throws IOException;   // 不覆盖
default void atomic(File directory, String name, byte[] bytes)   // 带崩溃恢复的持久化写
void syncDirectory(File directory) throws IOException;    // 持久化屏障
```

候选树是在**活动目录之外**完整建好、逐文件校验过 sha256 的（2.4a 已完成）。
把 `candidate/<成员>` 用 `move`（同文件系统内的 rename）换进活动位置，
**内容层面是原子的** —— 活动树在任何瞬间要么是旧的整棵、要么是新的整棵，
不存在半新半旧。真正要处理的是另外两件事：

| 真实的失败形态 | 处理 |
|---|---|
| **两次 rename 之间目标不存在**（`move` 不覆盖，所以必须先移走旧的） | 提交前写**相位标记**（`TransactionMarkers`，内容自描述）；恢复时按相位 + 摘要双判，把原件移回来 |
| `previous/` 残留（提交成功但没来得及删） | 标记走到 `finalized`；恢复时按摘要判定，再收尾 |

**恢复判定是「相位 + 摘要」双判，不是只看其中一个**（这是我手写那版缺的一半）：

| 相位标记 | `active` 摘要 | 判定 |
|---|---|---|
| 只有 `verified`（还没 `switching`） | 任意 | 一次都没动过 → 无事可做，直接返回 |
| `switching` 起 | 缺失 / == `before` | 死在两次 rename 之间 → 把原件移回来 |
| `switching` 起 | == `candidate` | 换进去了 → 收尾 |
| `switching` 起 | **都不是** | **拒绝并报错** —— 不猜，不覆盖，现场留着 |

**还有一条必须先断言的前提**：`task` 与 `user-data` 必须在**同一个文件系统**上
（`fs.stat(...).device` 相等），否则 `move` 会退化成跨设备拷贝，原子性假设当场失效。
这条检查要在提交前做，而不是靠「反正都在 `filesDir` 下」这种推断。

> ⚠️ **上游没有这条检查**，所以它补在**适配层**
> （[`HostRestoreTransaction.java`](../backup/src/main/java/com/dshfusion/app/backup/HostRestoreTransaction.java)），
> 上游的 `HostDataTransaction` 保持逐字节不动。

**实现**：上游 `HostDataTransaction`（315 行）+ `TransactionMarkers`（34 行）
+ `util/Ids`（13 行）**逐字节导入**；上游的 `HostDataTransactionTest`（202 行 / 5 条，
含一条**真杀子进程**的）**适配后导入**（11 处工具链替换）；
[`HostRestoreTransaction.java`](../backup/src/main/java/com/dshfusion/app/backup/HostRestoreTransaction.java)
是**本仓库自写**的适配层，只做词汇映射。

**上游比我手写的那份严密在哪（逐条）**：

| | 我手写的 | `HostDataTransaction` |
|---|---|---|
| 提交前重验 | 只重验 `before` | 重验 `before` **和** `after`（`CANDIDATE_CHANGED`） |
| 嵌套目标 | 没有 | `OVERLAPPING_TARGETS`（父目录与子目录互为目标，两次 move 会互相踩） |
| 持久标记 | 一个 `commit-journal.json` | 五相位标记（`verified`→`switching`→`files-committed`→`settings-committed`→`finalized`） |
| 恢复判定 | 只看摘要 | **相位 + 摘要**双判 |
| 崩溃测试 | 注入（而且第一版注错了，见第 31 条） | **真杀子进程**（`destroyForcibly`） |

**适配层只加了两样东西，都不是改写上游**：

1. **`CROSS_DEVICE`** —— 理由见上。
2. **设置轴故意做成「非空就抛 `SETTINGS_NOT_SUPPORTED`」。** 上游会在提交时一并切
   `settings.json` 并有三处断言；我们的恢复路径只写用户数据。若给它一个
   「恒空表、`apply` 什么都不做」的实现，那三处会**永远通过** ——
   那就是「没有输入空间的断言」（第 30 条）。所以反过来：空表才接受，非空直接红。

**测试分两份，分工不同**：上游那份测**事务本身**（用它自己手搭的 `sessions`/`settings` 两根）；
我们那份（`HostRestoreTransactionTest`，7 条）测**「接得上」** ——
① 我们的候选树路径与上游 `candidate(id)` 同构吗；② `plan.before` 能直接当 `expectedBefore` 吗；
③ 适配层自己加的 `CROSS_DEVICE` 真的会在动任何东西之前拦下吗（上游没有这条，**它那边永远测不到**）。

故障注入用上游的 `Fault.boundary(name)`：**边界名由被测代码自己给出**。
⚠️ 我手写那版数的是「第 N 次 `move`」，而 `fs.atomic()` 内部也在 `move` ——
**三个用例当时都是绿的，却一个都没测到它名字说的事**（`docs/INCIDENTS.md` 第 31 条）。

### 阶段 2 验收

- 导出的备份能在**另一台设备**上恢复，且对话、附件、配置、用户插件齐全；
- 恢复前有预检，恢复后逐文件核验；
- 故意投喂损坏备份 → **明确报错且不破坏现有数据**；
- 覆盖更新失败 → 自动回切到前一版本，数据不丢；
- 备份中**不含** API key 与桥 token。

> 上面每一条都要**在真机上**确认过才算数（硬约束 4）。到目前为止 2.1 与 2.2 通过的是
> **本机的脚本与字节层断言**加 **CI 的编译与单元测试**；「能装到手机上、能真正导出并
> 恢复一份备份」这一层**一项都还没做**，因为它要等 2.4 的恢复编排。
> 未运行的检查写「未运行」，不写「通过」。

---

### 2.5 的只读审计结论（2026-10-14）—— 审计已做，实现未开始

**这一节只有审计，一行实现代码都没写**，所以下面不声称任何一条成立。

#### 一、骨干里**什么都没有**

DSHBox 全库搜 `autobackup`/`schedul`/`Automatic` —— **零命中**；
版本目录 `libs.versions.toml` 里没有 `work`/`alarm`/`job` 任何依赖。
所以 2.5 对本项目是**全新功能**，不是「把骨干的能力接上」。

#### 二、DSHA 侧有一整套，规模与耦合如下

| 类 | 行数 | `android.*` 导入 | 判定 |
|---|---|---|---|
| `AutomaticBackupPolicy` | 30 | **0**（只有 `java.time`） | ✅ **可复用**，且有上游测试 |
| `AutomaticBackupPruner` | 35 | **0** | ⚠️ 需适配（见三） |
| `VerifiedBackupCopy` | 107 | **0** | ⚠️ 需适配（见三） |
| `AutomaticBackups` | 244 | 2 | ❌ 不可复用（见四） |
| `AutomaticBackupService` | 178 | 4 | ❌ 不可复用（Android Service） |
| `ScheduledBackupOwner` | 82 | **0** | ⚠️ 需看它引用了什么 |

`AutomaticBackupPolicy` 里就是计划本身：`Math.max(1, Math.min(168, intervalHours))` ——
**1–168 小时的钳位**，与阶段 2 的验收条目逐字对上。

#### 三、「零 android 导入」在这里**不代表可复用**（这一条与 2.4b 的教训同族）

`AutomaticBackupPruner` 看起来最干净（35 行、零 android），但它写着：

```java
Set<String> allowed = Set.of("portable.dshbak", "verified.json", "operation.json", "source-checks");
```

那是 **DSHA 自己的归档目录命名与文件清单**。我们的归档由 `HostSnapshot` 产出，
命名不同。直接搬过来会**永远匹配不上**，于是「清理」静默变成空操作 ——
**一个什么都不删的 pruner，和没有 pruner，在日志上长得一模一样。**

`VerifiedBackupCopy` 同理：它的 `metadata.get("automatic")`、`result(true)`、
`created` 都是 DSHA 备份记录的字段词汇。

⇒ **结论：这两个类要按我们的归档词汇重写，不能逐字节搬。** 这是 2.5 的主要工作量。

#### 四、编排层不可复用，理由是「指向上游自己的应用内部」

`AutomaticBackups` 的 18 个公开方法里，几乎每一个的第一个参数都是 `Context`，
它自己就是一个 **`SharedPreferences` 的设置门面**（`prefs`/`enabled`/`recordError`/
`suspendForFactoryReset`…），并且 `idle()`/`idleForOwner()` 直接调
`HarnessController.get(context)`。**我们的设置不在 `SharedPreferences` 里，
也没有 `HarnessController`。** 与 2.4b 拒绝 `NativeRestoreTargets` 的理由同类：
不是平台 API 的问题，是**架构承诺**的问题。

#### 五、调度机制：两个上游**独立地**选了同一个

| | 机制 | 依据 |
|---|---|---|
| DSHA | `JobScheduler` | `AutomaticBackups.java:50`、`AutomaticBackupService` 类注释「JobScheduler 持有任务寿命，不从后台强行启动前台服务或停止用户会话」 |
| DSHBox | `JobScheduler` | `RelayA11yWatchdog.kt:23` 注释：「用平台自带的 JobScheduler 而不是 WorkManager：本模块不引入新依赖」 |

⇒ **2.5 用平台 `JobScheduler`，不引入任何新依赖。** 两个上游各自独立地得出同一结论，
而骨干还有一条显式的不引依赖约定 —— 这条不需要再论证。

#### 六、当初没回答的三个问题，现在都有答案了（2026-10-15）

**① `ScheduledBackupOwner` 到底管什么。** 读了全文（82 行，只依赖 `java.lang`）：
它是**线程受限的单飞票据所有者**。事件线程（主线程）独占「这次的调度参数」和
「这次请求产生的那个 job id」，于是能回答一个别的写法答不对的问题：
*「现在拿到的这个 job id，是不是我这次提交的那个？」*
没有它就会出现「用户改了设置 → 旧请求晚到 → 把新设置重新提交成旧参数」。
`Ticket.parameters` 用的是**引用相等**（`active.parameters != parameters`），
不是 `equals` —— 调用方必须传同一个实例。**可逐字节复用，已导入。**

**② 「停止后备份」对应我们架构里的什么事件。** `due()` 的 `stop` 分支只要一个
`stopped` 时间戳（`stopped > since && now >= stopped`）。骨干侧对应
`SandboxService` 的停止路径；**这一条是胶水，还没写**，所以下面不声称它成立。

**③ 自动备份与「恢复期间目标必须静止」如何共存 —— 这是个伪冲突。**

我当初把它记成「真问题」，是因为把两条路径混在了一起：

| | 恢复（2.4b-②） | 备份（2.5） |
|---|---|---|
| 对活动目录做什么 | **写**（换进候选树） | **只读** |
| 为什么需要静止 | `buildCandidates` 把活动树合进候选，提交前重验 `before`；中途被改则 `TARGET_CHANGED` **拒绝提交** | 逐文件读 + 哈希 |
| 不静止的后果 | **拒绝**（安全的那一侧） | 可能拿到**跨文件的撕裂视图** |

所以约束是**恢复单方面的**，备份并不受它约束。而「撕裂视图」这件事上游早就
用容器里的 `integrity` 字段表达过了（`QUIESCENT`/`EXTERNAL_CHECKED`/`PARTIAL`/
`BEST_EFFORT`，见 `HostSnapshot.java:200-210`）—— 它描述的是**逐文件校验的完整性**，
**不是**跨文件一致性。`HostSnapshot` 从不声称跨文件一致。

⇒ **这正是上游有 `stop` 模式的原因**：它是那个「用户想要一个跨文件一致的时点」
的选项。所以三种模式不是三个并列的功能，而是**一致性强度递增**的三档：
`interval`/`daily`（随时可跑，逐文件完整）→ `stop`（DSH 停下之后，跨文件一致）。

⇒ 设计结论：自动备份**可以在 DSH 运行时跑**，但归档的 `integrity` 必须如实，
**不允许**把它标成跨文件一致。

#### 2.5 的设计与实现（2026-10-15）

| 部分 | 归属 | 说明 |
|---|---|---|
| 到期判定 `AutomaticBackupPolicy` | **逐字节导入**（30 行，纯 `java.time`） | 三种模式：`stop` / `interval`（1–168h 夹取）/ 本地日历时刻 |
| 单飞票据 `ScheduledBackupOwner` | **逐字节导入**（82 行，纯 `java.lang`） | 见上面 ① |
| 它们的测试 | **逐字节导入**（4 + 5 条） | 含 DST 与时钟回拨、跨线程拒绝 |
| 保留策略 `DshAutomaticBackupRetention` | **自写** | 见下 |
| 调度胶水（`JobService` + 设置界面） | **自写，未开始** | 用平台 `JobScheduler`，不引入新依赖 |

**保留策略为什么必须自写 —— 而且这条结论有可证伪的形式。**
审计原来只是**说**「直接搬会静默变成空操作」。现在
`DshAutomaticBackupRetentionTest.upstreamPrunerJudgementMatchesNothingOfOurs`
把上游那段判据**跑在我们的真实产物名上**，断言它一份都删不掉；
同时断言我们的策略**确实会删** —— 否则那条用例证明的只是「两边都不删」，
成了一个两边都绿、什么也没区分开的断言（事故表第 30 条的同族）。

**比上游严的三条**（每条都有注入证明它会红）：

1. 只碰 `operation = MAINTENANCE`。手动 `EXPORT` 与救援 `RESCUE` **永不入候选**。
   上游用 `metadata.automatic`，我们用容器本来就强制的 `operation` —— 少一个真相来源。
2. 证明不了完整的 `PARTIAL`/`BEST_EFFORT` **既不计数也不删，而且要报出来**
   （`Result.unverified` + `explain()`）。上游在这里是静默 `continue`。
3. `keep` 小于 1 时按 1 处理。**一个把用户全部备份删光的保留策略比没有保留策略更危险。**

**审计自身的更正。** 我在审计里写「命名不同」，实际差别比那更大：上游的自动备份是
**一个目录 + 四个具名成员**，我们的是**单文件** `DSHDATA` 容器。所以直接抄不是
「命名对不上」，是**连输入都没有**（`fs.list(file)` 对一个文件会抛）。

### 2.5 之后的整体盘点（2026-10-15）—— 一个从第一天起就在的缺口

**`:backup` 模块在 `app` 的依赖里根本不存在。**

实测：`app/build.gradle.kts` 的 `implementation(project(...))` 列表是
common / bridge / sandbox-manager / terminal-session / terminal-view /
plugin-manager / pilot:dshbox-adapter —— **没有 `:backup`**；
`settings.gradle.kts` 有 `include(":backup")`（所以 CI 能单独编译并跑它的测试），
但**没有任何模块依赖它，也就没有任何一行产品代码调用它**。

这不是这轮引入的疏忽 —— 从 2.1 建模块那天起就是这样。当时的理由是成立的：
2.1–2.4b-② 逐项都还是「格式 + 判定 + 纯逻辑」，在接上界面之前接进 app
只会制造一批「看起来有入口、实际什么都不做」的半成品。CI 的绿也始终是
`:backup` 自己的（Gradle 的 `testDebugUnitTest` 对所有模块逐个跑，不依赖有没有人依赖）。

**但它的后果现在必须如实说出来**：

1. **阶段 2 的全部成果，至今没有任何一行在真机上执行过** —— 不止是「没有界面入口」，
   是连依赖边都没接。APK 里没有这些类的字节码。
2. 「2.5 未做」的**调度胶水**（`JobService` + 设置界面）必须长在 `app` 里，
   而它一写就必然引入 `app → :backup` 依赖。所以依赖边和胶水是同一件事的两半。
3. 真机验证的路径因此是：**接依赖 → 导出/恢复的界面入口 → 自动备份的设置界面**，
   而不是先做自动备份。

⇒ **下一轮从「把 `:backup` 接进 `app` 并做出导出/恢复的最小界面」开始**，
那一项完成之前，阶段 2 的验收标准「restorable on another device」不满足，
也不声称满足。

### 2.6 的两段：接依赖（已完成）与界面（未做）（2026-10-15）

「`:backup` 在 `app` 的依赖里根本不存在」这个缺口，第一段已经补上：

| 段 | 内容 | 状态 |
|---|---|---|
| ① 生产侧文件系统 | 逐字节导入 `AndroidBackupFileSystem` + `OwnershipCheckFailure`（平台 API，非原生库）；本机编不了 → 把「排除」做成显式且与声明表对照的 | ✅ 已过 CI |
| ② 导出编排 | `DshBackupExporter`（Java，本机 14 条测试）：范围成员 → `BackupSource` → 上游 `HostSnapshot.create` → 移出任务目录 | ✅ 已过 CI |
| ③ 依赖边 | `app` → `:backup`（DIV-0021），阶段 2 的代码第一次会进 APK | ✅ 已过 CI（run 38685869212） |
| ④ 导出界面 | 选范围 → 导出到下载目录 → 显示名字/大小/摘要 | ⬜ 未做 |
| ⑤ 恢复界面 | 选归档 → 预检展示 → 确认 → 提交（含回切） | ⬜ 未做 |
| ⑥ 自动备份设置 | `JobService` + 设置界面（每天 / 1–168h / 停止后，保留 3） | ⬜ 未做 |

**② 里两处「少一步就静默出错」的地方，值得单独记：**

1. **排除谓词的坐标系差一个前缀。** `Policy.exclusion` 给的是「相对成员根」的
   （`credentials`），而 `DshBackupExclusions.isExcluded` 收的是「相对 user-data」的
   （`.dsh/credentials`）。少了拼接则**排除一条都不命中、且不报任何错** ——
   归档里安静地带上机器本地凭据，而所有计数与完整性判定照常通过。
   所以拼接放在本机可跑的 Java 层，并用「真实跑一条 `.dsh` 成员」的用例钉住。
2. **「路径出现在归档里」≠「内容进了归档」。** 被排除项**仍然会留下一条
   `kind=EXCLUDED` 的元数据记录**（没有载荷）—— 这是有意的：「这次备份漏了什么」
   必须能从归档里读出来。按「路径不许出现」写断言会把正确实现判失败。

**③ 已完成但 ④⑤⑥ 未做，所以阶段 2 的验收标准仍然不满足**：
「restorable on another device」需要界面才能走一遍，而真机验证是硬约束 4 的要求。
**在 ④ 完成并由用户在真机上走通之前，不声称阶段 2 完成。**

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
