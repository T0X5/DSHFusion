# 阶段 1 前置审计：运行时资产 · 许可证 · 数据迁移

**审计对象**：DSHBox 锁定提交 `266290a6d16fa1ea08b3b734cbfe726437a75fb2`
**审计方式**：对只读检出 `audit/DSHBox` 的源码阅读与脚本分析
**审计时间**：2026-10-13

> **本文档的边界。** 全部结论来自**源码与脚本阅读**。
> **未执行任何 Android 构建**（环境无 JDK / Android SDK / NDK / Gradle），
> **未连接任何设备**。凡标注「设计上可行」的路径，都**没有**经过实际运行验证。
> 这一点在阶段 1 的真机验收里必须补上。

---

## 0. 三项审计的结论

| 审计项 | 结论 | 是否阻塞阶段 1 |
|---|---|---|
| 运行时资产 | **可获取**。四层全部有公开或仓库内来源；但 dsh 层缺构建脚本 | 否（需自建一个打包步骤） |
| 许可证 | **义务清晰**。GPL-3.0 主干 + 三个 Apache-2.0 可分离模块 + 商标硬约束 | 否（私有自用阶段义务未触发） |
| 数据迁移 | **无法自动迁移**。applicationId 变更 = 全新私有目录，这是 Android 沙箱的必然结果 | 否（需在文档与首启提示里说清） |

---

## 1. 运行时资产审计

### 1.1 四层构成与来源

DSHBox 的运行环境由四层组成。**关键事实：四层全部可获取**，但来源各不相同。

| 层 | 内容 | 来源 | 在仓库内 | 可复现 |
|---|---|---|---|---|
| `dsh` | DeepSeek Harness（npm 包及子包） | npm `@deepseek-ai/dsh` | 否（构建产物） | 是（公开 npm） |
| `android-side` | PRoot + loader + shmem + talloc | `DSHBox:app/src/main/jniLibs/arm64-v8a/` | **是，784 KB** | 是（仓库内预编译二进制） |
| `base` | Debian 13 (trixie) minbase rootfs | `debootstrap` ← `http://deb.debian.org/debian` | 否（构建产物） | 是（公开 Debian 归档） |
| `node` | Node.js 24.19.0 | `https://nodejs.org/dist/` | 否（构建产物） | 是（公开，SHASUMS256 校验） |

**证据**：
- `DSHBox:runtime-bundle/build_base.sh:22,24,37` — `SUITE=trixie`、`MIRROR=http://deb.debian.org/debian`、`debootstrap --arch=arm64 --variant=minbase`
- `DSHBox:runtime-bundle/build_node.sh:23,36,46` — `NODE_VERSION=24.19.0`、从 nodejs.org 下载、对官方 `SHASUMS256.txt` 校验
- `DSHBox:runtime-bundle/build_android_side.sh:29,62-64` — `ANDROID_SIDE_SRC` 默认 `app/src/main/jniLibs/arm64-v8a`，要求 `libproot.so` / `libproot-loader.so` / `libandroid-shmem.so` 存在
- `DSHBox:runtime-bundle/scripts/install_dsh.sh:13` — `npm install --prefix /opt/dshapp/runtime npm:@deepseek-ai/dsh@${DSH_VERSION}`

### 1.2 构建 APK 实际需要哪两层

只有 `dsh` 与 `android-side` 进 APK。`base` 与 `node` 由「在线获取运行环境」现场组装，或由离线整包导入。

**证据**：
- `DSHBox:app/build.gradle.kts:78` — `assets.srcDirs("../../runtime/android-assets")`
- `DSHBox:README.md:231-232` — 该目录含 `runtime/android-side.tar.zst` 与 `dsh/0.2.0-rc.2.tar.zst`，两者标注「**内嵌进 APK**」
- `DSHBox:README.md:233` — `runtime/offline-baseline/{base,node}.tar.zst` 标注「**不进 APK**」
- `DSHBox:README.md:237` — 实测 APK 内条目「只有 `assets/dsh/*` 与 `assets/runtime/android-side.tar.zst`」

