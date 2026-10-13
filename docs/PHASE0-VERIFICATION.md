# 阶段 0 验证报告

**执行时间**：2026-10-13
**被测提交**：本文件所在的那个提交。用 `git log -1 --format=%H -- docs/PHASE0-VERIFICATION.md`
取得——刻意不写死哈希，否则每次修订本文件都会让哈希过期。
**执行环境**：Linux 容器，Python 3.12.3，无 JDK / Android SDK / NDK / Gradle
**执行方式**：`bash tools/verify-phase0.sh`（可重复执行，任何人可复现）

---

## 1. 结论

| 项 | 结果 |
|---|---|
| 验证套件 | **23 通过 / 0 失败**，退出码 0 |
| 治理契约检查 | 通过（4 项） |
| 引用完整性 | 通过（26 个 Markdown 链接、29 个仓库内路径引用） |
| 许可原件哈希 | 通过（5 个文件、65,034 字节） |
| 干净检出复跑 | 通过 |

**这个报告能证明的**：阶段 0 的骨架自洽、检查器有效、每个工具的成功与失败路径都按预期工作。

**这个报告不能证明的**：**没有任何 Android 构建被执行过**。
本环境没有 JDK、Android SDK、NDK 与 Gradle，阶段 0 也不含上游源码，
因此**不存在 APK 产物，也不存在构建结论**。任何关于"能构建出可用的 App"的说法
在本阶段都不成立。

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
引用检查通过：26 个 Markdown 链接、29 个仓库内路径引用全部有效（另有 1 处按约定跳过）。
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

### 2.8 干净检出复跑

从 git bundle 克隆到全新目录后复跑全部检查：

```
$ git clone dshfusion-phase0.bundle /tmp/verify-bundle
$ cd /tmp/verify-bundle && git rev-parse HEAD
<与交付提交一致>                              # 与 dshfusion-phase0.bundle 的 HEAD 相同
$ python3 tools/check-upstreams.py      -> 通过
$ python3 tools/check-references.py     -> 通过
$ python3 tools/check-license-hashes.py -> 通过
$ bash tools/verify-phase0.sh           -> 21 通过 / 0 失败
```

**21 而非 23** 的原因：第 6 组的两项检查依赖 `../audit/DSHA` 与 `../audit/DSHBox`
存在，克隆目录里没有审计副本，因此报告 **SKIP 而不是 PASS**。
这是刻意的设计——缺少审计副本既不能算通过，也不该算失败。

---

## 3. 已知限制（诚实记录）

1. **未执行 Android 构建。** 环境无 JDK / Android SDK / NDK / Gradle；
   阶段 0 也不含上游源码。**不存在 APK，不存在构建结论。**
2. **未做真机验证。** 阶段 0 没有可安装的产物。
3. **未推送 GitHub、未创建 PR。** 本环境无 GitHub 写入凭据。
   交付形式为本地 git 仓库 + bundle + ZIP，推送步骤由用户执行。
4. **工作流未在 GitHub 上真实运行过。** 只做了结构性检查
   （顶层 `name`/`on`/`jobs`/`permissions` 齐全、每个 job 有 `runs-on` 与 `steps`、
   无制表符）。**未用 YAML 解析器验证**——环境里没有 PyYAML 且无法安装。
   真实可运行性只能在推送后由 GitHub 确认。
5. **`check-references.py` 的跳过规则是约定，不是证明。**
   带 `DSHA:`/`DSHBox:` 前缀的路径与 `<!-- future: ... -->` 标注的路径不检查存在性。
   这依赖标注者诚实；它防的是笔误，不防故意。
6. **`--update` 可以改写许可哈希登记。** 它是同步上游后的合法操作，
   但也因此是唯一能"合法化"篡改的入口。用它必须人工复核许可变化。
