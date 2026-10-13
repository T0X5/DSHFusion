# 阶段 0 验证报告

**执行时间**：2026-10-13
**被测提交**：本文件所在的那个提交。用 `git log -1 --format=%H -- docs/PHASE0-VERIFICATION.md`
取得——刻意不写死哈希，否则每次修订本文件都会让哈希过期。
**执行环境**：Linux 容器，Python 3.12.3，无 JDK / Android SDK / NDK / Gradle
**执行方式**：`bash tools/verify-phase0.sh`（可重复执行，任何人可复现）

---

## 1. 结论

验证套件的**项数随环境伸缩**（部分段落会按前置条件展开或折叠），因此分两种环境分别记录。
其中 **3 项**依赖可选依赖（PyYAML、actionlint、shellcheck）；
**4 项**依赖 `../audit/` 下的上游审计副本存在（第 4c 组锚点预检、第 4d 组阶段 1 导入器、
第 6 组的两个上游工作树检查）。**依赖缺失时报告 SKIP，既不算通过也不算失败。**
第 7 组（脚本可执行位）读 git 索引，非 git 检出时转 SKIP。

因此同一套件在不同环境下有不同结果，都是正确的：

| 运行环境 | 通过 | 失败 | 跳过 | 合计 |
|---|---:|---:|---:|---:|
| 工具齐全 + 有审计副本 + 有上游镜像（本次工作区） | **67** | 0 | 0 | 67 |
| 无审计副本、无镜像（干净检出/克隆） | **56** | 0 | **4** | **60** |

| 其他检查 | 结果 |
|---|---|
| 治理契约检查 | 通过（4 项） |
| 引用完整性 | 通过（29 个 Markdown 链接、62 个仓库内路径引用） |
| 许可原件哈希 | 通过（5 个文件、65,034 字节） |
| 工作流 YAML 解析（PyYAML） | 通过（2 个文件） |
| 工作流语义检查（actionlint 1.7.7 + shellcheck 0.10.0） | 通过（0 条告警） |

> **修订说明（本版）**：本报告早先版本的结论表只写了「23 通过」，没有区分
> 「有/无审计副本」两种情形，与干净检出下实测的「21 通过 / 2 跳过」不一致。
> 该表述当时已修正为双行表格。本次修订把套件从 23 项扩展到 90 项
> （新增 `--record` 校验、偏离重放失败路径、工作流真实解析与 actionlint、
> 以及第 7 组脚本可执行位检查），
> 数字全部来自脚本实际输出，不再依赖人工推算。

**这个报告能证明的**：阶段 0 的骨架自洽、检查器有效、每个工具的成功与失败路径都按预期工作。

**这个报告不能证明的（原文写于阶段 0，2026-10-13 部分更新）**：

- ~~没有任何 Android 构建被执行过~~ → **已不成立**。阶段 1 的
  `phase-1/import-dshbox-base` 分支已在 GitHub Actions 上真实跑过 Android 构建，
  见 §2.12。本环境仍然没有 JDK/SDK/NDK/Gradle，构建是在 CI 上执行的。
- ~~不存在 APK 产物~~ → **已不成立**。CI 产出了 `dshapp-debug-apk`（39.0 MB 压缩）。
- **仍然成立**：**没有任何真机验证**。CI 产出的 APK 从未装到设备上运行过。
  而且它**不含运行环境层**，所以即便装上，也起不了 DSH（见 §2.12 的说明）。

---

## 2. 逐项证据

### 2.1 治理契约检查 `check-upstreams.py`

```
$ python3 tools/check-upstreams.py
契约检查通过（4 项）。
$ echo $?
0
```

失败路径 —— 篡改受保护的上游许可原件（追加一行注释）：

