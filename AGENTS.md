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
14 处偏离已重放，见 [governance/divergences.json](governance/divergences.json)。

**「原样导入」是可验证的，不是自我声称**：`tools/check-import-fidelity.py`
逐 blob 对照上游审计副本的 git 索引 —— 765 个文件与上游**逐字节（含模式）一致**，
40 处差异全部能对上已登记的偏离（其中 39 处是代码偏离，1 处是继承 lint 基线）。

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

> ⚠️ **构建已验证；真机安装已验证（第二次成功）；启动与运行还没验证。**
>
> 2026-10-13，上游 `android.yml` 在 DSHFusion 下首次真实运行**通过**：
> 10 个模块的 `testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部成功，
> 产出 `dshapp-debug-apk`（39.0 MB 压缩 / 94.0 MB 解压，777 个条目）。
> 这一步不再是「凭配置推断」。
>
> 2026-10-14，真机安装试了两次：第一次**失败 -13**
> （`INSTALL_FAILED_CONFLICTING_PROVIDER`，根因见下方专节），
> 按 DIV-0011 修复后第二次**成功**。
> **但「装上」不等于「能用」** —— 启动、运行环境获取、DSH 起来都还没验证过。
>
> **已修复（2026-10-14，提交 `e676e38`；格式更正于 `bc4bdb0`）**：此前 CI 产出的 APK
> **不含任何运行环境层** —— 而且这不只是「还得在线装一次」那么轻。
> 见下方「APK 不含运行环境层」专节：**两条获取途径都是死的**。
> 现已补上 `tools/build-runtime-assets.sh` 并接进 CI，
> 新产物内嵌 `assets/runtime/android-side.tar.zst` + `assets/dsh/<版本>.tar.zst`，
> 且 `check-apk-identity.py` 会把「缺层」直接判失败（不再是备注）。
> ⚠️ 但 `e676e38` 那一版**依然是坏的**：层是 `.tar.gz`，被 AGP 剥成了应用找不到的
> `.tar`，而断言 6 被 `.sha256` 侧车骗过去、报了个假的好消息。见下方「修好了？没有」。
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
| 生成的 APK 装到真机上验证 | ✅ **已通过**（2026-10-14，修复后第二次尝试）—— 见下方专节；**但启动与运行尚未验证** |
| APK 内含运行环境层（能真正跑起 DSH） | ✅ **已通过产物核验**（2026-10-14，run 38516880572）—— 见下方「修好了？没有」一节的结论 |
| **自有 DSH 真正在真机上跑起来** | ❌ **仍未验证** —— 修复前实测「界面报就绪、DSH 根本没起」，见下方 DIV-0014 专节 |
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

### 首次真机验证（2026-10-14）—— 失败了，而且是我的错

第一次把 APK 装到真机上，**安装直接失败**：

```
安装失败(-13)
失败原因：与已安装应用内容提供者冲突
处理建议：卸载当前已安装版本，再重新安装
```

-13 是 `INSTALL_FAILED_CONFLICTING_PROVIDER`。

**根因**：`applicationId` 只是「安装身份」的一条轴。**ContentProvider 的 authority
是另一条独立的轴** —— Android 要求它全局唯一，两个已安装应用声明同一个 authority 时，
后装的那个直接失败，**跟 applicationId 是否不同完全无关**。

APK 里 FileProvider 的 authority 写死成 `com.dshbox.app.fileprovider`，
与已装的 DSHBox 撞车。阶段 1 把 applicationId 改成了 `com.dshfusion.app`，
三条身份断言全过、CI 全绿 —— 但**它们查的都是「值对不对」，没有一条查
「这个值是不是从 applicationId 派生的」**。

我此前把「并存安装必需的改动」理解成只包含 applicationId、应用名、版本号，
漏掉了 authority。已按 **DIV-0011** 修复：两处 authority 都改为从 applicationId 派生。

> **第二重后果**（即使装上了也会中）：上游代码里 authority 有两个来源 ——
> manifest 写死 `com.dshbox.app.fileprovider`，而 `DshWebViewScreen.kt:449` 用
> `"${context.packageName}.fileprovider"` 计算。在 DSHBox 里两者恰好相等所以没事；
> 在 DSHFusion 里 packageName 是 `com.dshfusion.app`，两者不等，
> `getUriForFile` 会抛异常 —— 那个文件第 168 行的注释正好警告过这个后果。
> 所以两处必须一起改。

**补了两层检查，都做过反向验证**：

| 检查 | 层次 | 反向验证 |
|---|---|---|
| `check-apk-identity.py` 断言 4 | APK 产物 | 对那个装不上的真实 APK 跑 → 正确退出 1 |
| `check-android-identity.py` 检查 4 | 源码，构建前 | 分别把 manifest / 代码改回硬编码 → 都被拦下 |
| 夹具 `bad-authority` 场景 | 套件隔离测试 | 套件独立断言，不靠别的断言顺带发现 |

> 这次失败是有价值的，和之前那次 `Unresolved reference 'R'` 一样：
> 它验证了「CI 全绿 ≠ 真机能用」。CI 只跑编译与单测，
> 而 authority 冲突、签名、权限、运行时行为都只有真机能暴露。
> **在这条修好并重新真机验证之前，不要声称阶段 1 的产物可用。**

#### 第二次尝试：安装成功（2026-10-14）

按 DIV-0011 修复后重新构建（run 38510120522，提交 `cc972f3`），真机安装**成功**。

核验方式不是「用户说装上了」，而是自己从设备读回来：

```
$ pm list packages com.dshfusion.app
package:com.dshfusion.app

