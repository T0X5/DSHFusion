# 把 DSHFusion 装到真机上（手动步骤）

> 这份文档是给**你自己执行**用的。我没有操作你的手机。
> 每一步都写清了预期结果 —— 如果某一步的结果和预期不符，**停下来**，
> 把实际输出记下来，那是有价值的信息，不要硬往下走。

---

## 0. 先明确：你会得到什么，不会得到什么

**会得到**：一个能安装、能启动、能在设置里看到「DSHFusion v0.1.0」的应用，
它能与 DSHBox 并存安装（不同的 applicationId）。

**不会得到**：一个装完就能用的 DSH。原因不是缺陷，是上游的设计：

| 层 | 在 CI 产出的 APK 里吗 |
|---|---|
| `android-side`（JNI 库） | ✅ 在（`assets/dshbox/libdshbox-link.so`） |
| `dsh`（DSH 本体，纯 JS） | ❌ 不在 |
| `base`（Debian 根文件系统） | ❌ 不在 |
| `node` | ❌ 不在 |

APK 里只有 `assets/runtime-online/`（在线获取的引导骨架：apt 源、dpkg 元数据、
`bootstrap.index`）和 `assets/terminal-packages/`（24 个终端用 `.deb`）。

**所以首次启动后需要让 app 自己把运行环境拉下来**，见第 4 步。
这一步依赖网络，而且 Debian 源用的是清华镜像
（`mirrors.tuna.tsinghua.edu.cn`，见 `assets/runtime-online/bootstrap/etc/apt/sources.list`）。

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

**方式 A：手机浏览器**

把 APK 传到手机上（微信/网盘/数据线都行），用文件管理器点开安装。
会提示「未知来源应用」，允许即可。

**方式 B：adb**

```bash
adb install -r app-debug.apk
```

预期：`Success`。因为 applicationId 是 `com.dshfusion.app`，
**不会**覆盖你已装的 DSHBox（`com.dshbox.app`）—— 两者并存。

> ⚠️ 这是 debug 签名的 APK。将来如果你换成正式签名，必须先卸载再装，
> 否则会报签名不一致。

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
5. 获取完成后启动 DSH。

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
| 运行环境获取结果 | |
| 若失败，logcat 关键行 | |

拿到之后我会把它记进
[docs/PHASE0-VERIFICATION.md](PHASE0-VERIFICATION.md)，
并把 AGENTS.md 里「真机验证 ❌ 未做」那一行改成实测结果。

> **在拿到这些结果之前，本仓库不会声称「真机验证通过」。**
> 这是硬约束第 4 条：没有实际执行的验证，不得标记为成功。
