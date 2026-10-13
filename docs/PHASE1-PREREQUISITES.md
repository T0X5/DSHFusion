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
| R1 | **dsh 层没有仓库内构建脚本**。`build_arm64_runtime_bundle.sh:45,108,112` 明确只做 base/node/android-side 三层并打印「no DSH」「DSH is a SEPARATE product」；`install_dsh.sh` 是**guest 内**安装脚本，不是打包脚本 | 中 | DSHFusion 需自建一个打包步骤（见 1.6） |
| R2 | **PRoot 二进制是预编译 vendored**，构建来源未在仓库内记录。`THIRD_PARTY_NOTICES.md` 写「二进制来自 termux-packages 构建」，但没有可核对的版本号或校验和 | 中 | 记录来源假设；不声称已核验 |
| R3 | **`install_dsh.sh:8` 默认版本 `0.1.0-rc.6`** 与实际内嵌的 `0.2.0-rc.2` 不一致 | 低 | 已在 `docs/FUSION-PLAN.md` 登记为「不继承的上游缺陷」 |

### 1.6 阶段 1 的处置

1. **把 `assets.srcDirs` 改为可配置**（`DIV-0003`）：`DSHFUSION_RUNTIME_ASSETS` 环境变量覆盖，
   保留 `../../runtime/android-assets` 作为默认值。
   → 这样 `testDebugUnitTest` 与 `lintDebug` **不需要任何运行时资产**即可运行。
2. **保留两条获取途径**：在线获取（Debian 官方源）与离线整包导入，都不改动。
3. **自建 dsh 层打包步骤**（补 R1）：
   `npm install --prefix <staging> npm:@deepseek-ai/dsh@<pin>` → `tar` → `.sha256`，
   落到 `<assets>/dsh/<version>.tar.zst`。版本**必须显式 pin**，不用 `latest`。
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

| # | 步骤 | 产出 |
|---|---|---|
| 1 | 从 `main` 建分支 `phase-1/import-dshbox-base` | 分支 |
| 2 | 以 DSHBox `266290a6` 为源导入全部源码（790 文件） | 主干代码 |
| 3 | 原样保留上游 `LICENSE` / `THIRD_PARTY_NOTICES.md` / `TRADEMARK.md` | 许可文件（哈希仍受 CI 保护） |
| 4 | 施加 `DIV-0001`：`applicationId` / `namespace` → `com.dshfusion.app` | 并存安装能力 |
| 5 | 施加 `DIV-0002`：`app_name` → DSHFusion + **自绘图标** | 独立标识 |
| 6 | 施加 `DIV-0003`：`assets.srcDirs` 可被 `DSHFUSION_RUNTIME_ASSETS` 覆盖 | 无资产也能跑单测/lint |
| 7 | 施加 `DIV-0004` / `DIV-0005`：监测地址指向自身；不导入 `website/` | 去除上游硬引用 |
| 8 | 自建 dsh 层打包脚本（补 R1） | `tools/build-dsh-layer.sh` <!-- future: tools/build-dsh-layer.sh --> |
| 9 | 自建 keystore（不复用任何上游证书） | 独立签名 |
| 10 | 跑 `./gradlew testDebugUnitTest lintDebug`，记录真实输出与退出码 | 验证证据 |

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
