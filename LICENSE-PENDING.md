# 许可状态：待定（私有自用）—— 详细依据

> **根目录 [LICENSE](LICENSE) 是面向外的简短声明**（GitHub 读的是那个文件）。
> 本文件是它背后的详细依据与待办清单。

**DSHFusion 目前没有对外许可证。这不是疏忽，是一个明确的、有期限的状态。**

> **变更（2026-10-13，DIV-0009）**：根目录原先放的是上游的 GPL-3.0 全文，
> GitHub 因此把本仓库显示成「GPL-3.0」—— 那是一个**本仓库并未做出的许可承诺**。
> 现已替换为状态说明。**GPL-3.0 原文没有被删除**，它按原样保存在
> [governance/upstream-licenses/DSHBox/LICENSE](governance/upstream-licenses/DSHBox/LICENSE)，
> 受 `governance/upstream-license-hashes.json` 的 SHA-256 保护，
> 并由 `tools/check-license-hashes.py` 在每次 CI 中核验。

## 现状

| 事实 | 说明 |
|---|---|
| 仓库可见性 | 私有 |
| 分发范围 | 仅本人使用，不对外分发 APK 或源码 |
| 骨干来源 | DSHBox（**GPL-3.0**） |
| 特性来源 | DSHA（**MIT**） |

## 为什么现在不设许可证

如果**现在**就给 DSHFusion 挂 MIT，那是错的：骨干是 GPL-3.0，源码一旦公开，
整个衍生作品必须是 GPL-3.0。挂 MIT 会造成一个与事实不符的许可声明。

如果现在就挂 GPL-3.0，那是**提前承诺**：你可能还没决定要不要公开，
而 GPL-3.0 会影响你未来对这个项目的使用方式。

所以在"私有自用、不分发"这个阶段，正确的做法是**不设许可证**——
它准确描述了现状：没有授予任何人对本仓库的权利，也没有对外声称任何许可。

## 法律依据（为什么私有自用没问题）

GPL-3.0 的义务由**分发（conveying）**触发（GPL-3.0 §0、§4–§6）。
自己修改、自己编译、自己安装使用，不构成分发，因此不触发源码提供、
许可证随附、衍生作品同许可等义务。

MIT 的义务由**分发时的许可与版权声明保留**触发。同样不触发。

## 一旦你决定公开或分发，必须做的事

公开**之前**（不是之后），按以下顺序处理：

1. **把根 `LICENSE` 换回 GPL-3.0 全文**（原文已在
   `governance/upstream-licenses/DSHBox/LICENSE`，直接取用即可）。
   这是唯一能同时合法容纳两边代码的选择：MIT 代码可以并入 GPL-3.0 作品，
   反过来不行。DSHBox 的 Kotlin 模块（手机助手内核、插件市场、沙箱管理、
   终端模块）都受 GPL-3.0 约束。
   注意：这一步等于**撤销 DIV-0009**，因此要同时更新 `governance/divergences.json`，
   否则下一次同步重放会把根 LICENSE 又改回状态说明。
2. **补全署名**：按 [governance/ATTRIBUTION.md](governance/ATTRIBUTION.md) 的清单，
   在 `THIRD_PARTY_NOTICES.md` 中列出 DSHBox、DSHA、interlock-relay、
   Termux、proot/talloc、DSH 及全部传递依赖的版权与许可。
3. **随源码提供完整对应源代码**（GPL-3.0 §6）：
   包括你对上游代码的任何修改。
4. **处理商标**：换成自己的名称与图标，不复用 DSHBox 的品牌资产
   （其 `TRADEMARK.md` §4 明确禁止）。
5. **处理专有组件**：如果届时要复用 DSHA 的 proroot 二进制，
   必须先确认分发权利 —— 它是**上游专有许可**，DSHA 自己也声明
   "不声称可重编、审计源码或任意修改后再分发"。

## 变更记录

| 日期 | 事件 |
|---|---|
| 2026-10-13 | 项目创建，许可状态定为"待定（私有自用）" |
| 2026-10-13 | 根目录不再放 GPL-3.0 全文（DIV-0009），改为状态说明；GPL 原文移入 `governance/upstream-licenses/` 并受哈希保护 |
