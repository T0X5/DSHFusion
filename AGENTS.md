# AGENTS.md — DSHFusion 项目规范

> 本文件是 DSHFusion 的**唯一权威规范入口**。人和 AI 都先读这一份，不要先全库扫描。
> 面向的读者是「下一个接手这个仓库的人或 agent」。

---

## 一句话

DSHFusion 是**个人自用**的 Android 项目：在手机上运行 DeepSeek Harness（DSH），
**以 DSHBox 为第一阶段稳定骨干**，选择性吸收 DSHA 的成熟实现与运行时优化。

它不是 DSHBox 的 fork，也不是 DSHA 的 fork —— 它是**独立仓库**，通过显式的
双上游同步流程长期跟踪两者。见 [docs/UPSTREAM-SYNC.md](docs/UPSTREAM-SYNC.md)。

## 这个仓库当前处于什么阶段

**阶段 1：DSHBox 基线已导入分支 `phase-1/import-dshbox-base`（PR 待审，main 未动）。**

上游 790 个跟踪文件 → 排除 23 个（`website/`、`pages.yml`，DIV-0005）→ 归档 2 个
（`README.md`、`.gitignore`）→ **实际导入 765 个文件、25.4 MB**。
8 处偏离已重放，见 [governance/divergences.json](governance/divergences.json)。

**「原样导入」是可验证的，不是自我声称**：`tools/check-import-fidelity.py`
逐 blob 对照上游审计副本的 git 索引 —— 765 个文件与上游**逐字节（含模式）一致**，
17 处差异全部能对上已登记的偏离。