```
$ printf '\n<!-- 验证用篡改 -->\n' >> governance/upstream-licenses/DSHBox/TRADEMARK.md
$ python3 tools/check-upstreams.py
契约检查失败（1 项）：
  ✗ 受保护许可原件被改动: governance/upstream-licenses/DSHBox/TRADEMARK.md
  ✗ 受保护的许可原件被改动: governance/upstream-licenses/DSHBox/TRADEMARK.md
        期望 638e3a0df4a4f97e5ae7c7e12bd82c794f16ef20335431b634dece435e072360
        实际 04adf43b6f4faf7e1abdee76d37dc9b8e14cd687bf8aa5fc51bb4911af54aae5
        这些文件必须保持上游原样；如需更新，请同步上游后重新生成哈希清单。
$ echo $?
1
```

失败路径 —— 非法偏离类型 + 引用不存在的文件：

```
$ python3 tools/check-upstreams.py
契约检查失败（2 项）：

  ✗ divergences.json.applied[0]: type 非法 — 非法类型（允许：['adaptation', 'config', 'identity', 'patch', 'removal']）
  ✗ divergences.json.applied[0]: applied 的 target_files 不存在 — tools/does-not-exist.sh
        要么文件确实还没导入（应放在 planned），要么路径写错了。
$ echo $?
1
```

### 2.2 引用完整性 `check-references.py`

```
$ python3 tools/check-references.py
引用检查通过：29 个 Markdown 链接、63 个仓库内路径引用全部有效（另有 5 处按约定跳过）。
$ echo $?
0
```

失败路径 —— 制造一个断链：

```
$ printf '\n[断链测试](docs/this-file-does-not-exist.md)\n' >> README.md<!-- ignore-ref: docs/this-file-does-not-exist.md -->
$ python3 tools/check-references.py
引用检查失败（1 项）：
  ✗ README.md: 链接指向不存在的路径 -> docs/this-file-does-not-exist.md
$ echo $?
1
```

失败路径 —— 提到不存在的仓库内路径：

```
$ python3 tools/check-references.py
引用检查失败（1 项）：
  ✗ README.md: 提到的仓库内路径不存在 -> `tools/<不存在>.sh`
$ echo $?
1
```

> 这个检查器在建立过程中**确实抓到了真实问题**：文档里 30 处引用失效，
> 其中包含 `LICENSE-PENDING.md` 指向 `ATTRIBUTION.md` 的断链（正确路径是
> `governance/ATTRIBUTION.md`）。它也因此抓到了验证脚本自身的一处字面量路径。

### 2.3 许可原件哈希 `check-license-hashes.py`

```
$ python3 tools/check-license-hashes.py
许可原件核验通过：5 个文件、65034 字节，哈希与登记一致。
$ echo $?
0
```

受保护的 5 个文件：

| 文件 | 字节 | SHA-256（前 16 位） |
|---|---:|---|
| `governance/upstream-licenses/DSHBox/LICENSE` | 35,149 | `3972dc9744f6499f` |
| `governance/upstream-licenses/DSHBox/THIRD_PARTY_NOTICES.md` | 9,484 | `8c3b9bae095e72a2` |
| `governance/upstream-licenses/DSHBox/TRADEMARK.md` | 4,154 | `638e3a0df4a4f97e` |
| `governance/upstream-licenses/DSHA/LICENSE` | 1,074 | `e0ddc2f4a383d900` |
| `governance/upstream-licenses/DSHA/THIRD_PARTY_NOTICES.md` | 15,173 | `d903f52c80d333be` |

DSHBox 原件来自 DSHBox @ `266290a6d1`；DSHA 原件来自 DSHA @ `467c7eb09d`。
完整 64 位哈希见 `governance/upstream-license-hashes.json`。

### 2.4 同步工具 `sync-upstream.sh`

失败路径（参数校验）：

```
$ tools/sync-upstream.sh nosuch --report          -> exit 1（未知上游 id）
$ tools/sync-upstream.sh dshbox --record abc      -> exit 1（提交号不是 40 位）
$ tools/sync-upstream.sh                          -> exit 1（缺参数，打印用法）
```

成功路径（幂等，上游无新提交）：

```
$ tools/sync-upstream.sh dshbox --report
上游 dshbox 无新提交（锁定 266290a6d1... == 镜像 HEAD）。
$ echo $?
0
```