**对阶段 1 的意义**：要让 `assembleDebug` / `assembleRelease` 产出可用 APK，
DSHFusion 只需准备这两层 —— 一层来自 npm，一层已在仓库内。**这比预想的门槛低得多。**

### 1.3 压缩格式：两种都支持，不是缺陷

`DSHBox:tools/pack_runtime.sh:46-56` 的实际行为：

```
COMPRESSION="${PACK_COMPRESSION:-gzip}"     # 默认 gzip
EXT="tar.gz"
if [ "$COMPRESSION" = "zstd" ]; then ... EXT="tar.zst"; fi
OUT_FILE="$OUT_DIR/${LAYER_NAME}.${EXT}"     # 文件名跟随实际格式
```

输出扩展名**跟随实际压缩格式**，消费侧 `build_apk.sh` 用 `ls "$src/${layer}.tar."*` 通配匹配。
所以 README 里的 `.tar.zst` 与脚本默认的 `.tar.gz` 是**两种受支持的形态**，不是矛盾。

> **一处文档漂移**：`pack_runtime.sh:41-45` 的注释说降级到 gzip 是因为
> 「zstd-jni 在当前离线构建里无法解析」。但 zstd-jni **已经入库**
> （`libs/zstd-jni-1.5.7-15.aar`、`libs/zstd-jni-1.5.7-15-classes.jar`，
> 并由 `app/build.gradle.kts:129` 引用，arm64 `.so` 在 jniLibs）。
> 该注释已过期。不影响功能，但会误导下一个人。

### 1.4 仓库内已入库的资产（阶段 1 会一并导入）

| 路径 | 体积 | 内容 |
|---|---:|---|
| `DSHBox:app/src/main/assets/terminal-packages/` | 16 MB | 24 个 `.deb`（binutils / fakeroot / htop / jq 等） |
| `DSHBox:app/src/main/assets/runtime-online/` | 293 KB | 在线装配的引导包（bootstrap / dpkg 索引 / links.map） |
| `DSHBox:app/src/main/jniLibs/arm64-v8a/` | 784 KB | PRoot / loader / shmem / talloc / zstd-jni |
| `DSHBox:app/src/main/jniLibs/x86_64/` | — | 同上，x86_64 版 |
| `DSHBox:libs/` | ~1 MB | zstd-jni aar + classes jar |

仓库工作树 30 MB（不含 `.git`），`.git` 22 MB。**导入规模是可控的。**

### 1.5 审计发现的三个风险

| # | 风险 | 严重度 | 处置 |
|---|---|---|---|
| R1 | **dsh 层没有仓库内构建脚本**。`build_arm64_runtime_bundle.sh:45,108,112` 明确只做 base/node/android-side 三层并打印「no DSH」「DSH is a SEPARATE product」；`install_dsh.sh` 是**guest 内**安装脚本，不是打包脚本 | 中 | ✅ **已解决**：`tools/build-dsh-layer.sh`，已真跑通（见 1.6） |
| R2 | **PRoot 二进制是预编译 vendored**，构建来源未在仓库内记录。`THIRD_PARTY_NOTICES.md` 写「二进制来自 termux-packages 构建」，但没有可核对的版本号或校验和 | 中 | 记录来源假设；不声称已核验 |
| R3 | **`install_dsh.sh:8` 默认版本 `0.1.0-rc.6`** 与实际内嵌的 `0.2.0-rc.2` 不一致 | 低 | ⚠️ **部分解决，此前我把它说过头了**（见下方更正） |

### 1.6 阶段 1 的处置

1. **把 `assets.srcDirs` 改为可配置**（`DIV-0003`）：`DSHFUSION_RUNTIME_ASSETS` 环境变量覆盖，
   保留 `../../runtime/android-assets` 作为默认值。
   → 这样 `testDebugUnitTest` 与 `lintDebug` **不需要任何运行时资产**即可运行。
