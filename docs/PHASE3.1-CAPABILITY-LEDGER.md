# 阶段 3.1：能力台账（**范围与做法**）

> **这份是 3.1 的计划，不是台账本身。**
> 仓库的规矩是「先审计再动笔」—— 而 3.1 本身就是一次审计，
> 所以先把「枚举什么、怎么枚举、什么算做完」钉住，免得枚举出一堆没法用的东西。

## 一、3.1 要回答什么

用户 2026-10-16 定的方向是「**统一设备能力层**」：上游从「母体」变成「**来源**」，
要做出一件两个上游都没有的东西。3.1 是它的第一步：

> **枚举两边现有的设备能力 → 去重 → 标出各自的缺口。**

**它的输出必须能支撑 3.2–3.4 的决策**（授权模型 / 统一入口 / 安全边界），
否则就是一份好看的表格。

## 二、什么算一条「能力」

一条能力 = **一件对设备能做的事**，且必须能用下面四项描述清楚：

| 字段 | 含义 | 为什么必须有 |
|---|---|---|
| `id` | 稳定标识（如 `device.shell.command`） | 3.3 的统一入口要按它派发 |
| **实现**（DSHA / DSHBox / 都没有） | 哪一边有、文件在哪 | 「吸收精华」的对象 |
| **前提** | 需要什么才能用（root / Shizuku / 无障碍 / 应用层） | 3.2 授权模型按它分级 |
| **证据** | 我们**实际验证过**什么（跑过？真机？只读过代码？） | 仓库硬约束 4：没执行的不写「通过」 |

⚠️ **没有「证据」一栏的能力条目不算数** —— 那只是「上游声称有」。

## 三、枚举范围（已知的能力面，按来源）

### 来自 DSHA（`audit/DSHA`）

| 面 | 已知的东西 | 备注 |
|---|---|---|
| 设备 shell | `ShellService` / `HttpShellService`（2208 行） | ⚠️ **来源审计结论：不复用**（见 [DSHA-PROVENANCE-AUDIT.md](DSHA-PROVENANCE-AUDIT.md)） |
| 无障碍 | `interlock-relay-core` 一类的守护 | 与 DSHBox 的 pilot 有重叠 |
| proroot | DSHA 的招牌（性能） | 与骨干的 proot 路线不同 |
| **移动端适配** | `dsh-web-mobile`（1.7 MB / 13k 行 JS） | **3.1 的第一个真实条目** —— 见 [PRODUCT-DIRECTION 9.1](PRODUCT-DIRECTION.md) |
| 内建插件 | 8 个（`builtin-plugins/`，1.9 MB） | 含 `dsh-destructive-guard` / `dsh-device-shell-guide` 等 |
| 冷装 / 受管更新 | 4 个 `*Transaction` 类 | 归**阶段 2.8**，不在这里重复 |

### 来自 DSHBox（骨干，`audit/DSHBox`）

| 面 | 已知的东西 | 备注 |
|---|---|---|
| **Shizuku** | `pilot/dshbox-adapter/` + manifest 的 `.shizuku` 授权 | **我们的 Shizuku 路径来自这里** |
| 无障碍（pilot） | `interlock-relay-core/.../RelayA11yWatchdog.kt` | |
| **移动端适配** | `dsh-mobile-adapt`（107K）+ `mobile-pilot`（354K） | 与 DSHA 那套**是两套** |
| 插件市场 | `plugin-manager` 模块 | |
| 分层运行环境 | `BundleManager` 的三层装配 | |
| 终端 | `terminal-session` / `terminal-emulator` / `terminal-view`（Apache-2.0） | 第三方模块 |

## 四、怎么做（不许凭介绍填表）

1. **每条能力的「实现」都要指到具体文件**，并**读那一处引用**（不是读 README 的功能列表）。
   > 这条来自真实教训：阶段 2.4 的审计第一版有三处被实测推翻，
   > 共同形态是「**从导入清单和行数推断可达性，而没有去读那几处引用本身**」。
2. **「都有」的条目要判「是不是同一套」** —— 例如两个上游的移动端适配**不是同一套**。
   判据：词汇/前提/产物是否一致，而不是名字像不像。
3. **「缺口」要写成可证伪的形式**：不是「DSHA 有而我们没有」，
   而是「**我们要做 X，两边都没有提供 X 所需的 Y**」。
4. **每条都要标证据等级**：`真机验证过` / `本机跑过` / `只读过代码` / `只有上游声称`。

## 五、什么算做完（3.1 的验收）

- [ ] 台账覆盖上面第三节的**全部面**，每面至少一条条目
- [ ] 每条有 `id` / 实现 / 前提 / **证据等级** 四项，缺一不算
- [ ] 「两边都有」的条目给出去重判断（同一套 / 不是同一套）
- [ ] 「缺口」写成 3.2–3.4 能直接用的形式（**要做什么 → 缺什么前提**）
- [ ] 台账里**没有**任何一条只凭介绍/README 填的

