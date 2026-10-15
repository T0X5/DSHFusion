# 把 DSHFusion 装到真机上（手动步骤）

> 这份文档是给**你自己执行**用的。我没有操作你的手机。
> 每一步都写清了预期结果 —— 如果某一步的结果和预期不符，**停下来**，
> 把实际输出记下来，那是有价值的信息，不要硬往下走。

---

## 0. 先明确：你会得到什么，不会得到什么

**会得到**：一个能安装、能启动、能在设置里看到「DSHFusion v0.1.0」的应用，
它能与 DSHBox 并存安装（不同的 applicationId）。

**不会得到**：一个装完就能用的 DSH。**首次启动后需要让 app 自己把 base 与 node 拉下来**，
见第 4 步。这一步依赖网络，而且 Debian 源用的是清华镜像
（`mirrors.tuna.tsinghua.edu.cn`，见 `assets/runtime-online/bootstrap/etc/apt/sources.list`）。

> ⚠️ **下面这张表在 2026-10-14 更正过，两行都曾写错。**
> 更正前的说法是「`android-side` ✅ 在（`assets/dshbox/libdshbox-link.so`）」——
> 那个 67 KB 的 `.so` 是 PRoot 的 `link()` 垫片，**不是** 784 KB 的 android-side 层；
> 而 `dsh` 那行的「❌ 不在」当时是事实，但成因被记轻了：不是「需要在线补」，
> 而是**当时没有任何一条路能补上**。

各层在 APK 里的真实情况（`bc4bdb0` 之后，格式已改成 zstd）：

| 层 | 在 APK 里吗 | 路径 |
|---|---|---|
| `android-side`（PRoot + loader + shmem + talloc，4 个文件） | ✅ 在 | `assets/runtime/android-side.tar.zst`（约 113 KB） |
| `dsh`（DSH 本体，纯 JS） | ✅ 在 | `assets/dsh/<版本>.tar.zst`（约 86 MB） |
| `base`（Debian 根文件系统） | ❌ 不在 | 首次启动时在线获取 |
| `node` | ❌ 不在 | 同上 |

> **在 `e676e38` 之前，前两层也都不在**，而且**两条获取途径都是死的**：
> 在线获取会在下载完几百 MB 之后才失败（`OnlineRuntimeImportManager.kt:458`），
> 离线基线则静默什么都不做（`provisionBundledDshBaseline` 第 733 行直接 return）。
> 也就是说，**修复前的 APK 装上去永远起不了 DSH**。
> 详见 [AGENTS.md](../AGENTS.md) 的「APK 不含运行环境层」专节。

> ⚠️ **而 `e676e38` 那一版依然是坏的，别用它。** 它把层建成了 `.tar.gz`，
> 但 **AGP 会解压 `.gz` 资源并去掉 `.gz` 扩展名** —— 层进 APK 后变成
> `assets/runtime/android-side.tar`（裸 tar），而应用侧要求名字以
> `android-side.tar.` 开头（`OnlineRuntimeImportManager.kt:769`，带那个点），
> **匹配不上，等于没建。** 更糟的是当时的检查器被 `.sha256` 侧车骗过去、
> 报了个假的好消息。所以层必须是 `.tar.zst`（`bc4bdb0` 起）。
> 详见 [AGENTS.md](../AGENTS.md) 的「修好了？没有」专节。
>
> 这也解释了为什么不是 gzip：**`.tar.gz` 根本不是能过 AGP 的容器**，
> 跟压缩率、跟 `BundleManager` 支持几种格式都无关 ——
> 后者按魔数嗅探、确实支持 gzip，但层不是直接交给它的，
> 得先过 AGP 的资源打包，而 AGP 会在那之前就把 `.gz` 拆掉。
> 上游发布包用的正是 `.tar.zst`（真机上的官方 DSHBox 里就是
> `assets/runtime/android-side.tar.zst` 113245 B + `libzstd-jni-1.5.7-15.so`）。

APK 里还有 `assets/runtime-online/`（在线获取的引导骨架：apt 源、dpkg 元数据、
`bootstrap.index`）和 `assets/terminal-packages/`（24 个终端用 `.deb`）。

---

## 1. 拿到 APK

**方式 A：从 CI 下载（推荐 —— 这是被自动核验过的那个产物）**

