# 署名与许可义务

DSHFusion 以复用上游成熟实现为前提，因此署名不是形式，而是**许可合规的一部分**。
本文件是署名清单的权威来源。

> 上游许可原文**按原样**保存在 [upstream-licenses/](upstream-licenses/)，不要修改它们。
> 修改后的副本不再具有证明力。

## 一、直接上游

### DSHBox — 骨干（GPL-3.0）

```
DSHBox — 在 Android（ARM64）上本机运行 DeepSeek Harness 的开源应用
Copyright (C) 2026 WSK-build
Project: DSHBox — https://github.com/WSK-build/DSHBox
```

- 许可原文：`upstream-licenses/DSHBox/LICENSE`（GNU GPL v3）
- 商标声明：`upstream-licenses/DSHBox/TRADEMARK.md`
- 第三方组件清单：`upstream-licenses/DSHBox/THIRD_PARTY_NOTICES.md`

**义务**：GPL-3.0 的源码提供、许可证随附、修改标注与同许可义务**在分发时**触发。
私有自用阶段不触发（见 [../LICENSE-PENDING.md](../LICENSE-PENDING.md)）。
无论是否分发，**版权声明与许可原文必须保留**。

**商标约束**：不得使用 `DSHBox` 名称、`brand_icon.png`、`ic_pilot_brand.png`、
`ic_splash_mark.png`，也不得利用其设计构造方法制作近似标识。

### DSHA — 特性来源（MIT）

```
MIT License
Copyright (c) 2026 qiannianhuanxiang
```

- 许可原文：`upstream-licenses/DSHA/LICENSE`
- 第三方组件清单：`upstream-licenses/DSHA/THIRD_PARTY_NOTICES.md`

**义务**：MIT 要求在任何副本或实质部分中保留版权声明与许可原文。
MIT 代码并入本 GPL-3.0 骨干在法律上是允许的方向（宽松 → 严格）。

**已实际进入本仓库的 DSHA 代码**：阶段 2.1（2026-10-14）从 `467c7eb0`
原样复制了 7 个文件到 `:backup` 模块（v5 备份归档的格式与预检，合计 33 KB）。
逐文件的 DSHA 路径、锁定提交、blob 与正文 sha256 见
[dsha-imports.json](dsha-imports.json)，由 `tools/check-dsha-imports.py` 核验。
**在此之前，DSHA 在本仓库里只是"特性来源"，没有一行代码进来。**

## 二、传递上游（经 DSHBox 或 DSHA 进入）

| 组件 | 版本 | 许可 | 进入路径 | 备注 |
|---|---|---|---|---|
| DeepSeek Harness (`@deepseek-ai/dsh` 及子包) | 0.2.0-rc.2 | MIT | npm 运行环境 | 不在本仓库打源码补丁 |
| Cordis (`@deepseek-ai/cordis`) | — | MIT | npm | 插件框架 |
| Node.js | 24.19.0 | MIT | 运行环境 | |
| Debian rootfs (trixie) | — | 各包分别授权 | 运行环境 | 各包见 guest 内 `/usr/share/doc/*/copyright` |
| PRoot | Termux 分支 | **GPL-2.0** | `libproot*.so` | 二进制内置；对应源码见 termux-packages |
| talloc | 2.4.3 | **LGPL-3+** | `libtalloc.so` | 动态链接，未修改 |
| Termux terminal-emulator / terminal-view | 0.118.0 | **Apache-2.0**（例外） | `terminal-emulator/`、`terminal-view/` 模块 | ⚠️ 仅这两个模块是例外；**严禁**从 termux-app 的 GPLv3 部分（termux-shared、app 层）复制代码 |
| interlock-relay-core | 0.1.0 | **Apache-2.0** | `pilot/interlock-relay-core/` | 上游独立发布；需随附其 LICENSE 与 NOTICE |
| Shizuku API / provider | 13.1.5 | MIT | Gradle 依赖 | |
| zstd-jni | 1.5.7-15 | BSD-3-Clause | `libs/` | |
| commons-compress | 1.27.1 | Apache-2.0 | Gradle 依赖 | |
| Bouncy Castle (bcpg/bcprov/bcutil) | 1.78.1 | MIT | Gradle 依赖 | Debian `Release.gpg` 验签 |
| Sora Editor | 0.23.5 | **LGPL-2.1-or-later** | Gradle 依赖（未修改 aar） | 见下 |
| Markwon + commonmark-java | 4.6.2 / 0.13.0 | Apache-2.0 / BSD-2-Clause | Gradle 依赖 | |
| AndroidX / Compose / Kotlin / Coroutines | — | Apache-2.0 | Gradle 依赖 | |
| Gson | 2.13.1 | **Apache-2.0** | Gradle 依赖（`:backup`） | 阶段 2.1（DIV-0019）；下限 2.11，因为用到了 `Strictness` |
| dshmarket | — | MIT | **仅设计参照，重写** | 未分发其代码与资源 |
| awesome-dsh-plugin / awesome-dsh-mobile-plugins | — | CC0-1.0 | **运行时读取，不分发数据** | |