## 六、与别的阶段的关系

- **3.2 授权模型** 读台账的「前提」列 → 能力分级（无障碍 / root / Shizuku / 应用层）
- **3.3 统一入口** 读台账的 `id` → 派发表
- **3.4 安全边界** 读台账的「前提」+ 证据等级 → 哪些默认关
- **阶段 2.8（受管更新）** 不在这里重复；台账里只留一条指针

## 七、下一轮直接从这里开始

**第一件事**：把 DSHA 的 8 个内建插件 + DSHBox 的 `dsh-mobile-adapt`/`mobile-pilot`
逐个读一遍**入口文件**，填出第一批条目（预计 8–12 条）——
**因为「两套移动端适配」已经是已知的第一个真实条目，它能把表格的形状试出来。**

---

# 台账 · 第一批（2026-10-16）

> **依据**：逐个读两边的**入口声明**（`package.json` 的 `description` 与目录规模），
> 不是读 README 的功能列表。
>
> ⚠️ **证据等级整批都是「只读过代码/上游声称」** —— 我**没有执行过其中任何一条能力**。
> 按硬约束 4，这一批**不能**被当成「已验证」。它的用途是**把表格形状试出来**，
> 以及给 3.2–3.4 一个可用的骨架。

## DSHA 的 8 个内建插件（`app/src/main/assets/builtin-plugins/`）

| 插件 | 体积 | 它声明的能力（原文摘要） |
|---|---|---|
| `dsh-device-shell-guide` | 27K | 受保护的设备命令 —— **通过 root、Shizuku 或 ADB** |
| `dsh-computer-use-android` | 27K | **Android Computer Use** —— 经认证的 DSHA 设备桥 |
| `dsh-tool-vscreen` | 31K | **虚拟显示（vscreen）自动化工具** |
| `dsh-status-overlay` | 35K | 把 agent 输出**流到手机顶部悬浮条** |
| `dsh-task-notifier` | 19K | agent 完成一轮时**通知用户**（经 3090 桥） |
| `dsh-destructive-guard` | 87K | **破坏性容器命令（`rm` 之类）总是需要确认** |
| `dsh-auto-review` | 19K | 官方实验性 Auto review（**默认不选**） |
| `dsh-web-mobile` | **1.7M** | 尽可能让 DSH **适配竖屏等移动端** |

## 台账

| `id` | DSHA | DSHBox | **前提** | **证据等级** |
|---|---|---|---|---|
| `device.shell.command` | `dsh-device-shell-guide`(27K) | `pilot/dshbox-adapter`（Shizuku） | **root / Shizuku / ADB**（三选一） | 只读过代码 |
| `device.computer-use` | `dsh-computer-use-android`(27K) | **待查** | 设备桥已认证 | 只读过代码 |
| `device.vdisplay` | `dsh-tool-vscreen`(31K) | **待查** | 待查 | 只读过代码 |
| `ui.overlay.status` | `dsh-status-overlay`(35K) | **待查** | 悬浮窗权限 | 只读过代码 |
| `ui.notify.turn-end` | `dsh-task-notifier`(19K) | **待查** | 通知权限 | 只读过代码 |
| `safety.destructive-guard` | `dsh-destructive-guard`(87K) | **待查** | 无 | 只读过代码 |
| `ui.mobile-adaptation` | `dsh-web-mobile`(**1.7M**) | `dsh-mobile-adapt`(107K) + `mobile-pilot`(354K) | 无 | 🔴 **两边都有，且不是同一套**（已核实） |
| `agent.auto-review` | `dsh-auto-review`(19K) | **待查** | 无 | 只读过代码 |

## 这一批已经能看出的三件事

### 1. `device.shell.command` 是**两边都有**，而且前提也一致

DSHA 走 `dsh-device-shell-guide`（root/Shizuku/ADB 三选一），
DSHBox 走 `pilot/dshbox-adapter`（Shizuku）。**这是 3.2 授权模型最该先处理的一条** ——
因为它同时是「两边都有」和「前提有三档」的那一条。

### 2. `ui.mobile-adaptation` 是唯一一条**已核实「不是同一套」**的

其余「待查」的条目里，很可能还有同样的情形 ——
**「名字像」不等于「同一套」**，这正是 3.1 要判的东西（见第二节判据）。

### 3. DSHA 的 8 个里有 **5 个是「对设备做事的 UI/安全」能力**

`overlay` / `notifier` / `vscreen` / `computer-use` / `destructive-guard` ——
它们整体构成一套「**让 agent 在手机上被看见、被约束**」的层。
**这恰好与用户要的「统一设备能力层」同题** —— 而且 DSHBox 那边是 **`mobile-pilot`**（354K）
在扮演类似角色。**这两套的关系是下一批最该查的。**

