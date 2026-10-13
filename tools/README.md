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

### `apply-divergences.sh`

合并上游后重放已登记的偏离。上游版本会覆盖我们对上游文件的修改
（applicationId、应用名、资产路径等），本脚本按 `governance/divergences.json`
把它们重新施加回去。

```bash
tools/apply-divergences.sh --check    # 只报告每处偏离的施加状态
tools/apply-divergences.sh            # 施加 type=identity/config 的偏离
```

**退出码**：`0` 成功。阶段 0 会明确报告「上游代码尚未导入」并列出待施加的偏离。

---

## 验证类

### `verify-phase0.sh`

阶段验证套件。对每个工具跑**成功路径与失败路径**，记录真实命令、输出与退出码。

```bash
bash tools/verify-phase0.sh
bash tools/verify-phase0.sh 2>&1 | tee verification.log
```

**退出码**：`0` 全部通过；`1` 有失败项。

覆盖 6 组共 23 项检查：

| 组 | 内容 |
|---|---|
| 1 | 契约检查的成功路径 + 篡改许可原件 / 非法偏离类型的失败路径 |
| 2 | 引用检查的成功路径 + 断链 / 不存在路径的失败路径 |
| 3 | 同步工具的参数校验失败路径 + 幂等成功路径 |
| 4 | 偏离重放的阶段 0 行为 |
| 5 | 静态检查：shell 语法、Python 编译、JSON 解析、许可哈希 |
| 6 | 上游仓库工作树仍干净（只读审计承诺） |

第 6 组依赖 `../audit/DSHA` 与 `../audit/DSHBox` 存在；不存在时**报告 SKIP 而不是失败**——
缺审计副本不代表检查通过，也不代表失败。

> 失败路径是重点：一个只验证"通过"的套件无法证明检查器真的会拦下问题。
