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

> ### ⚠️ 这一次仍然需要先卸载一次（而且只有这一次）
>
> 你现在手机上装的那个版本，是用 **CI 每次现生成的随机调试密钥**签的。
> 新构建改用**固定密钥库**（DIV-0017），所以两者签名不同，
> 覆盖安装会报「签名不一致」。
>
> ```
> 设置 → 应用 → DSHFusion → 卸载
> ```
>
> **从这次之后就不需要再卸载了** —— 以后每个构建都是同一个身份，
> 可以直接覆盖升级（`adb install -r` 或点安装包）。
>
> 代价说明：卸载会清掉应用私有数据，包括已经下好的运行环境层
> （几百 MB，下次启动要重新获取）。这是最后一次付这个代价。
>
> 想确认新构建的签名是对的？看 APK 的证书 SHA-256 指纹应该是：
>
> ```
> 76:D5:88:D4:4A:AD:12:B0:0A:AC:0A:15:87:73:8B:C9:
> 1E:FC:18:2B:7C:0B:00:B2:88:A5:82:60:58:E9:81:26
> ```
>
> （`apksigner verify --print-certs app-debug.apk`，或看 CI 日志里
> `Restore signing keystore` 那一步打印的指纹。）

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

> 注意 `adb install -r` 只在签名一致时才能覆盖。第一次仍需按上面的说明先卸载。

---

## 4. 首次启动与运行环境获取

1. 桌面找到 **DSHFusion** 图标（我自绘的那个，不是 DSHBox 的）点开。
2. 预期能看到启动页 → 主界面。如果**闪退**，把 logcat 抓下来：
   ```bash
   adb logcat -d | grep -iE 'dshfusion|dshbox|AndroidRuntime' | tail -80
   ```
   （闪退本身就是重要发现 —— 说明有真机才暴露的问题，CI 的单测覆盖不到。）
3. 进设置页，确认版本显示 **v0.1.0**。
   > 这一条是阶段 1 修过的一个真问题：修之前这里会显示 **v1.4.0**，
   > 因为版本号沿用了 DSHBox 的。看到 v0.1.0 才说明修复真的生效了。
4. 找到运行环境获取的入口（在线获取 / 离线导入），触发在线获取。
   这一步会下载 Debian base + Node + DSH，**几百 MB，需要时间与稳定网络**。
5. 获取完成后点「启动 DSH」。

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
它不需要 Debian 沙箱或 arm64 主机（`@deepseek-ai/dsh` 是纯 JS 的 npm 包）。

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

> 最后四条是这一版新加的。它们问的不是「能不能起来」，
> 而是「**界面说的和实际发生的是不是同一件事**」—— 上一版正是在这里骗过了所有人。

拿到之后我会把它记进
[docs/PHASE0-VERIFICATION.md](PHASE0-VERIFICATION.md)，
并把 AGENTS.md 里「真机验证 ❌ 未做」那一行改成实测结果。

> **在拿到这些结果之前，本仓库不会声称「真机验证通过」。**
> 这是硬约束第 4 条：没有实际执行的验证，不得标记为成功。
