# 事故与真机验证记录（AGENTS.md 的配套明细）

> 这份文件是 [AGENTS.md](../AGENTS.md) 的**配套明细**，不是第二份规范。
> AGENTS.md 只保留「当前状态 + 该怎么做」，而**每一次踩坑的完整过程、
> 原始报错、复现命令、故障注入结果**都在这里 —— 避免因为太长而被工具静默截断。
>
> **为什么拆出来（2026-10-14）**：AGENTS.md 涨到 67.7 KB，超过了工作区指令预算
> 65536 字节，于是被**截断**掉了尾部 —— 而尾部恰好是「编码约定」和「已知陷阱」。
> 这本身就是本项目反复栽的那个病的又一形态：**"没有报错"不等于"内容都在"**。
> 拆成「规范（短）+ 明细（长）」之后，两边都不会再被截断。
>
> 阅读顺序建议：先看 AGENTS.md 的状态表；哪个事件需要细节，再跳到这里对应的小节。

---

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
[docs/UPSTREAM-SYNC.md](../docs/UPSTREAM-SYNC.md)。

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

**当时仍然没有验证的**：应用能否启动、能否获取运行环境、DSH 能否真正跑起来。
「装上」只是第一步 —— **这三条后来都在第六版过了**，见下方「自有 DSH 第一次真正跑起来」。

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

#### ⚠️ 这个判据有一个实测出来的前置条件：探测路径必须是 `/`

把 token 带上之后我又测了一遍**换个路径会怎样**（同一台 DSH，0.2.0-rc.2）：

```
GET /?token=<正确>            → 303   ← 判为「是我的」
GET /api/health?token=<正确>  → 401   ← ⚠️ 换路径就变 401
GET /api/health               → 401
```

**`/api/health` 看起来更"正规"，但它会让判据永久失败。** 那会退化成上游踩过的
另一个坑：一直判未就绪 → 120s 超时 → 销毁进程。上游最初只认 200..299，
正是被 DSH 的 401 打中才放宽的 —— 放宽是对的，只是放宽成了「任何响应」，
于是丢了身份判据。正确做法是**把 token 带上**，而不是再放宽一次。

所以 `SandboxConfig.healthPath` 必须保持 `"/"`。已用两条套件断言钉住
（默认值 + 无任何覆盖），并做过故障注入：改成 `/api/health`、以及在
`ServiceLocator` 里覆盖它，两条都被拦下。

> 这里留了一个**有意不做的改进**：更彻底的做法是让探测路径也来自 DSH 打印的
> 那一行（既然端口和 token 都从那里学，路径同理），这样结构上就不可能与 DSH 不一致。
> 但 `healthPath` 是上游的配置项，现在改成派生会扩大偏离面；
> 当前用「断言钉住 + 文档写明」把风险降到可接受，记在 DIV-0014 的 `note` 里备查。

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

- 源码断言：`verify-phase0.sh` 新增 5g 节（13 项）。
  **2026-10-14 更新：全套现在是 155 项通过 / 0 失败 / 0 跳过**
  （阶段 2.1 新增 5j 节 7 项后；此前曾写 148 项，那是补齐通知栏 3 项后的数字）。
  （此前这里写的是「136 项通过 / 0 失败」，那是过期的数字；
  加进 DIV-0015/0016/0017 之后本机其实有一条红断言 —— 见下方「启动命令的参数顺序」一节。）
- 偏离重放：`apply-divergences.sh --check` → **17 项已生效、0 失败**，
  `handle_div_0014` 有 **22 项断言**（2026-10-14 更新；原为 21 项）。
- 反向验证：注入 12 种故障逐一确认被拦；其中 **2 条第一轮漏过**并已收紧
  （详见 `divergences.json` 里 DIV-0014 的 `detection` 字段）。
- 单测：`DshWebLaunchParserTest` / `HttpHealthCheckerTest` / `DshHealthPolicyTest`
  已改写与新增。**注意 `HttpHealthCheckerTest` 的上游版本断言的正是这个 bug**
  （「HTTP 401 视为 webserver 存活」），本次一并改写 —— 这是为了让测试反映正确行为，
  不是为了让测试通过。
- **「这些测试真的在 CI 上跑了」也做了证明**（见下），不是从「CI 绿」推断的。

##### 怎么证明「测试真的跑了」—— 又一次同类教训

写完新测试、CI 全绿之后，我想确认 `DshWebLaunchParserTest` 是否真的被执行。
查 CI 日志，**这个类名一次都没出现**。原因只是 Gradle 默认不逐条打印测试名 ——
但「CI 绿」本身**只能说明「没有测试失败」，不能说明「我的测试类被发现了」**。
一个没被发现的测试类，和一个全过的测试类，在日志里长得一模一样。

这和本节前面几次是同一个病：**把「没有坏消息」当成了「好消息」。**

于是做了一次**在 CI 上的故障注入**：临时分支上把
`DshWebLaunchParserTest` 的期望端口从 `42797` 改成 `9999`，推送、开临时 PR，
看 CI 是否因此失败。结果（run 38527201721）：

```
> Task :sandbox-manager:testDebugUnitTest FAILED
DshWebLaunchParserTest > 解析实测输出：端口与 token 都取到 FAILED
    java.lang.AssertionError at DshWebLaunchParserTest.kt:31
```

第 31 行正是注入点。这一次失败同时证明了四件事：测试类**被发现**、**被执行**、
断言**真的在断言**、以及**CI 会拦住它的回归**。临时分支与 PR 已删除。

> 顺带记录两个容易误判的读法：
> - `:sandbox-manager:testDebugUnitTest` 这一行**没有** `UP-TO-DATE` /
>   `FROM-CACHE` / `NO-SOURCE` 后缀，才说明它真的执行了；
>   带后缀的话即使全绿也什么都没跑。
> - `head_sha` 过滤要**完整 40 位 SHA** —— 用 7 位短 SHA 查询会静默返回空列表。
>   这次就让一个监视脚本空转了十分钟：它拿不到任何 run，于是永远等不到「全部完成」。

> ✅ **这一节当时写的「真机运行仍未验证」现在已作废** —— 第六版（提交 `5a7e7c7`）
> 在真机上跑通了，见下方「自有 DSH 第一次真正在真机上跑起来」专节。
> 而且那次正是按当时定下的规矩做的：**用独立证据核对界面结论**（进程表 uid +
> `/proc/net/tcp` 的 owner），不只看界面。

#### 这一节真正的教训

前几节的教训是「CI 全绿 ≠ 真机能用」。这一节是更进一层的：
**界面显示的状态 ≠ 设备上真实发生的事。**

此前的验证清单里，每一行都在查「某个东西存在不存在」（层在不在、进程起没起、
版本对不对）。这一行查的是**一个结论**，而结论可以被一个错误的推理链正确地推出来。
所以清单里补了一条：**状态结论必须有独立证据** —— 界面说「就绪」时，
要用进程表与 `/proc/net/tcp` 的 uid 交叉确认那个端口确实是自己的。

### 层进了 APK 也还是起不来 —— 它是 x86_64 的（2026-10-14 发现，DIV-0015 修复）

v4 装到真机上、点启动，界面报 **「DSH 异常」**。这次我直接从应用自己的
「设置 → 诊断与日志 → 运行日志 → **DSH 进程日志**」里读到了原文
（`logcat` 只有 5 行，`/data/data` 又被桥接过滤，只有这个入口能看到）：

```
dsh: fatal uncaught exception: Error: dsh: host preparation failed:
     No usable native binding found for node-addon-require-builtin-linux-arm64-gnu (auto)
  attempts: [
    { source: 'optional-package', request: 'node-addon-require-builtin-linux-arm64-gnu',
      error: Cannot find module 'node-addon-require-builtin-linux-arm64-gnu' },
    { source: 'local-build', path: '…/build/napi/napi-v9-linux-arm64-gnu/require_builtin.node',
      error: Cannot find module … } ]
```

**注意这次 `node` 真的起来了** —— v3 那版连 `node` 进程都没有（DIV-0014）。
所以这是「往前走了一步之后才暴露的下一层问题」。

根因：`@deepseek-ai/dsh` 本身确实是纯 JS，但它的依赖树里有 **7 个按平台分发的
可选包**（`optionalDependencies`，npm 按**宿主**平台自动挑选）：

```
node-addon-require-builtin-linux-<arch>-gnu   ← dsh-app-boot 启动时必需
@deepseek-ai/node-addon-system-linux-<arch>
@img/sharp-linux-<arch> / @img/sharp-libvips-linux-<arch>
@koromix/koffi-linux-<arch>
@vscode/ripgrep-linux-<arch>
sherpa-onnx-linux-<arch>
```

CI 跑在 x86_64 runner 上，于是 npm 装的是整套 `-linux-x64`。

**判定依据是上游发布产物，不是推断。** 从 `WSK-build/DSHBox` v1.4.0 的 Release
下载官方 `0.2.0-rc.2.tar.zst`（85893395 B）解包后逐包对照，恰好是 7 个包一一对换。
这是本项目第二次「以发布产物为准，不以脚本或注释为准」（上一次是 zstd 格式）。

> **`AGENTS.md` 和 `tools/build-dsh-layer.sh` 的头部都写过一句错话**：
> 「`@deepseek-ai/dsh` 是纯 JavaScript 的 npm 包（无原生扩展、无 os/cpu 约束）」。
> 就是这句话让整件事看起来不需要考虑架构。**已一并删除。**
> 写文档时把「包本身是纯 JS」推成了「整棵依赖树都是纯 JS」——
> 中间那一步没有验证，而它恰好是错的那一步。

修复（DIV-0015）：`npm install` 加 `--os=linux --cpu=arm64 --libc=glibc`；
装完做平台自检（7 个包必须齐全、且无 x64 混入），不通过就拒绝产出；
`check-apk-identity.py` 新增**断言 7**，流式解开 APK 里嵌的层再查一遍 ——
这样即使有人绕过脚本直接塞一个层进 APK，CI 也会拦下。

**这是同一个病的第四次发作，每次都比上一次更浅一层：**

| # | 形态 | 通过了什么 |
|---|---|---|
| 1 | 缺运行环境层只记成备注 | 全部检查 |
| 2 | 断言 6 被 `.sha256` 侧车骗过 | 全部检查 |
| 3 | AGP 解压 `.gz` 并剥掉扩展名 | 全部检查 |
| 4 | 层是 x64 的（内容在、格式对、架构错） | **全部检查，包括专为「层能不能用」写的那条** |