## 下一批要做的（明确、可执行）

1. **把每个「待查」填上**：去 DSHBox 那边找对应物（`plugin-manager/dshbox-plugins/assets/plugins/`
   与 `pilot/`），**读入口文件**，判「同一套 / 不是同一套 / 没有」
2. **`device.shell.command` 单独深读**：它的三档前提（root/Shizuku/ADB）是 3.2 授权模型的直接输入
3. **`mobile-pilot` vs DSHA 那 5 个 UI/安全插件**：判它们是不是在解决同一个问题
4. 每条**补上「证据等级」的升级路径**（要做什么才算「本机跑过」/「真机验证过」）

---

# 台账 · 第二批（2026-10-16）：把「待查」填上

> 做法：去 DSHBox 侧**读入口文件**（`pilot/`、`plugin-manager/dshbox-plugins/assets/plugins/`），
> 判「同一套 / 不是同一套 / 没有」。**不是靠 grep 命中数当结论** ——
> 命中数只用来找候选，结论要看那一处引用。

## 先更正第一批的一个错误

**第一批写「DSHBox 的插件没有 `package.json`」—— 那是错的。**

`mobile-pilot` **有** `plugin/package.json`（`@local/mobile-pilot`），
只是布局与 DSHA 不同（在 `plugin/` 子目录里）。**我只看顶层就下了结论。**

> 结论没变（两边的插件机制**确实**不同），但**理由错了** ——
> 正确的是「**布局/机制不同**」，而不是「DSHBox 那边没有清单」。
> 这是本文件里第二次「读了一个不含答案的地方」。

## 填好的台账

| `id` | DSHA | DSHBox | 前提 | 证据等级 |
|---|---|---|---|---|
| `device.shell.command` | `dsh-device-shell-guide` | `pilot/dshbox-adapter` | **root / Shizuku / ADB** | 只读过代码 |
| `ui.overlay.status` | `dsh-status-overlay`(35K) | ✅ `mobile-pilot` 的 `悬浮`（`pilot.js` 里 11 处） | 悬浮窗权限 | 只读过代码 |
| `ui.notify.turn-end` | `dsh-task-notifier`(19K) | ✅ `mobile-pilot` 的 `通知`（9 处） | 通知权限 | 只读过代码 |
| `ui.a11y.drive` | 有 | ✅ `pilot/interlock-relay-core`（`RelayA11yWatchdog`） | **无障碍服务** | 只读过代码 |
| `device.app.control` | 待查 | ✅ `mobile-pilot` 的 `app.launch` / `app.info` 工具 | 应用层 | 只读过代码 |
| `device.alarm` | 待查 | ✅ `mobile-pilot` 的 `alarm.set` / `alarm.show` 工具 | 应用层 | 只读过代码 |
| `device.vdisplay` | `dsh-tool-vscreen`(31K) | ★ **没有**（`vscreen`/`虚拟显示` 命中 0） | 待查 | 只读过代码 |
| `device.computer-use` | `dsh-computer-use-android`(27K) | ★ **没有**（`computer-use` 命中 0） | 设备桥已认证 | 只读过代码 |
| `safety.destructive-guard` | `dsh-destructive-guard`(87K) | ⚠️ **部分**：5 个文件提到破坏性/危险命令，但**没找到等价的一道闸** | 无 | 只读过代码 |
| `ui.mobile-adaptation` | `dsh-web-mobile`(1.7M) | `dsh-mobile-adapt` + `mobile-pilot` | 无 | 🔴 **不是同一套**（已核实） |
| `agent.auto-review` | `dsh-auto-review`(19K) | 待查 | 无 | 只读过代码 |

## 这一批的**主要产出：两条 DSHA 独有的能力**

```
device.vdisplay        DSHA 有，DSHBox 没有
device.computer-use    DSHA 有，DSHBox 没有
```

**这两条是 3.1 里最值钱的输出** —— 因为它们是「**吸收 DSHA 精华**」这句话的**具体所指**：
不是笼统地「DSHA 功能更全」，而是**这两件具体的设备能力**在骨干上没有。

⚠️ **但「没有」不等于「该做」** —— 3.4（安全边界）要回答的是：
`computer-use`（让 agent 直接操作手机）与 `vdisplay`（虚拟显示）
**需要什么前提、默认该不该开**。**这正是 3.2–3.4 存在的原因。**

## 顺带查出的 `mobile-pilot` 工具面

它声明的工具里有：`app.launch`、`app.info`、`alarm.set`、`alarm.show`、
以及一批读屏相关的（`checkable` / `bottom` / `forward` / `backward` …）。