浏览器打开：

```
https://github.com/T0X5/DSHFusion/actions/workflows/android.yml
```

点最新一次绿色的运行 → 页面底部 `Artifacts` → 下载 `dshapp-debug-apk`。
得到的是个 zip，解压后里面是 `app-debug.apk`。

> GitHub 的 artifact 需要登录且有仓库权限（私有仓库）。

**方式 B：命令行（需要一台有 `gh` 的机器）**

```bash
gh run download --repo T0X5/DSHFusion --name dshapp-debug-apk
```

**方式 C：自己构建**

```bash
git clone https://github.com/T0X5/DSHFusion.git
cd DSHFusion
git switch phase-1/import-dshbox-base
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 21 与 Android SDK（compileSdk 36）。

---

## 2. 先核验你手上的 APK（**别跳过这步**）

在你自己的机器上跑：

```bash
python3 tools/check-apk-identity.py app-debug.apk
```

预期输出（这是 CI 上真实跑出来的）：

```
applicationId  : com.dshfusion.app
versionCode    : 1
versionName    : 0.1.0
application    : com.dshbox.app.DshApp
authority      : com.dshfusion.app.fileprovider, com.dshfusion.app.shizuku, com.dshfusion.app.androidx-startup
应用名（android:label = 0x7f0e001f）在各语言下的解析结果：
  ar         = 'DSHFusion'  ✅
  default    = 'DSHFusion'  ✅
  es         = 'DSHFusion'  ✅
  fr         = 'DSHFusion'  ✅
  ru         = 'DSHFusion'  ✅
  zh         = 'DSHFusion'  ✅