四次都是真机发现的。第 4 次的教训是：**「能用」还有一层含义没被覆盖 ——
在目标设备上能用**。核对产物要一直核到「它在目标设备上能用」，
而不是停在「它长得对」。

### 无障碍服务名与 DSHBox 撞名（2026-10-14 发现，DIV-0016 修复）

真机截图：系统「无障碍 → 已下载的应用」里出现**两条完全同名的
「DSHBox 手机助手」** —— 一条是真的 DSHBox，一条是 DSHFusion，用户无法判断该开哪一个。

这不是文案问题，是**功能性**的：开错了，DSHFusion 的手机助手通道就不通，
而界面上没有任何东西提示这一点。它与 DIV-0011（authority 冲突）是同一类 ——
都是「并存安装」这个设计目标在**系统可见身份**上的必然后果。
阶段 1 处理了 applicationId 与 authority，漏掉了无障碍服务名，
因为它是**资源里的文案**而不是配置里的标识符，前三条身份断言全都查不到它。

修复：`relay_a11y_label` 在 6 个语言里改为 DSHFusion。

> **其余「对用户可见但仍写着 DSHBox」的文案这一次有意不改**（求 star 文案、
> 后台保活提示、文件只读提示、插件市场小节标题等）。理由是它们**不会让人做错事**，
> 改了却会在 6 个语言 × 多个模块上产生大量 diff 并让每次上游同步都要重放，
> 统一归入阶段 4 的全量品牌重命名。
> 判据是**「会不会让人做错事」**，不是「是不是写着旧名字」。

### 每个构建都是不同的应用（2026-10-14 发现，DIV-0017 修复）

用户按「签名不一致」的提示卸载重装 v4 之后，我测了四个不同 CI 运行的产物签名公钥：

| 构建 | 公钥前缀 |
|---|---|
| v3（run 38516880572） | `a575fb04…` |
| `d009105`（run 38524550705） | `1f189500…` |
| `30ef440`（run 38526322508） | `08147e1f…` |
| `d16454e`（run 38529745494） | `4556a65c…` |

**两两不同。** 因为每个 CI runner 都是全新的，`~/.android/debug.keystore`
现生成，而工作流里没有任何东西缓存它（`gradle/actions/setup-gradle` 只缓存
`~/.gradle`，不缓存 `~/.android`）。

后果：**每个构建都是一个不同的应用** —— 新构建装不到旧构建上面
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），必须卸载重装，而卸载会连带清掉
应用私有数据，**包括已经下好的几百 MB 运行环境层**。
对一个「长期自用、持续吸收上游」的项目来说，这个代价不能接受。

这条与阶段 1 的一个早期决策直接相关：当时选的是「先不建密钥库，继续用 debug 签名」，
理解是「debug 签名足够」。**实测表明那不是「够不够正式」的问题，
而是「能不能覆盖升级」的问题** —— 那是那句话当时没说出来的代价。

修复（DIV-0017）：

- `app/build.gradle.kts` 的 **`debug` 构建类型**也用固定密钥库（复用 release 那套判断）。
- CI 新增 `Restore signing keystore` 步骤：从 Actions Secret 还原密钥库到
  `signing/dshfusion.jks`，并**核验证书 SHA-256 指纹**（`76d588d4…`），不符即失败。
- **密钥库不提交进仓库。** 上游 `.gitignore` 写着「签名密钥：绝不入库」——
  那条规定是有道理的，不需要为这件事破例，所以走 Secret。

> Secret 缺失或指纹不符时**直接失败，不静默退回调试密钥**：
> 静默退回会让「签名稳定」变成一句没人能验证的话，
> 而下次真机安装失败时会被归因到别处。

**这个密钥库的定位是稳定性，不是安全边界** —— 它的口令与密钥都不比 Android
标准调试密钥更保密。已导出一份备份到设备的 `Download/dshfusion-keystore-backup/`；
GitHub 上的 Secret 是唯一的工作副本，而**Secret 的值读不回来**，丢了就找不回。

> **尚未做**：`check-apk-identity.py` 还没有「产物必须由期望证书签名」的断言。
> 当前指纹检查在 CI 步骤里，覆盖的是「还原的密钥库对不对」；
> 「APK 实际用的是不是它」还没有产物级断言。已记入待办。

### 启动命令的参数顺序 —— 第五次真机失败（2026-10-14 发现，并入 DIV-0014 修复）

v5（arm64 层 + 固定签名）装到真机上、点启动，界面报 **「DSH 异常 / 地址待定」**。
这次的报错**很短，而且一眼看上去不像我们的问题**：

```
WARNING: linker: Warning: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"
error: unknown option '--patch'
```

连续 6 次，每次启动都一样。

**先确认了「不是 dsh 不认识 `--patch`」。** `@deepseek-ai/dsh/lib/bin.js` 里
明明白白有 `.option("--patch <path>", …)`（repeatable），模块注释还专门解释了
为什么它是 repeatable 而不是 variadic。所以问题不在「这个旗标存不存在」。

**真正的原因就在同一段模块注释里：**

> Launcher flags therefore come first: the first token this parser does not recognize
> starts the inner arguments

dsh 启动器只解析**它自己的**旗标（`--profile` / `--patch` / `--dump-*`），
**遇到第一个不认识的旗标，就把剩下的原样转交给被启动的应用**。
`--host` / `--port` 属于 web 应用、不属于启动器 —— 于是 `--patch` 一旦排在它们后面，
就被当成 web 应用自己的旗标，web 应用不认识它，打印 `error: unknown option '--patch'` 并退出。

**这是我改坏的。** 上游那一行是 `"--profile ${Constants.DSH_WEB_PROFILE}$patches"`，
`$patches` 紧贴在 `--profile` 之后，靠的正是这个顺序。DIV-0014 为了修端口，
在中间插进了 `--host` / `--port`，于是变成了
`"--profile … --host … --port $port$patches"` —— 坏顺序。

**上游不会撞上这个 bug**：它只在 overlay 文件存在时才拼 `--patch`
（`dshOverlayGuestPaths()` 里逐项判断 `isFile && length() > 0`），
而「DSH官方插件」那一层是**默认就会写**的，所以 DSHFusion 一装好就必然踩中。
换句话说：上游把这段代码留着但（在它自己的默认路径上）从没走到，我走到了，就炸了。

#### 本机复现与验证（不靠推断）

| 命令 | 结果 |
|---|---|
| `dsh --profile web --host 127.0.0.1 --port 0 --patch <f>` | `error: unknown option '--patch'` ← 与真机逐字相同 |
| `dsh --profile web --patch <f> --host 127.0.0.1 --port 0` | 正常解析 overlay（去读文件了） |

修复后的整条命令也真跑了一遍（不带 proot，直接跑层里的 `bin.js`）：

```
$ node …/dsh/lib/bin.js --profile web --patch /tmp/ovl/official.yml --host 127.0.0.1 --port 0
dsh web: http://127.0.0.1:37545/?token=qIFgjEK4ME2Y_8PEVtKBnanVOxgSuguE5_VTyaDO4zo
```

一次同时验掉四件事：`--host` 合法、`--port 0` 确实由内核分配（37545）、
overlay 被接受、**以及打印出来的这一行格式正是 `DshWebLaunchParser` 要解析的**。

overlay 的**内容**也单独验过（把 `OfficialPluginOverlay.render()` 的输出格式原样喂给 dsh）：

```
带 overlay：  disabled: false                                              ← 覆盖生效
不带 overlay：disabled: !!js ctx.get('profileContext')?.name !== 'desktop' ← 上游默认
```

进程存活与健康判据同样复现了 DIV-0014 的实测结论（正确 token → 303、
无 token → 401、错 token → 401），且 dsh 打印地址后**持续存活**，不是「打印完就退」——
这一条重要，因为 `DshHealthPolicy` 的就绪要求 `webUiReady && processAlive`。

#### 为什么检查器没拦住它

**因为原来的断言查的是「有没有」，而这次坏的是「在哪」。**
`handle_div_0014` 里钉的是 `--port $port` —— 一个**存在性**检查，
坏顺序下它**照样通过**。补了三层：

| 层 | 内容 |
|---|---|
| `handle_div_0014` | 增加下标比较：`--profile` < `$patches` < `--host`，且 `$patches` < `--port`。**只比对代码、不比对注释** —— 注释里必然引用这些旗标来解释原因，把注释算进去会让「解释」本身触发误判 |
| 单测 | `SandboxProcessRunnerTest` 新增 `dshPatchOverlaysMustPrecedeAppFlags`（钉**生成出来的命令字符串**里的下标），另加 `dshCommandOmitsPatchWhenNoOverlays`（无层时不得拼 `--patch`） |
| `verify-phase0.sh` 5g | 同一条顺序断言 + 「回归单测本身在位」的断言 —— 否则把单测删掉不会有任何东西变红 |

故障注入：把 `$patches` 挪回 `--port` 之后 → `handle_div_0014` 报**缺少 2 项**、
重放汇总变成 **16 项已生效 / 1 项失败**；恢复后回到 17 / 0。

#### 怎么证明修复真的进了产物（dex 常量折叠差分）

「源码改对了」和「产物里有这个改动」是两件事 —— 本节前面已经栽过一次
（断言 6 报了假的好消息）。而参数顺序是**运行期拼出来的字符串**，
看起来没法在产物里查。实际可以，而且判据很硬：

**Kotlin 会把编译期常量折叠进字面量。** `Constants.DSH_WEB_PROFILE` 是
`const val "web"`、`DSH_DEFAULT_HOST` 是 `const val "127.0.0.1"`，
于是 `"--profile ${…} --host ${…} --port "` 这一段被折叠成**一条完整字面量**；
而 `$patches` / `$port` 是运行期变量，折叠会在它们那里断开。
两个版本因此留下**互不相同、可以直接在新旧产物上差分**的 dex 字面量：

| `classes4.dex` 里的精确字面量 | v5（修复前） | 新产物（修复后） |
|---|---|---|
| `' /opt/…/bin.js --profile web --host 127.0.0.1 --port '` | 有 | **无** |
| `' /opt/…/bin.js --profile web'` | 无 | **有** |
| `' --host 127.0.0.1 --port '` | 无 | **有** |

差分干净、无歧义。做法是解析 dex 的字符串表（`string_ids` + ULEB128 长度 +
MUTF-8）取**精确字面量**，而不是在 APK 字节流里裸搜子串 ——
裸搜会命中注释、文档、别处的同名旗标。我在 `--port` 上就被骗过一次：
裸搜显示新旧产物**都有** `--port `，看起来毫无差别，差点得出「产物里查不出这个改动」。