**它是一套「应用层 + 读屏」的能力面** —— 与 DSHA 那 5 个 UI/安全插件
**在问题上重叠、在实现上不同**（同 `ui.mobile-adaptation` 的情形）。
**「判它们是不是在解决同一个问题」这件事仍未做完**（下一批）。

## 下一批（更新）

1. **`device.vdisplay` 与 `device.computer-use` 深读**：它们**需要什么前提**、
   在 DSHA 里**默认开不开** —— 这是 3.2/3.4 的直接输入
2. **`safety.destructive-guard` 到底有没有等价闸**：那 5 个文件要逐个读（现在只是「提到」）
3. **`mobile-pilot` vs DSHA 那 5 个**：判「同一问题、不同实现」还是「不同问题」
4. **`agent.auto-review` 在 DSHBox 侧的对应物**（仍是「待查」）
5. 给每条写**证据等级的升级路径**（要做什么才算「本机跑过」/「真机验证过」）

---

# 台账 · 第三批（2026-10-16）：两条 DSHA 独有能力的前提

> 做法：读 `register-builtin-plugins.py`（注册机制）+ 两个插件的 `package.json`
> （**依赖声明就是前提声明**）+ 它们的 `cordis.patch.yml`(机制)。

## 3.1 的结论：**它们不是独立能力 —— 都骑在 DSHA 的设备桥上**

| 能力 | DSHA 的实现 | 它声明的机制 | **前置依赖** |
|---|---|---|---|
| `device.vdisplay` | `dsh-tool-vscreen` | `dsh.bundle.patch: ./cordis.patch.yml` | **`@deepseek-ai/dsh-mcp-client` 0.2.0-rc.2** |
| `device.computer-use` | `dsh-computer-use-android` | 同上 | **`@deepseek-ai/dsh-computer-use`** 0.2.0-rc.2<br>+ `@deepseek-ai/dsh-mcp-client` 0.2.0-rc.2<br>+ **「经认证的 DSHA 设备桥」**（写在它的 `description` 里） |

**两条都是 Cordis bundle patch（`dsh.bundle.patch`）** —— 也就是说它们是
**对 DSH 组合的补丁**，不是独立进程。这一点直接决定了 3.3（统一入口）能不能复用它们。

## 三条对 3.2/3.4 直接有用的发现

### ① `device.computer-use` 是**官方能力的 Android 绑定**，不是自研

它 peer 依赖 **`@deepseek-ai/dsh-computer-use`**（官方的 DSH 包，npm 上就有），
而自己的 `description` 只说「**through the authenticated DSHA device bridge**」。

**含义**：这件能力的「智能部分」是官方的，**DSHA 补的是「怎么落到 Android 上」** ——
而它选择的方式是**它自己的设备桥**。

### ② 两条都依赖同一个东西：**DSHA 的设备桥**

所以「吸收 DSHA 精华」在 `computer-use` / `vdisplay` 上**不能只要这两个插件** ——
**要么连它的设备桥一起吃进来，要么先有我们自己的桥。** 这是 3.1 给 3.3 的最硬的一条输入。

### ③ `dsh-tool-vscreen` 在 DSHA 里**需要专门的 workaround**

`register-builtin-plugins.py` 里有一整个 `migrate_vscreen_workaround()`（含正则匹配
`dsh-tool-vscreen` 在 profile 里的写法、备份原文件、改写、留 `.dsha-vscreen-original-<sha>`）。

**含义**：**这条能力在 DSHA 自己那边也不稳** —— 它需要一次迁移来修组合文件。
⚠️ **这不是「DSHA 做得不好」，而是「虚拟显示这类能力天然依赖平台细节」** ——
3.4（安全边界）要考虑它是**默认关**的那一类。

## 注册机制（两侧对照，3.3 的输入）

| | DSHA | DSHBox |
|---|---|---|
| 清单位置 | `register-builtin-plugins.py` 的 `DEFAULT_BUILTINS` | `mobile-pilot` 的 `plugin/package.json` |
| 实体位置 | rootfs 里的 `/root/dsha-*`（`link:` 依赖） | `plugin-manager/dshbox-plugins/assets/plugins/` |
| 启用方式 | 写进 profile 的 `bundles` | 写进 `dependencies`（`link:`） |

**两边都是「写进组合」** —— 机制形似，**但清单的位置与格式不同**。
`DEFAULT_BUILTINS` 里的名字**就是默认注册的那一批**（DSHA 的 8 个）。

## 仍未查（下一批）

1. 两条的 `cordis.patch.yml` **具体补了什么**（现在只知道「补了组合」）
2. `device.computer-use` 的「已认证设备桥」**认证的是什么**（token？3090 那条？）
3. `mobile-pilot` vs DSHA 那 5 个：**是不是在解决同一个问题**（上一批遗留）
4. `agent.auto-review` 在 DSHBox 侧的对应物（第一批遗留）
5. 每条能力的**证据升级路径**