2. **保留两条获取途径**：在线获取（Debian 官方源）与离线整包导入，都不改动。
3. **自建 dsh 层打包步骤**（补 R1）—— ✅ **已完成并实测**：`tools/build-dsh-layer.sh`。

   ```bash
   tools/build-dsh-layer.sh [输出目录]     # 默认 ./runtime/android-assets
   ```

   版本不写死在脚本里，而是从 `governance/upstreams.json` 的
   `transitive_upstreams[id=deepseek-harness].tracked_version` 读。

   > ### ⚠️ 更正（2026-10-13）：R3 没有被「从结构上消除」
   >
   > 我最初在这里写的是「R3 已从结构上消除，单一事实来源让两处不可能再分歧」。
   > **这个说法是错的，已改正。**
   >
   > 新脚本只是**新增了一条正确的路径**，它并没有碰 `install_dsh.sh:8` 里那个
   > `0.1.0-rc.6` 默认值。那个默认值当时仍然存在，仍然会在被直接调用时装错版本。
   > 换句话说：我增加了一个不会漂移的入口，但**没有消除原有的漂移源** ——
   > 这是两件不同的事，我混为一谈了。
   >
   > 现在已按 **DIV-0010** 处理：`install_dsh.sh` 取消默认值，未显式指定
   > `DSH_VERSION` 就报错退出（2）。静默装出一个错版本，比大声拒绝危险得多。
   >
   > 补充一个查证结果：**本脚本在 DSHFusion 里没有任何地方执行它。**
   > app 走的是 `SandboxManager.installDshFromNpm` 的 Kotlin 路径；
   > `RuntimeUpdateManager.kt:68` 与 `DshUpdateScreen.kt:72` 提到它，但都只是注释。
   > 所以这条偏离修的是一个**潜在的坑**，不是正在发作的 bug。

   脚本会：核对装出来的版本与 pin 一致（不一致直接失败，否则「已适配 X」变成假话）；
   跑 `node lib/bin.js --version` 冒烟测试；有 `zstd` 用 zstd、否则退回 gzip
   并明确告知（不静默改后缀，否则文件名与格式不符会让解包方失败）；
   产出 `<version>.tar.zst` 与同目录 `<version>.tar.zst.sha256`。

   **实测证据**（2026-10-13，本机 aarch64）：
   `added 538 packages in 2m`、层 **118.6 MB**（gzip）、
   sha256 `747c0b619b2b52bb5b73f98357efb51452da3116cb1f36588274cbd3cae67b9`、
   冒烟测试输出 `0.2.0-rc.2`。

   **不需要 Debian 沙箱，也不需要 arm64 主机**：`@deepseek-ai/dsh` 是纯 JavaScript
   的 npm 包，`optionalDependencies` 为空、无 `os`/`cpu` 约束，所以在 x86 开发机上
   也能构建同一个层。这一点是审计时实测确认的，不是推断。

   > 层内布局必须与 `DefaultSandboxManager.dshLayerDir()` 的期望一致：
   > 解包后得到 `node_modules/@deepseek-ai/dsh/lib/bin.js`，运行时整层被 bind
   > 到 guest 的 `/opt/dshapp/runtime`。脚本按此布局安装。
4. **`assembleDebug` 作为可选的慢通道**：需要资产，缺失时给出可读错误而不是晦涩的 Gradle 失败。

---

## 2. 许可证审计

### 2.1 上游许可

| 上游 | 许可 | 版权行 |
|---|---|---|
| DSHBox（骨干） | **GPL-3.0** + `TRADEMARK.md` 作为 GPL-3.0 §7(e) 附加条款 | `Copyright (C) 2026 WSK-build` |
| DSHA（来源） | **MIT** | `Copyright (c) 2026 qiannianhuanxiang` |

MIT 可被吸收进 GPL-3.0，反之不行 ⇒ **DSHFusion 一旦公开分发，整体必须是 GPL-3.0。**
私有自用不分发，GPL 的分发义务不触发。

### 2.2 模块级许可边界（实测，不是推断）

