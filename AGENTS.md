# AGENTS.md — DSHFusion 项目规范

> 本文件是 DSHFusion 的**唯一权威规范入口**。人和 AI 都先读这一份，不要先全库扫描。
> 面向的读者是「下一个接手这个仓库的人或 agent」。

---

## 一句话

DSHFusion 是**个人自用**的 Android 项目：在手机上运行 DeepSeek Harness（DSH），
**以 DSHBox 为第一阶段稳定骨干**，选择性吸收 DSHA 的成熟实现与运行时优化。

> 🔴 **定位已于 2026-10-16 由用户明确修正**：上游从「母体」变成「**来源**」。
> 目标不是「跟得上上游」，而是**做出一件两个上游都没有的东西**。
> 第二件东西定为 **统一设备能力层**；身份收回（全量包名重命名）**提前到现在**。
> 全部依据、可检查标准与阶段计划见 [docs/PRODUCT-DIRECTION.md](docs/PRODUCT-DIRECTION.md) ——
> **不要重新推翻，要改就改那一份。**
>
> 🔨 **阶段 3.0（身份收回）已开始**：分支 `phase-3.0/identity`，
> **审计已完成、尚未动代码** —— 实测 279 个文件 / 1069 处 / 52 个目录，
> 且**分类后每类的正确动作都不同**（盲 sed 会改错 4 类）。
> 范围、分类、执行顺序、登记方式与验证清单见
> [docs/PHASE3-IDENTITY-RENAME.md](docs/PHASE3-IDENTITY-RENAME.md)。

它不是 DSHBox 的 fork，也不是 DSHA 的 fork —— 它是**独立仓库**，通过显式的
双上游同步流程长期跟踪两者。见 [docs/UPSTREAM-SYNC.md](docs/UPSTREAM-SYNC.md)。

## 这个仓库当前处于什么阶段

**阶段 1 已完成、阶段 2 的「备份恢复」已完成，均已合并进 `main`（2026-10-16）。**

> ⚠️ **阶段 2 的定义是「备份恢复 + 受管更新」，而「受管更新」先前没有做、也没有归属** ——
> 它掉在了两份计划之间（FUSION-PLAN 的 2.x 只列到 2.6，而「环境更新」被写成「阶段 3 的事」，
> 但阶段 3 已改成统一设备能力层）。现定为 **阶段 2.8**，
> 审计与计划见 [docs/PHASE2.8-MANAGED-UPDATE.md](docs/PHASE2.8-MANAGED-UPDATE.md)。
> **在 2.8 完成之前，本文件不再写「阶段 2 已完成」。**

**阶段 2 的验收标准五条全部在真机上满足** —— 包括最后那条「可换台设备恢复」
（建会话 → 导出 → 新建一个 → 恢复 → 完全重启 DSH → 它消失了）。
明细见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 末尾的验收表。

**真机一共抓出 6 个 bug（事故第 39–44 条），而本机 174 条 + 335 项 + CI 全绿时
一个都没抓到。** 下一个阶段的计划见 [docs/PRODUCT-DIRECTION.md](docs/PRODUCT-DIRECTION.md)。

上游 790 个跟踪文件 → 排除 23 个（`website/`、`pages.yml`，DIV-0005）→ 归档 2 个
（`README.md`、`.gitignore`）→ **实际导入 765 个文件、25.4 MB**。
20 处偏离已登记，见 [governance/divergences.json](governance/divergences.json)。

**「原样导入」是可验证的，不是自我声称**：`tools/check-import-fidelity.py`
逐 blob 对照上游审计副本的 git 索引 —— 765 个文件与上游**逐字节（含模式）一致**，
46 处差异全部能对上已登记的偏离（其中 45 处是代码偏离，1 处是继承 lint 基线）。
**2026-10-14 更新：现在是 49 处** —— 先给 `SandboxProcessRunnerTest.kt`（上游文件）
补了参数顺序的回归单测（进入 DIV-0014），再为阶段 2.1 碰了 `settings.gradle.kts`
与 `gradle/libs.versions.toml`（进入 DIV-0019）。

**阶段 2.1 已实现（2026-10-14，分支 `phase-2/backup-v5`）**：新增 `:backup` 模块，
把 DSHA 的 **v5 备份归档格式与预检**整体搬了过来（DIV-0019）。这是**本项目第一次
从 DSHA 引入代码**，因此引入了两种新的可核验性要求：

- **导入副本的逐字节溯源**：`tools/check-dsha-imports.py` 核验「来源头在位 + 正文与
  登记一致」。与骨干保真度检查器刻意不同 —— 它的期望值**内嵌在
  `governance/dsha-imports.json`**，因此**在 CI 里是真跑**，而不是像骨干那个
  依赖仓库外审计副本、在 CI 里走「跳过」分支（见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 13 条）。
- **`dsha-imports.json` 与 `divergences.json` 分工**：前者管「我们从 DSHA 拿了什么」，
  后者管「我们改了骨干什么」。把导入本身也塞进后者会模糊它的本意（供同步重放）。
