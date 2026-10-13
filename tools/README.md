# tools/ — 工具清单

阶段 0 的全部工具。每个都能独立运行，都不依赖 Android 工具链。

> **可执行位提示**：`git clone` 会保留可执行位；但 **ZIP 归档不保留 Unix 权限位**，
> 从 ZIP 解压后 `./tools/<tool>.sh` 会报 `Permission denied`（exit 126）。
> 两种解决办法，任选其一：
> - 用 `bash tools/<tool>.sh` 显式调用（`verify-phase0.sh` 内部已经这样做，所以它不受影响）；
> - 或先 `chmod +x tools/*.sh tools/*.py`。

---

## 检查类（CI 会跑）

### `check-upstreams.py`

治理契约检查。校验 `governance/upstreams.json` 与 `governance/divergences.json`
的结构与内部一致性，并核验上游许可原件未被改动。

```bash
python3 tools/check-upstreams.py
python3 tools/check-upstreams.py --list     # 只列出检查项
```

**退出码**：`0` 通过；`1` 有失败项（失败原因逐条打印到 stderr）。

检查内容：
- 必需文档齐全；
- 受保护的上游许可原件与 `governance/upstream-license-hashes.json` 的哈希一致；
- `upstreams.json`：id 唯一、`role` 合法、`locked_commit` 是 40 位十六进制、
  `license_file` 真实存在、`runtime_asset_strategy` 已写明、
  **`auto_publish_allowed` 必须为 false**（不得默认自动发布）；
- `dshbox` 必须是 `base`、`dsha` 必须是 `feature_source`（防止有人悄悄翻转主干决定）；
- `divergences.json`：id 唯一、`type` 合法、必填字段齐全，
  且 **`applied` 条目引用的文件必须真实存在**（避免"登记了但文件不在"的空登记）。

### `check-references.py`

引用完整性检查。抓两类会悄悄失效的引用：

1. Markdown 内部链接（含锚点）指向的文件不存在；
2. 文档里提到的仓库内路径（如 `tools/<tool>.sh`）不存在。

```bash
python3 tools/check-references.py
python3 tools/check-references.py --verbose   # 附带扫描统计与跳过项
```

**退出码**：`0` 通过；`1` 有断链或不存在的路径。

约定：
- `` `DSHA:tools/x.py` `` / `` `DSHBox:docs/y.md` `` 表示**上游仓库**内的路径，
  不做存在性检查；
- `<!-- future: docs/xxx/ -->` 标注「未来阶段才会创建」的路径，跳过检查；
- `<!-- ignore-ref: docs/nope.md -->` 标注**故意引用不存在路径**的地方
  （例如验证报告里的失败路径测试用例），跳过检查；
- `governance/upstream-licenses/` 是上游许可原件的冻结副本，整体跳过；
- 含 `xxx`/`yyy`/`<...>` 的**示例占位路径**不算真引用。

> 这个检查器的来历很具体：一次交付只导出了顶层 Markdown，漏掉了 `governance/`、
> `tools/`、`.github/`，而当时没有任何检查发现它。它保证「文档声称存在的东西真的存在」。

### `check-workflows.py`

用**真实的 YAML 解析器**（PyYAML）校验 `.github/workflows/*.yml`，并断言
GitHub Actions 工作流必需的结构。

```bash
python3 tools/check-workflows.py
python3 tools/check-workflows.py --verbose
python3 tools/check-workflows.py --dir <目录>   # 测试用
```

**退出码**：`0` 通过；`1` 有解析失败或结构问题；`2` 环境缺少 PyYAML。

为什么需要它：在接入本脚本之前，两个工作流**只被结构性检查过**
（顶层键是否存在、有没有制表符），从未被真正的解析器读过。那无法发现
缩进错误、重复键、类型错误 —— 而这类问题会让工作流**静默失效**。

与 actionlint 的分工：
- 本脚本负责「YAML 合法 + 结构完整」，只需 PyYAML，本地随时可跑；
- actionlint 负责「语义正确」（表达式引用的 context、action 输入名、`run:` 块里的
  shell 问题），它是 Go 二进制，在 CI 里单独安装（见 `contracts.yml`）。

两者都不能被对方替代：actionlint 不校验"这个文件到底能不能被解析成工作流"，
PyYAML 不校验"这个表达式引用的 context 是否存在"。

> **YAML 1.1 陷阱**：PyYAML 把 `on:` 解析成布尔 `True`（`on`/`yes`/`true` 都是真值），
> 所以按字符串取顶层键会误报"缺少顶层键 on"。本脚本对此做了兼容处理。

### `check-license-hashes.py`

独立核验上游许可原件哈希。`check-upstreams.py` 也做这件事，
但本脚本提供更直白的输出，便于在没有完整仓库上下文时单独确认。

```bash
python3 tools/check-license-hashes.py
python3 tools/check-license-hashes.py --update   # 同步上游并复核许可后重新生成
```

**退出码**：`0` 一致；`1` 有缺失或被改动的原件。

> ⚠️ `--update` 会改写登记哈希。**只在确实同步了上游并复核过许可变化后使用**，
> 否则等于把篡改合法化。