| 模块 | 独立 LICENSE 文件 | 实际许可 |
|---|---|---|
| `app` | 无 | 继承根 LICENSE → GPL-3.0 |
| `common` | 无 | GPL-3.0 |
| `bridge` | 无 | GPL-3.0 |
| `sandbox-manager` | 无 | GPL-3.0 |
| `plugin-manager` | 无 | GPL-3.0 |
| `terminal-session` | 无 | GPL-3.0 |
| `terminal-view` | **无** | 继承根 → 名义 GPL-3.0；但源码取自 Termux，**原始许可是 Apache-2.0** |
| `terminal-emulator` | **无** | 同上 |
| `pilot/interlock-relay-core` | **有** | **Apache-2.0** |
| `pilot/dshbox-adapter` | **有** | **GPL-3.0** |

**关键结论**：真正可分离的是 `interlock-relay-core`（Apache-2.0）。
`terminal-emulator` / `terminal-view` **没有随附 Apache-2.0 许可副本**，
其 Apache-2.0 身份只体现在 `THIRD_PARTY_NOTICES.md` 的说明里
（已登记为不继承的上游缺陷）。`dshbox-adapter` 是 GPL-3.0，与主干同许可。

### 2.3 第三方清单的完整性

`DSHBox:THIRD_PARTY_NOTICES.md` 的质量**显著高于一般项目**：

- 逐个组件给出用途、许可证、分发形态与上游链接；
- 区分「随 APK 分发」与「仅测试期」；
- 说明传递依赖（如 Markwon → commonmark BSD-2-Clause）；
- 说明「重写而非复制」的情况（dshmarket：按其设计重写，未随包分发其代码）；
- 说明验签公钥来源（Debian 官方归档钥，非第三方许可物）。

覆盖的组件包括：PRoot (GPL-2+)、talloc (LGPL-3+)、Debian rootfs（逐包）、
DSH/Cordis (MIT)、Node.js (MIT)、Termux terminal-* (Apache-2.0)、
sora-editor (LGPL-2.1-or-later)、Markwon (Apache-2.0)、commons-compress (Apache-2.0)、
zstd-jni (BSD-3-Clause)、Bouncy Castle (MIT)、interlock-relay-core (Apache-2.0)、
Shizuku (MIT)、AndroidX/Compose/Kotlin (Apache-2.0)、JUnit (EPL-2.0) 等。

> **我无法验证它是否穷尽。** 核对一份依赖清单是否完整，需要对全部
> Gradle 依赖树与实际打包内容做比对。本环境无 Gradle、无法解析依赖树，
> 因此**没有做这项验证**。阶段 1 引入 Gradle 后应补上
> （`./gradlew :app:dependencies` 对照清单）。

### 2.4 商标约束（对 DSHFusion 有直接约束力）

`DSHBox:TRADEMARK.md` 是 GPL-3.0 §7(e) 的附加条款，**具有许可效力**，不是建议。

对 DSHFusion 的硬约束：

- 不得使用 `DSHBox` 名称；
- 不得复制、修改、再分发其品牌标识（`brand_icon.png`、`ic_pilot_brand.png` ×5 密度、
  `ic_splash_mark.png` ×2 密度）；
- 不得制作**相同或近似**的标识 —— 包括改变比例、旋转角度等变形；
- **不得将其标识的构造方法、生成方案用于开发近似标识**（§四）。

> 幸运的是：`TRADEMARK.md:36` 明确「设计源文件（原始 SVG、画板、方案包）**不在本仓库内**」，
> 所以即便想误用也拿不到设计源。DSHFusion 必须**从零设计**自己的图标。

#### 2.4.1 受保护的图形共 8 个文件（实测）

`DIV-0002` 最初的登记**只列了 `brand_icon.png` 一个**，漏掉 7 个。逐个查证后的完整清单：

| 文件 | 份数 | 引用点 |
|---|---:|---|
| `DSHBox:app/src/main/res/mipmap-xxxhdpi/brand_icon.png` | 1 | `AndroidManifest.xml:54,55` 的 `android:icon` 与 `android:roundIcon` |
| `DSHBox:app/src/main/res/drawable-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_pilot_brand.png` | 5 | `HomeScreen.kt:305` |
| `DSHBox:app/src/main/res/drawable-{xxhdpi,xxxhdpi}/ic_splash_mark.png` | 2 | `LaunchScreen.kt:93,101` |

