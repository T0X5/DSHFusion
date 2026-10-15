# `:backup` — v5 备份归档的格式、预检、恢复核心与落点映射

阶段 2.1–2.4a 的落地模块。它**不**做备份计划、**不**做隔离试运行与失败回切、**不**碰运行环境；
它负责四件事：**把「一份备份是不是完整、自洽、没被篡改」变成可判定的**（2.1）、
**把「这次要备份哪些东西、哪些绝对不碰」变成可判定的**（2.2）、
**把「机器本地凭据不进归档、而 provider 凭据还在」变成可判定的**（2.3），
以及**把「恢复时先预检、再写平行候选、校验通过才谈提交」变成可判定的**（2.4a）。

## 这个模块里的代码来自哪里

**包名就是答案，而且是刻意的。**

| 包 | 来源 | 性质 |
|---|---|---|
> ✅ **本模块已通过真实 CI**（run 38633884134，commit `c7e83fa`）：
> `:backup:testDebugUnitTest` 测试全过（当时记的「62 条」是错的，实测反推应为 **63 条**）、
> `lintDebug`、`assembleDebug`、
> APK 产物身份核验，23/23 步绿。
> ⚠️ 但**没有在真机上验证过** —— 2.2 还没有导出/恢复入口。

| `com.deepseekharness.app.backup`（**13** 个类）<br>`com.deepseekharness.app.util`（`BackupScopes`、`FileKind`） | DSHA `467c7eb0` | **逐字节原样副本**（MIT，**17** 个文件）<br>**另有 4 份测试/类属于改写或适配** |
| `com.dshbox.app.backup`（`V5Archive.kt`、`DshBackupScope.kt`、`DshBackupExclusions.kt`） | DSHFusion | 本仓库自己写的 Kotlin |
| `com.dshbox.app.backup.CredentialProjectionCodesTest` | DSHFusion | 本仓库自写的测试（单列在 `dsha-imports.json` 的 `original` 段） |

阶段 2.1 引入的是容器与预检（`BackupArchive`/`BackupLimits`/`BackupJson`/`BackupControl`、
`BackupScopes` 与两份原始测试）；阶段 2.2 补上**枚举与写入**那一半
（`BackupFileSystem`/`BackupSource`/`FileBackupSource`/`HostSnapshot`/`GuestDataResolver`、
`FileKind` 与四份原始测试）。

> 主源码里 `.java` 一律是 DSHA 的逐字节副本，`.kt` 一律是本仓库自己写的 ——
> 这个命名约定让「哪段不是我们写的」在文件名层面就能看出来，
> 也正好是 `check-dsha-imports.py` 反向检查那条「导入目录里没有未登记 `.java`」的边界。

**3 份测试文件是「适配副本」**（`GuestDataResolverTest` / `BackupFileSystemAtomicTest` /
`HostSnapshotTest`），登记为 `derived` 的 `kind: adapted`。原因**不是测试写错**，
而是两个上游的工具链不同：

| | AGP | 单元测试编译时 `java.nio.file.Files` 来自 | Java 11 的 `readString`/`writeString` |
|---|---|---|---|
| DSHA | 9.1.1 | JDK | ✅ 有 |
| 本仓库（DSHBox 工具链） | 8.9.2 | `android.jar`（API 36） | ❌ 没有 |

所以 16 处 `Files.writeString(p, s)` / `Files.readString(p)` 改成机械等价的
`Files.write(p, s.getBytes(StandardCharsets.UTF_8))` /
`new String(Files.readAllBytes(p), StandardCharsets.UTF_8)`。
**为什么不把整块 `:backup` 改成纯 JVM 模块**（那样能逐字节保留）：阶段 2.4 要搬的
`BackupTree` / `AndroidBackupFileSystem` / `AndroidTreeFileSystem` 必须要 Android，
改过去早晚要改回来。

两类文件的**来源头不同**（逐字节副本 vs 适配副本），`check-dsha-imports.py`
**强制**各自出现在对应 `kind` 上 —— 否则文件可以自己声称是逐字节副本，
而没有任何东西会不同意。完整过程见 [../docs/INCIDENTS.md](../docs/INCIDENTS.md) 第 20 条。

包名保留 DSHA 原名不是疏忽。它让两件事变简单：

1. **「这段代码不是我们写的」在源码层面自带标识**，不需要靠注释或文档记得；
2. 将来比对 DSHA 上游改动时可以直接逐 blob 比，不必先做包名映射。

逐文件清单（含上游路径、锁定提交、blob 与 sha256）在
[`../governance/dsha-imports.json`](../governance/dsha-imports.json)，
由 [`../tools/check-dsha-imports.py`](../tools/check-dsha-imports.py) 核验 ——
**期望值内嵌在仓库里，所以在 CI 里是真跑**，不依赖仓库外的审计副本。

## v5 容器长什么样

```
"DSHADATA"           8 字节魔数
int32 = 5           格式版本
重复：
    int32  元数据长度（2 … 16384）
   bytes  元数据（有界 JSON：root / path / kind / scope / size / sha256）
    int64  载荷长度（必须与元数据里的 size 一致）
   bytes  载荷（FILE 记录逐块核 SHA-256）
int32 = -1          终止标记
    int32  清单长度（2 … 2 MiB）
   bytes  清单（有界 JSON）
bytes[32]           SHA-256（覆盖以上全部字节）
                    之后必须 EOF —— 多一个字节都算坏
```

设计上值得注意的三点（这也是它值得直接复用、而不是重写的原因）：