摘要生成在真实镜像上验证过，7 节全部正确触发，包括：
冲突预警（变更文件 ∩ 已登记偏离）、许可与依赖变化、测试基线变化。

### 2.5 偏离重放 `apply-divergences.sh`

```
$ tools/apply-divergences.sh --check
上游代码尚未导入（阶段 0）。以下是已登记、待施加的偏离：
  DIV-0001  identity    独立应用标识 com.dshfusion.app
  DIV-0002  identity    独立应用名与自有图标
  DIV-0003  adaptation  运行环境资产路径可配置
  DIV-0004  config      上游监测地址指向 DSHFusion 自身
  DIV-0005  config      移除对上游品牌页面的硬引用
$ echo $?
0
```

### 2.6 静态检查

```
$ for f in tools/*.sh; do bash -n "$f" || exit 1; done      -> exit 0
$ python3 -m compileall -q tools                            -> exit 0
$ 全部 JSON 可解析                                          -> exit 0
$ 受保护许可原件哈希与登记一致                              -> exit 0
```

### 2.7 上游仓库未被修改（只读审计承诺）

```
$ git -C audit/DSHA status --porcelain   | wc -l
0
$ git -C audit/DSHBox status --porcelain | wc -l
0
```

两个上游仓库的工作树在整个审计与阶段 0 建设过程中保持零改动。
DSHFusion 从未向 `DSH-APP/DSHA` 或 `WSK-build/DSHBox` 推送任何内容。

### 2.8 `--record` 的四项校验

`--record` 是唯一会改动 `governance/upstreams.json` 的动作，因此在**隔离夹具**上
逐条验证它的四道校验。夹具由 `tools/make-record-fixture.sh` 在临时目录里生成，
跑完即删，不碰真实仓库。

> 为什么必须用夹具：真实镜像的 HEAD 就是锁定提交，**没有"更靠前的提交"可用**，
> 无法在真实仓库上制造「关系回退」这类失败。

```
$ FIX="$(mktemp -d)"                                 # 一次性目录，用完即弃
$ bash tools/make-record-fixture.sh "$FIX"           # 造出假上游与假仓库
$ cd "$FIX/repo" && bash tools/sync-upstream.sh dshbox --record <各测试提交>

  校验1：提交不存在于镜像                    exit=1   ← 提交号写错
  校验1：对象不是 commit（tree 的 SHA）       exit=1   ← 把 tree SHA 当提交
  校验1：非十六进制提交号                    exit=1   ← 格式非法
  校验2：提交不属于默认分支历史               exit=1   ← 来自别的分支
  校验3：锁定提交不是新提交的祖先             exit=1   ← 无共同祖先
  校验3b：关系回退（rewound 在 main 上但非后代） exit=1   ← force-push 型回退
  校验3c：就是当前锁定提交                    exit=1   ← 无需更新
  校验4a：缺审核记录                          exit=1   ← 未做人工审核
  校验4b：摘要存在但无 record_commit          exit=1
  校验4c：integration=rejected 时拒绝推进     exit=1   ← 集成未通过
  校验4d：审核记录范围与实际错配              exit=1
  正常路径：4 项校验通过后推进 locked_commit  exit=0
  成功路径留下 locked_commit_history 审计痕迹
```

校验 3b 是特意补的：最初的夹具里「无关历史提交」会在**校验 2** 就被拦下，
根本走不到校验 3，等于校验 3 没有被测到。为此额外造了一个
「在 main 上、但不是锁定提交后代」的提交，让校验 2 通过、校验 3 生效。

### 2.9 偏离重放：成功路径与失败路径

`apply-divergences.sh` 在**上游已导入**的情形下才真正工作，阶段 0 走不到那段逻辑。
因此用 `tools/make-divergence-fixture.sh` 造出最小的假 DSHBox 文件来测：