---

## 同步类

### `sync-upstream.sh`

拉取上游并生成变更摘要。**只报告，不改主干、不合并、不推送。**

```bash
tools/sync-upstream.sh dshbox --fetch            # 拉取镜像 + 生成摘要
tools/sync-upstream.sh dsha --report             # 只用本地镜像生成摘要
tools/sync-upstream.sh dshbox --record <40位提交> # 更新 upstreams.json 的锁定提交
```

**退出码**：`0` 成功（含"上游无新提交"）；`1` 参数错误、上游 id 未知、
提交号不完整、或找不到镜像（并打印建立镜像的命令）。

摘要写入 `docs/upstream-reports/<日期>-<上游>.md`，包含 7 节：
提交清单、按模块的变更量、**冲突预警**（变更文件 ∩ 已登记偏离）、
测试基线变化、**许可与依赖变化**、变更文件全清单、人工同步的下一步命令。

**环境变量**：`DSHFUSION_MIRRORS` 指定镜像目录（默认 `../upstream-mirrors`）。

#### `--record` 的四项校验

`--record` 是**唯一会改动 `governance/upstreams.json` 的动作**，因此它有四道校验，
任何一道不过就拒绝推进 `locked_commit`（退出码 1）：

| # | 校验 | 拦下的情形 |
|---|---|---|
| 1 | 提交对象存在于镜像且是 commit | 提交号写错、把 tree/blob 的 SHA 当提交、镜像里根本没有 |
| 2 | 提交属于该上游的默认分支历史 | 提交来自别的分支、别的仓库、或已被 force-push 丢弃 |
| 3 | 当前 `locked_commit` 是它的祖先 | 上游 force-push 导致关系回退；并算出领先几个提交 |
| 4 | 存在针对**该提交**的审核记录 | 没做人工审核就想推进 |

第 4 项按**内容**查找审核记录（在全部摘要里搜 `record_commit: <提交>`），
不按文件名推断 —— 摘要文件名用的是「运行日期」，而提交日期可能不同
（从旧提交开始集成、跨天合并都会错位）。若记录里写了 `range:`，还会校验它与
实际范围一致，防止记录与提交错配。

**为什么不提供强制跳过的开关**：跳过校验会让未集成的提交在下次摘要里静默消失。
真需要人工干预时，直接编辑 `upstreams.json` 并写明理由 —— 手动编辑会留下 diff 痕迹。

成功时会把这次推进追加到 `locked_commit_history`，留下可审计的轨迹
（谁、从哪个提交、到哪个提交、集成几个提交、依据哪份审核记录）。

### `apply-divergences.sh`

合并上游后重放已登记的偏离。上游版本会覆盖我们对上游文件的修改
（applicationId、应用名、资产路径等），本脚本按 `governance/divergences.json`
把它们重新施加回去。

```bash
tools/apply-divergences.sh --check    # 只报告每处偏离的施加状态
tools/apply-divergences.sh            # 施加 type=identity/config 的偏离
```

**退出码**：
- `0` 全部偏离要么已生效、要么被明确判定为「待上游导入」，没有静默遗漏；
- `1` 有偏离无法施加（目标文件缺失、固定字符串未匹配、替换后未生效、
  或该偏离类型没有实现施加逻辑）。

阶段 0 会明确报告「上游代码尚未导入」并列出待施加的偏离，同时检查
`divergences.json` 的 `applied` 是否为空 —— 若上游还没导入却登记了 `applied`，
说明有偏离被错误标记为「已施加」，它会在下次同步时被跳过。这种情况报错退出。

**每一处修改都必须通过三项检查**：前置（目标文件存在且能匹配固定字符串）、
动作（替换成功）、后置（改完之后偏离声明的目标状态真的成立）。
任何一项不过就**还原该文件**并报错，不留半成品。

> **一个真实教训**：早先版本的 `app_name` 偏离只检查「旧值 `DSHBox` 不存在了」
> 就算成功。上游一旦把 `app_name` 改名或删掉，这个判据会**假性成立** ——
> 脚本报"已生效"，而目标状态其实从未达成。现在改为**正向确认目标值存在**，
> 这个漏洞是靠 `verify-phase0.sh` 的 `strings-moved` 夹具场景发现的。

**不做破坏性操作**：`website/` 这类目录型偏离不会自动删除，只打印提示，
由人确认后执行 `git rm -r website/`。

---

## 验证类

### `verify-phase0.sh`

阶段验证套件。对每个工具跑**成功路径与失败路径**，记录真实命令、输出与退出码。

```bash
bash tools/verify-phase0.sh
bash tools/verify-phase0.sh 2>&1 | tee verification.log
```

**退出码**：`0` 全部通过；`1` 有失败项。

覆盖 15 组检查。项数随环境伸缩：依赖齐全、有审计副本与上游镜像时 **71 项**；
干净克隆里 **60 项**。其中 **3 项**依赖可选依赖
（PyYAML、actionlint、shellcheck），其余依赖上游审计副本或镜像存在：