> ⚠️ **构建已真实验证，真机运行没有。**
>
> 2026-10-13，上游 `android.yml` 在 DSHFusion 下首次真实运行**通过**：
> 10 个模块的 `testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部成功，
> 产出 `dshapp-debug-apk`（39.0 MB 压缩 / 94.0 MB 解压，777 个条目）。
> 这一步不再是「凭配置推断」。
>
> **但装到手机上还没做过** —— 没有设备连接。更要紧的是：CI 产出的 APK
> **不含任何运行环境层**（无 `base/node/android-side/dsh` 的 `.tar.zst`），
> 因为上游不把 `runtime/` 提交进仓库。所以它**能安装，但起不了 DSH**，
> 除非先通过「运行环境在线获取」或离线整包导入补上。
> 不要把这个状态读成「装上就能用」。
> 见 [docs/PHASE0-VERIFICATION.md](docs/PHASE0-VERIFICATION.md) 的诚实性声明。

阶段 1 的未完成项（**不要误认为已完成**）：

| 项 | 状态 |
|---|---|
| 自有品牌图形 8 个 | ✅ 已自绘；`tools/make-brand-assets.py` 可复现，CI 逐字节核验 |
| 独立签名密钥库 | ❌ 未创建 |
| 运行环境层构建脚本 `tools/build-dsh-layer.sh`（补运行环境风险 R1） | ✅ 已编写并**真跑通**（538 包 / 118.6 MB 层 / 冒烟测试通过） |
| 上游 `android.yml` 在 DSHFusion 下的首次真实运行 | ✅ **通过**（run 38478523535，含单元测试 / lint / APK 组装） |
| APK **产物**身份核验（DIV-0008） | ✅ **已通过**（run 38483533327 第 15 步）—— 对 CI 真实产物解二进制清单核验 |
| 单元测试 / lint / `assembleDebug` | ✅ 通过（10 个模块全部成功，`terminal-view` 无测试源） |
| 生成的 APK 装到真机上验证 | ❌ **未做**（无设备连接、无 adb） |
| APK 内含运行环境层（能真正跑起 DSH） | ❌ 不含 —— 仓库未提交 `runtime/`，CI 只做编译 |
| `upstream-watch.yml` 真实运行 | ✅ **已跑通**（run 38483621472，手动触发）—— 检出 DSHA 有新提交并建了 Issue #2 |

### 首次真实 CI 运行（2026-10-13）暴露的问题

`phase-1/import-dshbox-base` 的第一次真实运行里，上游 `android.yml` 在
`compileDebugKotlin` 阶段失败，报 **36 个 `Unresolved reference 'R'`**。

原因是 DIV-0001 一开始把 `namespace` 也一起改了。这是错的：
`applicationId` 才决定安装身份，`namespace` 决定 R/BuildConfig 的包名与
manifest 相对类名的解析基准。改 namespace 等于启动被推迟到阶段 4 的全量重命名。
已回退，并补了 `tools/check-android-identity.py` 在本地拦住这一类错误 ——
它还能发现 CI 发现不了的那个后果：3 处 manifest 相对类名会在**运行时**崩。

> 这个失败是有价值的：它验证了「不要仅凭配置推断构建结果」这条规则。
> 在此之前，本仓库对「能不能编译」的判断全部来自静态阅读。

### 产物身份核验的首次真实运行（2026-10-13）

`android.yml` 里新增的 `Verify APK application identity` 步骤（DIV-0008）
在 run 38483533327 首次执行并**通过**。CI 日志原文：

```
applicationId  : com.dshfusion.app
versionCode    : 1
versionName    : 0.1.0
备注：不含任何运行环境层（无 .tar.zst / assets/dsh/）—— 这个 APK 能安装，但起不了 DSH
APK 身份核验通过 ✔
```

这一条比它看起来重要：它证明**版本修复真的进了产物**。
在此之前，「独立版本线」只存在于 `app/build.gradle.kts` 里 ——
而阶段 1 恰好吃过这个亏：配置里是 `8` / `1.4.0`，配置与产物一致，但两者都错。
现在由产物本身作证。

`versionCode` 从 `8` 改成 `1` 的那次修复，此前只验证过「源码改对了」；
现在是「打出来的 APK 里确实是 1」。

### 双上游监测的首次真实运行（2026-10-13）

`upstream-watch.yml` 此前从未运行过（只在每周一 03:00 定时触发）。
用手动 `workflow_dispatch` 触发了一次，**完整跑通**：

- 检出上游镜像、生成变更摘要、上传 artifact、建跟踪 Issue，全部成功。
- 检出 DSHA 有新提交：`467c7eb09da8` → `68e199ccd1`，1 个提交、3 个文件。
- **内容是无关紧要的**：`github-actions[bot]` 自动刷新的 star 曲线
  （`docs/star-history*.{json,svg}`）。没有触及任何已登记偏离的文件，
  摘要的「§3 冲突预警」为空。
- 因此**不推进锁定提交** —— 为一个自动生成的图表去动 `locked_commit` 没有收益，
  反而会让下次同步的基线变得不确定。

这份摘要同时验证了监测机制本身是有用的：它正确识别了「上游动了」，
也正确判断出「这次不用管」。见
[docs/UPSTREAM-SYNC.md](docs/UPSTREAM-SYNC.md)。

## 决策依据（不要重新推翻）

主干选择基于 2026-10-13 的只读审计，完整证据见 [docs/AUDIT.md](docs/AUDIT.md)。结论摘要：

| 判据 | DSHBox（选中为骨干） | DSHA（作为来源） |
|---|---|---|
| 主干 CI 状态 | 全绿 | **失败**（4 项契约检查） |
| 运行环境资产可获取性 | 公开可复现（Debian 官方源 + 公开 Release） | **9 个未提交的私有资产** |
| 上游漂移成本 | 低（不改 DSH 源码，运行期垫片） | 高（维护大量上游源码补丁） |
| 架构 | 多模块 Kotlin | 单模块 Java |
| 许可 | GPL-3.0 | MIT |
| 独有能力 | 手机助手内核、插件市场、分层运行环境 | proroot 性能、完整离线 Ubuntu、设备桥 |

**不要仅凭 Star 数（DSHA 1114 vs DSHBox 117）或功能列表翻盘。** DSHA 功能面更宽，
但它的主干当前不可验证、资产不可复现，作为长期主干的风险高于收益。

## 硬约束（改代码前必读）

1. **不修改两个上游仓库。** 永远不向 `DSH-APP/DSHA` 或 `WSK-build/DSHBox` 推送。
   所有改动都发生在 DSHFusion 内。
2. **不盲目覆盖。** 合并上游前必须先审计；能直接保留的成熟实现就保留，
   不要重写。重复模块必须选一个权威实现，另一边通过适配层兼容。
3. **每一处对上游代码的偏离都要登记**在
   [governance/divergences.json](governance/divergences.json) 里。
   未登记的偏离 = 下次同步时静默丢失。
4. **没有实际执行的构建/测试/真机验证，不得标记为成功。**
   未运行的检查写"未运行"，不写"通过"。
5. **保留上游版权与许可文件。** 见 [governance/ATTRIBUTION.md](governance/ATTRIBUTION.md)。
6. **不默认自动发布。** 低风险变更可自动开候选 PR，但发布必须人工决定。

## 许可状态

DSHFusion 当前**暂不设置许可证，私有自用**（见 [LICENSE-PENDING.md](LICENSE-PENDING.md)）。

这不是"随便用"的意思 —— 而是说：本仓库**没有对外分发**，因此 GPL-3.0 的分发义务
尚未触发；但**一旦决定公开或分发，必须重新评估**，因为骨干 DSHBox 是 GPL-3.0。
相关上游版权与许可原文已按原样保存在 [governance/upstream-licenses/](governance/upstream-licenses/)。

## 目录结构

```
dshfusion/
├── AGENTS.md                    ← 你在这里（唯一权威规范）
├── README.md                    对外入口
├── LICENSE-PENDING.md           许可待定说明
├── governance/
│   ├── upstreams.json           双上游清单（URL、锁定提交、许可、同步策略）
│   ├── divergences.json         对上游的每一处偏离登记
│   ├── import-exclusions.json   阶段 1 导入的取舍清单（排除/归档/需改写）
│   ├── upstream-license-hashes.json  受保护许可原件的 SHA-256（改动即 CI 失败）
│   ├── ATTRIBUTION.md           署名与许可义务
│   └── upstream-licenses/       上游许可原文（原样保留，勿改）
├── docs/
│   ├── AUDIT.md                 只读审计报告（决策证据）
│   ├── FUSION-PLAN.md           融合方案与实施优先级
│   ├── UPSTREAM-SYNC.md         双上游长期同步流程
│   ├── PHASE0-VERIFICATION.md   阶段 0 验证报告（命令、输出、退出码）
│   ├── PHASE1-PREREQUISITES.md  阶段 1 前置审计（资产 / 许可 / 数据迁移）
│   └── upstream-reports/        每次同步生成的变更摘要
├── tools/
│   ├── README.md                工具清单（各自用途、用法、退出码）
│   ├── check-upstreams.py       治理契约检查
│   ├── check-references.py      引用完整性（断链、不存在的仓库内路径）
│   ├── check-license-hashes.py  上游许可原件哈希核验（可独立运行）
│   ├── sync-upstream.sh         拉取上游、生成变更摘要与冲突预警
│   ├── apply-divergences.sh     合并上游后重放已登记的偏离
│   ├── check-workflows.py       工作流 YAML 验证（真实解析器 + 结构断言）
│   ├── make-record-fixture.sh   --record 校验的隔离测试夹具
│   ├── make-divergence-fixture.sh  偏离重放的隔离测试夹具
│   ├── check-divergence-anchors.py 偏离锚点预检（对照真实上游）
│   ├── make-anchor-fixture.sh   锚点预检的隔离测试夹具
│   ├── import-upstream-base.py  阶段 1 导入器（拒绝在 main 上运行）
│   ├── check-import-fidelity.py 导入保真度：逐 blob 对照上游，差异必须已登记
│   ├── check-lint-baseline.py   对照继承基线检查 shellcheck（只拦新增 + 检测基线过期）
│   ├── check-exec-bits.py       .sh 索引模式双向对照继承基线
│   ├── check-android-identity.py  namespace 对得上 R 引用与 manifest 相对类名
│   ├── make-brand-assets.py     生成自有品牌图形 8 个（--check 逐字节核验）
│   ├── build-dsh-layer.sh       构建 DSH 运行环境层（版本取自 upstreams.json）
│   ├── check-apk-identity.py    核验 APK 产物的应用标识/版本/应用名
│   ├── make-apk-identity-fixture.py  合成 APK 夹具（测上面那个检查器）
│   └── verify-phase0.sh         阶段验证套件（成功与失败路径；工作区 90 项）
├── governance/inherited-lint-baseline.json  上游导入文件既有的 lint/可执行位状态
└── .github/workflows/
    ├── contracts.yml            契约与规范检查（每次 push/PR）
    ├── android.yml              上游的 Android CI（已补 permissions，DIV-0006）
    └── upstream-watch.yml       上游变更监测，产出摘要（每周）