- **测试夹具与容器源码不许各说各话**：`tools/check-backup-contract.py` 核验
  「魔数三处一致 + 测试不手抄常量 + 断言的错误码真的会被抛出」。它存在的直接原因
  是一个真实事故：测试把 8 字符的 `DSHADATA` 手抄成 7 字符的 `DSHDATA`，
  5 条用例同时红，而**本机其他检查一条都发现不了**（见
  [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 17–18 条）。

> ⚠️ **第一次真实编译阶段 2.1 红了三次**（类型错误 → 魔数手抄 → 均为测试侧，
> 主模块每次都编译通过）。
>
> 所以「本机通过」只覆盖脚本与字节层面，**编译与运行层面的结论只能由 CI 给出**。
> 每一条负面用例红了都要先问「它证明的是被测代码错了，还是我的夹具坏了」——
> 第 17 条就是后者，而当时那三条 ComparatorFailure 长得完全像「拦截成功」。
>
> 🔴 **更正（2026-10-14，见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 21 条）：
> 「本机没有 JDK」这条记录是错的 —— 本机一直有完整的 JDK 17（`javac`/`jar`/`keytool` 都在）。
> 因此 `:backup` 的 **Java 侧**（不含 `.kt`）一直可以在本机真编译真运行。
> **没有的是 Android SDK、Gradle 与 kotlinc**，Kotlin 与 Android 侧的结论仍然只能由 CI 给出。
> 这条错误记录让 2.1/2.2 的四次 CI 红里有三次（都是纯 Java 侧问题）本可以两秒发现。**

**阶段 2.2 已实现（2026-10-14，分支 `phase-2.2/scope-backup`）**：范围模型与排除清单。

> ✅ **阶段 2.2 已通过真实 CI**（run [38633884134](https://github.com/T0X5/DSHFusion/actions/runs/38633884134)，
> commit `c7e83fa`）：**23/23 步全绿** —— `testDebugUnitTest`（`:backup` 测试全过；
> 当时记的是「62 条」，**那个数字是错的** —— 阶段 2.3 加上计数断言后实测反推，2.2 应为 **63 条**
> （Java 28 + Kotlin 35）。见下面第 26 条，以及 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 27 条）、
> `lintDebug`、`assembleDebug`、APK 产物身份核验，产物 `dshapp-debug-apk` 128,790,096 字节。
> 但**2.2 仍未在真机上验证过**：它只是「能判定范围与排除对不对」，
> 还没有导出/恢复入口，装到手机上无可操作的东西（见下面「刻意没做」）。
>
> 走到这一步红了**四次**，四次全在测试侧、主模块每次都编译通过：
> ① 16 处 `Files.writeString` 编不过（工具链差异，见第 20 条）→
> ② 我自己机械替换留下的悬空限定名 `java.nio.file.new String(…)` →
> ③ 断言把正确实现判失败（`FULL` 的路径就是 `.dsh`，我却要求 `startsWith(".dsh/")`）→
> ④ 全绿。**三次里有两次是我造成的**，而两次本机都「看起来没问题」。

- **枚举与写入那一半也搬进来了**（`BackupFileSystem`/`BackupSource`/`FileBackupSource`/
  `HostSnapshot`/`GuestDataResolver`，共 11 个逐字节副本 + 4 份原始测试）——
  它们全是纯 JVM、零 Android 导入。`BackupTree` 刻意**没搬**：它引用
  `AndroidTreeFileSystem`（Android 专属快速路径），且只被 2.4 的事务类使用。
- `DshBackupScope.kt` 是**改写后移植** DSHA 的 `BackupScope.java`，登记在
  `dsha-imports.json` 新增的 `derived` 段 —— 正文不是逐字节副本，所以不能靠 sha256
  证明「没改」，只能如实列清保留/改掉了什么。**改写必须自证，否则「移植」就成了洗代码的通道。**
- `DshBackupExclusions.kt` 是本仓库自己写的。它挡住的那件事见事故表第 19 条。
- **3 份 DSHA 测试文件做了机械适配**（`Files.writeString` → `Files.write(…, UTF_8)`，共 16 处），
  因此从 `files[]` 移入 `derived.items[]` 的 `kind: adapted`。原因不是测试写错，
  而是**两个上游的工具链不同**（AGP 9.1.1 vs 8.9.2 决定了单元测试按 JDK 还是按
  `android.jar` 解析 `java.nio.file`）。见事故表第 20 条。
  来源头据此拆成两种：逐字节副本用 `provenance_header`，适配副本用 `adapted_header`，
  检查器强制各自出现在对应 `kind` 上。

**阶段 2.2 刻意没做**：真正调用 `HostSnapshot.create`（属于 2.4 的恢复编排）、
凭据的**字段级**排除（2.3）、范围与排除的界面文案（2.4）。
**2.2 只声明「整份 `.dsh/credentials` 不进归档」，不声称「归档里一定没有密钥」** ——
后者要等 2.3 做完并由它自己的测试作证。

**阶段 2.3 已实现（2026-10-14，分支 `phase-2.3/credential-exclusion`）**：凭据投影
（把机器本地凭据挡在归档之外）。

- **先审计再动笔，审计推翻了最初的假设。** 我原以为 `CredentialProjection` 处理的是
  **DSHA 自己的** `.credentials.yaml`，所以要「先弄清 DSHFusion 的凭据布局」才能决定复不复用。
  实测相反：DSH 自己的凭据就是 `$DSH_HOME/.credentials.yaml`，格式由
  `@deepseek-ai/dsh-credentials-local` 定义（`version: 1` + `refs:` + `records:`，
  记录键 `<scope>/<id>`，类型 `api-key`/`grant`）—— **与 DSHA 的投影 schema 逐条一致**。
  容器里的 DSH 是 `0.2.0-rc.2`，与本仓库锁定的版本相同，所以这份证据对本仓库有效。
  要删的机器本地记录（`client-connection/browser-session`，浏览器会话授权）就是
  `dsh-client-connection` 里 `credentialKey("client-connection", "browser-session")` 那一个。
  **结论：直接复用，不重写** —— 与硬约束 2 一致。
- **2 个逐字节副本**（`CredentialProjection.java` 283 行、`CredentialFileBackupSource.java` 110 行，
  零 Android 导入）+ **1 份适配副本**（`CredentialProjectionTest.java`，3 条语句因工具链差异机械替换）
  + **1 份本仓库自写的测试**（`CredentialProjectionCodesTest.kt`，单列在 `dsha-imports.json`
  的 `original` 段，不混进 `derived`）。
- **删掉了上游 1 条测试方法，并如实登记。** `oldV5ArchiveMachineRecordIsProjectedInCandidateBeforeItCanBecomeActive`
  依赖 `NativeRestorePlan` → `BackupTree` → Android 耦合的 `AndroidTreeFileSystem`/`AndroidBackupFileSystem`，
  正是登记为阶段 2.4 的那条链。**这是真实的覆盖损失** —— 它证明的性质（机器记录在候选阶段就被投影）
  **在 2.3 没有被验证**，已写进 `dsha-imports.json` 的 `changed` 与 `not_imported`（补回阶段 2.4）。
  删测试而不留痕等于覆盖静默流失，所以 5m 节专门断言这条登记完整。
- **上游那条断言太弱，所以补了一条自己的。** 上游对 5 种坏输入只断言
  `startsWith("CREDENTIAL_PROJECTION_")` —— 只能证明「被拒绝了」，证明不了「因为正确的原因被拒绝」。
  新测试按**实测的具体错误码**逐条钉住。
- **期望值是跑出来的，不是读出来的。** 我先按源码推理，推出 `anchor-alias → _ALIAS`、
  `explicit-tag → _TAG`、`deep-nesting → _LIMIT` —— **三个全错**（SnakeYAML 在自己的
  `compose()` 里就先抛了，被兜底 catch 转成 `_FAILED`）。若照推理写进断言，红的是断言不是代码。
  见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 22 条。
- **本机第一次真正编译并运行 `:backup`。** 新增 `tools/run-backup-jvm-tests.sh`：
  取 junit/hamcrest/gson/snakeyaml 四个 jar，编译并运行 `backup/src/{main,test}` 的**全部 .java**，
  再跑 `tools/check-credential-projection.java` 做**行为**核验。**版本必须与版本目录一致**
  （脚本自己 grep `libs.versions.toml`，对不上退 2；已做注入验证），取不到 jar 也**退 2 不静默跳过**。
  已接进 `contracts.yml`（补 `actions/setup-java`）。
- **新增依赖 SnakeYAML 2.4**（Apache-2.0，DIV-0020）：`CredentialProjection` 用的是 SnakeYAML 2.x
  才有的 `LoaderOptions` 限流项，降到 1.x 是**静默的安全退化**。已解包核对
  `snakeyaml-2.4.jar` 的 META-INF —— **没有任何 consumer proguard 规则**；而本仓库 release 开了混淆、
  上游 DSHA 的 release 是 `minifyEnabled false`，所以「DSHA 装了它能跑」不构成证据。
  app 侧补了 `-keep class org.yaml.snakeyaml.**`。**这条规则是保险，不是修复 ——
  它的效果没有被验证**，CI 只构建 debug。
> ✅ **阶段 2.3 已通过真实 CI**（run [38643847540](https://github.com/T0X5/DSHFusion/actions/runs/38643847540)，
> commit `19e95bd`）：**24/24 步全绿**（比 2.2 多一步 —— 就是下面那条计数断言），
> `:backup` **76 条测试**（Java 33 + Kotlin 43，11 个测试类），产物 `dshapp-debug-apk` 128,791,472 字节。
> **但这 76 条里有一条特别值得注意**：那 8 条 Kotlin 测试**本机跑不了**（没有 kotlinc），
> 而 Gradle 对通过的测试不打名字 —— 所以 CI 的绿**本身**并不作证它们跑过。
> 为此专门加了一步把每个测试类的条数打进日志并判失败（见第 26 条），
> 而它**第一次真跑就红了** —— 红的是我写错的期望值（见第 27 条）。
> **2.3 仍未在真机上验证过**（还没有导出/恢复入口）。

- **刻意没做**：`settings.yaml` 里模型凭据的**字段级**排除。目前的模型凭据走 DSH 自己的
  凭据存储（与 `.credentials.yaml` 同一条路）；`settings.yaml` 里是否还有需要单独排除的字段，
  要等确认过它的实际内容才能决定。**没有证据之前不动它。**

**阶段 2.4a 已实现（2026-10-14，分支 `phase-2.4/restore-core`）**：**恢复核心**
（预检 → 平行候选 → 校验）。这是本模块**第一条会毁数据**的路径。

- **先审计再动笔，而审计的第一版有三处被实测推翻。** 推翻它的不是评审，是编译器和
  一次真实的 `grep System.loadLibrary`。三处错的共同形态：**从「导入清单」和「行数」
  推断可达性，而没有去读那几处引用本身。** 完整记录在
  [docs/FUSION-PLAN.md](docs/FUSION-PLAN.md) 的 2.4 节。
- **第一处：审计漏了两个类。** 第一版列了 6 个候选，实际闭包里还有
  `DataFormatEvidence`(55) 与 `RuntimeDescriptor`(154) —— 是**编译报错**逼出来的，
  不是读出来的。
- **第二处（决定性，推翻了第一版的落点）：真正卡住的不是 `android.*`，是两处
  「指向上游自己应用内部子系统」的耦合。**
  `AndroidTreeFileSystem` → `NativeStorage` → 🔴 **`System.loadLibrary("termux")` + 7 个
  native 方法**，符号在 DSHA 的 `libtermux.so` 里，而**骨干只有 `libproot` 系列**；
  `NativeRestoreTargets` → `ConfigStore`(817) → `KeyVault`(145) → `CredentialRead`(73)，
  那是 **DSHA 自己的设置与凭据模型**。
  **一个 `android.os` 依赖比一个指向上游应用内部子系统的依赖好处理得多** ——
  前者是平台 API，后者是架构承诺。
- **第三处（这一处让 2.4 有解）：`BackupTree` 的 Android 耦合是快路径优化，不是语义。**
  它的 `digest()` 是「`fs.getClass() == AndroidBackupFileSystem.class` → 用
  `AndroidTreeFileSystem`，否则走通用 `digestTree`」，两条路径**共用同一个 `TreeHash`/`visit`**，
  且分支只取决于 `fs` —— **任何一次调用里两侧必然走同一分支**，摘掉它不会造成不一致。
- **落点**：`NativeRestorePlan`/`DataFormatEvidence`/`RuntimeDescriptor` **逐字节导入**；
  `BackupTree` **适配导入**（11,171 → 7,330 字节，删掉的**全部是 Android 专有分支**，
  通用遍历逐字保留）；`AndroidBackupFileSystem`+`AndroidTreeFileSystem`+`NativeStorage`
  与 `NativeRestoreTargets` **明确不导入**并点名真正的原因。
- **2.3 那条被删测试的开放问题，由真实执行回答 —— 而且答案是「回来了」。**
  2.3 写的是「这条覆盖在 2.3 没有被验证」，2.4a 摘掉快路径后它变成纯 JVM，
  **恢复回去、本机真跑通过**。本机 Java 测试 **33 → 42 条**。
  > 原文写的是「JVM 会不会在类加载阶段解析 `AndroidTreeFileSystem`，**读源码判断不了**，
  > 必须真跑」—— **方法是对的**，但答案之所以是「能跑」不是因为惰性解析，
  > 而是因为**我们先把那条引用删掉了**。前者是运气，后者是可复现的决定，别混。
- **新断言既查「Android 摘干净了」，也查「通用逻辑还在」** ——
  `verify-phase0.sh` 的 5n 节同时检查 `BackupTree` 里没有
  `AndroidTreeFileSystem`/`loadLibrary`，**且** `ENTRY_LIMIT`/`INVALID_CHILD`/
  `SPECIAL_FILE`/`SOURCE_CHANGED`/`TreeHash` 都还在。只查前者会允许「顺手把限额记账也删了」。
- ⚠️ **残留风险如实登记**：两条路径的目录枚举顺序是否一致**我们没验证**
  （验证它需要那个原生库）。不影响当前用法，但它是这条适配的已知缺口。

> ✅ **阶段 2.4a 已通过真实 CI**（run [38647873314](https://github.com/T0X5/DSHFusion/actions/runs/38647873314)，
> commit `0629409`）：**24/24 步全绿**，`:backup` **85 条测试 / 12 个测试类**
> （2.3 是 11 个类，多的正是 `NativeRestorePlanTest`），产物 `dshapp-debug-apk` 128,789,848 字节。
> Contracts 4/4 全绿。
>
> **那个计数断言这次「确认」了我的预测，而上一次它是「证伪」的** —— 差别值得记住。
> 我在 `android.yml` 里写的是 **85**，推导是「2.3 的 CI 实测 76 = Java 33 + Kotlin 43，
> 加上 2.4a 本机实测的 Java 42（33 + 8 + 恢复的 1），Kotlin 未动仍 43」。
> CI 报的就是 85 条、12 个类。
> **推断这次对了，不代表推断可靠** —— 可靠的是「把推断放进一条会失败的断言里」这件事本身。
> 2.3 那次它错了，代价只是一次 CI；若只写在文档里，那个错会一直活着（第 27 条）。
>
> ⚠️ **但仍未在真机上验证过。** 还没有导出/恢复的界面入口，装到手机上无可操作的东西。
> CI 通过只覆盖构建、单元测试、lint 与产物身份 —— 它证明的是「这些代码编得过、这些测试跑得过」，
> **不是「恢复在真机上不会毁数据」**。后者要等 2.4b 有了界面入口。

**阶段 2.4b-① 已实现（2026-10-14，同一分支）**：**恢复的落点** ——
[`HostRestoreMapping.java`](backup/src/main/java/com/dshfusion/app/backup/HostRestoreMapping.java)，
把归档的逻辑根映射到本机 `user-data/.dsh`。三条要点：

1. **审计推翻了「复用 DSHA 的落点实现」这个默认假设，而且最强的理由与 `android.*` 无关。**
   `NativeRestoreTargets.target()` 第一句就是 `BackupJson.string(root, "logicalKind")`，
   而我们的归档根只有 `id`/`scope`/`external`（`BackupSource.description()`）—— **它会直接抛**。
   DSHA 认 `dsh-home`/`dsh-child`（`.dsh` 子条目逐个成根），我们认一个成员一个根：
   **两套词汇，不是同一套的两个实现。**
2. **写成 Java 而不是 Kotlin，是为了能本机真跑。** 本机有 JDK 17、没有 kotlinc（第 21 条）。
   这是**会毁数据的那条路径**（它决定往哪写），只让 CI 编译它太贵。代价是破了语言约定，
   所以这两个文件如实登记在 `dsha-imports.json` 的 `original` 段。
3. **`mergeSubdir` 由 `path` 推导，不再是构造参数。** 少一个来源就少一类分叉（第 17/20 条）。

> ✅ **阶段 2.4b-① 已通过真实 CI**（run [38651955250](https://github.com/T0X5/DSHFusion/actions/runs/38651955250)，
> commit `0aea1dd`）：**24/24 步全绿**，`:backup` **96 条测试 / 13 个测试类**
> （2.4a 是 85 / 12；多的 11 条正是 `HostRestoreMappingTest`，CI 日志里逐类打印为
> `11 com.dshfusion.app.backup.HostRestoreMappingTest`），产物 `dshapp-debug-apk` 128,789,923 字节。
> Contracts 4/4 全绿。
>
> **这次计数断言又是「确认」而不是「证伪」** —— 我在 `android.yml` 里写的是 **96**
> （2.4a 的 CI 实测 85 + 本机实测新增的 11），CI 报的就是 96 / 13 个类。
> **连续两次对了，恰恰是它最危险的时候** —— 第 27 条那次是错的，代价只有一次 CI。
> 可靠的不是我的算术，是「把算术放进一条会失败的断言里」这件事。
>
> ⚠️ **仍未在真机上验证过**：还没有导出/恢复的界面入口。CI 证明的是「编得过、测试跑得过」，
> **不是「恢复在真机上不会毁数据」**。而且 **2.4b-②（提交与回切）还没写** ——
> 现在的代码能算出「该往哪写、候选树长什么样」，但**还不能真的写**。

**阶段 2.4b-② 已实现（2026-10-14，同一分支；同日**推倒重来一次**）**：**提交与回切**。

> 🔴 **这一节我写错过一次，而且错得比前 32 条都贵。**
>
> 第一版我**从零手写了 350 行** `HostRestoreCommit.java`，并在类注释与
> `dsha-imports.json` 里写下理由：「上游把它放在 `HostDataTransaction` 里，
> **那个类依赖它自己的 `ConfigStore`/`KeyVault`**，没搬。」
>
> **那个理由是错的 —— 我写下它的时候没有读那个文件。** 实测：
> `HostDataTransaction` 只有 `java.io`/`java.util` 两个 import，
> 一次都没提到 `ConfigStore`/`KeyVault`/`CredentialRead`，同包依赖前五个我们都有。
> **它就在可达范围内**，而且比我写的那份**更严密**。已删除手写的那一份，
> 改为**逐字节导入**。见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 33 条。

**现在的落点**：`HostDataTransaction`（315 行）+ `TransactionMarkers`（34 行）
+ `Ids`（13 行）**逐字节导入**；上游的 `HostDataTransactionTest`（202 行 / 5 条，
含一条**真杀子进程**的）**适配后导入**；我们自写一层
[`HostRestoreTransaction.java`](backup/src/main/java/com/dshfusion/app/backup/HostRestoreTransaction.java)
做词汇映射。

**两边的词汇恰好同构**，所以适配层很薄：

| 上游 | 我们 |
|---|---|
| `directory/candidate/<id>` | `NativeRestorePlan` 的 `task/candidate/<group>`（逐字同名） |
| `Targets.resolve(rootId)` | `plan.targets.get(group).write` |
| `prepare(roots, expectedBefore, …)` 的 `expectedBefore` | `plan.before` |
| `Settings`（设置轴） | **我们没有这个轴**（见下） |

**上游比我手写的那份严密在哪（逐条）**：

| | 我手写的 | `HostDataTransaction` |
|---|---|---|
| 提交前重验 | 只重验 `before` | 重验 `before` **和** `after`（`CANDIDATE_CHANGED`） |
| 嵌套目标 | 没有 | `OVERLAPPING_TARGETS`（父目录与子目录互为目标） |
| 持久标记 | 一个 `commit-journal.json` | 五相位标记（`verified`→`switching`→`files-committed`→…） |
| 恢复判定 | 只看摘要 | **相位 + 摘要**双判 |
| 崩溃测试 | 注入（而且第一版注错了，见第 31 条） | **真杀子进程** |

**适配层只加了两样东西，都不是改写上游**：

1. **`CROSS_DEVICE` —— 上游没有这条检查。** `HostDataTransaction` 直接 `fs.move`；
   跨文件系统时 `move` **不报错**，只是退化成拷贝加删除 —— 于是「两次 rename 换进去」
   这个原子性**静默消失**，而所有测试仍然全绿。补在适配层，上游那个类保持逐字节不动。
2. **设置轴故意做成「非空就抛 `SETTINGS_NOT_SUPPORTED`」。** 上游会在提交时一并切
   `settings.json` 并有三处断言（`SETTINGS_CHANGED` / `SETTINGS_COMMIT_VERIFICATION`）；
   我们的恢复路径只写用户数据。若给它一个「恒空表、apply 什么都不做」的实现，
   那三处会**永远通过** —— 那就是第 30 条说的「没有输入空间的断言」。
   所以反过来：空表才接受，非空直接红。将来真做设置轴时它会红，而不是静默丢掉用户的设置。

**故障注入改用上游的命名边界。** 手写那版数「第 N 次 `move`」，而 `fs.atomic()` 内部
也在 `move`，于是注入打偏、**三个用例全绿却什么都没测到**（第 31 条）。
上游的 `Fault.boundary(name)` 由**被测代码自己给出边界名**，从根上不会有那个病。

**审计还顺带发现登记文件在自相矛盾**：`BackupTree.java` 既在 `derived`（2.4a 适配导入）
又在 `not_imported`；`NativeRestorePlan.java` 与 `HostDataTransaction.java` 同样两侧都在
—— **而在此之前没有任何检查会红。** 这比「少登记一个文件」更危险：登记文件是
「我们从上游拿了什么」的权威答案，下一个读它的人会**相信错的那一半**（第 33 条就是这么来的）。
`tools/check-dsha-imports.py` 已补一条「两侧不许相交」，5p 节带注入。
> ⚠️ 这条新检查的**第一版是瞎的** —— 它拿 `upstream_path` 去比一个本机路径的集合，
> 于是永远不可能相等。**是 5p 节自己的注入把它抓出来的**（第 30 条的形态）。

**CI 计数断言（下限 108）**：阶段 2.4b-② 的 CI 实测 105（Java 62 + Kotlin 43）→
本轮 `62 − 9（删掉手写的）+ 5（上游那份）+ 7（我们的集成）= 65`，
Kotlin 未动仍 43 ⇒ **108**。两个类**分别钉** 5 / 7 —— 只钉总数的话，
一个可以静默消失而总数仍然够。

**审计还推翻了本文件自己先前写的落点**：回切**不**落在
`runtime-current/` ↔ `runtime-new/` 上 —— 那对槽换的是 base/node/dsh 三层**运行环境**
（`BundleManager.promoteNewSlotToCurrent()`），而恢复写的是**用户数据** `user-data/.dsh`。
用「换整个系统」去实现「换一个目录」是错的；环境更新属于阶段 3。

**阶段 2.5 核心已实现（2026-10-15，同一分支）**：自动备份的**到期判定**与**保留策略**。

- **两个纯逻辑类逐字节导入**（`AutomaticBackupPolicy` 30 行 / 只有 `java.time`；
  `ScheduledBackupOwner` 82 行 / 只有 `java.lang`）+ 它们的测试（4 + 5 条，含 DST
  与时钟回拨、跨线程拒绝）。`ScheduledBackupOwner` 是**线程受限的单飞票据所有者**，
  参数用**引用相等** —— 它回答的是「现在这个 job id 是不是我这次提交的那个」。
- **保留策略必须自写**（[`DshAutomaticBackupRetention.java`](backup/src/main/java/com/dshfusion/app/backup/DshAutomaticBackupRetention.java)）：
  上游 pruner 的判据建立在「自动备份是**一个目录 + 四个具名成员**」上，而我们是
  **单文件** `DSHDATA` 容器 —— 直接抄不是「命名对不上」，是**连输入都没有**，
  后果是**静默的空操作**（用户以为保留 3 份，磁盘无限增长）。
  **这条审计结论有可证伪的形式**：`upstreamPrunerJudgementMatchesNothingOfOurs`
  把上游那段判据跑在我们的真实产物名上，断言一份都删不掉，**同时**断言我们的策略
  确实会删 —— 否则那条用例证明的只是「两边都不删」（第 28/30 条）。
  比上游严三条：只碰 `MAINTENANCE`（手动/救援永不入候选）、证明不了完整的
  `PARTIAL`/`BEST_EFFORT` **既不计数也不删而且要报出来**（上游是静默 `continue`）、
  `keep < 1` 按 1 处理（不允许「全删」）。四个方向各有注入证明会红。
- **审计原来把「自动备份与恢复期间目标必须静止」记成真问题，那是个伪冲突**：
  约束是**恢复单方面**的（恢复写、备份只读）。备份不静止的后果是**跨文件撕裂视图**，
  而容器的 `integrity` 字段描述的本来就是逐文件完整性、**不是**跨文件一致性。
  ⇒ 这正是上游 `stop` 模式存在的原因：三种模式是**一致性强度递增**的三档。

**阶段 2.5 未做**：调度胶水（`JobService` + 设置界面）。逐条理由见
[backup/README.md](backup/README.md) 与 `dsha-imports.json` 的 `not_imported`。

> 两个方向都要查，缺一不可：`check-import-fidelity.py` 抓「多出来的改动」，
> `apply-divergences.sh --check` 抓「已登记却没生效的偏离」。实测过 ——
> 把 DIV-0011 第三个文件的改动撤掉后，保真度检查是**通过**的
> （该文件已在 `target_files` 里，于是不再比对），只有重放检查报错。

> ⚠️ **这个检查器读的是 git 索引，不是工作区 —— 改动没 `git add` 之前它对你是瞎的。**
> 实测：DIV-0014 跨 22 个文件、其中 16 个此前从未偏离过，`git diff --stat` 是
> 500 插入 / 103 删除；但未暂存时跑保真度检查，它报的仍是**上一次提交**的
> 「24 处差异全部已登记」，一切看起来都干净。`git add -A` 之后同一个命令报 **40 处**。
>
> 这不算工具 bug（索引比对是有意设计），但它是一个**会给出假好消息**的用法陷阱，
> 与断言 6 被 `.sha256` 侧车骗过是同一类。所以：**跑保真度检查前先 `git add`，
> 并把「差异数」当成断言来读** —— 数目没变说明你的改动根本没进去。

> ✅ **构建、真机安装、真机运行三关都已在真机上验证通过（2026-10-14，第六版 `5a7e7c7`）。**
>
> 自有 DSH **第一次真正跑起来**，而且全链路已**逐环独立核验**：
> 进程 uid → 端口 owner → 界面显示 → 浏览器 ESTABLISHED 连接 → token 被接受。
> 每一环都有设备侧证据，不看界面自述。明细见
> [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 14 条。
>
> **阶段 1 至此没有「未验证」的项了。** 下面表里剩下的都是**已经决定推迟到后续阶段**
> 的事（全量品牌重命名、DSHA 的来源审计、密钥库的产物级签名断言等），
> 不是「还不知道行不行」。
>
> 此前的历史（每一步都是真机上才暴露的）：第一次安装失败 -13
> （`INSTALL_FAILED_CONFLICTING_PROVIDER`）→ 修复后装上 → 发现 APK 不含运行环境层
> → 层是 `.tar.gz` 被 AGP 剥坏 → 界面误报「就绪」而 DSH 根本没起（DIV-0014）
> → 层是 x64（DIV-0015）→ 启动命令参数顺序（并入 DIV-0014）。
>
> 2026-10-13，上游 `android.yml` 在 DSHFusion 下首次真实运行**通过**：
> 10 个模块的 `testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部成功，
> 产出 `dshapp-debug-apk`（39.0 MB 压缩 / 94.0 MB 解压，777 个条目）。
> 这一步不再是「凭配置推断」。
>
> 2026-10-14，真机安装试了两次：第一次**失败 -13**
> （`INSTALL_FAILED_CONFLICTING_PROVIDER`，根因见
> [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 4 条），
> 按 DIV-0011 修复后第二次**成功**。
> （当时写的是「装上不等于能用」—— 那句话是对的，后面确实又踩了三层，
> 见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 6–12 条。
> **2026-10-14 第六版起，这三层都过了。**）
>
> **已修复（2026-10-14，提交 `e676e38`；格式更正于 `bc4bdb0`）**：此前 CI 产出的 APK
> **不含任何运行环境层** —— 而且这不只是「还得在线装一次」那么轻。
> 见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 6 条：**两条获取途径都是死的**。
> 现已补上 `tools/build-runtime-assets.sh` 并接进 CI，
> 新产物内嵌 `assets/runtime/android-side.tar.zst` + `assets/dsh/<版本>.tar.zst`，
> 且 `check-apk-identity.py` 会把「缺层」直接判失败（不再是备注）。
> ⚠️ 但 `e676e38` 那一版**依然是坏的**：层是 `.tar.gz`，被 AGP 剥成了应用找不到的
> `.tar`，而断言 6 被 `.sha256` 侧车骗过去、报了个假的好消息。见
> [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 7 条。
> 见 [docs/PHASE0-VERIFICATION.md](docs/PHASE0-VERIFICATION.md) 的诚实性声明。

阶段 1 的未完成项（**不要误认为已完成**）：

| 项 | 状态 |
|---|---|
| 自有品牌图形 8 个 | ✅ 已自绘；`tools/make-brand-assets.py` 可复现，CI 逐字节核验 |
| 独立签名密钥库 | ✅ **已创建并接入 CI**（2026-10-14，DIV-0017）—— 见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 11 条 |
| 运行环境层构建脚本 `tools/build-dsh-layer.sh`（补运行环境风险 R1） | ✅ 已编写并**真跑通**（538 包 / 118.6 MB 层 / 冒烟测试通过） |
| 上游 `android.yml` 在 DSHFusion 下的首次真实运行 | ✅ **通过**（run 38478523535，含单元测试 / lint / APK 组装） |
| APK **产物**身份核验（DIV-0008） | ✅ **已通过**（run 38483533327 第 15 步）—— 对 CI 真实产物解二进制清单核验 |
| 单元测试 / lint / `assembleDebug` | ✅ 通过（10 个模块全部成功，`terminal-view` 无测试源） |
| 生成的 APK 装到真机上验证 | ✅ **已通过**（2026-10-14，修复后第二次尝试）—— 见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 4–5 条；**启动与运行已于第六版通过**（第 14 条） |
| APK 内含运行环境层（能真正跑起 DSH） | ✅ **已通过产物核验**（2026-10-14，run 38516880572）—— 见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 7 条的结论 |
| **自有 DSH 真正在真机上跑起来** | ✅ **已验证**（2026-10-14，第六版提交 `5a7e7c7`）—— **进程 + 端口 owner + 浏览器连接的独立证据**，见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 14 条。此前连续失败五次：v3「界面报就绪、DSH 根本没起」（DIV-0014）、v4「层是 x64」（DIV-0015）、v5「启动命令参数顺序」（并入 DIV-0014） |
| `upstream-watch.yml` 真实运行 | ✅ **已跑通**（run 38483621472，手动触发）—— 检出 DSHA 有新提交并建了 Issue #2 |

### 事故与真机验证记录

**44 条事故的索引表在 [docs/INCIDENTS.md 附录 B](docs/INCIDENTS.md#附录-b事故索引表44-条)。**
每条事故的完整过程、原始报错、复现命令与故障注入结果，在那边正文的各节里。

**为什么表不在这里**：它在这里时**五次**把本文件撑过 65536 字节的指令预算而被**静默截断**，
截掉的正是后面的「编码约定」与「已知陷阱」两节 —— 而那是本文件最有用的部分。
所以**改结构**：规范留这里，表放那边。本文件有 5s 节断言字节数与尾部小节都在。

三条方法论（已写进上面的硬约束）：
**「步骤通过」≠「产物对」≠「在目标设备上能用」**；**状态结论必须有独立证据**；
**每一个新断言都要用一个真实注入的故障证明它真的会红**。

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

DSHFusion 当前**暂不设置许可证，私有自用**（见根目录 [LICENSE](LICENSE) 与
[LICENSE-PENDING.md](LICENSE-PENDING.md)）。

这不是"随便用"的意思 —— 而是说：本仓库**没有对外分发**，因此 GPL-3.0 的分发义务
尚未触发；但**一旦决定公开或分发，必须重新评估**，因为骨干 DSHBox 是 GPL-3.0。

**根 `LICENSE` 放的是状态说明，不是 GPL-3.0 全文**（DIV-0009）。
原因：上游根 LICENSE 是 GPL-3.0 全文，GitHub 据此把本仓库显示成「GPL-3.0」，
而本仓库并未做出那个承诺 —— 矛盾的是 GitHub 那一侧。

> ⚠️ **GPL-3.0 原文没有被删除，只是换了位置**：它按原样保存在
> [governance/upstream-licenses/DSHBox/LICENSE](governance/upstream-licenses/DSHBox/LICENSE)，
> 受 `governance/upstream-license-hashes.json` 的 SHA-256 保护。
> `handle_div_0009` 会**正向核验**这份副本仍然存在且哈希一致 ——
> 只检查「根目录不再是 GPL」是不够的，那等于允许许可证在换位置时凭空消失。
> 这条安全网做过三种故障注入（删副本 / 改副本一个字节 / 把 GPL 全文塞回根目录），
> 全部正确拦截。

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
│   ├── dsha-imports.json        从 DSHA 原样导入的文件清单（来源头 + 正文 sha256）
│   ├── ATTRIBUTION.md           署名与许可义务
│   └── upstream-licenses/       上游许可原文（原样保留，勿改）
├── docs/
│   ├── AUDIT.md                 只读审计报告（决策证据）
│   ├── INCIDENTS.md             事故与真机验证记录（AGENTS.md 的配套明细）
│   ├── FUSION-PLAN.md           融合方案与实施优先级
│   ├── UPSTREAM-SYNC.md         双上游长期同步流程
│   ├── PHASE0-VERIFICATION.md   阶段 0 验证报告（命令、输出、退出码）
│   ├── PHASE1-PREREQUISITES.md  阶段 1 前置审计（资产 / 许可 / 数据迁移）
│   ├── INSTALL-ON-DEVICE.md     真机安装步骤（给用户手动执行）
│   ├── evidence/                真机证据截图（成功基线留档）
│   └── upstream-reports/        每次同步生成的变更摘要
├── tools/
│   ├── README.md                工具清单（各自用途、用法、退出码）
│   ├── check-upstreams.py       治理契约检查
│   ├── check-references.py      引用完整性（断链、不存在的仓库内路径）
│   ├── check-license-hashes.py  上游许可原件哈希核验（可独立运行）
│   ├── sync-upstream.sh         拉取上游、生成变更摘要与冲突预警
│   ├── apply-divergences.sh     合并上游后重放已登记的偏离
│   ├── check-workflows.py       工作流 YAML 验证（真实解析器 + 结构断言）
│   ├── check-workflow-python.py 工作流内联 Python 的语法编译（不执行）
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
│   ├── build-runtime-assets.sh  组装可内嵌 APK 的层（android-side + dsh，产出 sha256 侧车）
│   ├── check-apk-identity.py    核验 APK 产物的应用标识/版本/应用名
│   ├── make-apk-identity-fixture.py  合成 APK 夹具（测上面那个检查器）
│   ├── check-dsha-imports.py    DSHA 导入副本核验（来源头 + 正文 sha256，CI 真跑）
│   ├── check-backup-contract.py 备份模块契约（魔数三处一致 / 不手抄常量 / 错误码有效）
│   ├── run-backup-jvm-tests.sh  本机真编译真运行 :backup 的 Java 侧（含行为探针）
│   ├── check-credential-projection.java  凭据投影的行为核验（跑一遍，不是 grep）
│   └── verify-phase0.sh         阶段验证套件（成功与失败路径；工作区项数见运行输出）
├── backup/                      阶段 2.1–2.4b②：v5 格式与预检 + 范围与排除 + 凭据投影 + 恢复核心 + 落点映射 + 提交与回切（见其 README）
│   └── src/main/java/com/deepseekharness/app/**  DSHA 原样导入（MIT，勿改正文）
│   └── src/main/java/com/dshfusion/app/backup/**    本仓库自写（Java，为能本机真跑）
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
| 签名 | 独立自建密钥库（DIV-0017） | 固定身份，密钥库走 Actions Secret，**不入库**；不要求、也不使用任何上游发布证书 |

> **阶段 1 刻意没做全量包名重命名**（当时的理由：DSHBox 内部有 721 处 `com.dshbox.app`
> 引用，一次性重命名会让后续每次上游同步都冲突）。
> **阶段 3.0 起已提前执行** —— 见 [PHASE3-IDENTITY-RENAME.md](docs/PHASE3-IDENTITY-RENAME.md)
> 与 `governance/divergences.json` 的 DIV-0025。

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
> 且版本从 `governance/upstreams.json` 读 —— 单一事实来源，这个脚本自身不会漂移。
>
> **R3 我一度说成「已从结构上消除」，那是过头话，已更正。** 新脚本只是新增了
> 一条正确的路径，并没有碰 `install_dsh.sh:8` 那个 `0.1.0-rc.6` 默认值。
> 现按 DIV-0010 处理：取消默认值，未显式指定 `DSH_VERSION` 就报错退出。
> 该脚本在 DSHFusion 里**没有任何地方执行它**，所以修的是潜在的坑，不是发作中的 bug。
>
> ⚠️ **更正（2026-10-14）**：此处原先写着「`build-dsh-layer.sh` 不需要 Debian 沙箱或
> arm64 主机 —— `@deepseek-ai/dsh` 是纯 JS 的 npm 包」。**后半句是错的**：
> `@deepseek-ai/dsh` 包本身确实是纯 JS，但它的依赖树里有 7 个按平台分发的可选包，
> npm 会按**构建宿主**挑选。在 x86_64 上建层会得到一套 `-linux-x64` 的包，
> 装到 arm64 手机上会在 host preparation 阶段致命退出。
> 该脚本现在**不需要** arm64 主机（用 `--os=linux --cpu=arm64` 交叉挑选即可），
> 但**必须显式声明目标平台**并自检。见 [docs/INCIDENTS.md](docs/INCIDENTS.md) 第 9 条。

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