备注：不含任何运行环境层（无 .tar.zst / assets/dsh/）—— 这个 APK 能安装，但起不了 DSH
APK 身份核验通过 ✔
```

只依赖 Python 3 标准库，不需要装 apktool。

**为什么值得做**：它确认三件装之前看不出来的事 —— 装上去会不会覆盖你的 DSHBox
（看 applicationId）、设置页会显示什么版本号、以及应用名各语言下对不对。

---

## 3. 安装

> ### ✅ 这一次**不要卸载**，直接覆盖升级
>
> 手机上现在装的 v5 已经是**固定密钥库**签的（DIV-0017 之后的构建），
> 新构建用同一个密钥库，签名一致 ⇒ **可以直接覆盖安装**。
>
> ```
> 点开安装包 → 安装（会提示「是否更新此应用」）→ 更新
> ```
>
> 覆盖升级**不会清掉应用私有数据**，包括已经下好的几百 MB 运行环境层。
> 这一点这一轮特别重要：手机上 `files/runtime/runtime-current/base` 已经在位，
> 直接升级还能用；一旦卸载，那几百 MB 就得重新获取一次。
>
> 如果你点安装时报「签名不一致 / 应用未安装」，那说明手机上装的**不是** v5，
> 这时才需要先卸载。可以先核对下面这个指纹再决定。
>
> 想确认 APK 的签名是对的？APK 的证书 SHA-256 指纹应该是：
>
> ```
> 76:D5:88:D4:4A:AD:12:B0:0A:AC:0A:15:87:73:8B:C9:
> 1E:FC:18:2B:7C:0B:00:B2:88:A5:82:60:58:E9:81:26
> ```
>
> （`apksigner verify --print-certs app-debug.apk`，或看 CI 日志里
> `Restore signing keystore` 那一步打印的指纹。）

> ### ⚠️ 2026-10-14 记录：为什么之前每次都要卸载
>
> DIV-0017 之前，每个 CI runner 都**现生成**一个随机调试密钥 ——
> `gradle/actions/setup-gradle` 只缓存 `~/.gradle`，不缓存 `~/.android`。
> 于是**每个构建都是一个不同的应用**，v3 / v4 / v5 每次都因签名不一致而卸载重装，
> 而卸载会连带清掉已下好的运行环境层。
> 现在密钥库固定，这个代价只付了最后一次（v5 那次）。

> ### ⚠️ 2026-10-14 记录：更早的两次安装
>
> 第一次**失败 -13**（`INSTALL_FAILED_CONFLICTING_PROVIDER`）：
> FileProvider 的 authority 写死成 `com.dshbox.app.fileprovider`，与已装的 DSHBox 撞车。
> authority 是**独立于 applicationId 的另一条身份轴**，Android 要求它全局唯一。
> 已按 DIV-0011 修复（两处 authority 都改为从 applicationId 派生）。
>
> 第二次**成功**（run 38510120522）。此后又装了 v3、v4，
> 每次都是因为签名不一致而卸载重装 —— 这正是 DIV-0017 要解决的问题。


**方式 A：手机浏览器**

把 APK 传到手机上（微信/网盘/数据线都行），用文件管理器点开安装。
会提示「未知来源应用」，允许即可。

**方式 B：adb**

```bash
adb install -r app-debug.apk
```

预期：`Success`。因为 applicationId 是 `com.dshfusion.app`，
**不会**覆盖你已装的 DSHBox（`com.dshbox.app`）—— 两者并存。

> 注意 `adb install -r` 只在签名一致时才能覆盖 —— 手机上装的是 v5 的话就可以直接覆盖，
> 见上面的说明。

---

## 4. 启动与运行环境获取

> **这一轮手机上已经有运行环境了。** `files/runtime/runtime-current/base` 在位
> （只有卸载才会丢），所以第 4 步这次**可以直接跳过**，直接点「启动 DSH」。
> 如果界面提示还需要获取，再走第 4 步。

1. 桌面找到 **DSHFusion** 图标（我自绘的那个，不是 DSHBox 的）点开。
2. 预期能看到启动页 → 主界面。如果**闪退**，把 logcat 抓下来：
   ```bash
   adb logcat -d | grep -iE 'dshfusion|dshbox|AndroidRuntime' | tail -80
   ```
   （闪退本身就是重要发现 —— 说明有真机才暴露的问题，CI 的单测覆盖不到。）
3. 进设置页，确认版本显示 **v0.1.0**。
   > 这一条是阶段 1 修过的一个真问题：修之前这里会显示 **v1.4.0**，
   > 因为版本号沿用了 DSHBox 的。看到 v0.1.0 才说明修复真的生效了。
4. （**这一轮可跳过**）找到运行环境获取的入口（在线获取 / 离线导入），触发在线获取。
   这一步会下载 Debian base + Node + DSH，**几百 MB，需要时间与稳定网络**。
5. 点「启动 DSH」。
6. **这一轮新增**：万一还是起不来，先别猜 —— 去
   「设置 → 诊断与日志 → 运行日志 → **DSH 进程日志**」把原文读出来。
   v5 的根因就是从那一条日志里读出来的（`error: unknown option '--patch'`），
   而 `logcat` 里只有 5 行、看不出任何东西。

### 4.1 ⚠️ 启动之后必须做的核验（这一节是新增的，别跳过）

**为什么新增**：上一版 APK 在真机上「启动」之后，界面显示
**「DSH 就绪」「http://127.0.0.1:3080」「已运行 00:00:49」** —— 三条读数全都正常，
而实际上 **DSH 一次都没起来**。那台 3080 是**另一个应用**（`com.dsh.client`）的 DSH。

所以：**界面说「就绪」不算数。** 请按下面的顺序做，做到哪一步算哪一步。

#### 第 1 步（一定要做）：看地址里的端口号

启动成功后，首页应该显示一个形如 `http://127.0.0.1:<端口>` 的地址。

| 看到什么 | 说明 |
|---|---|
| 端口是 **3080** | ❌ **可疑** —— 大概率又探到别人的服务了，请继续做第 2 步 |
| 端口是**别的数字**（如 42797） | ✅ 这正是修复的预期效果：端口由内核动态分配 |
| 显示**「地址待定」** | ⏳ 正常中间态 —— DSH 还没打印出它的地址，等几秒；一直这样就是没起来 |
| 显示**「地址待定」且「启动」按钮可以再点** | ✅ 至少没有卡死 —— 这是修复的第二个预期效果 |

> 修复前后最关键的区别**不是「能不能起来」，而是「起不来的时候界面说不说实话」**。
> 修复前：起不来却报就绪，且「启动」按钮永久灰掉，**无法重试**。
> 修复后：起不来就显示「地址待定」，且「启动」按钮仍可点。

#### 第 2 步（强烈建议）：用独立证据确认那个端口是自己的

界面和进程表**互相独立**，两边对上才算数。手机上有 root 或能用 `adb shell` 的话：

