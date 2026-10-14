# `:backup` — v5 备份归档的格式与预检

阶段 2.1 与 2.2 的落地模块。它**不**做备份计划、**不**做恢复、**不**碰运行环境；
它负责两件事：**把「一份备份是不是完整、自洽、没被篡改」变成可判定的**（2.1），
以及**把「这次要备份哪些东西、哪些绝对不碰」变成可判定的**（2.2）。

## 这个模块里的代码来自哪里

**包名就是答案，而且是刻意的。**

| 包 | 来源 | 性质 |
|---|---|---|
| `com.deepseekharness.app.backup`（11 个类）<br>`com.deepseekharness.app.util`（`BackupScopes`、`FileKind`） | DSHA `467c7eb0` | **逐字节原样副本**（MIT，15 个文件）<br>**另有 3 份测试是「适配副本」** |
| `com.dshbox.app.backup`（`V5Archive.kt`、`DshBackupScope.kt`、`DshBackupExclusions.kt`） | DSHFusion | 本仓库自己写的 Kotlin |

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

## 这个模块**不**做什么

明确划出去的部分，免得读者以为「格式与预检」等于「能备份能恢复」：

| 不做的事 | 归属 | 为什么现在不做 |
|---|---|---|
| 真正调用枚举与写入（`HostSnapshot.create`） | 阶段 2.4 | 需要逻辑根 → 本机路径的映射与隔离试运行；现在搬进来只会是「有代码没调用方」 |
| 凭据的**字段级**排除（`settings.yaml` 里的模型凭据、桥 token） | 阶段 2.3 | 2.2 只做了整份 `.dsh/credentials` 的排除，不声称「归档里一定没有密钥」 |
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