### 需要额外注意的三项

**1. Sora Editor（LGPL-2.1-or-later）** —— LGPL 的"可替换库"要求在 Android 的
aar 静态打包模式下难以单独满足。上游 DSHBox 的合规论证是"宿主应用整体以 GPLv3
分发"，这在本项目**公开分发时同样成立**；但若你未来改为宽松许可分发，
必须重新评估此项。私有自用不受影响。

**2. PRoot（GPL-2.0）** —— 以二进制形式内置。GPL-2.0 要求随二进制提供对应源码。
DSHBox 的 `THIRD_PARTY_NOTICES.md` 已给出源码获取地址；**公开分发时必须保留
这些地址，并确保你实际分发的二进制版本能从该地址获取对应源码**。

**3. proroot（上游专有）** —— 仅 DSHA 使用，DSHFusion 阶段 1/2 不引入。
若要引入，必须先单独确认分发权利。

## 三、本仓库自身的署名要求

任何从 DSHA 进入 DSHFusion 的文件，**必须在文件头标注来源**。两种情况的模板不同：

**原样复制**（阶段 2.1 的 `:backup` 用的就是这一种）——正文一个字节都不改，
来源头是唯一新增的内容：

```java
// 来源：DSHA (https://github.com/DSH-APP/DSHA) — MIT
// Copyright (c) 2026 qiannianhuanxiang
// 原样复制进 DSHFusion（阶段 2.1），正文未做任何修改。见 governance/dsha-imports.json。
```

**修改后移植** ——除来源头外，还必须登记进 `divergences.json` 并说明修改内容：

```java
// 来源：DSHA (https://github.com/DSH-APP/DSHA) — MIT
// Copyright (c) 2026 qiannianhuanxiang
// 移植到 DSHFusion，适配 DSHBox 骨干。修改点见 governance/divergences.json 的 DIV-XXXX。
```

**两种都由机器核验，不靠人记得**：

- 原样复制的文件进 [`dsha-imports.json`](dsha-imports.json)：`tools/check-dsha-imports.py`
  先断言文件**正好**以那 3 行头开头，再把去掉头之后的正文按 sha256 与登记比对。
  所以「署名在位」与「正文未改」是两个独立断言。
- 修改过的上游文件由 `tools/check-upstreams.py` 与 `tools/check-import-fidelity.py` 覆盖。

这既是 MIT 的版权声明保留义务，也是 GPL-3.0 §5(a) 的"显著修改标注"要求（就本项目而言），
更是同步时不丢改动的前提。

## 四、检查方式

`tools/check-upstreams.py` 会验证：

- `governance/upstreams.json` 中声明的每个 `license_file` 是否存在；
- `upstream-licenses/` 下的许可原文是否与锁定提交处的上游原文一致（按 SHA-256）；
- 每个 `divergences.json` 条目引用的文件是否真实存在。

`tools/check-dsha-imports.py` 单独验证从 DSHA 原样导入的副本（来源头 + 正文双向），
**期望值内嵌在仓库里，因此在 CI 中是真跑**——与骨干那个需要仓库外审计副本、
在 CI 里会走「跳过」分支的保真度检查器不同。

许可原文被意外修改会导致检查失败 —— 这是有意设计的。