```bash
# ① DSHFusion 的进程里有没有 node？（只有 bash/sleep 说明 DSH 没起来）
ps -A -o USER,PID,ARGS | grep -E 'dshfusion|proot' | grep -v grep

# ② 界面显示的那个端口，owner 是谁？（uid 应该是 DSHFusion 的，不是别人的）
grep -i ':0C08' /proc/net/tcp      # 0C08 = 3080；换成你实际看到的端口
```

端口换算：`/proc/net/tcp` 里的端口是十六进制。`0C08` = 3080，
`A735` = 42797。同一行末尾的 `uid` 字段就是属主。

**判断标准**：

- 进程表里有 `node … web --no-open --host 127.0.0.1 --port <N>`
- **且** `/proc/net/tcp` 里 `<N>` 那一行的 uid = DSHFusion 的 uid
- **且** 界面显示的 `<N>` 与上面两处一致

三条都对上 = 真的起来了。任何一条不对 = 没起来（那就把证据发我）。

> 顺便说明：`已运行 00:00:49` 这个计时是**界面自己的本地计数器**，
> 跟进程死活没有关系。它是上一版误导里看起来最"可信"的一条读数 ——
> 所以这一版特意保留它，但请**不要**用它判断 DSH 是否在跑。

#### 第 3 步：点「浏览器」真的能打开 DSH 界面

修复后这个按钮会带上**本实例的**地址与启动令牌。点开应该直接进入 DSH 的 Web 界面
（不是登录页、不是错误页）。如果弹「启动令牌尚未就绪」，说明 DSH 还没打印出地址，
回到第 1 步。

#### 如果还是起不来：去应用自己的日志里读原文

`logcat` 对这个问题基本没用（实测只有 5 行），`/data/data` 也读不到。
**唯一能看到 DSH 真实报错的地方是应用内建的日志页**：

```
设置 → 诊断与日志 → 运行日志 → DSH 进程日志（process-dsh.log）
```

上一轮的根因就是从那里读出来的：

```
dsh: fatal uncaught exception: Error: dsh: host preparation failed:
     No usable native binding found for node-addon-require-builtin-linux-arm64-gnu (auto)
```

`No usable native binding found for …-linux-arm64-gnu` 的意思是
**打进 APK 的那层是 x86_64 的**（DIV-0015）—— 层本身格式正确、内容也在，
只是架构错了，装到 arm64 手机上必然起不来。已修复，但如果你在新构建上
又看到这一行，说明层还是被在 x86_64 上建了，请把这段原文发我。

**再上一轮（v5）的根因，是从同一处读出来的另一行：**

```
WARNING: linker: Warning: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"
error: unknown option '--patch'
```

`error: unknown option '--patch'` 的意思是**启动命令的参数顺序错了**：
dsh 启动器只解析它自己的旗标（`--profile` / `--patch` / `--dump-*`），
**遇到第一个不认识的旗标，就把剩下的原样转交给被启动的应用**。
`--host` / `--port` 属于 web 应用，于是排在它们后面的 `--patch` 被交给了 web 应用，
web 应用不认识它就退出。修法是把 `--patch` 放回 `--profile` 紧后面。
（那一行 `WARNING: linker` 是 PRoot 环境里的正常噪音，不是错误，别被它带偏。）

> **这两行都属于「一眼看上去不像我们的问题」的那一类。** 上一行像是 node 装错了，
> 这一行像是 dsh 不认识自己的旗标 —— 两次都不是。所以别猜，把原文发我。

**如果第 4 步失败**，可能的原因（按可能性排序）：

- 清华镜像不可达（网络环境限制）—— 换网络或用离线整包导入
- 存储空间不足（base + node + dsh 加起来约 300–500 MB）
- app 需要特定权限（存储/网络）被系统拦下

---

## 5. 想更快看到结果？用离线整包

在线获取慢，而且依赖镜像可达。另一条路是先在本机把层构建好再导进去：

```bash
tools/build-dsh-layer.sh ./runtime/android-assets
```