$ dumpsys package com.dshfusion.app
    versionCode=1 minSdk=29 targetSdk=36
    versionName=0.1.0
    firstInstallTime=2026-10-14 12:27:29
```

| 核验项 | 结果 |
|---|---|
| `com.dshfusion.app` 已安装 | ✅ |
| `com.dshbox.app` 仍在（并存安装） | ✅ |
| 版本号是 `1` / `0.1.0`（不是上游的 8 / 1.4.0） | ✅ |
| 两个应用的 authority 集合**交集为空** | ✅ |

authority 对照（这次专门比对了集合，不只比单个值）：

```
DSHBox    : com.dshbox.app.shizuku
            com.dshbox.app.fileprovider
            com.dshbox.app.browser.upload
            com.dshbox.app.browser.download
DSHFusion : com.dshfusion.app.shizuku
            com.dshfusion.app.fileprovider
交集      : （空）
```

> **注意 DSHBox 有 4 个 authority，而我们源码里只有 2 个 provider。**
> `browser.upload` / `browser.download` 在本仓库与上游锁定提交里都**不存在**
> —— 说明手机上装的 DSHBox 官方包不是从我们锁定的提交构建的（版本号却同样是 8 / 1.4.0）。
> 这不影响本次修复（我们的 authority 全部从 applicationId 派生，与任何 DSHBox 构建都不冲突），
> 但它说明**「上游源码」与「上游发布包」不一定是同一个东西** ——
> 下次核对冲突时不能只看源码。

**这次成功说明了什么**：DIV-0011 的修法是对的，而且「authority 必须从 applicationId
派生」这条原则比「改成某个期望值」更稳 —— 它对任何 applicationId、任何 DSHBox 构建都成立。

**仍然没有验证的**：应用能否启动、能否获取运行环境、DSH 能否真正跑起来。
「装上」只是第一步。

### APK 不含运行环境层（2026-10-14 发现，`e676e38` 修复）—— 两条路都是死的

这一条我一开始记成了「APK 不含运行环境层 ❌，需要在线获取或离线导入补上」。
**这个记法是错的，而且错得危险**：它把「装不上层」写成了「还没装层」，
读起来像是有一条能走通的路。实际上**两条获取途径都走不通**。

事实：CI 产出的 APK 里 `assets/` 顶层只有
`dshbox` / `plugins` / `relay` / `runtime-online` / `terminal-packages` 五项，
**没有 `runtime/`，也没有 `dsh/`**。于是：

| 途径 | 代码位置 | 实际结果 |
|---|---|---|
| 在线获取 | `OnlineRuntimeImportManager.kt:458` | 下载完几百 MB 之后才硬失败（`ONLINE_ASIDE`）—— 因为 `prepareAndroidSide` 的两个来源（已有的 android-side、`assets/runtime/`）都不存在 |
| 离线基线 | `provisionBundledDshBaseline` `:733` | `assets.list("dsh")` 拿到 null 直接 `return`，**一句日志都不打**，静默无操作 |

也就是说：这个 APK 能装上、能打开、界面正常，**但永远起不了 DSH**，
而且在线获取也救不了它 —— 用户会白等一次几百 MB 的下载才看到失败。

**根因是我抄上游 `android.yml` 时漏了一步。** 上游不把 `runtime/` 提交进仓库，
它的发布流程在**树外**把层建到 `../../runtime/android-assets` 再指给 gradle
（`app/build.gradle.kts:78` 的 `assets.srcDirs`）。我只抄了编译步骤，没抄建层步骤。

> 这暴露了一个更一般的教训：**「仓库里没有」不等于「构建时没有」**。
> 上游把这一步放在仓库之外，我从仓库内容推断构建输入，于是漏掉了它。
> 与 `Unresolved reference 'R'` 那次同源 —— 都是**从静态阅读推断构建结果**。

修复（提交 `e676e38`）：

- 新增 `tools/build-runtime-assets.sh`：从 `jniLibs` 组装 `android-side` 层
  （4 个文件：`bin/proot`、`libexec/proot/loader`、`lib/libandroid-shmem.so`、
  `lib/libtalloc.so`；显式 `chmod 755` 以对齐 git 索引里的 `100755`，
  不信任检出后的工作区模式），再委托 `tools/build-dsh-layer.sh` 建 `dsh` 层。
  带布局自检与 sha256 侧车。
- `android.yml` 在 `assembleDebug` 前插入该步骤，并把 `DSHFUSION_RUNTIME_ASSETS`
  指到同一目录。
- `check-apk-identity.py` 把「缺层」**从备注提升为断言 6** —— 这是关键的一条：
  以前它如实备注了「不含任何运行环境层」，然后 CI 照样全绿，
  缺层的 APK 一路走到了真机。**一个发现了问题却不判失败的检查，等于没有检查。**
  另加 `--allow-missing-runtime-layers` 逃生口给确实不该带层的场景。
- 夹具新增 `no-layers` 场景（身份全部正确、只缺层），套件 5d 独立断言它，
  确保它只因为「缺层」这一条失败，而不是碰巧被别的断言拦下。

本机全量实测产物（**这次是错的，留着作为记录**）：

```
runtime/android-side.tar.gz       125930 B   sha256 侧车一致
dsh/0.2.0-rc.2.tar.gz          124380096 B   sha256 侧车一致
```

当时我在这里写的是：「两层都是 gzip。这不是降级：`BundleManager.extractTarGz`
按**魔数**分派而非扩展名，zstd / bzip2 / xz / 纯 tar 各有分支，**gzip 是 else
兜底分支**，本来就是受支持的一等格式。」

**那句话只对了一半，而且是致命的那一半。** 应用侧确实按魔数嗅探 ——
但层不是直接交给应用的，它要先过 **AGP 的资源打包**，而 AGP 会解压 `.gz`
资源并去掉 `.gz` 扩展名。详见下面「修好了？没有」一节。

### 修好了？没有 —— `e676e38` 之后 CI 全绿，产物依然是坏的

CI run 38513950774 全绿，`Build runtime assets` 通过，`Verify APK application
identity` 也通过。我把 179.5 MB 的产物下载下来独立核对，发现：

| | 本地产出 | APK 内实际 |
|---|---|---|
| android-side | `android-side.tar.gz` 125930 B（gzip） | `android-side.tar` **337920 B（裸 tar）** |
| dsh | `.tar.gz` | `0.2.0-rc.2.tar.zst` 85687490 B ✅ |

`assets/runtime/android-side.tar` —— **扩展名被剥掉、内容被解压了。**
而应用侧的查找条件是 `OnlineRuntimeImportManager.kt:769`：

```kotlin
names.firstOrNull { it.startsWith("android-side.tar.") }
```

`"android-side.tar"` **不以 `"android-side.tar."` 开头**（少那个点）。
层进了 APK，但应用永远看不见它 —— **等于没建，bug 根本没修好。**

**断言 6 还报了个假的好消息。** 它第一版写的是
`n.startswith("assets/runtime/android-side.tar.")`，而
`android-side.tar.gz.sha256`（64 字节的哈希侧车）**也满足这个前缀**。
于是检查器打印「运行环境层 android-side：…」并放行。

> 这是同一个病的第三次发作，而且这次最糟：前两次是「发现了问题却不判失败」，
> 这次是**检查器主动报了一个不存在的好消息**。教训：
> 断言必须钉住「应用侧会去用的那个东西」，而不是「名字长得像的东西」。

#### 正确格式以**发布产物**为准，不以脚本默认值为准

从真机上装的官方 DSHBox 包里直接读出来的：

```
assets/runtime/android-side.tar.zst   113245 B
assets/dsh/0.2.0-rc.2.tar.zst       85893395 B
lib/arm64-v8a/libzstd-jni-1.5.7-15.so
```

上游 `tools/pack_runtime.sh` 默认 `COMPRESSION=gzip`，注释说「app 的 zstd
解压需要 zstd-jni，当前离线构建解析不到」—— **那段注释是过时的**：
官方包里明明带着 `libzstd-jni`，DSHFusion 也把
`libs/zstd-jni-1.5.7-15-classes.jar` 接进了 `app` 与 `sandbox-manager`。
而且 zstd 还更小（113245 B vs 我的 gzip 125930 B）。

修复（提交 `bc4bdb0`）：

- 两个构建脚本**要求 zstd，缺了就退 2**，不静默降级到 gzip ——
  一个必然被 AGP 剥坏的产物，比一个明确的报错危险得多。
  失败信息解释的是「为什么 gzip 不可用」，不是「找不到可选依赖」。
  另加魔数自检（必须是 zstd 帧头 `28 b5 2f fd`）与解包模式自检。
- `check-apk-identity.py` 断言 6 改用精确正则
  `^assets/runtime/android-side\.tar\.[A-Za-z0-9]+$`（扩展名内不允许再有点，
  侧车自然被排除），并**按魔数核对容器格式**是否在 `BundleManager` 认识的四类里。
- 夹具新增 `sidecar-only` 与 `gz-stripped` 两个场景，各对应一种真实形态。
- 套件新增 5c-2 节，并补上 `skip()` 助手（「跳过」单独计数，不混进通过数）。

#### 结论：产物已核验通过（2026-10-14，run 38516880572，提交 `bc4bdb0`）

CI 全绿之后我又**独立下载产物核对了一遍** —— 因为「步骤通过」和「产物对」
这次刚刚才被证明是两件事。结果：

```
assets/runtime/android-side.tar.zst   113187 B   magic=28b52ffd（zstd 帧头）
assets/dsh/0.2.0-rc.2.tar.zst       85661153 B   magic=28b52ffd
两份 .sha256 侧车都在
```

`assets/` 顶层：`dsh` / `dshbox` / `plugins` / `relay` / `runtime` / `runtime-online` /
`terminal-packages` —— `runtime` 与 `dsh` 都在了。

**并且模拟了应用侧那段查找逻辑**（不是「看起来对」，是照抄那行代码跑一遍）：

```
assets.list("runtime") 会看到: ['android-side.tar.zst', 'android-side.tar.zst.sha256']
firstOrNull { it.startsWith("android-side.tar.") } → android-side.tar.zst   ✅ 命中
```

与上游官方包对照，尺寸几乎一致：

| 层 | DSHFusion | DSHBox 官方 | 差 |
|---|---:|---:|---:|
| `android-side.tar.zst` | 113187 B | 113245 B | −58 B |
| `dsh/0.2.0-rc.2.tar.zst` | 85661153 B | 85893395 B | −232242 B |

（差异来自 tar 元数据与 npm 安装顺序，不影响内容正确性。）

**仍未验证的**：真机上是否真能装层、base/node 能否在线获取、DSH 是否真能起来。
层进了 APK 只是**必要**条件，不是充分条件 —— 这一步已经卡了两次，
所以在真机跑通之前，这一行仍然只能记成「产物已核验，运行未验证」。

### 界面报「DSH 就绪」，其实 DSH 根本没起来（2026-10-14 发现，DIV-0014 修复）

层进了 APK 之后，用户在真机上装了 `dshfusion-v3-zstd.apk` 并点了启动。
界面显示：**「DSH 就绪」「http://127.0.0.1:3080」「已运行 00:00:49」**。

我从设备上读回来的事实：

| 证据 | 读数 |
|---|---|
| DSHFusion 的进程 | 只有 `libproot.so → bash → sleep 3600`，**没有 `node`** |
| 全进程表匹配 `dsh web` | 只命中 `u0_a402 … node …/lib/bin.js web --no-open --host 127.0.0.1 --port 3080` |
| `/proc/net/tcp` 的 3080 | `0100007F:0C08 … 0A`，owner uid = **10402**（`com.dsh.client`），不是 DSHFusion 的 10473 |

**那台 3080 是另一个应用的 DSH。** DSHFusion 自己的 DSH 一次都没起来过，
而界面报告它「就绪」—— 因为它探到的是**别人**的服务。

> 这里要特别说明：`已运行 00:00:49` 是界面自己的本地计时器
> （`HomeScreen.kt` 里一个 `remember` 计数），**不代表任何进程活着**。
> 它是这次误导里最"可信"的一条读数，也是唯一一条完全不来自设备的读数。

**后果比「显示错误」严重得多。** 首页「启动」按钮的可用条件是
`sandboxRunning && !dshReady`；`dshReady` 被误报为 `true` 之后这个按钮**永久灰掉**，
「浏览器」按钮虽然可点，但 token 永远拿不到，只会弹「启动令牌尚未就绪」。
用户被卡在一个**既没起来、又无法重试**的状态里 —— 唯一的出路是杀进程重开。

#### 根因：三处独立假设叠加，任何一处单独修都不够

| 假设 | 位置 | 为什么它单独看起来是合理的 |
|---|---|---|
| 端口是编译期常量 | `Constants.DSH_DEFAULT_PORT = 3080` | 在「这台设备上只有我一个 DSH」的前提下成立 |
| 「有 HTTP 响应就算活」 | `HttpHealthChecker.httpProbe()` 返回 `code in 200..599` | 注释写得很清楚：「任何 HTTP 响应（含 401/302/404/5xx）都说明 webserver 存活」——**这个推理本身没错**，但它答的是「3080 上有没有 webserver」，不是「我的 DSH 起来了没有」 |
| 「webUiReady 就够」 | `DshHealthPolicy` 的 `Ready` 只看 `webUiReady`，**从不查 `processAlive`** | 进程存活恰恰是唯一能区分「我的服务」和「别人的服务」的本地证据，却没用上 |

上游的这三处**在 DSHBox 里是自洽的** —— 它就是那台设备上唯一的 DSH，没有第二个应用来抢端口。
所以这不是「上游写错了」，而是 **DSHFusion「可与其它 DSH 并存安装」这一设计目标带来的必然代价**。
阶段 1 专门为了并存安装改了 applicationId 与 authority（DIV-0011），
却没有把「并存」这个前提继续推演到网络端口上。已登记为 **DIV-0014**（adaptation，非上游缺陷）。

> **最刺人的一点**：`docs/AUDIT.md:139-140` 早在阶段 0 就写着
> `| 端口 | 3080 固定 | **动态分配**并复用上次可用端口 |`，
> 把「动态端口」列为 DSHA 的相对优势。我当时把它当成一条无关的功能对比记下了，
> 没有把它连到「DSHFusion 会与别的 DSH 并存」这件**我自己刚刚做过**的事上。
> 记下了事实，没有做出推论 —— 这是比「不知道」更常见的失误。

#### 查清的两件事（解释了它为什么能潜伏这么久）

**① 上游那个 `PORT` 环境变量是死代码。** `DefaultSandboxManager` 里设了
`base["PORT"] = "3080"`，看起来像是在配置端口。实际：

- `dsh --profile web --dump-config` 显示 webserver 的配置是
  `port: !!js ctx.webStartup.port ?? 3080` —— 只来自命令行 `--port`；
- `@deepseek-ai/dsh-web-app/lib/startup.js` 也只读 `options.port`；
- 全包内 `process.env.PORT` 的唯一命中是一个无关的示例文件。

**一个不起作用、却又长得像配置的变量，正是让「端口写死」长期不被怀疑的原因。**

**② 端口可以从 DSH 自己的输出里学到，不需要新机制。**
`dsh-web-app/lib/index.js` 打印 `dsh web: ${authenticatedUrl}`，其端口取自
`connectionCtx.webServer.port`，即**实际绑定**的端口。本机实测 `--port 0`：

```
$ dsh web --no-open --host 127.0.0.1 --port 0
dsh web: http://127.0.0.1:42797/?token=8OMeQzE98nRyjCPBhqSu4_sVBhKFQdbq8mCP-9qf5u4
```

应用本来就在解析这一行取 token（`ingestDshWebLaunchToken`），多取一个端口即可。

#### 判据为什么是「状态码 ≠ 401」而不是「= 200」

也是实测定的。对本机 DSH 请求 `/`：无 token → **401**、错 token → **401**、
对 token → **303**（不是 200）。所以 401 是 DSH 明确表达的
「这个 token 我不认识」，即**应答者不是我**。

拿不到 token 时一律判**未就绪**，而不是退回「任何响应都算活」：
宁可继续等（启动有 120s 窗口），也不要再产生一次无法归因的假成功。

#### 修复（DIV-0014，跨 22 个文件）

- 端口：`DSH_DEFAULT_PORT`/`DSH_BASE_URL` 删除 → `DSH_PORT_AUTO = 0` +
  函数 `Constants.dshBaseUrl(port)`；`buildProotDshCommand` 新增 `port` 参数并生成 `--port`；
  删掉那个不起作用的 `PORT` 环境变量。
- 身份核验：`HttpHealthChecker` 改为运行期 provider 取端口与 token，
  判据收紧为 `code != 401`；`SandboxHealth` 新增 `instanceVerified`
  区分「没人应答」与「有人应答但不是我」。
- 就绪：`DshHealthPolicy` 的 `Ready` 改为 `webUiReady && processAlive`。
- 新增纯函数 `DshWebLaunchParser`（从 DSH 输出解析端口+token，
  **解析失败返回 null，绝不返回默认端口**）。
- UI/通知栏改用本实例地址；端口未知时显示新增文案 `home_dsh_address_unknown`（6 个语言同步）。

**设计取舍：端口每次启动都变**（不记忆上次可用端口）。审计里 DSHA 的做法是
「动态分配并复用上次可用端口」，那对需要固定地址的外部工具有用；
但它引入一个新的失败模式（复用的端口恰好被别人占了），而当前阶段的首要目标是
**不再互撞、不再误报**。地址在界面上随时可见 —— 先保证正确，再谈稳定。

#### 验证状态

- 源码断言：`verify-phase0.sh` 新增 5g 节（11 项），全套 **134 项通过 / 0 失败**。
- 偏离重放：`apply-divergences.sh --check` → **14 项已生效、0 失败**，
  `handle_div_0014` 有 **21 项断言**。
- 反向验证：注入 12 种故障逐一确认被拦；其中 **2 条第一轮漏过**并已收紧
  （详见 `divergences.json` 里 DIV-0014 的 `detection` 字段）。
- 单测：`DshWebLaunchParserTest` / `HttpHealthCheckerTest` / `DshHealthPolicyTest`
  已改写与新增。**注意 `HttpHealthCheckerTest` 的上游版本断言的正是这个 bug**
  （「HTTP 401 视为 webserver 存活」），本次一并改写 —— 这是为了让测试反映正确行为，
  不是为了让测试通过。

> ⚠️ **真机运行仍未验证。** 上面全部是源码与套件层面的证据。
> 这次修复的真实效果（自己的 DSH 起来、界面地址指向自己的端口、启动按钮可用）
> **必须再装一次 APK 才能确认** —— 而且这次要用**独立证据**核对界面结论，
> 不能只看界面。步骤见 [docs/INSTALL-ON-DEVICE.md](docs/INSTALL-ON-DEVICE.md)。

#### 这一节真正的教训

前几节的教训是「CI 全绿 ≠ 真机能用」。这一节是更进一层的：
**界面显示的状态 ≠ 设备上真实发生的事。**

此前的验证清单里，每一行都在查「某个东西存在不存在」（层在不在、进程起没起、
版本对不对）。这一行查的是**一个结论**，而结论可以被一个错误的推理链正确地推出来。
所以清单里补了一条：**状态结论必须有独立证据** —— 界面说「就绪」时，
要用进程表与 `/proc/net/tcp` 的 uid 交叉确认那个端口确实是自己的。

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
│   ├── ATTRIBUTION.md           署名与许可义务
│   └── upstream-licenses/       上游许可原文（原样保留，勿改）
├── docs/
│   ├── AUDIT.md                 只读审计报告（决策证据）
│   ├── FUSION-PLAN.md           融合方案与实施优先级
│   ├── UPSTREAM-SYNC.md         双上游长期同步流程
│   ├── PHASE0-VERIFICATION.md   阶段 0 验证报告（命令、输出、退出码）
│   ├── PHASE1-PREREQUISITES.md  阶段 1 前置审计（资产 / 许可 / 数据迁移）
│   ├── INSTALL-ON-DEVICE.md     真机安装步骤（给用户手动执行）
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
│   └── verify-phase0.sh         阶段验证套件（成功与失败路径；工作区 134 项）
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
> 且版本从 `governance/upstreams.json` 读 —— 单一事实来源，这个脚本自身不会漂移。
>
> **R3 我一度说成「已从结构上消除」，那是过头话，已更正。** 新脚本只是新增了
> 一条正确的路径，并没有碰 `install_dsh.sh:8` 那个 `0.1.0-rc.6` 默认值。
> 现按 DIV-0010 处理：取消默认值，未显式指定 `DSH_VERSION` 就报错退出。
> 该脚本在 DSHFusion 里**没有任何地方执行它**，所以修的是潜在的坑，不是发作中的 bug。
>
> `build-dsh-layer.sh` 不需要 Debian 沙箱或 arm64 主机 —— `@deepseek-ai/dsh` 是纯 JS 的 npm 包。

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