| 组 | 内容 | 条件 |
|---|---|---|
| 1 | 契约检查的成功路径 + 篡改许可原件 / 非法偏离类型的失败路径 | 无条件 |
| 2 | 引用检查的成功路径 + 断链 / 不存在路径的失败路径 | 无条件 |
| 3 | 同步工具的参数校验失败路径 + 幂等成功路径 | 无条件 |
| 3b | `--record` 的四项校验：提交对象、上游来源、祖先关系、审核记录 | 无条件（隔离夹具） |
| 4 | 偏离重放的阶段 0 行为 | 无条件 |
| 4b | 偏离重放的成功路径 + 7 种失败场景（夹具） | 无条件（隔离夹具） |
| 4c | 锚点预检：真实上游 1 项 + 夹具 6 项 | 真实上游项依赖审计副本；夹具项无条件 |
| 4d | 阶段 1 导入器：分支保护、上游校验、完整导入、导入后自洽 | 整段依赖审计副本 |
| 5 | 静态检查：shell 语法、Python 编译、JSON 解析、许可哈希 | 无条件 |
| 5b | 工作流验证：PyYAML 解析 + actionlint + shellcheck | 需对应工具 |
| 6 | 上游仓库工作树仍干净（只读审计承诺） | 需审计副本 |
| 7 | 所有 `.sh` 在 git 索引里是 `100755` | 非 git 检出时 SKIP |

> **第 7 组为什么存在**：本套件通过 `run_tool()` 用解释器显式调用脚本，
> 因此**永远测不到可执行位**。CI 的 `contracts.yml` 却会查 `-x`，
> 于是出现「本地全绿、CI 失败」的盲区 —— 2026-10-13 首次推送到 GitHub 时
> 就撞上了（`tools/make-anchor-fixture.sh` 索引模式为 `100644`）。
> 该组读 `git ls-files -s` 而不是工作区权限，因为索引里的模式才是别人 clone 后拿到的东西。

**条件项一律报告 SKIP，不报告 PASS。** 缺 PyYAML 或 actionlint 不代表工作流验证通过，
只代表这次没验证。脚本会打印「通过 / 失败 / 跳过 / 合计」四项计数，
并在有跳过项时提示 **"本次运行并未验证全部 N 项"**（N 按本次实际项数算），避免把跳过误读成通过。

因此结果有多种，都是正确的：依赖齐全且有审计副本时 **71 通过 / 0 失败 / 0 跳过**；
无审计副本、无 PyYAML 时约 **53 通过 / 0 失败 / 7 跳过**。

**3b 与 4b 为什么在夹具里跑**：`--record` 是唯一会改 `upstreams.json` 的动作，
它的失败路径（提交不存在、来源不对、关系回退、缺审核记录）在真实仓库上无法安全制造——
真实镜像的 HEAD 就是锁定提交，没有"更靠前的提交"可用。偏离重放的失败路径同样会污染主干。
两个夹具脚本（`make-record-fixture.sh`、`make-divergence-fixture.sh`）在临时目录里
造出这些情形，测试跑完即删，不碰真实仓库。

> 失败路径是重点：一个只验证"通过"的套件无法证明检查器真的会拦下问题。

### `make-record-fixture.sh`

为 `--record` 的四项校验构造隔离测试夹具。

```bash
tools/make-record-fixture.sh /tmp/fixture
```

产出 `mirrors/DSHBox.git`（假上游：main 有推进、side 是旁支、orphan 无共同祖先、
rewound 在 main 上但非锁定提交后代）与 `repo/`（假 DSHFusion 仓库）。

为什么需要它：`--record` 的失败路径在真实仓库上**无法安全制造** ——
真实镜像的 HEAD 就是锁定提交，没有"更靠前的提交"可用。

### `make-divergence-fixture.sh`

为偏离重放构造「上游已导入」的测试夹具，按场景制造偏差。

```bash
tools/make-divergence-fixture.sh /tmp/fixture <场景>
```

场景：`normal`（全部可施加）、`drift`（上游改了 `applicationId` 写法）、
`asset-missing`（`assets.srcDirs` 不存在）、`strings-moved`（`app_name` 条目消失）、
`brand-intact`（品牌图形仍是上游原样）、`brand-missing`（品牌图形文件缺失）、
`unknown-div`（混入未实现施加逻辑的偏离）、`applied-nonempty`（阶段 0 下 `applied` 非空）。

两个夹具脚本都只在临时目录里工作，测试跑完即删，不碰真实仓库。
> 4b 的 `strings-moved` 场景就是靠这套夹具发现的真实漏洞 —— 上游把 `app_name`
> 改名后，脚本原先会因为"找不到旧字符串"而报成功。
>
> `brand-intact` / `brand-missing` 同理，是 2026-10-13 阶段 1 前置审计发现的第二处假性通过：
> `DIV-0002` 原先只改 `app_name`、完全不看 8 个受 `TRADEMARK.md` §4 保护的品牌图形，
> 于是会报告「已生效」而把上游的品牌资产原样留在包里。
> 现在按 `divergences.json` 登记的上游 SHA-256 **正向核验**这些图形 ——
> 注意这里不能靠"旧值是否还在"判断，因为脚本本来就不改二进制文件。
