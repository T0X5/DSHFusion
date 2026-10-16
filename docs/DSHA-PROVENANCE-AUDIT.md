# DSHA 来源审计：Shizuku `ShellService`

> **为什么要有这一份**：`AGENTS.md` 的「已知陷阱」写着 ——
> DSHA 自己的 `docs/source-provenance.md` 记录了它的 Shizuku `ShellService`
> **存在来源不可核验的 CFR 反编译标注**，所以「若要复用 DSHA 的对应实现，
> 必须先单独做来源审计；在结论明确前不要把它并入主干」。
>
> **这一份就是那次审计。**

## 1. 被标注的是什么

| 文件 | 行数 | 说明 |
|---|---|---|
| `app/src/main/aidl/.../IShellService.aidl` | 10 | **公开的 `IShellService` 协议**（Shizuku 的标准接口） |
| `app/src/main/java/.../ShellService.java` | **85** | 🔴 **含 `Decompiled with CFR 0.152.`** —— 就是被标注的那个 |
| `app/src/main/java/.../HttpShellService.java` | **2208** | 另一条路径，**没有**这个标注 |

**标注的含义**：CFR 是一个 Java 反编译器。文件头写着它是反编译产物，
**说明这份实现不是手写的** —— 它可能来自某个第三方制品（例如 Shizuku 的示例/sample）。
**而反编译标注本身既不能证明来源，也不能证明许可兼容。**

## 2. DSHA 自己怎么说

它的 `docs/source-provenance.md`：

- 承认旧文件头标注 CFR 0.152，并明确说**该标注不能证明外部反编译对象、原作者身份或许可不兼容**
- build156 对本地可用 Git 历史逐次读原始 blob，找到该路径的 **10 次记录**，
  最早可见提交为 `a3a0b003…`
- **声明当前执行路径用的是公开 `IShellService` 协议、Android `ProcessBuilder`、
  以及仓库自己的 `DeviceShellExecutor` / `DeviceShellPolicy` / `BoundedProc…`**

**也就是说：DSHA 的主张是「现在走的不是那份反编译代码」—— 但那份文件仍在仓库里。**

## 3. 我们的结论：**不复用**（因此这条疑点不成为我们的问题）

**决定性的一步不是去核实那份反编译代码的来源，而是先问「我们需不需要它」—— 答案是：不需要。**

| 依据 | 证据 |
|---|---|
| **DSHFusion 已有自己的 Shizuku 集成** | 骨干（DSHBox）自带：`pilot/dshbox-adapter/` 引用 Shizuku，且 manifest 里有 `${applicationId}.shizuku` 授权 |
| **它的作用与本项目的方向不重叠** | 我们要的是「统一设备能力层」（阶段 3.1–3.5），而那**正是要从两边现有能力里选一套或重做一套** —— 而不是先把一个有来源疑点的实现搬进来 |
| **它不在我们的主干路径上** | 我们没有任何代码引用 DSHA 的 `ShellService` / `HttpShellService` |

**所以：不导入 DSHA 的 Shizuku 实现。** 连带地，那份 CFR 标注的
**来源问题不会进入本仓库** —— 我们既不复制、也不分发它。

## 4. 什么情况下这个结论会失效（必须重做审计）

1. 我们**决定移植 DSHA 的 `HttpShellService`（2208 行）**或其执行路径 ——
   那时要核的是**那一份**的来源（它没有 CFR 标注，但仍需核）
2. 阶段 3.1–3.5 决定**采用 DSHA 的设备桥实现**（而不是自研）
3. 有任何一处代码**派生自** `ShellService.java`

⚠️ **一旦出现上面任一条，本审计作废，必须从「逐 blobs 读原始历史」重新开始**
—— 也就是 DSHA 自己在 build156 做的那件事。

## 5. 本审计**没有**做的事（如实登记）

- **没有**逐 blob 读 DSHA 的 Git 历史去核那 10 次记录
  （我们没有它的完整历史；审计副本是 `--blob:none` 克隆）
- **没有**去比对 CFR 产物与任何公开 Shizuku 制品
- **没有**独立判断那份反编译代码的许可兼容性

**这三件都没做 —— 而结论「不复用」不依赖它们。**
这正是这次审计最值得记的地方：**先问「需不需要」，就绕开了整场来源争论。**
