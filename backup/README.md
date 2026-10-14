# `:backup` — v5 备份归档的格式与预检

阶段 2.1–2.3 的落地模块。它**不**做备份计划、**不**做恢复、**不**碰运行环境；
它负责三件事：**把「一份备份是不是完整、自洽、没被篡改」变成可判定的**（2.1）、
**把「这次要备份哪些东西、哪些绝对不碰」变成可判定的**（2.2），
以及**把「机器本地凭据不进归档、而 provider 凭据还在」变成可判定的**（2.3）。

## 这个模块里的代码来自哪里

**包名就是答案，而且是刻意的。**

| 包 | 来源 | 性质 |
|---|---|---|
> ✅ **本模块已通过真实 CI**（run 38633884134，commit `c7e83fa`）：
> `:backup:testDebugUnitTest` **62 条测试全过**、`lintDebug`、`assembleDebug`、
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

> ⚠️ **诚实边界**：`app/proguard-rules.pro` 里给 SnakeYAML 加的
> `-keep class org.yaml.snakeyaml.**` 是**保险，不是修复** ——
> 它的**效果没有被验证**：CI 只构建 debug（`assembleDebug`），
> 而 release 混淆后的行为要等真的构建 release 并运行凭据投影才能说「可用」。
> 加它的理由：已解包核对 `snakeyaml-2.4.jar` 的 `META-INF`，
> **没有任何 consumer proguard 规则**；而本仓库 release 开了混淆，
> 上游 DSHA 的 release 是 `minifyEnabled false` ——
> 所以「DSHA 装了它能跑」不构成「它在 R8 下能用」的证据。

## 这个模块**不**做什么

明确划出去的部分，免得读者以为「格式与预检」等于「能备份能恢复」：

| 不做的事 | 归属 | 为什么现在不做 |
|---|---|---|
| 真正调用枚举与写入（`HostSnapshot.create`） | 阶段 2.4 | 需要逻辑根 → 本机路径的映射与隔离试运行；现在搬进来只会是「有代码没调用方」 |
| 凭据的**字段级**排除（`settings.yaml` 里的模型凭据、桥 token） | 仍未做 | 2.3 做的是**机器本地记录**的投影（见下）；`settings.yaml` 里是否有需要单独排除的字段，**要先在真机上确认它的实际内容**才能决定 |
| 逻辑根 → 本机路径的映射、隔离试运行、失败回切 | 阶段 2.4 | 切口是 DSHA `NativeRestorePlan.Mapping` 接口 |
| 自动备份计划（每日 / 1–168h / 停止后，保留 3 份） | 阶段 2.5 | 依赖 2.1–2.3 |
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
