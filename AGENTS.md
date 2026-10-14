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

**阶段 1 完成（分支 `phase-1/import-dshbox-base`，PR #1 待审，main 未动）；
阶段 2 已开始（分支 `phase-2/backup-v5`，2.1 见下）。**

上游 790 个跟踪文件 → 排除 23 个（`website/`、`pages.yml`，DIV-0005）→ 归档 2 个
（`README.md`、`.gitignore`）→ **实际导入 765 个文件、25.4 MB**。
19 处偏离已重放，见 [governance/divergences.json](governance/divergences.json)。

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
> 主模块每次都编译通过）。**本机没有 JDK/SDK/Gradle，Kotlin 编译不了**，
> 所以「本机 166 项通过」只覆盖脚本与字节层面，**编译与运行层面的结论只能由 CI 给出**。
> 每一条负面用例红了都要先问「它证明的是被测代码错了，还是我的夹具坏了」——
> 第 17 条就是后者，而当时那三条 ComparatorFailure 长得完全像「拦截成功」。

**阶段 2.2 已实现（2026-10-14，分支 `phase-2.2/scope-backup`）**：范围模型与排除清单。

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

**阶段 2.3–2.5 未做**：凭据排除、隔离试运行与失败回切、自动计划。逐条理由见
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

### 事故与真机验证记录（明细见 [docs/INCIDENTS.md](docs/INCIDENTS.md)）

**这一节只留索引。** 每次踩坑的完整过程、原始报错、复现命令与故障注入结果
都移到了 [docs/INCIDENTS.md](docs/INCIDENTS.md) —— 因为本节曾把 AGENTS.md 撑到
超过指令预算而被**静默截断**（截掉的正是后面的「编码约定」与「已知陷阱」）。
**规范放这里，明细放那边**，两边都不再溢出。

| # | 事件 | 真机现象 | 根因 | 偏离 | 结论 |
|---|---|---|---|---|---|
| 1 | 首次真实 CI 运行（10-13） | — | DIV-0001 误改 `namespace` | — | 36 个 `Unresolved reference 'R'`；回退并补 `check-android-identity.py` |
| 2 | 产物身份核验（10-13） | — | — | DIV-0008 | 「独立版本线」由**产物**作证，不再靠配置自述 |
| 3 | 双上游监测（10-13） | — | — | — | 正确识别「上游动了」且「这次不用管」（只是 star 曲线） |
| 4 | 第一次真机安装 | 安装失败 **-13** | authority 写死撞车；**且没查「值是否从 applicationId 派生」** | DIV-0011 | 补产物级 + 源码级两层检查，都做过反向验证 |
| 5 | 第二次真机安装 | 装上了 | — | — | 但**「装上」≠「能用」**，后面又踩了三层 |
| 6 | APK 不含运行环境层 | 能开、永远起不了 DSH | 抄 `android.yml` 时漏了树外建层步骤；**「仓库里没有」≠「构建时没有」** | `e676e38` | 「缺层」从备注提升为**断言**（发现却不判失败 = 没检查） |
| 7 | 层是 `.tar.gz` | 层进了 APK，应用却看不见 | **AGP 解压 `.gz` 并剥掉扩展名**；断言 6 还被 `.sha256` 侧车骗过 | `bc4bdb0` | 以**发布产物**为准而非脚本默认值；脚本缺 zstd 直接退 2 |
| 8 | 界面报「DSH 就绪」 | 界面说就绪，实际 DSH 根本没起 | 探到了**别人**在 3080 的服务；三处假设叠加 | **DIV-0014** | 动态端口 + 身份核验（`code != 401`）+ 就绪需 `processAlive` |
| 9 | 层是 x86_64 | 「DSH 异常」 | 7 个平台可选包按**构建宿主**挑选；文档把「包是纯 JS」推成「依赖树是纯 JS」 | **DIV-0015** | `--os=linux --cpu=arm64`；断言 7 直接解层复查 |
| 10 | 无障碍服务重名 | 系统里两条同名「DSHBox 手机助手」 | 并存安装的后果漏了**系统可见身份**最后一条轴 | **DIV-0016** | 判据是「会不会让人做错事」，不是「是不是旧名字」 |
| 11 | 每个构建都是不同的应用 | 新构建装不到旧构建上，必须卸载 | CI runner 全新，`~/.android/debug.keystore` 每次重生成 | **DIV-0017** | 固定密钥库 + Secret + 指纹核验，失败不静默退回 |
| 12 | 启动命令参数顺序 | `error: unknown option '--patch'` | **我自己改坏的**：DIV-0014 把 `--patch` 挪到了首个未知旗标之后 | 并入 **DIV-0014** | 补下标断言 + 单测 + 套件断言三层 |
| 13 | 一条红了很久没人看见的断言 | — | 该节需要仓库外的审计副本，CI 里走「跳过」分支 | — | 从真实数据推导期望集合；本机跑过才算数 |
| 14 | 自有 DSH 第一次跑起来 | ✅ **起来了** | — | — | 进程 uid + 端口 owner + 浏览器 ESTABLISHED 三重独立证据 |
| 15 | 通知栏动作只剩一个字 | 4 个按钮显示为 `打…/启…/重…/停…` | **个数与长度相乘**：4 个动作把动作栏分成四份 × 6 个语言都是长文案；且「打开/启动」本该互斥 | **DIV-0018** | 动作随状态收敛到 ≤3 个 + 文案改短；断言要钉**性质**，别钉「我以为的实现形态」（第一版断言把正确实现判失败） |
| 16 | 故障注入**自己没生效** | — | 给导入副本的第 200 字节写空格，而那个偏移**本来就是空格** | **DIV-0019** | 注入必须先自证（断言文件哈希确实变了）；没生效的注入会给出**完全虚假的安全感**，与「发现问题却只记成备注」同族 |
| 17 | 手抄魔数错一个字节 | CI：23 条测试里 **5 条同时红** | 容器魔数是 8 字符的 `DSHADATA`，我在测试夹具与文档里都写成了 7 字符的 `DSHDATA` | **DIV-0019** | 测试改成**从真实写入器取**魔数；新增 `check-backup-contract.py`。**负面用例红了，证明的却是「夹具坏了」而不是「读取侧挡住了攻击」** —— 这才是最危险的形态 |
| 18 | 新写的检查器**自己**漏抓 | — | 防手抄的正则要求 `ATA` 前至少 2 字符，恰好匹配不了 7 字符的 `DSHDATA`；修好后又在 KDoc 的 `*` 续行上报错 | **DIV-0019** | 第 16 条的下一环：**注入生效了、检查器还是没红**，同样给出虚假安全感且更难察觉。5k 节把注入固化进套件，每次现场证明 |
| 19 | `.dsh/dshbox` 整目录排除**本来是错的** | —（写代码前被审计挡下） | 那目录里装着三份 overlay，其中「哪些插件被停用」是**安全机制**：丢了会把隔离的坏插件重新加载，真机上出过「每次都起不来」 | —（阶段 2.2） | 排除改成按子路径逐项；`.dsh/bin` **不**排除（没有命名空间，可能是用户自己的）。**前 18 条都是跑出来才发现，这一条是先审计再动笔避免的** |
| 20 | `Files.writeString` 在骨干的 AGP 下编不过 | CI：`:backup:compileDebugUnitTestJavaWithJavac` 16 处 `cannot find symbol` | **两个上游的工具链不同**：DSHA 用 AGP 9.1.1，单元测试按 JDK 解析 `java.nio.file`；DSHBox 用 AGP 8.9.2，按 `android.jar`（API 36）解析，而它没有 Java 11 的 `readString`/`writeString` | —（阶段 2.2，3 份测试移入 `derived` / `kind: adapted`） | **「我的代码写错了」与「两个上游的工具链不同」报错长得一模一样**。顺带发现来源头在撒谎（改完正文还写「正文未做任何修改」）而检查器只查 `files[]` 不查 `derived` —— 已拆成两种头并强制。**补记：修完还剩 1 处红，是我自己的机械替换留下的悬空限定名（`java.nio.file.new String(…)`）—— 断言 4 只能说「替换发生了」，说不了「替换结果合法」，因此加了断言 5。**补记二：修完编译全过、62 条只红 1 条，而那 1 条是我的断言错了 —— `FULL` 的成员路径就是 `.dsh`，我却要求它 `startsWith(".dsh/")`；本机把谓词在数据上跑了一遍，旧谓词恰好只在那一条上为 false，与 CI 一致** |