- **没有任意路径解压。** 记录里的 `path` 是相对逻辑根的路径，不是文件系统路径；
  校验会拒绝绝对路径、`..`、反斜杠、`C:` 前缀、空段、`\0`、`\n\r`，并按
  Unicode NFC + 小写做碰撞键 —— 所以「`A` 与 `a`」「`é` 与 `e\u0301`」
  不会被当成两个不同的项。
- **完整性是双向的。** 清单里的 `entries` / `bytes` 必须与逐条记录的实际累计相等；
  清单声明的逻辑根集合必须与记录里出现的根集合相等；非 `application` 范围的根，
  其记录不许带别的范围。**少报也是错**，不只是多报。
- **「假完整」被单独挡住。** 归档里只要有 `MISSING` / `UNREADABLE` 记录，
  就不允许声明 `QUIESCENT` / `EXTERNAL_CHECKED`；声明了就是 `FALSE_COMPLETE`。

## DSHFusion 加了什么

`V5Archive`（[`V5Archive.kt`](src/main/java/com/dshbox/app/backup/V5Archive.kt)）只做两件事：

- `hasMagic(header)` —— 这是不是一份未加密的 v5 归档；
- `verify(input)` —— 全程只读地核验，并返回 `V5ArchiveManifest`。

`verify` 传给容器的 visitor 的 `payload` **恒返回 null**。这是有意的：容器据此
走「只校验此记录」分支 —— 载荷照样逐块读、照样算 SHA-256、照样和记录声明比，
但**不写到任何地方**。所以它既给出真实统计，又不可能污染目标路径。

返回的 `usage` 是**按范围聚合的实际统计**（来自逐条记录），不是清单的自述。
这两者会不一样，而且差异有实际意义：清单的 `roots` 只声明了根，
一个声明为 `application` 的根底下可以装任意范围的记录。

## 阶段 2.2 加了什么：范围与排除

`DshBackupScope.kt` 是**改写后移植** DSHA 的 `BackupScope.java`（登记在
`dsha-imports.json` 的 `derived` 段 —— 正文不是逐字节副本，所以不能靠 sha256
证明「没改」，只能如实列清保留了哪条不变式、改掉了什么、为什么）。
`DshBackupExclusions.kt` 是本仓库自己写的排除清单。

### 范围

四个范围（`FULL`/`SESSIONS`/`SETTINGS`/`PLUGINS`），每个范围声明
「读取起点、合并位置、v5 记录范围」三元组。三条不变式由单测逐项钉住：

- **部分备份绝不叫全量的名字**（`DSHFusion-backup-` 只给 `FULL` 用，且各前缀互不包含）——
  否则老版本会把一份「只有会话」的归档按全量恢复，清掉配置与插件；
- **读取起点与合并位置逐项对应**（DSHA 的同名用例只比长度，挡不住顺序错位）；
- **记录范围必须是容器认识的，且部分范围不能声称自己能带别的范围的记录** ——
  后者直接对接 2.1 容器的 `ROOT_SCOPE_MISMATCH` / `UNKNOWN_SCOPE` 判定。

DSHA 的 `PLUGINS` 范围列了 `plugin-src` / `plugin-sources.json` / `plugin-history` /
`plugin-safe-mode.json`。**这四个在 DSHFusion 里根本不存在**（全库 grep 无引用）——
它们是 DSHA 自建插件管理的目录。本仓库的插件实体与清单在
`.dsh/profiles/<profile>/`（见 `PluginPaths.profileDir` / `nodeModulesDir`），
所以这里按本仓库的实证布局校正。照搬会备份一个永远为空的目录。

### 排除：`.dsh/dshbox` **不能**整目录排除

这是阶段 2.2 最有价值的一次审计结论，因为它推翻的是第一直觉。

`.dsh/dshbox` 从名字看就是「我方随包资产目录」，直觉是整目录排除。
但 `DshboxLayoutMigration` 的类注释写着：

> 三份层文件承载**用户状态**：官方插件开关、**哪些插件被停用**、绝对安全模式整层。
> 其中「被停用」是安全机制：丢了会把此前被隔离的坏插件重新加载 ——
> 真机上出过「每次都起不来」的事故。

那三份文件就在 `.dsh/dshbox/` 里面。所以排除必须**按子路径逐项**：

| 排除（重铺得出来） | 保留（看着像资产，其实是状态） |
|---|---|
| `.dsh/dshbox/bin` | `.dsh/dshbox/dsh-official-plugin` |
| `.dsh/dshbox/dshbox-plugins` | `.dsh/dshbox/plugin-market` |
| `.dsh/dshbox/pilot`、`mobile-adapt`、`mobile-pilot` | `.dsh/dshbox/safe-mode` |
| `.dsh/pnpm-store`（内容寻址的 pnpm store，可由锁文件重建） | `.dsh/dshbox/plugin-guard.json` |
| `.dsh/credentials`（整份凭据，字段级属于 2.3） | |

**`.dsh/bin` 刻意不排除。** 它没有命名空间 —— `SandboxService.removeLegacyAssetDirs()`
的注释明确写着「每次启动都无条件删会静默吃掉用户自己建的 `~/.dsh/bin`」。
同理，它可能是用户自己放东西的地方；丢用户数据比多备份一份脚本严重得多。

`DshBackupExclusions.checkInvariants()` 把「排除项写宽了」变成可执行断言，
`DshBackupExclusionsTest` 还会把每个必须保留路径的**每一个祖先目录**逐个问一遍 ——
有人把 `.dsh/dshbox` 加进排除清单时立刻红，并指出是哪个祖先。

## 阶段 2.3 加了什么：凭据投影（机器本地记录不进归档）