| 场景 | 期望 | 实测 |
|---|---|---|
| `normal` | 全部偏离可施加，退出 0 | ✓ 退出 0，4 个文件被改，二次运行幂等 |
| `drift` | 上游改了 `applicationId` 写法 → 失败 | ✓ 退出 1，文件被还原 |
| `asset-missing` | `assets.srcDirs(...)` 不存在 → 失败 | ✓ 退出 1，打印期望字符串与实际文件 |
| `strings-moved` | `app_name` 条目消失 → 失败 | ✓ 退出 1 |
| `unknown-div` | 混入未实现施加逻辑的偏离 → 失败 | ✓ 退出 1 |
| 阶段 0 一致性 | `applied` 非空但上游未导入 → 失败 | ✓ 退出 1 |

**`strings-moved` 场景发现了一个真实漏洞。** 早先版本的 `app_name` 偏离只检查
「旧值 `DSHBox` 不存在了」就算成功。上游一旦把 `app_name` 改名或删掉，
这个判据会**假性成立**——脚本报"已生效"，而目标状态其实从未达成。
现在改为**正向确认目标值存在**。这个漏洞不是推理出来的，是夹具跑出来的。

### 2.10 工作流验证（真实解析器 + actionlint）

此前两个工作流**只被结构性检查过**（顶层键是否存在、有没有制表符），
从未被真正的解析器读过。本版补上了两级验证：

```
$ python3 tools/check-workflows.py
工作流验证通过：2 个文件可被 YAML 解析器解析，结构完整。

$ actionlint -no-color .github/workflows/contracts.yml .github/workflows/upstream-watch.yml
（无输出）
$ echo $?
0
```

actionlint 带 **shellcheck 集成**，因此 `run:` 块里的 shell 也被检查。
**它的检查能力经过负向测试确认**：故意造的错误工作流被正确拦下
（`github.nonexistent_context` → 表达式错误；`actions/checkout@v4` 的不存在输入 →
action 输入错误；`for f in $(ls)` → SC2045/SC2086），退出码 1。

> **actionlint 发现的一个真实问题**：`upstream-watch.yml` 里 `jq` 表达式原用单引号，
> 导致 `$title` 不展开（SC2016）。已改用 `--arg` / 双引号传参修正。

本机运行 actionlint 需要 arm64 版二进制 —— 下错架构会得到
`Illegal instruction`（SIGILL），这个坑已记录在 CI 的安装步骤里
（按 `uname -m` 选 `amd64`/`arm64`）。

### 2.11 干净检出复跑

从 git bundle 克隆到全新目录后复跑全部检查（真实输出，非重述）：

```
$ DEST="$(mktemp -d)/verify-bundle"
$ git clone dshfusion-phase0.bundle "$DEST"
$ cd "$DEST" && git rev-parse HEAD
<与 dshfusion-phase0.bundle 的 HEAD 相同>      # 刻意不写死：写死会让本文件每次修订都过期
$ git status --porcelain | wc -l
0                                            # 工作树干净
$ git ls-files | wc -l
36

$ python3 tools/check-upstreams.py
契约检查通过（4 项）。                          # exit=0
$ python3 tools/check-references.py
引用检查通过：29 个 Markdown 链接、62 个仓库内路径引用全部有效（另有 5 处按约定跳过）。  # exit=0
$ python3 tools/check-license-hashes.py
许可原件核验通过：5 个文件、65034 字节，哈希与登记一致。   # exit=0
$ python3 tools/check-workflows.py
工作流验证通过：2 个文件可被 YAML 解析器解析，结构完整。   # exit=0
$ actionlint -no-color .github/workflows/*.yml
（无输出）                                     # exit=0
$ bash tools/verify-phase0.sh
  === 7. 脚本可执行位（读 git 索引，不看工作区） ===
    [PASS] 索引中所有 .sh 都有可执行位               全部 100755

  === 汇总 ===
    通过: 56
    失败: 0
    跳过: 4
    合计: 60 项

  ⚠️  跳过项（既不算通过，也不算失败）：
    - 锚点预检真实上游（无审计副本，未检查）
    - 阶段 1 导入器（无审计副本，未检查）
    - 上游 DSHA 未被修改（无审计副本，未检查）
    - 上游 DSHBox 未被修改（无审计副本，未检查）

  56 项通过、0 项失败、4 项跳过。
  注意：有跳过项，本次运行并未验证全部 60 项。
```