```

## 命名与标识约定（阶段 1 生效）

| 项目 | 值 | 说明 |
|---|---|---|
| 应用标识 | `com.dshfusion.app` | **独立于** `com.dsh.client` 与 `com.dshbox.app`，可与两者并存安装 |
| 应用名 | DSHFusion | |
| 版本号 | `versionCode = 1` / `versionName = 0.1.0` | 独立版本线，不复用上游的 8 / 1.4.0 |
| 签名 | 独立自建密钥库 | 不要求、也不使用任何上游发布证书 |

> **阶段 1 刻意不做全量包名重命名。** DSHBox 内部有 721 处 `com.dshbox.app`
> 引用，一次性重命名会产生巨大的 diff 并让后续每次上游同步都冲突。
> 阶段 1 只改 `applicationId`、应用名、版本号与签名 —— 这些是"并存安装"必需的。
> 完整重命名列为阶段 4 的独立任务。见 [docs/FUSION-PLAN.md](docs/FUSION-PLAN.md)。

## 构建（阶段 1 完成后生效）

主干使用 DSHBox 的工具链：**JDK 17/21、AGP 8.9.2、Gradle 8.11.1、Kotlin 2.0.21、
compileSdk 36、minSdk 29、arm64-v8a**。

```bash
./gradlew testDebugUnitTest      # 单元测试（不需要运行环境资产）
./gradlew lintDebug              # Lint
./gradlew assembleDebug          # 需要运行环境资产，见下
```

**运行环境资产不在仓库内。** DSHBox 的 `DSHBox:app/build.gradle.kts` 从
`../../runtime/android-assets` 读取运行环境层。DSHFusion 保留这一机制，
但把路径改为可配置（`DSHFUSION_RUNTIME_ASSETS`），并支持两条获取途径：
在线获取（Debian 官方源）或离线导入整包。

> **修正（2026-10-13 阶段 1 前置审计）**：此处原先写「读取 base/node/android-side
> 三层」，**与实际不符**。v1.4.0 起 APK 内**只内嵌 `dsh` 与 `android-side` 两层**；
> `base` 与 `node` 不进 APK，由在线获取现场组装或离线整包导入。
> 四层的来源、可复现性与风险见
> [docs/PHASE1-PREREQUISITES.md](docs/PHASE1-PREREQUISITES.md) 第 1 节。
>
> **R1（DSH 层没有构建脚本）已解决**：`tools/build-dsh-layer.sh` 现在能构建它，
> 且版本从 `governance/upstreams.json` 读 —— 顺手把 R3（`install_dsh.sh` 默认
> `0.1.0-rc.6` 与实际适配的 `0.2.0-rc.2` 不一致）从结构上消灭了：
> 只有一个事实来源，两处不可能再分歧。
> 该脚本不需要 Debian 沙箱或 arm64 主机 —— `@deepseek-ai/dsh` 是纯 JS 的 npm 包。

## 编码约定

- 主干沿用 DSHBox 的风格：Kotlin + Compose、模块化、`DSHBox:tools/i18n_check.sh` 与
  `DSHBox:tools/privacy_audit.py` 门禁必须保持通过。
- 提交信息用中文，带 `type:` 前缀说明**原因**（为什么改，而不是改了什么）。
- 新增纯逻辑要有单测。DSHBox 现有 **1058 个 `@Test` 方法**是基线，不允许净减少。
  用 `@Test` 计数而不是测试文件数做护栏（文件数会被重构轻易改变）。
- 不要为了"看起来整洁"而重排上游代码 —— 每一行无意义的改动都会变成同步冲突。

## 已知陷阱

- **DSHBox 的商标声明**（`governance/upstream-licenses/DSHBox/TRADEMARK.md`）
  保留 `DSHBox` 名称、应用图标与品牌图形的全部权利，且明确禁止使用其
  **设计构造方法**开发近似标识。DSHFusion **必须使用自己的名称与图标**，
  不得复用其 `brand_icon.png` / `ic_pilot_brand.png` / `ic_splash_mark.png`。
- **DSHA 的发布证书（E7E3）与 debug keystore 是其历史身份**，不要获取、不要复用。
- **DSHA 的 `DSHA:docs/source-provenance.md` 自己记录了 Shizuku `ShellService`
  存在来源不可核验的 CFR 反编译标注**。若要复用 DSHA 的对应实现，
  必须先单独做来源审计；在结论明确前不要把它并入主干。