> 可以推广：**任何「运行期拼装、但片段是编译期常量」的行为，都能用 dex 字面量
> 差分来验证。** 它把「产物里有没有这个修复」从「相信构建」变成「可核验」，
> 补上了此前那条从「源码断言」到「APK 身份断言」之间的空档 ——
> 这两条都不看**代码逻辑本身**有没有进产物。

#### 顺带发现：一条红了很久却没人看见的断言

修完上面的东西跑全套，发现 4d 节里那条「导入后偏离重放」是**红的** ——
而它从 DIV-0015 加进来那天起就是红的：

- 它的期望值是「失败数 = 登记总数 − 导入排除数」，隐含假设「除导入排除项外，
  导入后每一项都该报失败」。
- 但 DIV-0015 的断言查的是 `tools/build-dsh-layer.sh` 这类**本项目自有文件**，
  导入器根本不碰它们，所以导入后它仍然**通过** —— 数字于是对不上。

**为什么没人发现**：这一节需要 `../audit/DSHBox`（上游审计副本），而它**不在仓库里**。
CI 里这一节走 `else` 分支，只记一项「跳过」，于是「CI 全绿」永远看不到这条红断言。
**本机跑过才算数，而本机上一次跑它是什么时候已经不可考** ——
这正是本项目反复栽的那个坑：把「没有坏消息」当成「好消息」。

已改成**从真实数据推导**：期望的「已生效」集合 = 导入排除项 ∪
（`target_files` 全都不在「本次导入实际改动的文件」里的项）。
后者用 `git status --porcelain` 现算 —— 导入后恰好只有 46 个文件被改动，
正是那些带偏离的文件。实测预测集合与实际集合完全一致（`{DIV-0005, DIV-0015}`），
并加了「导入没有改动任何文件 ⇒ 夹具是空的」这条反空转断言。

> **故障注入本身的陷阱（2026-10-14 亲历，差点被骗）**：4d 夹具是
> `git clone .` 之后 `switch phase-1/import-dshbox-base` —— 它测的是**那个分支的
> 提交状态**，不是工作区。我第一次把故障注入在一个临时分支上，夹具切回干净分支
> 就完全看不见，于是断言「通过」了；差一点把**「注入没生效」读成「断言有效」**。
> 凡是给夹具做故障注入，必须注入到**夹具会检出的那个提交**上。
> 这与「保真度检查读的是 git 索引而不是工作区」是同一个坑的两种形态：
> **先确认你改的东西真的在检查器的视野里，再解读结果。**
>
> 注入到正确位置后，新断言的两个分支都真的报红：
> `已生效集合不符：期望 DIV-0005,DIV-0015，实际 DIV-0001,DIV-0005,DIV-0015`
> 与 `重放没有对全部 17 项给出结论（已生效 2、失败 14）`。

> **这一节真正的新教训**：前四次的病是「检查器查的东西与实际语义不等价」，
> 这一次是它的变体 —— **检查器查的是「存在性」，而缺陷在「顺序」上**。
> 同一个「东西在不在」的思维定式，换了一层皮又骗过了一遍。

### 自有 DSH 第一次真正在真机上跑起来（2026-10-14，第六版 `5a7e7c7`）

用户装上第六版并点了启动。**这次没有看界面一眼，全部从设备读回来**：

| 证据 | 读数 |
|---|---|
| DSHFusion 的 `node` 进程 | ✅ PID 28990，uid **10475**（= `com.dshfusion.app`），二次读仍在（已稳定存活 1 分 42 秒） |
| 它的启动命令（进程表原文） | `… bin.js --profile web --patch /root/projects/.dsh/dshbox/…-overlay.yml --host 127.0.0.1 --port 0` ← **修复后的顺序，在真机上直接可见** |
| 它监听的端口 | **39983**（`0x9C2F`），`/proc/net/tcp` 该行 owner uid = **10475** |
| 3080 的 owner | uid **10402** = `com.dsh.client`（DSHA）—— 两个 DSH **并存**，互不干扰 |
| 两个端口的应答 | 都是 DSH 的 `401 dsh web authentication required` —— 都是**真的 DSH**，只是不认我的 token |

**这是自有 DSH 第一次真正跑起来。** 前五次分别停在：界面误报就绪（DIV-0014）、
层是 x64（DIV-0015）、启动命令参数顺序（并入 DIV-0014）。

#### 为什么这一轮一次就成

不是运气。**每一次失败都被记成了一个可重放的断言**，而第六版在装之前
还把「修复到底有没有进产物」单独核验过（dex 字面量差分，见上一节）。
真机上第一眼看到的就是 `--profile web --patch … --host … --port 0` ——
**我在 dex 里核验过的那个字符串，原样出现在进程表里。**

#### 三条结论，分两轮核验，全部通过

| 结论 | 状态 |
|---|---|
| DSHFusion 自己的 DSH 进程起来了、在监听、能应答 | ✅ 进程表 + `/proc/net/tcp` uid 交叉确认 |
| 界面显示的端口是**这个**实例的（而不是 3080） | ✅ 界面读 **39983**，与 uid 10475 拥有的那个端口一致 |
| 点「浏览器」能带着正确 token 打开 DSH Web 界面 | ✅ 浏览器（uid 10139）与 39983 **存在 ESTABLISHED 连接**，且页面是 DSH 的引导页而非 401 |

**分开核验是有意的**：DIV-0014 的教训正是「**进程活着**」和「**界面说的是实话**」
是两件事 —— 上游当初只查后者，于是界面探到了别人的服务还报「就绪」。
所以先是设备侧独立确认进程与端口归属，再去看界面说的对不对。

##### 界面那一半：读到的原文

第二轮（用户给出无障碍权限后）直接读屏 + 截屏：

```
窗口应用: com.dshfusion.app
  "DSH 就绪"
  "http://127.0.0.1:39983"        ← 界面显示的端口
  "已运行 00:09:36"
  启动（不可用）  重启（可点）  关闭（可点）  浏览器（可点）
```

`启动` 不可用是**正确行为**（条件是 `sandboxRunning && !dshReady`，此时 `dshReady` 为真）；
这与 v3 那种「误报就绪导致按钮永久灰掉、无法重试」是两回事 —— 那种情况下
`浏览器` 也拿不到 token，这里拿到了。

证据留档：`docs/evidence/dshfusion-home-39983.png`、
`docs/evidence/dshfusion-browser-token-ok.png`。

##### 「浏览器」那条为什么不能只看页面标题

页面标题是 `DeepSeek Harness`、内容是「添加一个 API Key 开始使用」。
**这看起来已经是证据了，但它不是** —— 它没有回答「这个页面来自 39983 还是 3080」。
同一台设备上跑着两个 DSH，两个都可能渲染出同一张引导页。
如果只看标题就收工，那就是 DIV-0014 那个病的第五次发作：**把「看起来对」当成「是对」。**

所以去查了**活的 TCP 连接**（这比端口归属更直接 —— 它证明的是「谁正在跟谁说话」）：

```
/proc/net/tcp（state 01 = ESTABLISHED）
0100007F:896A  0100007F:9C2F  01  …  uid 10139

$ pm list packages -U | grep 10139
package:com.android.browser uid:10139
```

`9C2F` = **39983**，而它的 LISTEN socket owner uid 是 **10475** = `com.dshfusion.app`。
即：**DSHFusion 的「浏览器」按钮打开的那个浏览器进程，正在跟 DSHFusion 自己的
DSH 说话**，而不是 DSHA 的 3080。

再叠加一条：页面是 DSH 的**引导页**，而不是那句
`dsh web authentication required`。说明 token 是被接受的 ——
DIV-0014 定的判据（`code != 401`）在真实链路上成立。

> 这两轮合起来才叫「全链路通了」：**进程 → 端口 → 界面 → 浏览器 → token → DSH 页面**。
> 少任何一环都还能是假的：进程活着但界面指着别人的端口（v3）、
> 界面指着自己的端口但 token 传不过去（DIV-0014 的另一半）、
> 或者浏览器打开的是另一台 DSH 的同名页面（这一轮差点又踩中）。

> 顺带记两个当时走不通、后来才通的入口：
> 上一轮 `/app/ui/screenshot` 与 `/app/ui/dump` 返回 `SCREEN_TARGET_UNAVAILABLE`
> （无障碍服务没连上），所以界面那两条当时只能搁置 —— 这**不影响**设备侧的结论，
> 进程表与 `/proc/net/tcp` 都不经过无障碍通道。用户随后开了无障碍，第二轮才读到界面。
> 另一个仍然走不通的是 `/app/export`：**181 MB 的 APK 被 `TOO_LARGE` 拒绝**，
> 所以产物交付只能走 GitHub 下载。

> **这一节的方法论收获**：这一轮的核验完全建立在「**不经过界面**的独立证据」上 ——
> 进程表的 uid、`/proc/net/tcp` 的 owner、端口的应答。三者互不依赖，也都不来自应用自身。
> 而第六版的**产物级**核验（dex 差分）和**设备级**核验（uid 交叉）恰好补上了
> 此前那条断链的两端：一端是「源码对不对」，另一端是「设备上有没有真的发生」。
> 中间那段「产物里有没有」此前只能靠「相信构建」，现在也可核验了。


---

### 通知栏的动作按钮只剩一个字（2026-10-14 发现，DIV-0018 修复）

**真机现象**：DSHFusion 的常驻通知里，4 个动作按钮**全部只显示一个字加省略号** ——
`打…` `启…` `重…` `停…`。通知正文（`沙箱在线 · DSH 就绪` / `http://127.0.0.1:30285`）是完整的，
只有动作栏坏掉。

**先排除掉「不是这个原因」**：

| 假设 | 怎么排除的 |
|---|---|
| 用户把系统字体调大了 | `settings get system font_scale` = **1.0**，正常值 |
| 通知内容太长挤掉了动作栏 | 正文两行都完整显示，只有按钮被压 |
| DSHFusion 自己引入的 | 上游 `SandboxService.kt` 的 4 个 `addAction` 与 4 条文案一直如此，本仓库没动过 |

**根因是两个因素相乘**：

1. **动作个数**：上游固定放 4 个（打开 / 启动 / 重启 / 停止）。Android 把动作栏按个数
   平分宽度，每个只分到约四分之一。