**问题**：`$DSH_HOME/.credentials.yaml` 里混着两类东西 ——

- **provider 凭据**（`refs:` 指向的 API Key）：**必须**跟着备份走，否则换机后要重填；
- **机器本地记录**（`records:` 里的 `client-connection/browser-session`，
  即「这台机器上的浏览器会话已授权」）：**不能**跟着备份走 ——
  它描述的是**这一台设备**的授权状态，带到另一台设备上要么无用、要么是**别人设备的会话**。

**做法**：不是删掉整个文件（那会连 API Key 一起丢），而是**投影** ——
解析 → 去掉机器本地记录 → 再序列化。`CredentialProjection` 就是干这个的，
它逐字节来自 DSHA（见下）。它认的机器本地前缀常量是 `"client-connection/"`，
与 DSH 自己的 `dsh-client-connection` 里 `credentialKey("client-connection", "browser-session")`
**逐字一致** —— 这不是巧合，是复用成立的前提。

**为什么必须用 YAML 解析器而不是字符串替换**：要改的是「哪个记录」，
而记录可能写成 flow mapping、可能带引号、可能带注释或锚点。
字符串替换会在这些形态上出错，而这是**唯一一处处理用户密钥的代码** ——
出错代价是密钥丢失或泄漏。因此引入 SnakeYAML 2.4（Apache-2.0，DIV-0020）。
**版本下限是硬要求**：`CredentialProjection` 用 `LoaderOptions` 的
`codePointLimit` / `maxAliasesForCollections` / `nestingDepthLimit` / `allowDuplicateKeys`，
这些是它抵御 YAML 炸弹与别名展开的边界 —— 1.x 上它们不存在，**编译期不会报错**。

**序列化的严格程度**（这些是它的安全边界，不是风格偏好）：

| 限制 | 值 | 后果 |
|---|---|---|
| 文件上限 | `1 MiB` | 超了报 `CREDENTIAL_PROJECTION_LIMIT`，**在解析前** |
| 重复键 | 拒绝 | 否则「后一个覆盖前一个」会被用来夹带 |
| 锚点 / 别名 | 拒绝（`maxAliases 0`） | 否则可用别名展开炸内存 |
| 嵌套深度 | 32 | |
| 显式标签 | 拒绝 | 否则可触发任意类构造 |
| 非有限浮点 | 拒绝 | |
| 非法 UTF-8 | 拒绝 | `CREDENTIAL_PROJECTION_ENCODING`，也在解析前 |
| 诊断信息 | **绝不带出密文** | 见 `dsha-imports.json` 里对这条的说明 |

**没有机器本地记录时逐字节不变**：投影发现「没有可删的记录」就**原样返回输入**，
不做无谓的重新序列化 —— 避免每次备份都产生无意义的字节差异
（那会让「备份内容变了没有」这个问题失去意义）。

**一处真实的覆盖损失，已登记**：上游有一条测试
`oldV5ArchiveMachineRecordIsProjectedInCandidateBeforeItCanBecomeActive`
证明「机器本地记录在**候选阶段**就被投影掉」，而它依赖
`NativeRestorePlan` → `BackupTree` → `AndroidTreeFileSystem` ——
正是阶段 2.4 才搬的那条 Android 耦合链。所以 **2.3 删掉了这一条测试方法**，
并把「它证明的性质在 2.3 没有被验证」写进 `dsha-imports.json` 的
`changed` 与 `not_imported`（补回阶段 2.4）。
**删测试而不留痕等于覆盖静默流失**，所以 `verify-phase0.sh` 第 5m 节专门断言这条登记完整。

**上游的断言太弱，因此另写了一条**：上游对 5 种坏输入只断言
`startsWith("CREDENTIAL_PROJECTION_")` —— 那只能证明「被拒绝了」，
证明不了「因为正确的原因被拒绝」。`CredentialProjectionCodesTest.kt` 按**实测的**
具体错误码逐条钉住（实测怎么来的见 [../docs/INCIDENTS.md](../docs/INCIDENTS.md) 第 22 条：
我按源码推了三个码，**三个全错**）。

**这个模块怎么被验证**（本机就能跑，不依赖 Android）：

```bash
bash tools/run-backup-jvm-tests.sh     # 编译并运行 backup 的全部 .java + 行为探针
```

它取 junit/hamcrest/gson/snakeyaml 四个 jar（放进 `.jvm-test-cache/`，已 gitignore），
**版本必须与 `gradle/libs.versions.toml` 一致** —— 脚本自己 grep 版本目录，对不上退 2；
取不到 jar 也**退 2，不静默跳过**。然后跑 `tools/check-credential-projection.java`，
它核验的是**行为**：机器本地记录真的消失了、provider 凭据真的还在、`version: 1` 真的还在、
没有机器记录时真的逐字节不变、坏输入的诊断里真的没有密钥。

### Kotlin 测试只能在 CI 跑 —— 而 CI 的绿**不**作证它们跑过

`tools/run-backup-jvm-tests.sh` 只编译 `.java`（本机没有 kotlinc），所以
`CredentialProjectionCodesTest.kt` 这 **8** 条**只能**在 `android.yml` 里跑。
而 `Run unit tests` 那一步通过**不能**说明它们跑了 ——
**Gradle 只在测试失败时打印名字**，通过的一律沉默。整份 CI 日志里 grep
`CredentialProjectionCodesTest` 是 **0 次**。

所以 `android.yml` 里另加了一步，解析 `backup/build/test-results/**/*.xml`：