上游没有自适应图标（无 `mipmap-anydpi*`），应用图标只存在于 xxxhdpi 一档。
`drawable-nodpi/ic_launcher_fg_empty.png` 无任何代码或布局引用，判定为通用占位图，不在保护范围。

**同时修掉了一个假性通过。** 原先的偏离重放只改 `app_name`、完全不看图形，
于是会报告「DIV-0002 已生效」，而 8 个受保护的品牌资产原样留在包里。
现在 `tools/apply-divergences.sh` 按登记的上游 SHA-256 **正向核验**这 8 个文件 ——
只要任一仍与上游一致，就报 FAILED 并点名具体文件。
三种状态（已替换 / 仍为上游 / 文件缺失）都有隔离夹具覆盖，见 [tools/README.md](../tools/README.md)。

#### 2.4.2 DSHFusion 的替代图形是**生成**出来的，不是手绘后提交

8 个替代图形全部由 `tools/make-brand-assets.py` 生成：

```bash
tools/make-brand-assets.py            # 生成
tools/make-brand-assets.py --check    # 重新渲染并逐字节比对（CI 用这个）
```

- 渲染器是**纯 Python**（只用 `zlib` 做 PNG 编码，不依赖 Pillow）。
  CI 里没有 Pillow，也不该为画图标装一个图像库。
- `--check` 在**临时目录**里重渲染再比对，不落盘到仓库 ——
  这样 CI 能证明「提交的图确实由脚本生成」，又不会因为 CI 自己改了文件而变脏。
- 生成器是自写的 SDF 光栅化，**没有复用上游的标识构造方法** ——
  这是 `TRADEMARK.md` §四 明确禁止的。

> 记录一个曾经踩过的坑：`ic_splash_mark.png` 的**上游原图是白底透明前景**
> （白图形 + 全透明背景），在浅色启动背景上等于看不见。
> 上游自己就有这个潜在缺陷。我们的替代图没有继承它。

### 2.5 DSHFusion 的义务

**私有自用（当前状态）**：
- 保留上游 `LICENSE` / `THIRD_PARTY_NOTICES.md` / `TRADEMARK.md` 原样（已做，哈希受 CI 保护）；
- 在 `governance/ATTRIBUTION.md` 记录来源与偏离；
- 无需对外提供源码。

**一旦公开分发**，按 `LICENSE-PENDING.md` 的清单逐项完成，其中最关键的是：
- 整体以 GPL-3.0 发布并提供完整对应源码；
- 补 `terminal-emulator` / `terminal-view` 的 Apache-2.0 许可副本；
- 自己的图标与名称，不复用上游品牌资产。

---

## 3. 数据迁移审计

### 3.1 数据布局（实测）

DSHBox 的全部数据都在 `context.filesDir`，即 `/data/data/com.dshbox.app/files/`：

| 路径 | 内容 | 属主 |
|---|---|---|
| `files/user-data/` | **用户工作区** | 用户 |
| `files/user-data/.dsh/` | DSH 自身数据：凭据、会话 | 用户 |
| `files/user-data/.dsh/dshbox/` | guest 内的 DSHBox 资产（`bin/dsh`、插件） | 随包资产 |
| `files/runtime/runtime-current/{base,node,dsh,android-side}` | 运行环境四层 | 可重建 |
| `files/runtime/previous/` | 上一层备份（单份，可回滚） | 可重建 |
| `files/guest-cache` | guest 缓存，**刻意不进用户数据** | 可丢弃 |
| `files/online-runtime-staging`、`online-node-staging` | 在线装配暂存 | 可丢弃 |

SharedPreferences：`dshapp_prefs`（`Constants.PREFS_NAME`）、
`app_settings`（语言 + 主题）、`app_update_ignore`（忽略的更新版本）。

**权限侧**：`AndroidManifest.xml` **未声明** `MANAGE_EXTERNAL_STORAGE`，
也未声明 `READ/WRITE_EXTERNAL_STORAGE`。这是刻意的 —— DSHBox 把数据全部留在应用私有目录。

### 3.2 applicationId 变更的后果

`filesDir` 的路径**包含 applicationId**。把 `com.dshbox.app` 改成 `com.dshfusion.app` 后：

