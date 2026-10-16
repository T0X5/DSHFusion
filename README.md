# DSHFusion

在 Android 手机上运行 **DeepSeek Harness（DSH）** 的个人自用项目。
**以 [DSHBox](https://github.com/WSK-build/DSHBox) 为第一阶段稳定骨干**，
选择性吸收 [DSHA](https://github.com/DSH-APP/DSHA) 的成熟实现与运行时优化。

---

## 当前状态：阶段 1 与阶段 2 已完成

**这是一个可构建、可安装、真机上跑得起来的 Android 项目。**

阶段 1 与阶段 2 均已合入 `main`（2026-10-16）。阶段 2 的数据安全验收标准
**五条全部在真机上满足**，包括「可换台设备恢复」。

| 阶段 | 内容 | 状态 |
|---|---|---|
| **0** | 骨架：规范、双上游清单、CI | ✅ 完成 |
| **1** | 骨干落地：可构建、可装、可跑的极小版本 | ✅ **已完成（真机验证）** |
| **2** | 数据安全：备份恢复 + 受管更新 | 🔨 备份恢复 ✅ **真机验证通过**；**受管更新见 [2.8](docs/PHASE2.8-MANAGED-UPDATE.md)** |
| **3** | 设备能力：设备桥 / LAN / ADB 无线 | 🔨 进行中 —— 已重新定义为**统一设备能力层** |
| **4** | 深度优化：proroot、高级功能 | ⬜ 待做（**包名重构已于阶段 3.0 完成**） |

### 关于阶段 3 的重要更正

**上游从「母体」变成「来源」。** 目标不是「跟得上上游」，
而是**做出一件两个上游都没有的东西**。

阶段 3 的第二件定为**统一设备能力层**（DSHBox 的无障碍助手与 DSHA 的设备桥合为一套
能力分级、可授权、可撤销、可审计的统一层）；身份收回（全量包名重命名）提前到现在。
依据、可检查标准与阶段计划见
[docs/PRODUCT-DIRECTION.md](docs/PRODUCT-DIRECTION.md)——**不要重新推翻**。

---

## 先读这些

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | **唯一权威规范入口**（人和 AI 都先读这份） |
| [docs/AUDIT.md](docs/AUDIT.md) | 只读审计报告 —— 主干选择的完整证据 |
| [docs/FUSION-PLAN.md](docs/FUSION-PLAN.md) | 融合方案与实施优先级 |
| [docs/UPSTREAM-SYNC.md](docs/UPSTREAM-SYNC.md) | 双上游长期同步流程 |
| [tools/README.md](tools/README.md) | 每个工具的用途、用法与退出码 |
| [docs/PHASE0-VERIFICATION.md](docs/PHASE0-VERIFICATION.md) | 阶段 0 验证报告（命令、输出、退出码） |
| [docs/PRODUCT-DIRECTION.md](docs/PRODUCT-DIRECTION.md) | 🔴 **产品方向**：为什么上游是来源、要做什么、怎么检查 |
| [docs/INCIDENTS.md](docs/INCIDENTS.md) | 44 条事故与真机验证记录（附录 B 是索引表） |
| [docs/PHASE1-PREREQUISITES.md](docs/PHASE1-PREREQUISITES.md) | 阶段 1 前置审计：运行时资产 · 许可证 · 数据迁移 |
| [docs/INSTALL-ON-DEVICE.md](docs/INSTALL-ON-DEVICE.md) | 真机安装步骤（手动执行） |
| [governance/ATTRIBUTION.md](governance/ATTRIBUTION.md) | 署名与许可义务 |
| [LICENSE-NOTES.md](LICENSE-NOTES.md) | 许可状态说明（为何暂不设许可证） |

### 可核验性（不靠「我说完成了」）

- `python3 tools/verify-phase0.sh` —— **337 项**，每项负面路径都带故障注入，注入失效会自己报红
- `bash tools/run-backup-jvm-tests.sh` —— 本机真编译真跑 `:backup` 的 Java 侧
- 24 处对上游的偏离全部登记于 `governance/divergences.json`，可由 `apply-divergences.sh` 重放
- DSHA 导入的 30 个文件逐字节溯源，正文 sha256 受 `check-dsha-imports.py` 保护
- 真机证据逐环独立：进程 uid → 端口 owner → 浏览器连接 → token 被接受 → 备份/恢复端到端

---

## 为什么以 DSHBox 为骨干

三条可验证的工程事实（完整证据见 [docs/AUDIT.md](docs/AUDIT.md)）：

1. **DSHBox 主干是可验证的**（CI 全绿）；**DSHA 主干当前是坏的**
   （CI 失败，已本地复现 4 项契约检查失败，由 `7bcae595d1` 引入且未修复）。
2. **DSHBox 的运行环境可从公开来源重建**（Debian 官方源 + npm 镜像，含
   `Release.gpg` 验签）；**DSHA 需要一份不公开的私有资产包**（9 个大型生成物不提交）。
3. **DSHBox 不修改 DSH 源码**（运行期 `node --import` 垫片 + 原生桥）；
   **DSHA 维护大量上游源码补丁**，每次 DSH 上游重构都要跟着修。

第 3 条对"长期吸收上游"这个目标最关键：漂移成本最低的项目才适合当骨干。

DSHA 并非劣质项目——它的功能面更宽（备份 v5、LAN、ADB 无线、proroot +58%）。
它的定位是**特性与优化的来源**，逐项移植，而不是整体合并。

---

## 工具

```bash
python3 tools/check-upstreams.py          # 治理契约检查
python3 tools/check-references.py         # 引用完整性（断链、不存在的路径）
python3 tools/check-license-hashes.py     # 上游许可原件哈希核验
bash tools/verify-phase0.sh               # 阶段验证套件（337 项，含失败路径与故障注入）
tools/sync-upstream.sh dshbox --fetch     # 拉取上游 + 生成变更摘要
tools/apply-divergences.sh --check        # 检查已登记偏离的施加状态
```

上游镜像**不入库**（上百 MB 对象库），需在仓库外建立：

```bash
mkdir -p ../upstream-mirrors
git clone --mirror https://github.com/WSK-build/DSHBox.git ../upstream-mirrors/DSHBox.git
git clone --mirror https://github.com/DSH-APP/DSHA.git   ../upstream-mirrors/DSHA.git
```

---

## 许可

当前**暂不设置许可证，私有自用**。理由与后续步骤见
[LICENSE-NOTES.md](LICENSE-NOTES.md)。

上游版权与许可原文按原样保存在
[governance/upstream-licenses/](governance/upstream-licenses/)：

- DSHBox —— GPL-3.0，Copyright (C) 2026 WSK-build
- DSHA —— MIT，Copyright (c) 2026 qiannianhuanxiang

> **一旦决定公开或分发，必须改为 GPL-3.0** —— 这是唯一能同时合法容纳两边代码的选择。
> 同时必须更换名称与图标（DSHBox 的 `TRADEMARK.md` 保留其品牌资产全部权利）。
