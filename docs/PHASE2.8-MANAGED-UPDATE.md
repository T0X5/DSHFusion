# 阶段 2.8：受管更新的健康门槛与自动回切

> **这份文件回答一个账面上含混的问题：阶段 2 定义里的「受管更新」去哪了。**
>
> 结论：**它既没做、也没有归属** —— 掉在了两份计划之间。本文给它一个家。

## 一、问题是怎么被发现的

阶段 2 的定义（`docs/FUSION-PLAN.md` 的融发表格第 16 行）是：

> **数据安全：备份恢复 + 受管更新**

而实测：**「备份恢复」做完了（真机验收五条全过），「受管更新」基本没做。**

`governance/dsha-imports.json` 里 **18 个明确未搬的 DSHA 类**中，**4 个是更新事务**：

| 类 | 行数 | 它是什么（类注释） |
|---|---|---|
| `ColdInstallTransaction` | 500 | 冷装：**只用于全新安装**，绝不接受非空 user root、绝不替换现有环境 |
| `EnvironmentRebuildTransaction` | 398 | 整个运行环境重建的宿主日志 |
| `ManagedRuntimeTransaction` | 663 | **受管更新**：复用宿主多根事务；**用户目录不进入切换清单**；**健康验证结束前不提交** |
| `ProfileSettingsTransaction` | 326 | profile 设置的事务 + 真实服务读回 |

**而它为什么没有归属**：`FUSION-PLAN.md` 的 2.x 条目只列到 2.6；
`FUSION-PLAN.md:288` 写着「环境更新是**阶段 3** 的事」——
但阶段 3 已经被重新定义成「统一设备能力层」（见 `PRODUCT-DIRECTION.md`）。
**于是它两边都不属于。**

## 二、审计：DSHFusion 现在有什么

骨干（DSHBox）的运行环境更新在
[`BundleManager.kt`](../sandbox-manager/src/main/java/com/dshfusion/app/sandbox/BundleManager.kt)：

```
installToNewSlot(bundleFile, expectedSha256)   验 sha256 → 解到 runtime-new
promoteNewSlotToCurrent()                      current→previous, new→current
rollback()                                     存在，但**要手动调**
```

调用方 `DefaultSandboxManager.promoteRuntimeBundle()` 只做一件事：
**检查沙箱不在 RUNNING**（`SANDBOX_RUNNING`），然后直接 promote。

### 对比 DSHA 的受管更新，缺三条

| DSHA 的性质 | DSHFusion 现状 |
|---|---|
| **健康验证结束前不提交** | ❌ **promote 完就直接生效**；只验了 bundle 文件的 sha256 |
| 持久化（`syncDirectory` / 跨设备检查 / 五相位标记） | ❌ 用的是 `File.renameTo`，不经过 `BackupFileSystem` |
| 失败**自动**回切 | ❌ `rollback()` 只有手动路径，**没有任何地方在坏更新后自动调它** |
| 用户数据不进入切换清单 | ✅ **天然成立**（运行环境槽与 `user-data/` 是分开的目录） |
| 静止（stop first） | ✅ 有 `SANDBOX_RUNNING` 检查 |

### 后果是什么

装一个**结构上合法但功能上坏掉**的运行环境（sha256 对得上、解压成功，
但架构不对或缺包）→ `promoteNewSlotToCurrent()` **照样把它设成 current** →
DSH 起不来 → **没有自动回退**，用户得自己知道去调 `rollback()`。

**这正是 DIV-0015（层是 x64）那一类事故**：当时是靠 CI 的产物断言拦住的，
但如果它从**用户的更新入口**进来，现在没有任何东西会拦。

## 三、结论：差距是**一个安全性质**，不是 663 行代码

**不搬 `ManagedRuntimeTransaction`。** 理由是：

1. 它 663 行，且依赖 DSHA 自己的 `ManagedRuntimeLayout`、`PrivateRoots`、`ConfigStore` 等
   —— 那是「指向上游自己应用内部子系统」的耦合（第 28 条那一族），搬它等于搬半个更新系统。
2. **DSHFusion 已经有实现那个性质所需的全部零件**：
   - `healthChecker.check()` 带 `webUiReady` + `processAlive`（DIV-0014 建好的）
   - `runtime-previous` 回退槽（已存在）
   - `runtime-failed` 槽（已存在，但只被 `SandboxCleanup` 分类用）
3. 我们要的是**性质**，不是**实现**。用骨干的机制实现同一个性质，改动小得多，
   而且不会把上游的内部耦合带进来。

## 四、实现计划

**目标性质**：**新运行环境在「真的能跑起来」被验证之前，不许成为唯一的 current。**

| 步 | 做什么 |
|---|---|
| 1 | `promoteNewSlotToCurrent()` 之后**不删 previous**（现在是保留的，确认即可） |
| 2 | promote 后**立即启动沙箱 + 健康检查**（复用 `healthChecker`，判据是 `webUiReady` **且** `processAlive`） |
| 3 | 通过 → 提交（保留 previous 一个版本）；**不通过 → 自动 `rollback()`**，并把坏的那份移到 `runtime-failed` |
| 4 | 界面：把「正在验证新运行环境…」和「验证失败，已自动回退到上一版」如实显示 —— **不许静默** |

**测试（缺一不可）**：
- 一条**故障注入**：让新槽的健康检查失败 → 断言**自动回切**且 current 仍是旧的那份。
  注入必须证明自己生效（这是第 16/18/31/38/44 条那一族：**注入静默失效会给出虚假的安全感**）。
- 一条正常路径：健康通过 → 提交，previous 保留一份可回退。
- 一条**反向**：回切之后**再启动一次必须成功**（否则「回切」只是看起来回切了）。

**验收标准**（写进阶段 2 的验收表）：

> 装入一份**结构合法但功能损坏**的运行环境 → 应用能自己发现它起不来 →
> **自动回到上一版**，且**如实告诉用户**；用户不需要知道 `rollback()` 存在。

## 五、归属：**阶段 2.8**

放在阶段 2，不放阶段 3。理由：

- 它是**阶段 2 定义里字面上的另一半**（「受管更新」），不属于「统一设备能力层」那个主题
- 它是**数据安全 / 可用性**问题（坏更新会让环境起不来），与阶段 2 的主题一致
- 阶段 3 的 3.1–3.5 已经排满，塞进去会稀释那件事

**做完 2.8，阶段 2 才算真正完成** —— 在那之前，阶段 2 的完成声明必须写成
「备份恢复已完成；受管更新见 2.8」，而不是「阶段 2 已完成」。