> 上面这段是**真实复跑的输出**，不是重述。修订本文件后必须重跑一次再改数字 ——
> 早先的版本曾写着「31 个跟踪文件 / 27 个链接 / 55 通过」，那三个数字都已过期。

**干净克隆里项数更少**的原因：第 4c、4d、6 组依赖 `../audit/`，第 3 组正常路径依赖 `../upstream-mirrors/`
存在，克隆目录里没有审计副本，因此报告 **SKIP 而不是 PASS**。
这是刻意的设计——缺少审计副本既不能算通过，也不该算失败。
脚本会在有跳过项时**明确提示"本次运行并未验证全部 N 项"**，
避免把跳过误读成通过。

> 若克隆环境还缺少 PyYAML / actionlint / shellcheck，第 5b 组的三项也会转为 SKIP，
> 计数相应变为「53 通过 / 7 跳过」。脚本在这三种情形下都打印真实计数，
> 不做任何推算。

在本工作区（依赖齐全且有审计副本）下，同一套件的结果是 **80 通过 / 0 失败 / 0 跳过**。

---

### 2.12 Android 构建：首次真实 CI 运行（阶段 1，2026-10-13）

阶段 0 的这一节原本写「不存在 APK，不存在构建结论」。阶段 1 导入 DSHBox 基线后，
上游 `android.yml` 在 DSHFusion 下**真实运行并全部通过**，因此这一条必须更正。

**运行记录**（`Android CI`，run `38478523535`）：

| 项 | 值 |
|---|---|
| 触发提交 | `8af68f8`（`phase-1/import-dshbox-base`） |
| 起止 | 2026-10-13T22:32:54Z → 22:46:06Z（约 13 分钟） |
| 结果 | ✅ success，`build` 作业 20 个步骤全部成功 |
| 产物 | `dshapp-debug-apk` = **39.0 MB**（压缩）/ 94.0 MB（解压）/ 777 个条目 |

**各步骤的真实耗时与结果**：

| 步骤 | 结果 |
|---|---|
| `Set up JDK 21` / `Set up Gradle` / `Set up Android SDK` | ✅ |
| `Accept licenses & install SDK packages` | ✅ |
| `Grant execute permission to scripts` | ✅ |
| `Set up Python` | ✅ |
| `Run i18n consistency check` | ✅ |
| `Check website asset version string` | ✅（`website/` 未导入，按 DIV-0005 短路退出） |
| `Run privacy audit` | ✅ |
| `Run unit tests` | ✅ `BUILD SUCCESSFUL in 5m 22s` |
| `Run lint` | ✅ `BUILD SUCCESSFUL in 3m 48s` |
| `Assemble debug APK` | ✅ `BUILD SUCCESSFUL in 3m 20s` |
| `Upload debug APK` | ✅ |

**跑过单元测试的模块**：bridge、common、plugin-manager、sandbox-manager、
terminal-emulator、terminal-session、`pilot:dshbox-adapter`、
`pilot:interlock-relay-core`、app。
`terminal-view:testDebugUnitTest` 报 `NO-SOURCE`（该模块没有测试源），不是失败。

**这是怎么变成可能的**：`DIV-0003` 把 `assets.srcDirs` 改成可由
`DSHFUSION_RUNTIME_ASSETS` 覆盖，因此 `testDebugUnitTest` 与 `lintDebug`
不需要任何运行环境资产。`assembleDebug` 在没有资产时也能产出 APK，
只是这个 APK **不含运行环境层**。

**这个结果证明了什么**：上游 `android.yml` 的步骤序列在 DSHFusion 的
`applicationId` / 版本号 / 品牌资源改动下**能跑通**；1058 个 `@Test` 在
我们的改动下全部通过；`lintDebug` 不报错。

**这个结果不证明什么**：