这条命令**我已经真跑通过**：538 个包、118.6 MB、冒烟测试输出 `0.2.0-rc.2`、
sha256 `747c0b619b2b52bb5b73f98357efb51452da3116cb1f36588274cbd3cae67b9`。
它**不需要 arm64 主机**（用 `npm --os=linux --cpu=arm64 --libc=glibc` 交叉挑选即可），
但**必须显式声明目标平台**：`@deepseek-ai/dsh` 包本身是纯 JS，
它的依赖树里却有 7 个按平台分发的可选包，npm 默认按**构建宿主**挑 ——
在 x86_64 上建层会得到一套 `-linux-x64`，装到 arm64 手机上必然起不来（DIV-0015）。

然后按 app 里「离线导入整包」的流程把产物导进去。

---

## 6. 请把结果告诉我

不管成功还是失败，这几条信息对我都有用：

| 项 | 值 |
|---|---|
| 安装成功？ | |
| 启动成功（没闪退）？ | |
| 设置页显示的版本号 | |
| 应用名显示为 | |
| 与 DSHBox 并存安装？ | |
| authority 冲突是否已消失？ | |
| 运行环境获取结果 | |
| **首页地址里的端口**（是 3080 还是别的数字，或是「地址待定」） | |
| **进程表里有没有 `node … --port <N>`** | |
| **`/proc/net/tcp` 里那个端口的 uid 是不是 DSHFusion 的** | |
| **点「浏览器」能否真的打开 DSH 界面** | |
| 若失败，logcat 关键行 | |
| **若失败，「DSH 进程日志」里的原文**（这一条比 logcat 有用得多） | |

> 最后四条是这一版新加的。它们问的不是「能不能起来」，
> 而是「**界面说的和实际发生的是不是同一件事**」—— 上一版正是在这里骗过了所有人。
>
> 而最后一条是**唯一能看到 DSH 真实报错**的渠道：`logcat` 实测只有 5 行，
> `/data/data` 读不到。前两轮的根因都是从那一条日志里读出来的。

拿到之后我会把它记进
[docs/PHASE0-VERIFICATION.md](PHASE0-VERIFICATION.md)，
并把 AGENTS.md 里那一行状态改成实测结果。

> **在拿到这些结果之前，本仓库不会声称「真机验证通过」。**
> 这是硬约束第 4 条：没有实际执行的验证，不得标记为成功。

---

## 7. ✅ 成功之后应该看到什么（2026-10-14 实测基线，第六版 `5a7e7c7`）

**这一节不再是待办 —— 第六版已经在真机上跑通了。** 留存它有两个用处：
一是下一次升级后可以**照这个基线逐条对照**，二是省掉「成功长什么样」的猜测。

下面全部是从设备上直接读回来的，**没有一条来自界面**：

| 检查 | 实测读数 |
|---|---|
| DSHFusion 里有 `node` 进程 | ✅ `u0_a475 28990 node --expose-internals --import /opt/dshbox/link-shim.mjs … --port 0` |
| 它的完整启动命令 | `… bin.js --profile web --patch /root/projects/.dsh/dshbox/…-overlay.yml --host 127.0.0.1 --port 0` |
| `/proc/net/tcp` 里它监听的端口 | **39983**（`0x9C2F`），该行 **owner uid = 10475** = `com.dshfusion.app` |
| 同机上 3080 的 owner | uid **10402** = `com.dsh.client`（DSHA）—— 两个 DSH 并存，互不干扰 |
| 对端口发一个 HTTP 请求 | `401 dsh web authentication required` —— 这**正是 DSH 的应答**（DIV-0014 的判据） |

**关键点：端口是内核随机分配的（39983），不是 3080。** 看到 39983 说明
`--port 0` 生效了；只要这个端口的 owner uid 是 DSHFusion 的，就是对的。
**看到 3080 反而是可疑的** —— 那大概率又探到了别人的服务。

另外两个「看着像错、其实正常」的噪音：

- `WARNING: linker: failed to find generated linker configuration from
  "/linkerconfig/ld.config.txt"` —— PRoot 环境里的常态，不是错误。
- 进程的 `--port 0` 不等于实际端口。`--port 0` 是**请求内核分配**，
  真实端口在 DSH 打印的那一行里，也在 `/proc/net/tcp` 里。