- DSHFusion 拿到的是 `/data/data/com.dshfusion.app/files/` —— **一个全新的空目录**；
- Android 的应用沙箱禁止一个应用读取另一个应用的私有目录，**同一用户身份也不行**；
- 因此**没有任何自动的数据延续**。DSHFusion 首启就是一个干净的应用。

**这是 Android 平台行为，不是 DSHBox 的限制，也无法通过改代码规避。**

### 3.3 会丢什么

若用户此前真正用过 DSHBox：

| 数据 | 能否重建 | 迁移价值 |
|---|---|---|
| DSH 凭据 / API Key（`user-data/.dsh/`） | 需人工重填 | **高** |
| DSH 会话与历史 | 不可重建 | 高 |
| 已安装插件 | 可重装 | 中 |
| 运行环境四层（数百 MB） | 需重新下载或离线导入 | 中（耗时/流量） |
| 应用设置（主题、语言、忽略版本） | 手工重设 | 低 |

### 3.4 迁移路径评估

| 路径 | 可行性 | 代价 | 结论 |
|---|---|---|---|
| **A. 不迁移** | 一定可行 | 重新配置 + 重新获取运行环境 | **推荐**（若尚未重度使用 DSHBox） |
| **B. 经 DSHBox 内置 Files 导出** | 设计上可行，**未实测** | 需人工操作；有凭据暴露风险 | 需要用户数据时的唯一非 root 通路 |
| **C. 复用已下载的离线整包** | 一定可行 | 无 | **强烈建议**：保留 `dshapp-runtime-debian-arm64-0.1.0.zip` 即可免去数百 MB 重新下载 |
| **D. root / adb 直接搬目录** | 需 root | DSHFusion 明确不要求 root | 不采用 |

**路径 B 的依据**（源码证据，但未运行验证）：
- `DSHBox:app/src/main/java/com/dshbox/app/ui/files/FilesScreen.kt:170` —
  `workspaceRoot = File(context.filesDir, "user-data")`，即文件页可浏览用户工作区
- `FilesScreen.kt:811,844` — `exportSelectionToTree(treeUri)` / `exportSelectionToZip(zipUri)`
- `DSHBox:app/src/main/java/com/dshbox/app/util/FileOps.kt:109,223` — `exportToTree` / `exportToZip` 实现
- `FileOps.kt:80` — `importFromUri`，导入侧对应能力

> ⚠️ **路径 B 的凭据风险**：`user-data/.dsh/` 内含 API Key 等凭据。
> 导出到 Download 或任何 SAF 位置，都是**明文落到应用沙箱之外**。
> 若走这条路：导出后立即在 DSHFusion 导入，然后**删除导出文件**，
> 并确认该位置不被云同步（如 Google 相册/网盘自动备份）。

> ⚠️ **路径 B 未经验证。** 我没有设备，无法确认导出/导入在真机上能跑通、
> 大目录导出是否会超时或 OOM、SAF 授权是否覆盖全部子目录。
> 阶段 1 真机验收时应把它列为待验证项。

### 3.5 建议

1. **阶段 1 不做自动迁移** —— 做不到，不要假装能做。
2. **并存安装是刻意设计**（`AGENTS.md` 命名约定要求与 DSHBox/DSHA 并存），
   所以「两份独立数据」是预期行为，**要在文档和首启提示里说清楚**，避免用户以为数据丢了。
3. **首启提示**：DSHFusion 首次启动时，若检测到同设备装有 DSHBox，提示
   「这是独立应用，不共享 DSHBox 的数据」并给出路径 C（离线整包导入）的入口。
   *（此提示属于阶段 1 之后的功能，阶段 1 先只做文档说明。）*
4. **优先路径 C**：在交付说明里明确告诉用户**保留那个离线整包 zip**。

---

## 4. 阶段 1 PR 实施计划

### 4.1 分支与结构

**不直接改 `main`。** 阶段 1 全部在分支 `phase-1/import-dshbox-base` 上进行，
以 PR 形式提交审阅。`main` 保持在阶段 0 的稳定基线不动。