- 打印**每个测试类各跑了多少条**（把「跑了什么」变成日志里的可见事实）；
- `CredentialProjectionCodesTest` 必须出现，且必须是 **8 条**；
- 总数不得低于阶段 2.3 的**实测**总数 **76** 条。

> **这两个数字的第一版都是错的**（我写的是 7 与 62）—— 而断言第一次真跑就红了，
> 报「跑了 8 条，期望 7 条」。见 [../docs/INCIDENTS.md](../docs/INCIDENTS.md) 第 27 条。

> 这一步做过反向验证（从 YAML 里把那段 Python 原样抽出来，配四组夹具）：
> 正确→通过；新测试缺席→红；只有 6 条→红；总数缩到 17→红。
> 详见 [../docs/INCIDENTS.md](../docs/INCIDENTS.md) 第 26 条。

**推广规则：任何新增的、本机跑不到的测试，都必须同时加一条「它确实跑了」的断言** ——
否则测试数量可以降到 0 而 CI 依然全绿。

> ⚠️ **诚实边界**：`app/proguard-rules.pro` 里给 SnakeYAML 加的
> `-keep class org.yaml.snakeyaml.**` 是**保险，不是修复** ——
> 它的**效果没有被验证**：CI 只构建 debug（`assembleDebug`），
> 而 release 混淆后的行为要等真的构建 release 并运行凭据投影才能说「可用」。
> 加它的理由：已解包核对 `snakeyaml-2.4.jar` 的 `META-INF`，
> **没有任何 consumer proguard 规则**；而本仓库 release 开了混淆，
> 上游 DSHA 的 release 是 `minifyEnabled false` ——
> 所以「DSHA 装了它能跑」不构成「它在 R8 下能用」的证据。

## 阶段 2.4a 加了什么：恢复核心（预检 → 候选 → 校验）

**这是本模块第一条会**毁数据**的路径。** 2.1–2.3 只判断「一份备份是不是好的」，
2.4a 开始**往活动目录写东西** —— 所以它的安全性要求与前面几项不是一个量级：
校验失败时必须**一个字节都没写进活动目录**，而不是「写了一半再回滚」。

**做法照搬 DSHA 的三步次序**（这是它成熟的地方）：

1. **预检**（`NativeRestorePlan.inspect`）：只读地看归档与目标，产出计划，**不碰活动数据**；
2. **候选**（`buildCandidates`）：把内容写到一个**平行的候选目录**，不是活动目录；
3. **校验**：候选构建完再核对，通过了才谈提交。

**搬了什么、为什么这么搬** —— 这一段的结论与第一版审计**不同**，
被推翻的地方在 [docs/FUSION-PLAN.md](../docs/FUSION-PLAN.md) 的 2.4 节有完整记录：

| 上游文件 | 怎么处理 | 为什么 |
|---|---|---|
| `NativeRestorePlan.java`(402) | **逐字节** | 只依赖同包已有类 + `BackupTree` |
| `DataFormatEvidence.java`(55) | **逐字节** | `NativeRestorePlan` 的依赖（审计第一版漏了它，是编译报错逼出来的） |
| `RuntimeDescriptor.java`(154) | **逐字节** | 上面那个的依赖，纯 `java.io`/`java.util` |
| `BackupTree.java`(304) | **适配** | 摘掉 Android 快路径，见下 |
| `AndroidBackupFileSystem`+`AndroidTreeFileSystem`+`NativeStorage`(1418) | **不导入** | 需要骨干没有的 `libtermux.so`，见下 |
| `NativeRestoreTargets.java`(301) | **不导入** | 依赖 DSHA 自己的设置/凭据子系统（`ConfigStore`→`KeyVault`），见下 |

### `BackupTree` 为什么可以摘掉 Android 那半，以及**不能**顺手摘掉什么

上游的 `digest()` 是「Android 快路径 + 通用回退」：

```java
if (fs.getClass() == AndroidBackupFileSystem.class && fs.stat(root).type.equals("DIRECTORY")) {
  try (AndroidTreeFileSystem tree = new AndroidTreeFileSystem(root)) {
    return digestTree(tree, root, control);
  }
}
return digestTree(fs, root, control);          // ← 通用回退，功能完整
```

两条路径**共用同一个 `TreeHash`/`visit`**，而且分支条件只取决于 `fs` 的类型 ——
**任何一次调用里两侧必然走同一分支**，所以摘掉它不会造成「一侧快、一侧通用」的不一致。
它只是吞吐优化。

**但它拖进来的东西是 1418 行，其中 `NativeStorage` 调 `System.loadLibrary("termux")`
并声明 7 个 native 方法**（`statChildrenBatchBytes`/`prepareSmallFileBytes`/…）。
这些符号在 DSHA 的 `libtermux.so` 里，而**骨干没有这个库**（只有 `libproot` 系列）。
搬进来会得到「编译通过、运行必 `UnsatisfiedLinkError`」的死代码 ——
这正是本项目最怕的那种东西：**看起来接好了，真机上才炸**。
何况 DSHA 是 proroot、DSHBox 是 proot，这条快路径在我们的运行模型里本来就不适用。

> **摘干净了没有，有断言管着**：`verify-phase0.sh` 的 5n 节既检查 `BackupTree` 里
> 不再出现 `AndroidTreeFileSystem`/`AndroidBackupFileSystem`/`loadLibrary`，
> **也检查 `ENTRY_LIMIT`/`INVALID_CHILD`/`SPECIAL_FILE`/`SOURCE_CHANGED`/`TreeHash` 还在** ——
> 只查「Android 没了」会允许「顺手把限额记账也删了」。