2. **文案长度**：4～6 个字符，而且**6 个语言都是长文案** ——
   `打开 DSH`、`Stop sandbox`、ru 的 `Перезапустить DSH`、es 的 `Detener entorno aislado`。

单独看，两个都不致命；乘在一起就一个字都放不下。这也是为什么
**只改文案不够**（ru/fr 的长词在四分之一宽度里依然紧张）、
**只减个数也不够**（4～6 字符在三分之一宽度里依然紧张）。

**顺带发现一个语义错误**：「打开 DSH」与「启动 DSH」**本来就互斥** ——
DSH 已经在跑时「启动」是误导，没在跑时「打开」无处可开。上游把两个都列出来，
**既占了宽度（正是这次压缩文案的直接原因），又让人选错**。

**修法（DIV-0018）**：

- 新增纯函数 `dshNotifyActions(dshState)` 与枚举 `DshNotifyAction`，
  按状态给出 **最多 3 个**动作：`READY` → 打开/重启/停止；`STARTING`、`RUNNING` → 重启/停止；
  其余 → 启动/停止。**「停止」永远在场且排最后** —— 它是沙箱卡住时唯一的出路，
  这条规矩来自 DIV-0014 的教训（一个误报的「就绪」曾让首页按钮永久灰掉、无路可走）。
- 6 个语言的 4 条文案改成单个短词（`Open` / `打开` / `Открыть` …）。
- 新增 `DshNotifyActionsTest`（7 个 `@Test`），钉的是四条**性质**而不是实现长什么样：
  个数 ≤ 3、打开/启动互斥、停止在场且最后、`READY` 之外不给「打开」。

**一个值得记下来的自摆乌龙**：`handle_div_0018` 的第一版断言写的是
「`addAction` 出现次数必须为 **0**」（想表达「不许再写死」），
结果把**正确的实现**判成了失败 —— 修复后的写法是循环里调用**一次** `addAction`、
文案由 `labelRes` 决定。断言改成了「恰好 1 处，且该行的文案参数不写死」。
**教训与第 13 条同类：断言要钉性质，不要钉「我以为的实现形态」。**

**故障注入（4 项，全部正确变红）**：

| 注入 | 结果 |
|---|---|
| ru 的 `notify_action_restart_dsh` 改回 `Перезапустить DSH` | ✅ 失败：`仍是长文案` |
| fr 的 `notify_action_stop_sandbox` 改回 `Arrêter le bac à sable` | ✅ 失败：`仍是长文案` |
| 加回一处写死的 `addAction(0, …, R.string.notify_action_open_dsh, …)` | ✅ 失败：`应恰好 1 处，实际 2 处` + `又写死了具体文案` |
| 把循环改回固定两个动作（去掉 `dshNotifyActions` 调用） | ✅ 失败：`没有按 dshNotifyActions(dshState) 循环装配` |

**诚实性声明**：本机没有 JDK / Android SDK，**Kotlin 编译不了**，所以这次修复的
验证链是「脚本断言 + 单测源代码在场」→（待办）CI 首次真实编译 →（待办）**真机截图对照**。
**在拿到真机截图之前，这一条只能记成「已改、未验证」，不得记成「已修好」。**

### 故障注入自己没生效（2026-10-14 发现，随 DIV-0019 记录）

**现象**：给 `check-dsha-imports.py` 做故障注入 —— 往导入副本
`BackupScopes.java` 的第 200 字节写入一个空格 —— 检查器报「通过」。

**根因**：那个偏移**本来就是空格**。写入一个与原值相同的字节，文件一个字节都没变，
注入自然什么都没注入。检查器是对的，**注入是坏的** —— 而我差点拿着「检查器没报错」
当「检查器拦不住篡改」的结论去加固它。

**这不是新病，是老病的新形态**：与「发现问题却只记成备注、不判失败」是同一族 ——
都是**验证动作本身没有产生可观测的效应**，于是给了完全虚假的安全感。
此前记录里的「注入 12 种故障逐一确认被拦」之所以可信，是因为那些注入都**改变了被测对象**。

**修正**：注入前先断言文件哈希确实变了（本节复现命令里那条 `diff`）。
改成按位翻转后，同一条注入立即转红（退出码 1，报「正文与登记不符」）。

**验证状态**：改用按位翻转后，四种注入全部转红 —— 正文翻一位、删掉整个文件、
塞入未登记文件、来源头被改一个字，各自命中不同的断言分支，复原后退出码 0。

**这条没有对应的「修复」，只有一条方法论**，已写进 AGENTS.md 的硬约束：
**每一个新断言都要用一个真实注入的故障证明它真的会红 —— 而注入必须先自证它真的注入了。**

### 第一次真实编译阶段 2.1：测试文件有类型错误（2026-10-14，CI run 38623849715）

**现象**：Android CI 在第 12 步 `Run unit tests` 失败：

```
e: V5ArchiveTest.kt:179:17 Argument type mismatch: actual type is
   'kotlin.collections.List<kotlin.Any!>', but
   'kotlin.collections.List<kotlin.Pair<BackupArchive.Record, kotlin.ByteArray?>>' was expected.
e: V5ArchiveTest.kt:253:17 （同一处，另一条用例）
> Task :backup:compileDebugUnitTestKotlin FAILED
```

**根因**：`listOf(record to null, anotherRecord)` —— 第一个元素是 `Pair`，
第二个漏了 `to null`，于是 `listOf` 推断成 `List<Any>`。

**为什么本机所有检查都没发现**：本机没有 JDK / Android SDK / Gradle，**Kotlin 编译不了**。
`verify-phase0.sh` 的 5j 节查的是「文件在不在、错误码字符串在不在」，
`check-dsha-imports.py` 查的是「字节有没有变」—— **没有一条能看类型**。
这正是硬约束 4（没有实际执行的构建不得标记为成功）存在的理由，不是形式主义。

**注意主模块是编译通过的**：`:backup:compileDebugKotlin` 与
`compileDebugJavaWithJavac` 都成功，导入的 4 个 Java 文件与 `V5Archive.kt` 都没问题。
**失败面比想象的小，但只有真跑才知道小在哪。**

**修正**：两处补 `to null`。同时把整个测试文件按类型重读一遍（`listOf` / `mapOf` /
`ByteArray` 赋值逐处核对），确认没有第二类问题 —— 因为一轮 CI 约 5 分钟，
盲改后重推的代价比读一遍高。

**方法论**：这与第 13 条（需要仓库外副本的断言在 CI 里走跳过分支）是一对 ——
那条是「检查跑了但没真查」，这条是「检查真查了但查不到这个维度」。
**本机检查的覆盖面必须如实声明**：阶段 2.1 的「本机 155 项通过」只覆盖
脚本与字节层面，**编译层面的结论只能由 CI 给出**。

### 魔数被手抄错一个字节，5 条用例同时红（2026-10-14，CI run 38625190214）

**现象**：类型错误修掉后重新跑 Android CI，`:backup:testDebugUnitTest`
`23 tests completed, 5 failed`：

```
V5ArchiveTest > hasMagicAcceptsOnlyTheV5Header FAILED          java.lang.AssertionError  (:190)
V5ArchiveTest > forgeProducesAContainerTheReaderAccepts FAILED java.io.IOException        (:140)
V5ArchiveTest > archiveClaimingFullWhileCarryingMissingMembersIsRejected FAILED  ComparisonFailure (:261)
V5ArchiveTest > rootDeclaringOneScopeCannotCarryAnother FAILED                   ComparisonFailure (:276)
V5ArchiveTest > manifestThatUnderReportsItsOwnEntriesIsRejected FAILED          ComparisonFailure (:286)
```

**根因**：容器魔数是 `BackupArchive.MAGIC = {'D','S','H','A','D','A','T','A'}`
—— 8 个字符的 **`DSHADATA`**。我在三处把它写成了 7 个字符的 `DSHDATA`：
测试夹具 `forge` 的 `out.write("DSHDATA"…)`、`hasMagic` 用例的字面量，
以及 `V5Archive.kt` 的 KDoc 与 `backup/README.md` 的格式图。

一处错，五种红，而且**红的理由各不相同**：

| 用例 | 为什么红 |
|---|---|
| `hasMagicAcceptsOnlyTheV5Header` | 直接读了那个错字面量，`hasMagic` 当然 false |
| `forgeProducesAContainerTheReaderAccepts` | 夹具的前 8 字节不是魔数，读取侧判 `ARCHIVE_VERSION` |
| 三条 forge 负面用例 | **夹具本身坏了**，抛的不是要测的那个错误码 |

最后一行是这次最值得记住的地方：**负面用例红了，但它证明的是「夹具坏了」而不是
「读取侧挡住了攻击」**。如果我只看到「红了」就去把断言改松，这五条就会变成
永远通过、但什么都不验证的装饰。

**为什么本机没发现**：与上一条同源，但更具体 —— 本机跑的所有检查
（套件 155 项、导入核验、偏离重放、隐私审计、工作流检查）查的都是
「文件在不在、字节变没变、字符串在不在」，**没有一条把测试夹具和容器源码对起来**。

**修正**：

1. 测试**不再手抄魔数**，改成从真实写入器取：`private val magic by lazy {
   twoScopeArchive().copyOf(V5Archive.MAGIC_SIZE) }` —— 测试与容器从此不可能各说各话。
2. `hasMagic` 用例顺带加强：首字节**与末字节**都翻转各测一次
   （原来那个错字面量恰好差在末字节，加强后同类错误会被直接钉住）。
3. 文档与 KDoc 的 `DSHDATA` 全部改成 `DSHADATA`。
4. 新增 `tools/check-backup-contract.py`，接进 `contracts.yml` 与
   `verify-phase0.sh` 的 5k 节。

### 新写的检查器**自己**漏抓它该抓的缺陷（2026-10-14，同批）

**现象**：`check-backup-contract.py` 写完后，把上面那个真实缺陷注入回去
（`out.write("DSHDATA".toByteArray())`），**它报通过**。

**根因**：防手抄的正则写成 `DSH[A-Z]{2,5}ATA` —— 要求 `ATA` 之前至少 2 个字符，
于是 7 字符的 `DSHDATA`（`ATA` 前只有 `D` 一个字符）**恰好落在外面**。

**修好之后又漏第二次**：这次能匹配了，但匹配到的是 KDoc 里的
`` `"DSHDATA"` ``（`*` 续行注释），于是它把**注释**当代码报了错。

**结论与教训**：