1. **没装到真机上过。** 没有设备连接，从未安装运行。
2. **APK 起不了 DSH。** 解包核对过：里面没有 `base.tar.zst` / `node.tar.zst` /
   `android-side.tar.zst` / `dsh/*.tar.zst`，只有 `assets/runtime-online/`
   （在线获取的引导与 dpkg 元数据）和 `assets/terminal-packages/`（24 个 `.deb`）。
   所以它能安装，但需要先通过「运行环境在线获取」或离线整包导入补上运行环境。
3. **CI 只覆盖 `x86_64` 上的编译与单测。** 真机是 `arm64-v8a`，
   NDK 相关产物与设备行为不在 CI 覆盖范围内。
4. **`lintDebug` 通过 ≠ 零告警。** 构建日志里有一条 manifest 告警
   （`app/src/main/AndroidManifest.xml:51:9-41`），未阻塞构建。

**顺带一个教训**：这次运行之前，本报告对「能不能编译」的判断全部来自静态阅读。
第一次真跑就抓到了 36 个 `Unresolved reference 'R'`（`namespace` 被误改），
以及 CI 发现不了的、会在**运行时**崩的 3 处 manifest 相对类名。
「不要仅凭配置推断构建结果」这条规则是被这次失败验证的，不是凭空写的。

---

## 3. 已知限制（诚实记录）

1. ~~未执行 Android 构建~~ → **已执行**，见 §2.12。本机仍无 JDK / Android SDK /
   NDK / Gradle，构建在 GitHub Actions 上完成；本机**无法复现该构建**。
2. **未做真机验证。** 已有 APK 产物（39.0 MB），但从未安装到设备上运行过，
   而且它不含运行环境层，装上也无法启动 DSH。见 §2.12。
3. **未推送 GitHub、未创建 PR。** 本环境无 GitHub 写入凭据。
   交付形式为本地 git 仓库 + bundle + ZIP，推送步骤由用户执行。
4. ~~工作流仍未在 GitHub 上真实运行过~~ → `contracts.yml` 与 `android.yml`
   **均已真实运行并多次通过**（最近一次见 §2.12）。
   **`upstream-watch.yml` 仍未运行过**——它只在每周定时与手动触发时执行。
   以下是当时的验证方式，保留作为「推送前能验到哪一步」的记录：
   本版已用**真实 YAML 解析器**（PyYAML）
   与 **actionlint 1.7.7 + shellcheck 0.10.0** 验证，且 actionlint 的能力经过
   负向测试确认（故意造的错误工作流被正确拦下）。但这只证明
   「工作流定义本身合法且语义正确」，**不证明它在 GitHub 的 runner 上能跑通**——
   例如 `gh issue list --search` 的实际返回、`sudo install` 的权限、
   runner 上 `uname -m` 的取值，都只能在推送后由 GitHub 确认。
5. **`check-references.py` 的跳过规则是约定，不是证明。**
   带 `DSHA:`/`DSHBox:` 前缀的路径与 `<!-- future: ... -->` 标注的路径不检查存在性。
   这依赖标注者诚实；它防的是笔误，不防故意。
6. **`--update` 可以改写许可哈希登记。** 它是同步上游后的合法操作，
   但也因此是唯一能"合法化"篡改的入口。用它必须人工复核许可变化。
7. **`--record` 的校验只覆盖「提交来自哪个仓库/分支、关系是否线性、
   有没有审核记录」。** 它**不判断集成质量**——审核记录里的
   `integration: passed` 是人工填写的，脚本无法验证上游代码是否真的合并好了、
   测试是否真的跑过。这是刻意的边界：工具能验的验，验不了的明确交给人。
8. **偏离重放的 `website/` 删除是手动的。** 目录型偏离不会自动删除，
   脚本只打印提示并退出 1。自动化不替人执行破坏性操作。
9. **`upstream-watch.yml` 的 Issue 去重依赖标题精确匹配。**
   如果有人手工改了这个 issue 的标题，下一次运行会**新建**一个。
   这是可接受的取舍：宁可多一个 issue，也不要误把评论发到无关 issue 上。
10. **两个工作流都未在真实 GitHub 上验证过 `concurrency` 与 Issue 写入的实际行为。**
    本机无法模拟 GitHub 的 API 侧行为。