### 4.2 步骤

步骤 2 之后的内容已由 `tools/import-upstream-base.py` 与
`governance/import-exclusions.json` 固化为可执行流程，不再依赖手工操作。

| # | 步骤 | 产出 | 工具 |
|---|---|---|---|
| 0 | **先修 `.gitignore`**：`runtime/`、`dist/` 加前导斜杠；放行 `libs/*.aar` | 避免 23 个文件静默丢失 | 见 §4.5 陷阱 1 |
| 1 | 从 `main` 建分支 `phase-1/import-dshbox-base` | 分支 | 手动（导入器强制检查） |
| 2 | 以 DSHBox `266290a6` 为源导入源码（**767** 文件，排除 23） | 主干代码 | `import-upstream-base.py --apply` |
| 3 | 原样保留上游 `LICENSE` / `THIRD_PARTY_NOTICES.md` / `TRADEMARK.md` | 许可文件（哈希仍受 CI 保护） | 随步骤 2 导入 |
| 4 | 施加 `DIV-0001`：`applicationId` / `namespace` → `com.dshfusion.app` | 并存安装能力 | `apply-divergences.sh` |
| 5 | 施加 `DIV-0002`：`app_name` → DSHFusion + **自绘图标** | 独立标识 | 脚本改名字，**图标必须人工绘制** |
| 6 | 施加 `DIV-0003`：`assets.srcDirs` 可被 `DSHFUSION_RUNTIME_ASSETS` 覆盖 | 无资产也能跑单测/lint | `apply-divergences.sh` |
| 7 | 施加 `DIV-0004` / `DIV-0005`：监测地址指向自身；不导入 `website/` | 去除上游硬引用 | 脚本 + 清单排除 |
| 8 | 改写 `.github/workflows/android.yml`<!-- future: .github/workflows/android.yml --> 中依赖 `website/` 的步骤 | 可用构建 CI | 见 §4.5 陷阱 2 |
| 9 | 自建 dsh 层打包脚本（补 R1） | `tools/build-dsh-layer.sh` | ✅ 已编写并实测（538 包 / 118.6 MB / 冒烟通过） |
| 10 | 自建 keystore（不复用任何上游证书） | 独立签名 | 尚未创建 |
| 11 | 跑 `./gradlew testDebugUnitTest lintDebug`，记录真实输出与退出码 | 验证证据 | **本机无 JDK/SDK，只能在 CI 跑** |

### 4.5 导入会踩的四个坑（2026-10-13 在一次性克隆里实测）

这些不是推测，是在 `/tmp` 的克隆里跑完整导入流程时真实撞到的。
全部已修，并已纳入 `tools/verify-phase0.sh` 第 4d 段（8 项）作为回归护栏。

**坑 1（最严重）：`.gitignore` 静默吞掉 23 个文件。**
DSHFusion 的 `.gitignore` 写的是 `runtime/` 与 `dist/`，**没有前导斜杠** ——
这种模式匹配任意层级的同名目录。后果：

- 上游 4 个名为 `runtime` 的源码包全部被吞：
  `app/src/main/java/com/dshbox/app/runtime/`（6 个）、
  `app/src/test/java/com/dshbox/app/runtime/`（4 个）、
  `pilot/interlock-relay-core/src/{main,test}/java/interlock/relay/core/runtime/`（各 6 个）
- `libs/zstd-jni-1.5.7-15.aar` 命中 `*.aar` 被吞 —— 而它是上游**刻意 vendored
  的构建依赖**（`app/build.gradle.kts:129` 引用），不是构建产物

文件会复制到磁盘但**不进提交**，克隆后直接构建失败，而且**没有任何提示**。
修正后 git 跟踪数 **744 → 767**。导入器现在会在 `--apply` 结尾重新检出被忽略的
导入文件并以非零码退出 —— 这道检查就是为它加的。

**坑 2：`website/` 的连带依赖。**
上游 `website/` 22 个文件属 `TRADEMARK.md` §4 保留范围，不导入。但
`.github/workflows/pages.yml`<!-- ignore-ref: .github/workflows/pages.yml --> 第 28 行写死
`path: website`，必须一并排除；
`android.yml`<!-- future: .github/workflows/android.yml --> 第 55-57 行还有一句「Check website asset version string」读取
`website/` 下的版本串，导入后必然失败，需人工改写。