- 一条检查器「写完了、跑通了、报通过」**不等于**它能抓住它存在的那件事。
  这是硬约束里「每个新断言都要用一个真实注入的故障证明它真的会红」的又一次兑现：
  **本轮 4 条注入（字面量 / `MAGIC_SIZE` / 错误码拼错 / `byteArrayOf` 摊开）
  全部先自证「文件确实变了」，再看检查器是否变红。**
- 第 16 条事故说的是「注入没生效会给出虚假安全感」；这一条是它的下一环：
  **注入生效了，但检查器还是没红** —— 同样给出虚假安全感，而且更难察觉，
  因为它留下了「已做过注入」的记录。所以 5k 节把注入**固化进套件**，
  每次跑都现场证明一次，而不是只留一句「我做过了」。

### `.dsh/dshbox` 整目录排除是错的 —— 那里面装的是用户状态（2026-10-14，阶段 2.2）

**现象**：没有现象。这是一次**在写代码之前**被审计挡下来的错误设计。

**本来要写的东西**：阶段 2.2 要给备份定排除清单。`.dsh/dshbox` 是我方随包资产目录
（`Constants.DSHBOX_ASSETS_RELATIVE_DIR`），注释写着「这里的一切都是我们的固定资产」，
直觉就是 `".dsh/dshbox"` 一整条加进排除列表 —— 干净、好解释、一看就懂。

**审计挡下来它的那句话**，在 `DshboxLayoutMigration` 的类注释里：

> 三份层文件承载**用户状态**：
> - 官方插件开关（`feature-overlay.yml`）
> - **哪些插件被停用**（`plugin-overlay.yml`）
> - 绝对安全模式整层（`absolute-overlay.yml`）
>
> 其中「被停用」是安全机制：丢了会把此前被隔离的坏插件重新加载 ——
> 真机上出过「每次都起不来」的事故。所以整理目录时**必须连内容一起搬**。

那三份文件（迁移后的新路径是 `dsh-official-plugin/`、`plugin-market/`、`safe-mode/`）
**就在 `.dsh/dshbox/` 里面**。再加一个 `PluginPaths.guardStateFile`：
`dshbox/plugin-guard.json` = 安全模式的隔离清单与归因记录。

所以整目录排除会把「哪些插件被停用」这份**安全机制**从备份里抹掉。
而这个失败的形态恰好是最难发现的：备份成功、恢复也成功、
用户只在**下一次启动加载了坏插件、起不来**的时候才知道 —— 而且那时已经很难归因。

**修正**：排除改成**按子路径逐项**。可排除的（重铺得出来）是
`dshbox/bin`、`dshbox/dshbox-plugins`、`dshbox/pilot`、`dshbox/mobile-adapt`、
`dshbox/mobile-pilot`、`.dsh/pnpm-store`；必须保留的是那三份 overlay 加隔离清单。

**顺带挡下的第二个错**：`.dsh/bin` **不能**排除。`SandboxService.removeLegacyAssetDirs()`
的注释专门说明 `.dsh/bin` 这个**名字没有命名空间**，
「每次启动都无条件删会静默吃掉用户自己建的 `~/.dsh/bin`」，所以那是一次性清理。
同理它可能是用户自己放东西的地方 —— 丢用户数据比多备份一份脚本严重得多。
有命名空间的 `.dsh/dshbox/bin` 才是可以安全排除的那个。

**怎么防止再犯**：`DshBackupExclusions.checkInvariants()` 把「排除项写宽了」
变成可执行断言；`DshBackupExclusionsTest` 还会把每个必须保留路径的
**每一个祖先目录**逐个问一遍 `isExcluded` —— 有人把 `.dsh/dshbox` 加进排除清单时，
那条会立刻红并指出是哪个祖先。做过注入，两条都真的会红。

**结论（值得单独记）**：这一条与前面 18 条的形态不同 —— 它**没有对应的报错**，
因为代码还没写就改掉了。前面所有事故都是「跑出来才发现」，
这一条是「先审计再动笔」直接避免的，正好是「不盲目覆盖、先审计再决定融合方式」
这条要求在阶段 2 的第一次兑现。**审计的价值不在于能列出多少功能，
而在于能在动手之前认出哪一个看起来最自然的设计是错的。**

### 两个上游的工具链不同：`Files.writeString` 在 DSHBox 的 AGP 下编不过（2026-10-14，阶段 2.2）

**现象**：CI `Run unit tests` 红，`:backup:compileDebugUnitTestJavaWithJavac FAILED`，
16 处 `cannot find symbol: method writeString(Path,String) / readString(Path)`。

**第一反应是「我把测试写坏了」。不是。** 这 3 份测试文件是从 DSHA 逐字节复制的，
在 DSHA 里编得好好的。

**根因**：两个上游的 AGP 版本不同，而它决定了单元测试按谁解析 `java.nio.file`：

| | AGP | 单元测试编译时 `java.nio.file.Files` 来自 | Java 11 的 `readString`/`writeString` |
|---|---|---|---|
| DSHA | 9.1.1 | JDK | ✅ 有 |
| DSHBox（本仓库骨干） | 8.9.2 | `android.jar`（API 36） | ❌ **没有** |

**这不是配置疏漏，是 Android 的真实 API 面**：Android 的 `java.nio.file.Files` 来自
OpenJDK 8，没有 Java 11 加的那几个方法。

**两条路，选了小的那条**：

1. **改这 16 行**（选中）—— 机械等效替换：
   `Files.writeString(p, s)` → `Files.write(p, s.getBytes(StandardCharsets.UTF_8))`，
   `Files.readString(p)` → `new String(Files.readAllBytes(p), StandardCharsets.UTF_8)`。
   语义等价（`writeString` 默认就是 UTF-8；`readString` 对畸形字节严格抛错而
   `new String` 会替换，但这几处夹具内容全是 ASCII，不触发差异 —— 这一点写进了登记）。
2. 把整块 `:backup` 改成纯 JVM 模块（`java-library` + `kotlin("jvm")`）—— 那样
   这 3 份文件能逐字节保留。**没选，因为阶段 2.4 要搬的 `BackupTree` /
   `AndroidBackupFileSystem` / `AndroidTreeFileSystem` 必须要 Android**，
   模块类型早晚要改回来，改了还得再改一次测试。

代价是这 3 份文件从 `files[]`（逐字节副本）移到 `derived.items[]`（`kind: adapted`），
与 `DshBackupScope.kt` 同一套登记机制。

**顺带抓到两个「说法没人校验」的问题**：

- **(a) 来源头在撒谎。** 改完正文后，这 3 个文件的头仍写着「原样复制进 DSHFusion，
  **正文未做任何修改**」。而当时 `check-dsha-imports.py` **只对 `files[]` 查头**，
  `derived` 段完全不查 —— 于是文件可以自己声称是逐字节副本，没有任何东西会不同意。
  修正：登记拆成 `provenance_header`（逐字节副本）与 `adapted_header`（适配副本），
  检查器**强制**两者各自出现在对应 `kind` 的文件上，并禁止重写文件带「原样复制」头。
  做过注入（把适配副本的头改回「原样复制」），确实会红。
- **(b) 反向检查漏了 derived。** 「导入目录里没有未登记 `.java`」这条只看 `files[]`，
  把一份文件从 `files[]` 移到 `derived[]` 会被误判成「未登记」。已把 derived 的
  `local_path` 也算作已登记。

**新增的本机防线**：`check-backup-contract.py` 加了断言 4 —— `backup/src/**` 下的
`.java` 不得出现 `Files.readString(` / `Files.writeString(`。理由是**本机没有
JDK/SDK/Gradle，这类错平时只能等 CI 告**，而 CI 一轮十几分钟；变成静态检查后，
上游同步若把这几份测试还原成逐字节副本，本机立刻发现。注入验证过会红。

> 诚实边界：这**只覆盖已知会犯的这一类**，不是「所有 android.jar 缺失 API」的通用检查
> —— 通用检查要 android.jar 的 API 清单，本机没有。断言 4 的注释里也这么写着。

**教训**：**「我的代码写错了」和「两个上游的工具链不同」是两个完全不同的结论，
而它们的报错长得一模一样。** 先说清是哪一种，再决定改什么 —— 否则会去改一份本来正确的代码。

#### 补记：机械替换自己留了个尾巴（第三次 CI，run 38631318980）

16 处只剩 **1 处**错，而且是**我自己造成的**：

```java
// 原行（DSHA 原文，用了全限定名）
assertEquals("unknown original", java.nio.file.Files.readString(unowned[0].toPath()));
// 我的替换后 —— 正则只吃掉了 Files.readString(...)，前面的 java.nio.file. 留了下来
assertEquals("unknown original", java.nio.file.new String(Files.readAllBytes(unowned[0].toPath()), ...));
```

编译器：`symbol: class nio, location: package java`。

**为什么本机没发现**：我的 `check-backup-contract.py` 断言 4 查的是
「还有没有 `Files.readString(`」—— 而那一行里它**确实已经没有了**。
断言 4 只能说「替换发生了」，说不了「替换的结果是合法 Java」。

**新增断言 5**：限定名后面直接跟 `new`（锚定在 `new String(Files.readAllBytes(` 这个
替换产物上，所以合法的 `outer.new Inner()` 不会被误伤）。注入验证过会红。

**这一条值得单独记的教训**：**「替换发生了」与「替换的结果对」是两件事。**
断言 4 与断言 5 的差别，正是这个仓库反复踩的那个坑的又一次显形 ——
前面是「有文件」vs「文件对」、「步骤通过」vs「产物对」，
这里是「替换执行了」vs「替换后的代码是合法 Java」。

#### 补记二：断言把**正确的实现**判失败（第四次 CI，run 38632643360）

这一轮是好消息：`compileDebugKotlin` / `compileDebugUnitTestJavaWithJavac` 全过，
**62 条测试跑了 61 条通过**，只红 1 条 —— `DshBackupScopeTest.membersLineUpElementWise`
（`DshBackupScopeTest.kt:118`）。

```kotlin
assertTrue(member.path.startsWith(".dsh/"))   // 我写的
```

而 `FULL` 的成员路径**就是** `.dsh`（整个 DSH home 是它的根）。
`.dsh`.startsWith(`.dsh/`) 是 `false` —— **红的不是代码，是我的断言**。

改成钉性质而不是钉形态：

```kotlin
assertTrue(member.path == ".dsh" || member.path.startsWith(".dsh/"))
if (member.path == ".dsh") assertEquals(DshBackupScope.FULL, scope)   // 只有 FULL 能整根
```