**贯穿全部 20 条的同一个病**，值得单独记住：

| 发作形态 | 通过了的检查 |
|---|---|
| 发现问题却只记成备注、不判失败 | 全部 |
| 断言被「名字长得像」的东西骗过（`.sha256` 侧车） | 全部 |
| AGP 解压 `.gz` 并剥掉扩展名 | 全部 |
| 层的内容在、格式对，但**架构错** | 全部，包括专为「层能不能用」写的那条 |
| 检查器查「有没有」，而缺陷在「在哪」 | 全部 |
| **测试夹具与源码各说各话**（手抄常量差一个字节） | 全部，包括专为这份容器写的那些 |
| 检查器自己漏抓它存在的那件事 | （第 18 条；靠「注入必须先自证」抓出来） |
| **最自然的设计就是错的**（把「我方资产目录」整目录排除） | （第 19 条；**写代码之前**靠审计挡下，没有对应的报错可看） |
| **产物自己声称的身份是假的**（改完正文，头还写「正文未做任何修改」） | （第 20 条；因为检查器只查 `files[]` 不查 `derived`） |
| **替换发生了、但替换的结果不合法**（悬空限定名 `java.nio.file.new`） | （第 20 条补记；断言 4 只查「旧调用还在不在」） |
| **断言把正确的实现判失败**（`.dsh` 不是 `.dsh/` 的子串） | （第 20 条补记二；与第 15 条同族 —— 钉了「我以为的形态」而不是「必须成立的性质」） |
| 页面**看起来**是 DSH，但没验证它来自哪个端口 | （差点第五次发作，见 INCIDENTS 第 14 条） |

对应的三条方法论，已经写进上面的硬约束：
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
│   └── verify-phase0.sh         阶段验证套件（成功与失败路径；工作区 166 项）
├── backup/                      阶段 2.1–2.2：v5 归档格式与预检 + 范围与排除（见其 README）
│   └── src/main/java/com/deepseekharness/app/**  DSHA 原样导入（MIT，勿改正文）
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