> ✅ **界面那一半也已经核过了（同日第二轮，用户开了无障碍之后）。**
>
> 界面上读到的是 `DSH 就绪` + `http://127.0.0.1:39983` —— **与设备侧读到的端口一致**。
> 点「浏览器」确实打开了 DSH 的 Web 界面。而且这一条**没有只看页面标题就收工**：
> 页面标题是 `DeepSeek Harness`，但同机上还跑着 DSHA 的 DSH（3080），
> 两个都可能渲染出同一张引导页。所以又查了活的 TCP 连接：
>
> ```
> /proc/net/tcp（state 01 = ESTABLISHED）
> 0100007F:896A  0100007F:9C2F  01  …  uid 10139
> $ pm list packages -U | grep 10139   →  package:com.android.browser uid:10139
> ```
>
> `9C2F` = **39983**，其 LISTEN socket 的 owner uid 是 **10475** = `com.dshfusion.app`。
> 即：**浏览器正在跟 DSHFusion 自己的 DSH 说话**，不是 3080。
>
> 全链路至此逐环独立核验完毕：
> **进程 → 端口 → 界面 → 浏览器 → token → DSH 页面**。
> 完整记录见 [INCIDENTS.md](INCIDENTS.md) 第 14 条，截图在 `docs/evidence/`。
>
> 这一节保留下来，是因为下一次升级后仍可以照它逐条对照 ——
> **成功的样子也应该有基线**，否则每次都得重新猜。


---

## 8. 阶段 2 的真机验证（备份 / 恢复 / 自动备份）

> **这一节是阶段 2 唯一还没做的那件事。** 2.6 的六段都过了 CI，而 CI 只能证明
> 「编得过、测试跑得过、类在产物里」—— 它证明不了「恢复在真机上不会毁数据」。
> 下面每一项都写了**该看到什么**；看不到就是红，请把原话告诉我。

### 8.0 先决条件

- 用 §1 的方式拿到**最新一次绿色 CI** 的 `dshapp-debug-apk`。
- 装上并让 DSH **真正跑起来**一次（§4）。没跑起来的话，
  `user-data/.dsh` 里几乎是空的，后面几项看不到东西。

> ⚠️ **先手动导出一份留底再动任何恢复。** 下面的第 3、4 项会改 `user-data/.dsh`。
> 阶段 2 的全部意义就是让这件事可逆 —— 而验证「可逆」的第一步是自己留一份。

### 8.1 导出：看得见、位置对、大小合理

| 步骤 | 该看到什么 |
|---|---|
| 设置 → **备份与恢复** | 标题「备份与恢复」；范围四项（全部 / 会话 / 设置 / 插件） |
| 选「全部」→ **导出到下载目录** | 出现一行「已导出 DSHFusion-backup-<时间戳>.dshbak（N 字节，完整性 QUIESCENT）」 |
| 用文件管理器看 `Download/DSHFusion/` | 有那个文件；**大小在几十 KB 到几十 MB 之间**（DB 级的 0 字节或几百字节说明范围选错了） |

**完整性那一栏的三种可能**（`QUIESCENT` / `EXTERNAL_CHECKED` / `PARTIAL` / `BEST_EFFORT`）：
正常应当是 `QUIESCENT`。看到 `PARTIAL` 或 `BEST_EFFORT` 说明**有源校验失败**，
请把那一行原样告诉我 —— 那不是崩，是如实报告，但我要知道。

### 8.2 🔴 归档里**不能**有 API Key（这一项只能你来验）

CI 能证明「凭据投影的代码在场、单测全过」，**证明不了你的那份归档里没有你的密钥**。

用你的设备 shell（root 或 Shizuku 都可）跑：

```sh
# 把 sk-xxxx 换成你自己那把 key 的**前 8 个字符**（不必给出整把）
grep -a -c 'sk-你key的前8位' /sdcard/Download/DSHFusion/*.dshbak
```

**该看到 `0`。** 不是 0 就**立刻停止**，把那个数字告诉我 —— 那说明凭据排除没生效。

> 顺带也值得看一眼 `grep -a -c 'browser-session' …/*.dshbak` ——
> 那个键名是 DSH 的浏览器会话授权（机器本地记录），也应当在投影阶段被删掉，同样是 `0`。

### 8.3 恢复：先预检，再确认

| 步骤 | 该看到什么 |
|---|---|
| 「从归档恢复」→ **选择归档文件…** | 系统文件选择器；选 `Download/DSHFusion/` 里那份 |
| 选完 | 显示文件名；出现一行「将恢复 N 个根，覆盖 M 个，冲突 0 个」，**并有一行红色的「将被替换：…」** |
| 点**确认恢复** | 弹出对话框，标题「确认恢复这份归档？」 |
| 确认 | 「已恢复：N」 |