**这与事故表第 15 条是同一个病**（通知栏那次的「第一版断言把正确实现判失败」）。
两次都是**断言钉住了「我以为的实现形态」**，而不是「必须成立的性质」。

**本机验证手段**：编不了 Kotlin，但可以把**谓词**在数据上跑一遍 ——
从 `DshBackupScope.kt` 里解析出 6 个成员，逐条求值：新谓词全过；
**旧谓词恰好只在 `FULL/dsh` 上为 false**，与 CI 报的失败位置一致。
这既验证了修法，也反过来证明了「红的是断言不是代码」这个诊断本身。

> 这不能替代编译与真跑 —— 它只覆盖「谓词 vs 数据」这一层，
> 而这一层恰好就是本次失败的所在。

**第五次 CI（run 38633884134，commit `c7e83fa`）：23/23 步全绿。**
`:backup` 62 条测试全过、lint、`assembleDebug`、APK 产物身份核验全部成功，
产物 `dshapp-debug-apk` 128,790,096 字节。

**四次红，三次是我造成的**（工具链差异那次是上游之间的客观差异）：

| 次 | run | 谁的问题 | 本机能不能发现 |
|---|---|---|---|
| ① | 38629025679 | 上游工具链差异（AGP 9.1.1 vs 8.9.2） | ❌ 本机无 JDK/SDK |
| ② | 38631318980 | **我的机械替换**留下悬空限定名 | ❌ 断言 4 只说「替换发生了」 |
| ③ | 38632643360 | **我的断言**把正确实现判失败 | ❌ 谓词逻辑没有本地执行者 |
| ④ | 38633884134 | —— | ✅ 全绿 |

②③ 两次本机都「看起来没问题」，这正是这个仓库反复记的那件事：
**「本机全绿」只覆盖脚本与字节层面，编译与运行层面的结论只能由 CI 给出。**
②之后补了断言 5，③之后留下了「把谓词在数据上跑一遍」这个本机手段 ——
但两者都只能覆盖**已经犯过的那一类**，不能替代编译。

---

## 21. 「本机没有 JDK」这条记录**是错的** —— 一直有 JDK 17

**这不是一次运行失败，是一次**认知**失败，而且它已经影响了两轮决策。**

`AGENTS.md` 里从阶段 2.1 起就写着：

> ⚠️ **本机没有 JDK/SDK/Gradle，Kotlin 编译不了**，
> 所以「本机 166 项通过」只覆盖脚本与字节层面，**编译与运行层面的结论只能由 CI 给出**。

阶段 2.3 开工时我按这条记录行事，默认「改一行 → 只能等 CI 十几分钟」。
直到我在找 `CredentialProjection` 的格式证据时顺手 `command -v javac`，
才发现：

```
java     /usr/bin/java        openjdk version "17.0.20.1"
javac    /usr/bin/javac       javac 17.0.20.1
jar      /usr/bin/jar
keytool  /usr/bin/keytool
```

**JDK 17 一直都在，而且是完整的（javac / jar / keytool 齐全）。**
（讽刺的是：这份文档第 11 条记的就是「用 `keytool` 建固定密钥库」——
`keytool` 属于 JDK，我在那条里用过它，却没有从它反推出「本机有 JDK」。）

### 这条错误记录造成了什么

`:backup` 里**一行 Android API 都没有**（`CredentialProjection` 只依赖 Gson 与 SnakeYAML）。
这部分**本来就完全可以在本机编译和运行**，却被当成「只有 CI 能跑」：

| 阶段 | 本机其实能发现的 | 实际怎么发现的 |
|---|---|---|
| 2.1 | 魔数手抄错一个字节（5 条用例同红） | CI 第一次编译 |
| 2.2 | 16 处 `Files.writeString` 编不过 | CI 第二次编译 |
| 2.2 | 悬空限定名 `java.nio.file.new` | CI 第三次编译 |
| 2.2 | 断言把正确实现判失败 | CI 第四次编译 |

**这四次里有三次是纯 Java 侧的问题，本机两秒就能跑出来。**

### 修法：把「本机真跑」固化下来，而不是靠我记得

新增 `tools/run-backup-jvm-tests.sh`：

- 从 Maven Central 取 4 个 jar（junit / hamcrest / gson / snakeyaml）；
- **版本必须与 `gradle/libs.versions.toml` 一致** —— 脚本自己 grep 版本目录，
  对不上直接**退 2 报错**（做过注入验证：把目录里的 gson 改成 2.12.0，脚本拒绝运行）；
- 取不到 jar 时**退 2 报错，不静默跳过** —— 这正是第 13 条的病；
- 编译 `backup/src/{main,test}` 的**全部 .java** 并运行，再跑行为探针
  `tools/check-credential-projection.java`。

并接进 `contracts.yml`（补 `actions/setup-java`），于是它**在 CI 里也真跑**。

### 边界（别把这条当成「本机什么都能跑」）

| | 本机 | CI |
|---|---|---|
| `backup/src/**/*.java` 编译 + 运行 | ✅ | ✅ |
| `backup/src/**/*.kt`（`V5Archive` / `DshBackupScope` / `DshBackupExclusions` 及其测试） | ❌ 无 kotlinc | ✅ |
| lint / `assembleDebug` / APK 产物核验 | ❌ 无 Android SDK | ✅ |

**所以那句话要改成**：「本机没有 **Android SDK、Gradle 与 kotlinc**；
但**有 JDK 17**，因此 `:backup` 的 Java 侧能在本机真编译真运行。」

### 教训

**环境能力也要用证据确认，不能沿用旧结论。**
这条记录不是「一次性的笔误」——它在两个阶段里被当作前提使用，
每一轮都在替它多付一次 CI 往返。**一条关于环境的断言，和一条关于代码的断言一样，都需要被验证。**
（与第 13 条同族：**一个「跳过」的分支，会安静地把「没验证」伪装成「已验证」。**）

---

## 22. 我按源码推理错误码，**三个全错**

阶段 2.3 要把「凭据投影拒绝得对不对」钉死。上游 `CredentialProjectionTest`
对 5 种坏输入只断言 `startsWith("CREDENTIAL_PROJECTION_")` ——
**只能证明「被拒绝了」，证明不了「因为正确的原因被拒绝」**。
所以我打算自己写一条按具体错误码断言的测试。

期望值我先**读源码**推了一遍：

| 输入 | 我推的 | 实际 |
|---|---|---|
| `records: &record {…}` + `refs: *record` | `_ALIAS` | **`_FAILED`** |
| `payload: !!java.lang.String '…'` | `_TAG` | **`_FAILED`** |
| 40 层嵌套 | `_LIMIT` | **`_FAILED`** |

`CredentialProjection.check()` 里确实有 `_ALIAS` / `_TAG` 分支，读起来完全合理。
但 **SnakeYAML 在自己的 `compose()` 里就先抛了**，被 `portable()` 的兜底 catch
转成 `_FAILED` —— 于是那两个分支对这几类输入**实际不可达**。

如果我把推理结果写进断言，CI 会红，而**红的是断言，不是代码**
（与第 15、20 条补记二同族）。

### 修法

先写一个探针，把 15 个用例**跑一遍打印实际码**，再按实测值写断言：

```
anchor-alias         CREDENTIAL_PROJECTION_FAILED
explicit-tag         CREDENTIAL_PROJECTION_FAILED
deep-nesting         CREDENTIAL_PROJECTION_FAILED
duplicate-key        CREDENTIAL_PROJECTION_DUPLICATE
nonfinite-float      CREDENTIAL_PROJECTION_NUMBER
yaml-directive       CREDENTIAL_PROJECTION_SCHEMA
...
```

然后写 `CredentialProjectionCodesTest.kt`，并把「实测 vs 推理」的差异
**写进 KDoc 留在仓库里**，而不是只留在我的对话里：

> 结论：`CredentialProjection.check()` 里的 `_ALIAS` / `_TAG` 分支，
> 对这几类输入**实际不可达**。这条测试如实钉住**实际行为**，
> 而不是钉住「源码看起来应该怎样」。

### 补记：探针自己也有两个错

① 我在 **`.java` 文件里写了 Kotlin 的 `toByteArray(Charset)`** ——
Java 里叫 `getBytes`。javac 的报错很精确（`cannot find symbol: method toByteArray(Charset)`），
**是我先入为主地读成了「平台类库不全」**，还去查了一遍 JDK 安装。

② 改用单文件源码启动（`java tools/xxx.java`）时，中文注释编不过：
**那条路用的是平台默认字符集，本机是 US-ASCII，而且不接受 `-encoding`。**
改成由脚本 `javac -encoding UTF-8` 显式编译 —— 与其它源码的处理方式一致。

两个错都不是「设计问题」，是**没跑就写**。第 21 条刚证明了本机能跑，
我却在同一次改动里又靠推理走了两步。

### 教训

**期望值必须来自真实执行，不能来自阅读。**
「我读过源码，所以我知道它会抛什么」——这句话在有兜底 `catch` 的代码里尤其危险：
**兜底会把「本来会抛 A」变成「实际抛 B」，而阅读时最容易忽略的正是兜底。**

---

## 23. 一次 `git checkout --` 冲掉了整段未提交的登记 —— **同一类错我犯了两次**

阶段 2.2 已经记过一次：`git checkout -- HostSnapshotTest.java` 把**从未提交**的适配
悄悄丢掉了，当时的结论写得很清楚 ——「**留着备份副本，不要用 `git checkout`**」。

阶段 2.3 我又做了一次。在做 `check-dsha-imports.py` 新增的 `original` 段的反向验证时：

```bash
python3 -c "…把 derived[0] 的路径改成自写文件…"      # 故意制造身份重叠
python3 tools/check-dsha-imports.py 2>&1 | grep -m2 "✗"   # 确认它红了 ✓
git checkout -- governance/dsha-imports.json 2>/dev/null  # ← 这一行
```

**注入确实红了**（日志有 `✗`），但紧接着的 `git checkout --` 把
`governance/dsha-imports.json` **整个恢复到 HEAD** —— 而我此前对这一文件的所有改动
（2 个逐字节副本的登记、适配副本、`not_imported`、`original` 段、`phase` 文案）
**全都还没提交**。它们一起没了。

**发现它的方式是「看起来不相关的检查同时红了」**：下一次跑套件时报
「导入目录里有未登记的文件」（那两个新 `.java` 回到了未登记状态）、
「被删测试的登记不完整」、「original 段缺失」—— 三条同时红。
如果我只挑其中一条去「修」，会去修一个不存在的登记问题。

