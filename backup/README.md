# `:backup` — v5 备份归档的格式与预检

阶段 2.1 的落地模块。它**不**做备份计划、**不**做恢复、**不**碰运行环境；
它只负责一件事：**把「一份备份是不是完整、自洽、没被篡改」变成可判定的**。

## 这个模块里的代码来自哪里

**包名就是答案，而且是刻意的。**

| 包 | 来源 | 性质 |
|---|---|---|
| `com.deepseekharness.app.backup`（5 个类）<br>`com.deepseekharness.app.util.BackupScopes` | DSHA `467c7eb0` | **逐字节原样副本**（MIT） |
| `com.dshbox.app.backup`（`V5Archive.kt`） | DSHFusion | 本仓库自己写的 Kotlin 门面 |

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

## 这个模块**不**做什么

明确划出去的部分，免得读者以为「格式与预检」等于「能备份能恢复」：

| 不做的事 | 归属 | 为什么现在不做 |
|---|---|---|
| 决定备份哪些逻辑根（全量 / 只对话 / 只插件 / 只设置） | 阶段 2.2 | 那是范围决策，与格式无关 |
| 凭据排除（桥 token、`.credentials.yaml` 字段级） | 阶段 2.3 | 安全项，独立可测 |
| 逻辑根 → 本机路径的映射、隔离试运行、失败回切 | 阶段 2.4 | 切口是 DSHA `NativeRestorePlan.Mapping` 接口 |
| 自动备份计划（每日 / 1–168h / 停止后，保留 3 份） | 阶段 2.5 | 依赖 2.1–2.3 |
| 写入侧的目录枚举 | 阶段 2.2 | 容器可写且已验证，但枚举策略会与 2.2 的范围决策打架 |

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