**「将被替换」那一行是这一屏最重要的东西** —— 别的数字都不告诉你「我会失去什么」。
它如果没出现（而覆盖数不是 0），请告诉我。

### 8.4 🔴 恢复真的把东西拿回来了吗（端到端的那一条）

这是**最关键**的一项：它证明导出的产物能被恢复读懂。

1. 让 DSH 跑起来，在浏览器里**新建一个会话**，随便打几个字，起个记得住的名字。
2. 导出一次（8.1），记下文件名。
3. **再新建一个会话**，名字不同。
4. 恢复刚才那份归档（8.3），确认。
5. 让 DSH 重新跑起来（恢复会换掉 `.dsh`，进程要重启才能看到）。

**该看到**：第 3 步那个会话**不见了**，第 1 步那个**还在**（而且能打开、内容在）。
这正是「恢复 = 换回导出那一刻的状态」。

### 8.5 🔴 坏归档：要**明确报错**，且不破坏现有数据

这一项对应阶段 2 的验收标准「坏备份要明确报错且不破坏现有数据」。

```sh
# 复制一份并截掉一半（不要动原件）
cp /sdcard/Download/DSHFusion/<那份>.dshbak /sdcard/Download/DSHFusion/broken.dshbak
# 截断到 1KB —— 头部还在、内容是半截
head -c 1024 /sdcard/Download/DSHFusion/<那份>.dshbak > /sdcard/Download/DSHFusion/broken.dshbak
```

然后用「选择归档文件…」选 `broken.dshbak`：

**该看到**：一行**红字**，内容是**一个固定的错误码**（例如 `TRUNCATED` / `SHA256_MISMATCH` /
`RECORD_LIMIT` 之类的大写下划线串）。
**不该看到**：「已恢复」；也**不该**出现 DSH 再也起不来的情况。

验完之后：让 DSH 重新跑一次，确认它**照常能起来、数据还在**。这一条比报错本身更重要。

### 8.6 自动备份：四种模式里的三种

| 步骤 | 该看到什么 |
|---|---|
| 打开「自动备份」开关 | 出现三种模式（每隔几小时 / 每天一次 / 沙箱停止后）+ 「立即备份一次」 |
| 选「每隔几小时」并选 1 小时 | 间隔那几个按钮里 1 小时那个高亮 |
| 点**立即备份一次** | 一行结果（形如 `DSHFusion-backup-….dshbak（… 字节，清理 N 份）`）；「上次备份」变成刚才的时间 |
| 再点两次「立即备份一次」 | 每点一次生成一份；**`Download` 之外**的自动备份目录里最多留 3 份 |

> 自动备份存放在**应用私有目录**（`/data/data/com.dshfusion.app/files/backups`），
> 不在下载目录 —— 所以它的清理不会动你手动导出的那些。**手动与救援归档永不删除**，
> 这是保留策略里明确写死的一条。

**「沙箱停止后」这一档有一个已知缺口**（见下）—— 如果你只用界面上的「停止」，
它会到期；从**通知栏**停止则不会。

### 8.7 请把结果告诉我

按 §6 的方式，逐项说「第几项、看到了什么、和上面写的一样还是不一样」。

**特别想要原始文本的**：8.2 的两个计数、8.4 的第 5 步看到了什么、
8.5 报出的那个错误码、以及任何红字。

### 8.8 已知缺口（不用你去发现，我已经知道）

1. **`stop` 模式只有界面上的「停止」会记时刻**；通知栏的停止走 `SandboxService`
   内部路径、**没有记**。所以从通知栏停止之后，`stop` 模式不会到期。
   这是**已知缺口**，不是「大概能用」。
2. **恢复只有一个开关，不能逐根挑选。** 选择了「只恢复会话」而拿到一份全量归档时，
   设置与插件也会被恢复进去 —— 上游把它做成了通配符，我们目前照它的语义走。
   所以**别用错范围去恢复一份全量归档**。
3. `stop` / `daily` 的**时钟精度是 15 分钟**（心跳周期）。对「每天一次」可以忽略。