### 为什么这次比上次更值得记

上次丢的是**一个文件的适配**，重做一遍就是了。这次丢的是**一整套登记**，
而且**没有任何备份**。更糟的是：

- 输出被 `2>/dev/null` 吞掉了 —— `git checkout` 是**沉默**的，它不报错、不提示，
  看起来就像什么都没发生；
- 我是在三条**看起来不相关**的检查同时失败时才反推出来的，
  而不是在那一行执行时就发现。

### 修法（这次真的改了做法）

1. **不再用 `git checkout --` 回退未提交的改动。** 需要恢复时就 `cp` 一份备份回来，
   或者从 `git show HEAD:path` 取（读操作，不碰工作区）。
2. **注入测试的制造与还原都放在同一个脚本里**，且**不放 `2>/dev/null`** —— 让输出可见。
3. **验证完就立刻提交**，不要让「已登记」这种昂贵的东西长期停在未提交状态。

### 教训

**「上次记过这个教训」不等于「这次不会犯」。** 第 20 条补记里我刚写过
「同一类错两次都是我的」，这里是第三次。

一个更准确的推论是：**只要还原手段比制造手段更「强力」，这类事故就会重复。**
`git checkout --` 是一键的、沉默的、范围是「整个文件」的；而我的登记是逐步
追加出来的。两者的力道根本不对等。

---

## 24. 依赖缓存被 `git add -A` 收进了仓库（57 个文件，含 4 个 jar）

新增 `tools/run-backup-jvm-tests.sh` 时，它把依赖 jar 与编译产物放在
仓库根的 `.jvm-test-cache/`（1.4 MB：4 个 jar + 53 个 `.class`）。
我**没有**同时加 `.gitignore` 规则，然后惯常地 `git add -A` ——

```
A  .jvm-test-cache/classes/CheckCredentialProjection.class
A  .jvm-test-cache/classes/com/deepseekharness/app/backup/…（52 个 .class）
A  .jvm-test-cache/lib/gson-2.13.1.jar
A  .jvm-test-cache/lib/hamcrest-core-1.3.jar
A  .jvm-test-cache/lib/junit-4.13.2.jar
A  .jvm-test-cache/lib/snakeyaml-2.4.jar
```

**57 个文件，全是本机产物，一个都不该进仓库。**

### 为什么既有的门禁没拦住

`contracts.yml` 里本来就有一条「大资产不许入库」的断言：

```bash
git ls-files | grep -qE 'runtime/android-assets|\.tar\.zst$|\.tar\.gz$|offline-rootfs'
```

它匹配的是**层资产**（`*.tar.zst` / `*.tar.gz` / `runtime/android-assets`）——
**`*.jar` 一个字都不匹配**。所以这条断言对这次的形态是完全瞎的，
它存在于「防大资产入库」这个目的之下，却只覆盖了那一种大资产。

（同族的还有一条：`libs/zstd-jni-1.5.7-15.aar` 是上游刻意 vendored 的**构建依赖**，
`.gitignore` 必须放行 —— 所以也不能简单粗暴地禁掉所有二进制。）

### 修法

1. `.gitignore` 加 `/.jvm-test-cache/`（前导斜杠是必要的：它是仓库根下的固定位置）。
2. `contracts.yml` 的这条断言**加上 `.jvm-test-cache/`**，并在注释里写明
   「既有两条抓不到 jar」这个**具体原因**，而不是笼统地写「防大文件」。
3. `git rm -r --cached` 把它从索引里摘掉。

### 教训

**「有一条防 X 的检查」不等于「X 被防住了」。** 这条断言的名字是
「大资产不许入库」，而它实际只防了 `tar.zst/gz` 这一种形态 ——
名字覆盖的范围比实现大，于是它自己成了「看起来有门禁」的假信号
（与第 13、16、18 条同族：**门禁的覆盖面与它宣称的覆盖面不等价**）。

新增任何「本机产物目录」时，**.gitignore 与门禁断言必须和目录一起加**，
而不是等下一次 `git add -A` 来告诉你。

---

## 25. 我在改文件的同时跑套件 —— 三条失败全是竞态，不是缺陷

阶段 2.3 收尾时我起了后台任务跑 `tools/verify-phase0.sh`（约 90 秒），
然后**立刻开始编辑文件**：加 `DIV-0020`（其中一次还把 `divergences.json` 写坏过）、
写 `handle_div_0020`、改 `apply-divergences.sh`、改 `.gitignore`……

套件跑完报 3 项失败：

```
- 导入文件与上游逐字节一致（差异均已登记）|exit=1 期望=0
- 所有 JSON 可解析|exit=1 期望=0
- 恢复后重放仍失败（备份/还原有问题）|fail
```

**三条全是竞态**：套件读文件时，文件正处在「我改到一半」的状态 ——
包括那个我写坏过的 `divergences.json`。文件稳定后重跑：**176/176 全过**。

其中「所有 JSON 可解析」最值得注意：它失败的唯一原因，
是套件恰好在我把 `divergences.json` 拼坏的那几百毫秒里读到了它。

### 教训

**套件读的是工作区；在工作区还在变的时候跑它，得到的结论不属于任何一版代码。**
更要紧的是它的**失败方向是危险的**：竞态产生的红，长得和真缺陷完全一样。
如果我没多想就去「修」其中一条，会去修一个不存在的问题 ——
而这正是第 23 条那次事故的同一种误诊（三条看起来不相关的检查同时红）。

规矩：**要么先停止编辑再跑套件，要么只跑不读工作区的单项检查。**
另外，`git add -A` 与套件的保真度检查都要读索引，所以
「一边 `git add` 一边跑套件」同样属于这一类。

---

## 26. 「CI 绿」证明不了「测试跑过」—— Gradle 对通过的测试不打名字

阶段 2.3 的 Android CI **23/23 步全绿**（run 38640146073），其中包括
`Run unit tests`。我准备据此写下「Kotlin 侧的 8 条新测试已由 CI 验证」——
**幸好先去日志里找证据。**

在整份 CI 日志（119 KB、25 个条目）里 grep：

```
CredentialProjectionCodesTest 出现在日志: 0 次
CredentialProjectionTest      出现在日志: 0 次
client-connection（投影目标）出现在日志: 0 次
```

**0 次不是异常** —— Gradle 只在测试**失败**时打印名字，通过的一律沉默。
所以「`Run unit tests` 通过」这个事实，与「我的 8 条 Kotlin 测试跑过」
**之间没有任何蕴含关系**。

### 为什么这一次特别要紧

阶段 2.3 新增的 `CredentialProjectionCodesTest.kt` 是 8 条 **Kotlin** 测试。
而本机**没有 kotlinc**，`tools/run-backup-jvm-tests.sh` 只编译 `.java` ——

> **也就是说：这 8 条测试在本机跑不了，而 CI 的绿又不作证它们跑过。
> 在补上这一步之前，它们没有被任何地方观察到运行过。**

我此前在 AGENTS.md 与 PR #8 里写的「Kotlin 与 Android 侧仍然只能由 CI 给出结论」——
**这句话当时是空的**：CI 给出的结论里并不包含它们。

### 修法：把「跑了什么」变成日志里的可见事实，并判失败

`android.yml` 的 `Run unit tests` 之后新增一步
`Verify the :backup unit tests actually ran (not just "CI green")`：
解析 `backup/build/test-results/**/*.xml`，**打印每个测试类各跑了多少条**，
然后三条断言 ——

1. `CredentialProjectionCodesTest` **必须出现**（本机无 kotlinc，它只可能在这里跑）；
2. 它必须是 **8 条**；
3. 总数**不得低于阶段 2.3 的实测总数 76 条**（即「不允许净减少」）。

> **这一步做过的反向验证**（本仓库的硬规矩：新断言必须证明它会红）：
> 从 YAML 里把那段 Python **原样抽出来**（不是重写一份），配四组夹具：
> ① 正确 → 通过；
> ② **注入：新测试缺席** → `::error::…没有运行` 且退出 1；
> ③ **注入：条数不对** → `::error::…期望 8 条` 且退出 1；
> ④ **注入：总数缩水到 17** → `::error::…低于实测总数 76` 且退出 1。
>
> ⚠️ **但第一版的两个数字我都写错了（7 与 62）—— 而这个断言第一次真跑就把它抓了出来。
> 见下一条。**

### 教训

**「测试套件通过」是一个关于「失败没有发生」的断言，不是一个关于「检查发生过」的断言。**
这两者在**测试没跑**时给出完全相同的结果 —— 而这正是本仓库第 6、13、16、18、24 条
反复出现的同一个形状：**门禁的覆盖面小于它宣称的覆盖面。**

与前几条的区别只在于载体：这次的门禁不是我写的脚本，而是 **Gradle 自己的输出习惯**。
借用外部工具当门禁时，**它「什么都不说」时到底意味着什么，必须单独确认**。

具体到本仓库：**任何新增的、本机跑不到的测试（Kotlin 侧），都必须同时加一条
「它确实跑了」的断言**，否则测试数量可以降到 0 而 CI 依然全绿。

---

## 27. 新断言的第一次真跑，红的是**我自己的期望值** —— 又一次「数出来的」

第 26 条那一步写完后，它在 CI 里第一次真跑：

```
##[error]com.dshbox.app.backup.CredentialProjectionCodesTest 跑了 8 条，期望 7 条
    8  com.deepseekharness.app.backup.BackupArchiveTest
    4  com.deepseekharness.app.backup.BackupFileSystemAtomicTest
    3  com.deepseekharness.app.backup.BackupJsonTest
    6  com.deepseekharness.app.backup.CredentialProjectionTest
    3  com.deepseekharness.app.backup.GuestDataResolverTest
    7  com.deepseekharness.app.backup.HostSnapshotTest
    2  com.deepseekharness.app.util.FileKindTest
    8  com.dshbox.app.backup.CredentialProjectionCodesTest
    8  com.dshbox.app.backup.DshBackupExclusionsTest
   15  com.dshbox.app.backup.DshBackupScopeTest
   12  com.dshbox.app.backup.V5ArchiveTest
:backup 共 76 条测试，11 个测试类
```

**`Run unit tests` 本身是绿的**（第 12 步成功）—— 真正红的是我刚加的那一步。

### 两个数字都错了，而且错法不同