**坑 3：同名文件会覆盖 DSHFusion 自己的治理文档。**
逐文件同名 2 个：`README.md`、`.gitignore`。
`.github/`、`docs/`、`tools/` 三个目录虽有重叠，但内部无同名文件，安全。
归档位置刻意选在**仓库根**（`README.upstream.md`）—— 上游 README 里有
`README.en.md`、`CHANGES.md`、`LICENSE`、`TRADEMARK.md` 四处相对链接，
放根目录它们才成立；挪到 `docs/` 子目录立刻产生 4 个断链。

**坑 4：引用检查器对行内路径只按仓库根解析。**
上游嵌套文档里的相对路径指的是它自己所在目录，按根解析会误报断链
（导入后实测 2 处，见 `plugin-manager/dshbox-plugins/README.md`）。
`check_markdown_links` 本来就是文件相对，`check_backtick_paths` 却不是。
已统一为双基准解析。

### 4.6 导入后必须依次跑的检查

在一次性克隆里全部实测过，任一失败都说明导入不完整，**不要提交**：

| 检查 | 预期退出码 | 实测结果 |
|---|---|---|
| `tools/check-references.py` | 0 | 39 个链接、64 个路径全部有效 |
| `tools/check-upstreams.py` | 0 | 契约 4 项通过 |
| `tools/check-license-hashes.py` | 0 | 5 个受保护许可原件哈希一致 |
| `tools/check-divergence-anchors.py --upstream <上游检出>` | 0 | 16 个锚点全部成立 |
| `tools/apply-divergences.sh --check` | **1** | 预期失败：DIV-0001/0002/0003/0004 待重放，DIV-0005 已生效 |

最后一项**退出码为 1 是设计如此**，不是缺陷 —— 它正是在告诉你还有四处偏离没施加。

### 4.3 验收清单

沿用 `docs/FUSION-PLAN.md` 的阶段 1 验收清单，其中与本审计直接相关的是：

- `@Test` 方法数 **≥ 1058**（DSHBox 基线，不允许净减少）
- `applicationId` = `com.dshfusion.app`，可与 DSHBox 并存安装
- 上游三个许可文件哈希与 `governance/upstream-license-hashes.json` 一致
- 未复用任何上游品牌资产（`brand_icon.png` / `ic_pilot_brand.png` / `ic_splash_mark.png`）
- `divergences.json` 的 `applied` 与实际改动逐项对应

### 4.4 明确不做的事

- **不做全量包名重命名**（721 处 `com.dshbox.app` → 留到阶段 4）
- **不改上游仓库**、不向上游推送
- **不自动发布**、不产出未经验证的版本
- **不声称构建成功**，除非真的跑过 `./gradlew` 并拿到退出码 0
- **不声称真机验证**，除非真的装到设备上跑过

---

## 5. 本次审计未能验证的事（诚实记录）

1. **没有执行任何 Android 构建。** 环境无 JDK / Android SDK / NDK / Gradle。
   `assembleDebug` 能否成功、需要多长时间、APK 多大 —— **全部未知**。
2. **没有真机验证。** 数据迁移路径 B（导出/导入）**设计上可行但未实测**。
3. **没有核对第三方清单是否穷尽。** 需要 Gradle 依赖树，本环境没有。
4. **没有下载任何运行时资产。** Debian 归档、Node.js 发行版、npm 包的
   实际可获取性、体积、耗时 —— 均未实测，仅基于脚本与文档判断。
5. **没有验证 PRoot 二进制的构建来源。** 只有 `THIRD_PARTY_NOTICES.md`
   的一句「来自 termux-packages 构建」，无版本号或校验和可核对。
6. **DSHBox 的 `@Test` 基线 1058 是本地计数结果**，其 README 声称 1203，
   差额 145 未解释。阶段 1 应以**实际跑出来的数字**为准，而不是任何文档里的数字。