> ⚠️ **残留风险，如实记在 `dsha-imports.json` 的 `derived` 条目里**：两条路径的目录枚举
> 顺序是否完全一致**我们没有验证**（验证它需要那个原生库）。这不影响当前用法
> （每次比较两侧都走同一实现），但它是这条适配的已知缺口。

### 2.3 删掉的那条测试，在 2.4a 回来了

2.3 删掉 `oldV5ArchiveMachineRecordIsProjectedInCandidateBeforeItCanBecomeActive`
是因为它依赖 `NativeRestorePlan`，而当时的闭包会拖进 `AndroidTreeFileSystem`。
摘掉快路径之后这条链变成纯 JVM，于是**恢复回去、在本机真跑 —— 通过了**。
它证明的性质（**归档里的机器记录在候选阶段就被投影掉，来不及变成活动记录**）
在 2.3 确实没有被验证，现在被验证了。

**本机 Java 测试：33 → 42 条**（+`NativeRestorePlanTest` 的 8 条 + 恢复的这 1 条）。

> ✅ **阶段 2.4a 已通过真实 CI**（run [38647873314](https://github.com/T0X5/DSHFusion/actions/runs/38647873314)，
> commit `0629409`）：24/24 步全绿，`:backup` **85 条测试 / 12 个测试类**。
> 其中 `NativeRestorePlanTest` 的 8 条由 `android.yml` **单独钉住条数** ——
> 「总数够了」不代表「新那 8 条在里面」（`docs/INCIDENTS.md` 第 26 条）。
> **仍未在真机上验证过**：还没有导出/恢复的界面入口。

### 8 条恢复测试各自钉住什么

它们全部来自 DSHA，一条未改（除工具链差异）：

- 只读检查与候选构建**不碰活动数据**，未提及的文件不动；
- **footer 校验失败绝不产生活动写**（这是最要紧的一条）；
- 未选中的范围不会变成「应用恢复」；
- 相对链接逃逸被拒；
- 缺失链接目标的祖先**保留元数据但不建目录**；
- 普通文件的链接目标父目录仍被拒；链接目标的符号链接父目录**不被跟随**；
- 可用链接目标仍会建立链接。

## 阶段 2.4b 加了什么：落点映射（逻辑根 → 本机路径）

**只做了第一半。** 2.4b 完整的样子是「落点映射 + 隔离试运行 + 失败回切」；
这一节讲的是**落点映射**，提交与回切**尚未实现**（见本文件末尾的「不做什么」）。

### 为什么这个类是**我们自写**的，而不是移植 DSHA 的

DSHA 的对应实现是 `NativeRestoreTargets`（301 行）。第一版审计给它写的理由是
「它依赖 `ConfigStore`/`KeyVault`」—— 而那甚至不是最强的理由。读完代码后：

| # | 理由 | 证据 |
|---|---|---|
| ① | **逻辑根词汇根本不同（决定性）** | 归档根名 = `BackupSource.id()`（`HostSnapshot.java:43`/`:220`），而 `BackupSource.description()` 只给 `id`/`scope`/`external`（`BackupSource.java:62-68`）。DSHA 的 `target()` 第一句是 `BackupJson.string(root, "logicalKind")` —— **我们的根没有这个键，它会直接抛** |
| ② | 目的地路径不同 | 它写 `runtime-current/base`、`files/user-data-v5/dsh`、`files/plugin-imports/<task>`；我们的是 `user-data/.dsh` |
| ③ | 设置钩子不同 | 它走 `ConfigStore`（SharedPreferences + Keystore，817 行）；骨干是 Kotlin + DataStore |

**这是两套归档词汇，不是同一套的两个实现。** DSHA 认的根是
`dsh-home`/`dsh-child`/`native-settings`/`plugin-store`/…，其中 `dsh-child` 是
「`.dsh` 的子条目**逐个成根**」；我们是「一个 `DshBackupMember` 一个根」。
所以 `NativeRestoreTargets` 登记为 `not_imported`，我们另写
[`HostRestoreMapping.java`](src/main/java/com/dshbox/app/backup/HostRestoreMapping.java)。

### 为什么这个类是 Java，而不是像 `DshBackupScope.kt` 那样用 Kotlin

**为了能本机真跑。** 本机有完整的 JDK 17，但**没有 kotlinc**（`docs/INCIDENTS.md` 第 21 条）。
写成 Kotlin 的话，这个类只能由 CI 编译 —— 而它是**会毁数据的那条路径**（它决定往哪写）。
`tools/run-backup-jvm-tests.sh` 只编译 `backup/src` 下的 `.java`，所以写 Java 换来的是
「落点对不对」这个问题能在两秒内得到答案。

代价是它破了「`.java` 在 `com/deepseekharness/app/`、我们的在 `com/dshbox/app/*.kt`」这条
语言约定。**换来的是可验证性，这个交换是划算的**，但它必须被显式登记 ——
`governance/dsha-imports.json` 的 `original` 段如实列了这两个文件（自写、无上游对应物），
而 `check-dsha-imports.py` 的反向扫描据此把它们算作「已登记」而不是「上游文件漏登记」。

> ⚠️ 那次放宽**必须**有界：一个既不在 `files`、也不在 `derived`、也不在 `original` 里的
> `.java` **仍然会红**。`tools/verify-phase0.sh` 的 5o 节用一个真实注入证明这一点
> （放一个 `UnregisteredProbe.java` 进去，检查器必须失败）。

### 落点：`user-data/.dsh`（而且它只有一个事实来源）

```
guest /root/projects   ←PRoot bind→   宿主 filesDir/user-data      (SandboxFiles.PathMapper)
DSH_HOME = /root/projects/.dsh        →  宿主 filesDir/user-data/.dsh (SandboxService.kt:1017)
```

所以 `DshBackupMember.path`（`.dsh`、`.dsh/sessions`、…）**可以直接当相对 `user-data`
的宿主路径用** —— 不需要第二套映射表。2.2 写这句话时是对的，2.4b 复核后仍然成立。

`mergeSubdir` 不再是构造函数参数，而是**由 `path` 推导**
（`.dsh` → `""`，`.dsh/x/y` → `x/y`）。理由就是本仓库事故表第 17/20 条的成因：
「同一个事实写两遍」迟早会分叉。少一个来源，就少一类事故。

### 一个成员一个 group（与 DSHA 的一处刻意不同）

`NativeRestorePlan.Target` 支持「多个根共享同一个活动目标」（`group` + `prefix`）。
DSHA 用它把 `.dsh` 的子条目并进一个 group。**我们不这么做**：

| | 整个 `.dsh` 一个 group | 一个成员一个 group（采用） |
|---|---|---|
| `read`（合并来源） | 必须是 group 根 → **只恢复 sessions 也会把整个 `.dsh` 拷进候选**（含凭据、插件实体） | 成员自己的路径 |
| `before`/`readBefore` | 整个家目录一个摘要 → 任何改动都报 `TARGET_CHANGED` | **成员级**摘要 → 只有被动过的成员才报 |
| 提交半径 | 整个家目录 | 一个成员 |
| `memberRead` | 需要（为 group/prefix 那套准备） | 不需要，传 `null` |

### `target()` 的三条判定（都有测试）

1. **认不出的根 → 跳过，不猜。** 归档可能来自更新的版本。返回 `null` +
   `skippedReason = "ROOT_NOT_IN_MEMBER_TABLE"`（刻意的**具体**码，不复用默认的
   `ROOT_REQUIRES_DESTINATION` —— 「不在我的表里」与「需要用户先选目的地」是两件事）。
2. **认得出的根、范围对不上 → 报错。** `ROOT_SCOPE`。那说明这份归档要么不是这个范围打的，
   要么被人改过，两种情况都不该继续。
3. **落点必须在 `user-data` 之内。** `TARGET_OUTSIDE_APP`。成员表是编译期常量，但它是
   **数据** —— 一个手误的 `..` 会让恢复写到别人的目录里，所以构造时就炸。

### 端到端往返：2.1 → 2.4a → 2.4b 第一次串起来

`HostRestoreMappingTest` 里最重要的不是字段断言，而是两件事：

1. **往返真的通**：用我们**自己的** `HostSnapshot` 打一份归档，再用**我们自己的** mapping
   预检并构建候选，然后**直接在磁盘上核对候选树的字节与位置**。
   「两个模块各自的单测都过」证明不了它们能对接 —— 这正是事故表第 17 条的形态。
2. **注入**：预检之后改动活动目录，`buildCandidates` 必须报 `TARGET_CHANGED`，
   **且不留下候选树**。这是「会毁数据的那条路径」最基本的自我保护，而且它是注入出来的。

本机真跑：**53 条**（2.4a 是 42；新增 11 条）。成员表与 `DshBackupScope` 的一致性由
`tools/check-backup-contract.py` 逐项核验（6 个成员），并且**做过漂移注入自证**。

> ✅ **已通过真实 CI**（run [38651955250](https://github.com/T0X5/DSHFusion/actions/runs/38651955250)，commit `0aea1dd`）：
> **24/24 步全绿**，`:backup` **96 条测试 / 13 个测试类**（2.4a 是 85 / 12），
> 产物 128,789,923 字节。计数断言这次报的是 **96**，与预测一致。
>
> ⚠️ 但**提交与回切还没写** —— 本模块现在能算「往哪写、候选树长什么样」，**还不能真的写**。
> 真机验证更是没有：还没有界面入口。

## 阶段 2.4b-② 加了什么：提交与回切（唯一真的会动活动目录的代码）

**这一段我写错过一次，而且错得最贵，所以先说它。**

第一版我**从零手写了 350 行** `HostRestoreCommit.java`，并在类注释与
`dsha-imports.json` 里写下理由：「上游把它放在 `HostDataTransaction` 里，
**那个类依赖它自己的 `ConfigStore`/`KeyVault`**，没搬。」

**那个理由是错的 —— 我写下它的时候没有读那个文件。** 实测：`HostDataTransaction`
只有 `java.io`/`java.util` 两个 import，一次都没提到 `ConfigStore`/`KeyVault`/`CredentialRead`，
同包依赖的前五个我们都已经有了。**它就在可达范围内**，而且比我写的那份**更严密**。
手写的那一份已删除，不再保留两套。见 [`docs/INCIDENTS.md`](../docs/INCIDENTS.md) 第 33 条。

### 现在的落点：上游逐字节导入 + 一层很薄的适配

| 文件 | 身份 |
|---|---|
| [`HostDataTransaction.java`](src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java)（315 行） | DSHA **逐字节**导入 |
| `TransactionMarkers.java`（34 行）、`util/Ids.java`（13 行） | DSHA **逐字节**导入 |
| `HostDataTransactionTest.java`（202 行 / 5 条，含一条**真杀子进程**的） | 适配后导入（11 处工具链替换） |
| [`HostRestoreTransaction.java`](src/main/java/com/dshbox/app/backup/HostRestoreTransaction.java) | **本仓库自写**：词汇映射 |
| `HostRestoreTransactionTest.java`（7 条） | **本仓库自写**：集成 + `CROSS_DEVICE` + 设置轴 |

**两边的词汇恰好同构**，所以适配层很薄：

| 上游 | 我们 |
|---|---|
| `directory/candidate/<id>` | `NativeRestorePlan` 的 `task/candidate/<group>`（逐字同名） |
| `Targets.resolve(rootId)` | `plan.targets.get(group).write` |
| `prepare(roots, expectedBefore, …)` 的 `expectedBefore` | `plan.before` |
| `Settings`（设置轴） | **我们没有这个轴**（见下） |

> ⚠️ 事务目录名必须是 **UUID 形状** —— 上游用它给事务命名，而那个名字会被写进
> `plan.json` 与每个相位标记。适配层提前用 `TASK_NAME_NOT_UUID` 拦下，
> 而不是让上游抛 `TRANSACTION_ID`。

### 原子性靠什么 —— 先把话说明白

候选树是在活动目录**之外**完整建好、逐文件校验过 sha256 的（2.4a 做的）。
提交就是两次同文件系统内的 `move`（rename）：

```
move(active, task/previous/<id>)   // 旧内容挪到一边
move(candidate, active)            // 候选挪进来
```

**rename 是原子的**，所以活动树在任何瞬间要么是旧的整棵、要么是新的整棵 ——
**不存在半新半旧**。真正要处理的是另外两件事：

| 失败形态 | 处理 |
|---|---|
| 两次 rename 之间进程被杀 → **目标不存在** | 提交前写**相位标记**；恢复时按相位 + 摘要双判，把原件移回来 |
| 提交成功但 `previous/` 没来得及删 | 恢复时按摘要判定，再删 |

### 上游比我手写的那份严密在哪（逐条）

| | 我手写的 | `HostDataTransaction` |
|---|---|---|
| 提交前重验 | 只重验 `before` | 重验 `before` **和** `after`（`CANDIDATE_CHANGED`） |
| 嵌套目标 | 没有 | `OVERLAPPING_TARGETS`（父目录与子目录互为目标） |
| 持久标记 | 一个 `commit-journal.json` | 五相位标记（`verified`→`switching`→`files-committed`→`settings-committed`→`finalized`） |
| 恢复判定 | 只看摘要 | **相位 + 摘要**双判 |
| 崩溃测试 | 注入（而且第一版注错了，见第 31 条） | **真杀子进程**（`destroyForcibly`） |

### 适配层只加了两样东西，都不是改写上游

**① `CROSS_DEVICE` —— 上游没有这条检查。**

`HostDataTransaction` 直接 `fs.move`；跨文件系统时 `move` **不报错**，
只是退化成拷贝加删除 —— 于是「两次 rename 换进去」这个原子性**静默消失**，
而所有测试仍然全绿。补在适配层，上游那个类保持逐字节不动。
它必须在 `prepare` 之前跑，所以适配层把它单独暴露成 `checkSameFileSystem()`。

**② 设置轴故意做成「非空就抛 `SETTINGS_NOT_SUPPORTED`」。**

上游会在提交时一并切 `settings.json`，并有三处断言
（`SETTINGS_CHANGED` / `SETTINGS_COMMIT_VERIFICATION`）。我们的恢复路径只写用户数据。
若给它一个「恒空表、`apply` 什么都不做」的实现，那三处会**永远通过** ——
那就是「没有输入空间的断言」（第 30 条）。所以反过来：空表才接受，非空直接红。
将来真做设置轴时它会红，而不是静默丢掉用户的设置。

### 第二条前提同时是一条**运行约束**

| 前提 | 不成立时会怎样 | 检查 |
|---|---|---|
| `task` 与活动目录**同一个文件系统** | `move` **不报错**，它只是退化成拷贝 —— 原子性静默消失 | `CROSS_DEVICE`（适配层） |
| 候选建好后活动目录**没被改过** | 提交会**静默覆盖**用户在等待期间写进去的东西 | `TARGET_CHANGED`（上游） |

合并发生在 `buildCandidates` 那一刻，之后写进活动目录的东西不在候选里。
适配层只能做到「不静默地违反它」，**保证它不发生是调用方的责任** ——
恢复期间 DSH 应当停止，或至少目标成员不能被写。

### 测试：故障注入按**命名边界**，不按计数

| 注入点 | 必须发生什么 |
|---|---|
| `new-<id>` | 旧内容从 `previous/` **挪回来**，`recover()` 幂等 |
| `new-<id>` + `rollback-*` 全失败 | 回切也失败 → 异常挂成 **suppressed**（不吞）→ 现场留在磁盘 → `recover()` 救回 |

> ⚠️ **第一版注入没有注到它以为的地方。** 它数的是「第 N 次 `move`」，
> 而 `fs.atomic()` 内部也在 `move`（发布日志、轮转 `.previous`），
> 于是 `failAt=2` 实际打在 `move(active, previous)` 上 —— **三个用例当时都是绿的，
> 却一个都没测到它名字说的事。** 现在用上游的 `Fault.boundary(name)`：
> 边界名由**被测代码自己给出**，从根上不会有那个病。
> 见 [`docs/INCIDENTS.md`](../docs/INCIDENTS.md) 第 31 条。

**上游那份测试和我们这份分工不同**：上游测的是**事务本身**（用它自己手搭的
`sessions`/`settings` 两根），我们那份测的是**「接得上」** ——
① 我们的候选树路径与上游 `candidate(id)` 同构吗；② `plan.before` 能直接当
`expectedBefore` 吗；③ 适配层自己加的 `CROSS_DEVICE` 真的会在动任何东西之前拦下吗
（上游没有这条检查，所以**它那边永远测不到**）。

本机真跑：**65 条**（2.4b-① 是 53；`−9` 删掉手写的，`+5` 上游那份，`+7` 我们这份）。

> ✅ **阶段 2.4b-② 已通过真实 CI**（run [38655637817](https://github.com/T0X5/DSHFusion/actions/runs/38655637817)，
> commit `9d02c9f`，**手写那一版**）：**24/24 步全绿**，`:backup` **105 条测试 / 14 个测试类**
> （日志逐类打印 `9 com.dshbox.app.backup.HostRestoreCommitTest`），
> 产物 `dshapp-debug-apk` 128,789,786 字节。Contracts 4/4 全绿。
> **本轮换成移植版之后的新计数是 108（Java 65 + Kotlin 43），CI 待跑。**
>
> ⚠️ **CI 绿 ≠ 真机安全。** 这是本模块唯一真的会动活动目录的代码，
> 而它的真机行为**一次都没被观察过** —— 还没有界面入口。

## 这个模块**不**做什么

明确划出去的部分，免得读者以为「格式与预检」等于「能备份能恢复」：

| 不做的事 | 归属 | 为什么现在不做 |
|---|---|---|
| ~~逻辑根 → 本机路径的映射（`NativeRestoreTargets` 那一层）~~ | ✅ **阶段 2.4b-① 已做** | 见上：`HostRestoreMapping.java`（自写）。审计推翻了「依赖设置子系统」这个理由 —— 真正的原因是**逻辑根词汇不同** |
| 凭据的**字段级**排除（`settings.yaml` 里的模型凭据、桥 token） | 仍未做 | 2.3 做的是**机器本地记录**的投影（见下）；`settings.yaml` 里是否有需要单独排除的字段，**要先在真机上确认它的实际内容**才能决定 |
| ~~把候选树提交到活动目录，以及失败回切~~ | ✅ **阶段 2.4b-② 已做** | 见下节。`NativeRestorePlan.buildCandidates()` 只写 `task/candidate/`（读完全部 405 行确认），所以 2.4a 是安全的；提交由上游的 `HostDataTransaction` + 我们的适配层补上。
| ⚠️ **恢复期间目标必须静止** | 调用方的责任 | 合并（把活动目录拷进候选）发生在 `buildCandidates` 那一刻，之后写进活动目录的东西**不在候选里**。提交前会重新核验摘要并报 `TARGET_CHANGED` —— 但这个类只能做到「不静默地违反它」，做不到「保证它不发生」。**恢复时 DSH 应当停止**，或至少目标成员不能被写 |
| ~~回切落在 `runtime-current/` ↔ `runtime-new/`~~ | ❌ **这个落点被审计推翻** | 那对槽换的是 base/node/dsh 三层**运行环境**（`BundleManager.promoteNewSlotToCurrent()`），而恢复写的是**用户数据** `user-data/.dsh`。用「换整个系统」去实现「换一个目录」是错的；环境更新属于阶段 3 |
| 自动备份计划（每日 / 1–168h / 停止后，保留 3 份） | 阶段 2.5 | 依赖 2.1–2.4 |
| 范围与排除的界面文案 | 阶段 2.4 | DSHA 的 `label()`/`describe()` 走它自己那套 i18n；本仓库用 Android 资源 |

DSHA 侧同样属于 v5 预检、但**刻意没搬**的类，逐条理由记在
[`../governance/dsha-imports.json`](../governance/dsha-imports.json) 的 `not_imported` 里。

## 为什么是 `com.android.library` 而不是 `java-library`

这里一行 Android API 都不用。但本项目 CI 跑的是 `./gradlew testDebugUnitTest`，
纯 JVM 模块的测试任务叫 `:backup:test`，**不会被这个任务名匹配到** ——
那些负面用例（截断、篡改、假完整、范围夹带）会在 CI 里一次都不执行。

这与 [`../docs/INCIDENTS.md`](../docs/INCIDENTS.md) 第 13 条是同一类问题：
**一个不被执行的门禁等于没有门禁。** 宁愿多一个空壳 Android 模块。

## 测试

```bash
./gradlew :backup:testDebugUnitTest
```

- `CredentialProjectionTest` —— 原样导入的适配副本（3 条语句因工具链差异机械替换）；
  **上游原本还有 1 条测试方法，2.3 删掉了**，理由见上面「一处真实的覆盖损失」。
- `CredentialProjectionCodesTest` —— 本仓库自写。上游对坏输入只断言「前缀是
  `CREDENTIAL_PROJECTION_`」，那条断言**证明不了「因为正确的原因被拒绝」**，
  所以这一份按**实测的**具体错误码逐条钉住。
- `V5ArchiveTest` —— DSHFusion 侧，钉**具体错误码**而不是「抛了异常就行」；
- `BackupArchiveTest`、`BackupJsonTest` —— 原样导入的负面用例，覆盖截断到每一个
  长度、篡改、尾部多余字节、路径穿越与重复、假完整、深层嵌套 JSON、范围夹带。

`V5ArchiveTest` 里有两种夹具，分工不能混：

- `archive(...)` 走真实 `BackupArchive.Writer`，证明**写入侧在我们这里可用**；
- `forge(...)` 手写组装容器，用来构造**写入器自己就会拒绝**的恶意输入
  （写入器的 `finish` 做同一套一致性校验）。没有 `forge`，
  「假完整」与「范围夹带」这两条就只能在构造夹具阶段抛错，根本测不到读取侧。

`forgeProducesAContainerTheReaderAccepts` 是这些负面用例的地基：如果手写组装
本身不合规，那些用例会因为「夹具坏了」而红，而不是因为读取侧挡住了攻击。