**① 「7 条」是我数出来的。** 新测试文件里 `@Test` 出现了 **8** 次，我数成 7。
这正是第 22 条的教训（我按源码推出三个错误码，三个全错）——
只是这次错在**断言里**，而不是在被断言的目标里。区别在于：
**断言红了，所以我只付了一次 CI。**

**② 「总数 62」不是数出来的，是沿用下来的。** 阶段 2.2 记的「`62 条测试全过`」
来自**读 CI 日志**，而 Gradle 不打这个数字 —— 也就是说它当初就是个估计值。
现在有了真实的类清单，可以反推：

```
2.2 的 Java 侧   = 33（本机实测当前值） − 6（2.3 新增 CredentialProjectionTest）
                   + 1（2.3 删掉的那条方法）        = 28
2.2 的 Kotlin 侧 = 43（8+8+15+12） − 8（2.3 新增 CodesTest） = 35
2.2 真实总数     = 28 + 35 = 63
```

**所以我一直写的「2.2 有 62 条测试」是错的，真值是 63。**
一个从未被任何断言钉过的数字，在文档里活了整整一个阶段。

### 修法

- 期望值改成**实测值**：新类 **8 条**，总数下限 **76**（当前实测总数，即「不允许净减少」）；
- 更正 AGENTS.md 与 backup/README.md 里所有 62/7；
- 记录本条。

### 教训

**「把期望值写进一条会判失败的断言」这件事，本身就是让错误暴露的机制。**
第 22 条的错题（读源码推错误码）之所以代价大，是因为我**先写了测试才发现**；
这次的错题（数 `@Test`）代价小，是因为断言在那里等着第一次真实执行。

所以规矩应该比第 22 条更进一层：

> **凡是「我以为的数量」——数出来的、读出来的、沿用下来的 ——
> 都要给它安排一次会失败的相遇，而不是让它在文档里自然存活。**

数字在文档里是**不可证伪**的：没人会去数。放进断言里就变成可证伪的了。

---

## 28. 2.4 的只读审计第一版有三处是错的 —— **审计本身也要能被证伪**

阶段 2.4 动手前，我按惯例先做只读审计（这是第 19 条教出来的习惯，那次它确实挡下了一个
错的排除方案）。审计产出了一张 6 行的依赖表，并据此定了落点：
**「6 个类全进 `:backup`，本机测试工具按可达性排除 2 个 Android 文件。」**

这张表**看起来是量出来的**（每行都有行数和导入数）。它错了三处。

### 错法一：漏了两个类，而且是编译器告诉我的

第一版列了 6 个候选。真正开始导入时，`javac` 报：

```
NativeRestorePlan.java:135: error: cannot find symbol
      plan.warnings.add(DataFormatEvidence.restoreWarning(manifest));
```

补上 `DataFormatEvidence`(55 行) 之后，又报它需要 `RuntimeDescriptor`(154 行)。

**这两个类一直在我读的那个目录里。** 我没有把它们列进候选，因为我列的是
「我按名字猜和恢复有关的类」，而不是「从入口类出发走一遍可达性」。

> 如果审计的结论只写在文档里，这个错会以「漏掉两个类」的形式活到 PR 里；
> 它之所以被抓住，是因为**我紧接着就去编译了**。

### 错法二（决定性，推翻了落点）：真正卡住的不是 `android.*`

第一版的核心判断是：

> 「Android 特异性只集中在 2 个文件（`AndroidTreeFileSystem`、`AndroidBackupFileSystem`），
> 其余 4 个源码里没有任何 Android 调用，只是类型上引用了那两个。」

**这个判断只看 `android.*` 导入，所以漏掉了两处更硬的耦合。** 按可达性追下去：

| 起点 | 中间 | 终点 | 为什么这是死路 |
|---|---|---|---|
| `BackupTree` | `AndroidTreeFileSystem`(600) | `NativeStorage`(441) | `System.loadLibrary("termux")` + **7 个 native 方法** |
| `NativeRestoreTargets` | `ConfigStore`(817) | `KeyVault`(145) → `CredentialRead`(73) | DSHA **自己的**设置与凭据子系统 |

第一处的致命细节：`NativeStorage` 声明了

```java
System.loadLibrary("termux");
private static native int statChildrenBatchBytes(...);
private static native int prepareSmallFileBytes(...);
// …共 7 个
```

这些符号在 DSHA 的 `libtermux.so` 里（DSHA 上游有对应的 JNI 源码与 `jniLibs`，本仓库两者都没有）。
而 DSHFusion 骨干的 `app/src/main/jniLibs/arm64-v8a/` 只有：

```
libandroid-shmem.so  libproot-loader.so  libproot.so  libtalloc.so  libzstd-jni-1.5.7-15.so
```

**没有 `libtermux.so`。** 也就是说：把这三个文件搬进来，会得到一段
**编译得过、lint 过得去、单元测试跑得过（测试不碰它），而真机上第一次用到就
`UnsatisfiedLinkError`** 的代码。这正是本项目最怕的形态 ——
第 6/7/9 条都是「看起来接好了，真机上才炸」。

第二处的性质不同但结论一样：`NativeRestoreTargets` 在 4 处调用
`new ConfigStore(context).hostSettingsState()/applyHostSettings()/prepareHostSettings()`。
DSHFusion 的骨干是 Kotlin + DataStore，有它自己的设置模型。搬进 DSHA 那一套，
等于在骨干旁边**长期并行第二套设置与凭据模型** —— 每次双上游同步都会冲突。

> **由此得到一条判据，我把它写进了 FUSION-PLAN：**
> **一个 `android.os` 依赖，比一个指向上游自己应用内部子系统的依赖，更容易处理。**
> 前者是平台 API（`com.android.library` 本来就有）；后者是**架构承诺**。
> 用「有没有 `android.*` 导入」去筛，恰好会把前者当成危险、把后者当成安全 —— 正好反了。

### 错法三：把 `DataRootPolicy` 当成叶子

表里写它「依赖：叶子」。实际它有

```java
|| ManagedRuntimeLayout.paths().contains("root/.dsh/" + name)
```

`ManagedRuntimeLayout` 是 87 行。这次影响不大（它零 android、零跨包，随时可搬），
但它是**同一个坏习惯的第三个样本**：**没有打开文件去读那几处引用。**

### 让 2.4 有解的那处发现

推翻落点之后本来是无解的 —— 直到我把 `BackupTree.digest()` 打开看：

```java
if (fs.getClass() == AndroidBackupFileSystem.class && fs.stat(root).type.equals("DIRECTORY")) {
  try (AndroidTreeFileSystem tree = new AndroidTreeFileSystem(root)) {
    return digestTree(tree, root, control);
  }
}
return digestTree(fs, root, control);          // ← 通用回退，功能完整
```

**那条 Android 链只是快路径。** 两条路径共用同一个 `TreeHash`/`visit`，
而且分支条件只取决于 `fs` 的类型 —— **任何一次调用里两侧必然走同一分支**，
所以摘掉它不会造成「一侧快、一侧通用」的不一致。

于是落点变成：**搬纯 JVM 的恢复核心，摘掉这条用不上的快路径**，
并且把「删掉的每一行都是 Android 专有分支、通用遍历逐字保留」写成断言
（`verify-phase0.sh` 的 5n 节：既查 Android 引用没了，**也查 `ENTRY_LIMIT`/
`INVALID_CHILD`/`SPECIAL_FILE`/`SOURCE_CHANGED`/`TreeHash` 还在**）。

### 顺带被真实执行回答的那个开放问题

2.3 删掉的那条测试（`oldV5ArchiveMachineRecordIsProjectedInCandidateBeforeItCanBecomeActive`）
之所以被删，是因为它依赖 `NativeRestorePlan` → `BackupTree` → `AndroidTreeFileSystem`。
审计里我写的是：

> 「JVM 会不会在类加载/校验阶段就解析 `AndroidTreeFileSystem`，**读源码判断不了**，必须真跑。」

**方法是对的，但答案的来源被我记错了。** 它在本机跑通了 —— 不是因为 JVM 的惰性解析，
而是因为**我们先把那条引用删掉了**。前者是运气，后者是可复现的决定。
两种情况的日志长得一模一样（都是 `OK (42 tests)`），
若不写清区别，下一个人会以为「Android 引用留着也没事」。

### 教训

- **审计的产物不是一张表，是一条能走通的可达性路径。** 表会漏行；
  从入口类出发、每遇到一个未登记的名字就追进去，漏不掉。
- **审计的结论必须被一次真实执行证伪。** 这次证伪它的是 `javac` 的两次报错
  和一次 `grep System.loadLibrary`。**如果 2.4 是纯文档阶段，这三处错会全部活下来。**
- 与第 22 条同族（期望值不能来自阅读），但对象换了：
  那次错的是**断言**，这次错的是**审计**。同一个病，换了宿主。

---

## 29. 改文档时把上一行整行替换掉了 —— **表里少一条，没有任何检查会红**

想把第 28 条加进 `AGENTS.md` 的事故表末尾，我写了这样一个编辑：

```
old_string: | 27 | **新断言的第一次真跑…** | … |
new_string: | 28 | **2.4 的只读审计第一版有三处是错的** | … |
```

**`old_string` 取的是第 27 条那一整行，而 `new_string` 里没有它。**
于是这次「新增」实际上是一次**删除加新增**：第 27 条被第 28 条覆盖，
表里少了一条。

发现方式很朴素 —— 加完之后我数了一遍：

```
$ grep -c '^| 27 |' AGENTS.md
0
```

### 为什么这个错值得单列一条

- **它和第 23 条是同一族**：`git checkout --` 的破坏力来自「它比制造改动的手段更省事」；
  整行替换的破坏力来自「**它的语义是「删掉这一行」，而我读成了「在这一行后面加一行」**」。
- **没有任何检查会红。** 表格少一行，`check-references.py` 不关心，
  `check-workflows.py` 不关心，`verify-phase0.sh` 也不关心 ——
  它检查的是**文件里有什么**，不是**文件里应该有多少条**。
  这与第 26 条完全同构：**「没有失败发生」不等于「检查发生过」**。
- **代价不对称**：一次错误的编辑删掉的是一条**事故记录**，
  而事故记录是这份文档里**唯一无法从代码重建**的东西。

### 规矩

> **往列表/表格里加东西之后，数一遍旧的还在不在。**
> 「加」和「替换」在编辑工具里是同一个动作，区别只在我脑子里。

这条已经写进 `AGENTS.md` 的「同一个病」表。
