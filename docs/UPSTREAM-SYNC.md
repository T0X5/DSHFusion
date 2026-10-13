# 双上游长期同步流程

DSHFusion 要长期吸收 DSHA、DSHBox 及其各自上游的新功能与修复。
本文件定义**可重复执行的同步流程**，以及每一步的判据。

核心原则：**同步是流程，不是手气。** 任何一次同步都应该能回答三个问题：
从哪个提交同步、改了哪些文件、为什么这次能合。

---

## 一、上游角色与同步策略

| 上游 | 角色 | 同步方式 | 自动开 PR |
|---|---|---|---|
| DSHBox | 骨干 | 合并上游 tag，再重放已登记的偏离 | ✅ 允许（低风险） |
| DSHA | 特性来源 | **不整体合并**；按功能逐项移植 + 适配层 | ❌ 只出候选，人工审 |
| deepseek-harness (DSH) | 运行时 | 只升 npm 版本号，**不打源码补丁** | ✅ 允许 |
| interlock-relay | 手机助手内核 | 随 DSHBox 同步 | ✅ 允许 |
| Termux terminal-* | 终端模块 | 随 DSHBox 同步，注意只取 Apache-2.0 例外模块 | ✅ 允许 |

**为什么 DSHA 不整体合并**：DSHA 是单模块 Java 应用，DSHBox 是多模块 Kotlin 应用，
两者的运行时、依赖、数据结构与生命周期都不同（Ubuntu vs Debian、离线内嵌 vs
分层在线、proot/proroot vs proot）。整体合并等于重写。因此 DSHA 只作为
**实现与算法的来源**，逐项移植。

---

## 二、骨干同步（DSHBox）

### 前置

本地必须有上游镜像。仓库不跟踪镜像（避免把 86MB 对象库提交进 git）：

```bash
# 一次性建立镜像（在仓库外的兄弟目录）
git clone --mirror https://github.com/WSK-build/DSHBox.git upstream-mirrors/DSHBox.git
git clone --mirror https://github.com/DSH-APP/DSHA.git   upstream-mirrors/DSHA.git
```

### 流程

```bash
# 1) 拉取上游并生成变更摘要（不改主干）
tools/sync-upstream.sh dshbox --fetch

# 摘要输出到 docs/upstream-reports/<date>-dshbox.md，包含：
#   - 新提交列表（含作者、日期、是否安全相关）
#   - 触及的文件与模块
#   - 与 divergences.json 中登记文件的交集 ← 冲突预警
#   - 测试文件的增删（净减少要报警）

# 2) 在独立分支上尝试集成
git switch -c sync/dshbox-<新提交短号>
git merge --no-ff <上游tag或提交>      # 或 git subtree pull，见下

# 3) 重放偏离
tools/apply-divergences.sh            # 按 divergences.json 重写被覆盖的偏离

# 4) 跑门禁
./gradlew testDebugUnitTest lintDebug
python3 tools/check-upstreams.py

# 5) 记录
tools/sync-upstream.sh dshbox --record   # 更新 upstreams.json 的 locked_commit，
                                         # 并把本次结果写入 docs/upstream-reports/

# 6) 开 PR（人工审）
```

### 合并方式的选择

两种都可行，各有取舍，**第一次导入时决定并写进这里**：

| 方式 | 优点 | 缺点 |
|---|---|---|
| `git merge` 上游分支 | 保留完整历史，`git log` 能追溯上游 | 主干历史会混入上游提交；偏离冲突在合并时暴露 |
| `git subtree` | 上游代码集中在子目录，历史清晰 | 需要固定目录布局；DSHBox 是根目录多模块，套 subtree 会改变所有路径 |
| 源码快照导入 | 简单，一次性 | 丢失上游历史，无法 `git log` 追溯 |

**建议**：DSHBox 是多模块根目录布局，套 subtree 会破坏其相对路径
（模块间的 `project(":common")` 等）。因此采用 **`git merge` 上游分支**，
并通过 `divergences.json` 管理偏离。这也让 `git log --graph` 能直接看出
"这一段是上游的、这一段是我们的"。

---

## 三、特性移植（DSHA → DSHFusion）

逐项移植，每项一个 PR。**不允许把 DSHA 的一批改动打包成一个 PR。**

### 单项移植模板

```markdown
## 移植：<DSHA 的哪个能力>

- DSHA 来源：<文件路径> @ <提交号>
- 目标位置：<DSHFusion 中的模块/文件>
- 融合方式：直接复用 / 适配层 / 重新实现
- 冲突分析：<与 DSHBox 现有实现的运行时/依赖/数据/生命周期冲突及解决方式>
- 来源审计：<该文件在 DSHA 的 provenance 状态，见下>
- 测试：<新增/改动的测试，以及实际执行的命令与结果>
- 未验证范围：<明确写出没验的部分>
```

### 来源审计（强制）

DSHA 自己的 `DSHA:docs/source-provenance.md` 记录了 Shizuku `ShellService`
存在**来源不可核验的 CFR 反编译标注**。因此移植任何 DSHA 文件前必须：

1. 在 DSHA 仓库里 `git log --follow <文件>`，确认该文件的引入提交；
2. 如果文件头带反编译标注（CFR、jd-gui 等），**停止移植**，先单独做来源审计；
3. 在移植 PR 里写明审计结论。结论不明时用 `unknown`，**不要猜**。

### 候选移植清单（按优先级）

见 [FUSION-PLAN.md](FUSION-PLAN.md) 的阶段 2/3。每项都标注了
"是否与 DSHBox 现有实现重复"以及"选哪个为权威实现"。

---

## 四、变更摘要的生成内容

`tools/sync-upstream.sh` 生成的摘要**必须**包含：

1. **提交清单**：短号、日期、作者、标题；标记含 `fix:` / `security` / `CVE` 的条目。
2. **文件变更统计**：按模块聚合的增删改行数。
3. **冲突预警**：变更文件 ∩ `divergences.json` 中登记的 `target_files`。
4. **测试基线变化**：测试文件数量与总行数的增减。净减少 → 报警。
5. **许可与依赖变化**：`LICENSE`、`THIRD_PARTY_NOTICES.md`、`gradle/libs.versions.toml`、
   `package-lock.json` 等文件的变更。**任一变化都要求人工确认**。
6. **资产与工具链变化**：`DSHA:ci/toolchain.lock.json` 类文件、`runtime-bundle/` 脚本。

---

## 五、失败与回退

| 情况 | 处理 |
|---|---|
| 构建失败 | **保留现有稳定主干**，同步分支不合并；记录失败原因到报告 |
| 测试失败 | 同上。不允许"先合进去再修" |
| 真机验证失败 | 同上。真机是最终判据 |
| 偏离重放冲突 | 停下来人工解，不要用 `-X ours` 静默丢弃上游改动 |
| 上游引入了不兼容的运行环境版本 | 在 PR 中说明，**不要**为通过构建而降级门禁 |

> **测试失败时保留现有稳定版本** —— 这是硬规则，不是建议。
> 主干在任何时刻都必须是一个能构建、能装、能跑的状态。

---

## 六、记录要求

每次同步在 `docs/upstream-reports/` 下留一份报告，内容包含：

- 上游提交范围（从哪个提交到哪个提交）；
- 本次集成的补丁/偏离清单；
- 实际执行的构建与测试命令，以及**真实结果**；
- 未验证范围；
- 最终是否合并，未合并的原因。

**没有实际执行的验证不得写"通过"。** 未运行就写"未运行"。
