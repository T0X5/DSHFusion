# 阶段 3.5：真机验收（**命令尚未验证**）

> ⚠️ **先说清楚：下面这些命令一条都还没在真机上跑过。**
> 本文档是**待执行的清单**，不是「已验证的结果」。
> 第一次执行时，**同时也在验证这份清单本身能不能用** ——
> 若某条命令跑不通，要改的是清单，不是硬凑一个「通过」。
>
> 判据与预期值来自 [PHASE3.4-SECURITY-BOUNDARY.md](PHASE3.4-SECURITY-BOUNDARY.md) §四。

---

## 一、这一阶段要证明什么

**不是「功能能用」，而是「关着的时候真的做不了」。**

| # | 要证明的句子 | 为什么它最值钱 |
|---|---|---|
| 1 | 全新安装后，服务**根本没在监听** | 「默认关」是结构上的，不是策略上的 |
| 2 | 打开开关后，`/app/version` 能应答，`/app/ui/dump` 仍被拒 | 档位门在 = 授权门在工作 |
| 3 | 再打开无障碍后，`/app/ui/dump` 返回**真实屏幕内容** | 读屏那条链接通了 |
| 4 | **把无障碍关掉后再调一次，回到拒绝** | ★ **最能证明边界会守住** —— 服务被系统回收是常态 |
| 5 | 没有 token 一律 401 | 鉴权先于路由 |

> **第 4 条是整套验收里最有价值的一条**：它验的不是「能用」，
> 而是「**不能在不该用的时候用**」。前三条都能靠通读代码猜对，第 4 条不能。

---

## 二、前置

1. **debug APK**（必须，`DevDeviceCapabilityReceiver` 只在 debug 变体里注册）：

   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. **取桥的 token**（debug 应用可 `run-as`）：

   ```bash
   adb shell run-as com.dshfusion.app cat files/user-data/.dsh/.device-bridge-token
   ```

   > 该文件在**第一次构造容器时**生成（`BridgeToken.loadOrCreate`）。
   > 所以要先启动一次 App，或先发一次下面的广播。

3. **确定端口**：`startIfEnabled` 默认用 `preferredPort = 0`（系统挑）。
   广播的返回值里带着 `port=`，**以它为准**，不要猜。

---

## 三、执行清单

> 每条都写「命令 / 预期 / 实际」，实际那一栏**留空到真跑过再填**。

### 步骤 1 —— 全新安装：服务没在监听

```bash
adb shell am broadcast -n com.dshfusion.app/.dev.DevDeviceCapabilityReceiver \
  -a com.dshfusion.app.dev.action.DEVICE_CAPABILITY --ez enabled false
```

**预期**：`result=0`，数据里 `outcome=DISABLED`，日志 `previous=false requested=false outcome=DISABLED`。

再加一条反证（**这才是判据**）：

```bash
adb shell "cat /proc/net/tcp | grep -c ':0A' "   # 或对比开关前后
```

**预期**：找不到本应用的监听端口。**实际**：——

### 步骤 2 —— 打开开关，两个端点的表现应当**不同**

```bash
adb shell am broadcast -n com.dshfusion.app/.dev.DevDeviceCapabilityReceiver \
  -a com.dshfusion.app.dev.action.DEVICE_CAPABILITY --ez enabled true
# 记下返回值里的 port=NNNNN
```

然后（`<TOKEN>` 与 `<PORT>` 用上一步的值）：

```bash
# 无 token —— 预期 401 UNAUTHORIZED，且**不泄露端点是否存在**
adb shell curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:<PORT>/app/version

# 有 token + /app/version（T0）—— 预期 200，正文以 BRIDGE_PROTOCOL=3 开头
adb shell curl -s -H "X-Token: <TOKEN>" http://127.0.0.1:<PORT>/app/version

# 有 token + /app/ui/dump（T2，无障碍还没开）—— 预期 403 TIER_DISABLED
adb shell curl -s -H "X-Token: <TOKEN>" http://127.0.0.1:<PORT>/app/ui/dump
```

> ⚠️ **`adb shell curl` 可能不存在**（部分系统镜像没有 curl）。
> 若没有，改用容器里的 curl（DSH 的 Ubuntu 有），或临时用 `toybox` 的能力。
> **这条要在第一次执行时确定下来，然后写回本文档。**

**预期**：`401` / `200 BRIDGE_PROTOCOL=3…` / `403 TIER_DISABLED`。**实际**：——

### 步骤 3 —— 打开无障碍后再调

手动在系统设置里给我们应用开无障碍（`RelayAccessibilityService`），然后：

```bash
adb shell curl -s -H "X-Token: <TOKEN>" http://127.0.0.1:<PORT>/app/ui/dump | head -5
```

**预期**：`200`，正文是 `[0] "…" clickable center=(x,y) bounds=l,t,r,b` 这样的行。
**实际**：——

### 步骤 4 —— ★ 关掉无障碍，再调一次

在系统设置里关掉无障碍服务，然后重复步骤 3 的命令。

**预期**：**回到 403 `ACCESSIBILITY_SERVICE_OFF`**（不是 200、不是 500、不是空正文）。

> 这一条验的是 `RelayAccessibilityService.current()` 在服务被回收后返回 null，
> 以及那条 null 被如实变成拒绝 —— **而不是被某个宽泛的 catch 吞成「看起来成功」**。

**实际**：——

### 步骤 5 —— 关掉开关，服务不再应答

```bash
adb shell am broadcast -n com.dshfusion.app/.dev.DevDeviceCapabilityReceiver \
  -a com.dshfusion.app.dev.action.DEVICE_CAPABILITY --ez enabled false
adb shell curl -s -o /dev/null -w '%{http_code}\n' -m 3 http://127.0.0.1:<PORT>/app/version
```

**预期**：连接被拒绝（curl 退出码非 0），**不是** 401/403 —— 因为**服务已经不在了**。

> 「连接被拒」与「鉴权失败」是不同的两件事。这一条要的正是前者。

**实际**：——

---

## 四、这一阶段**不做**什么

- **不验 release 变体**：那个接收器不在 release 里，所以 release 下**没有**这条打开路径 ——
  这是设计，不是缺口。设置页开关（下一步）才是 release 的路径。
- **不验多用户 / 多进程**。
- **不验性能**。

---

## 五、跑完之后要更新什么

1. 本文档每条命令的「实际」栏；
2. 把「命令尚未验证」这句话改掉，或改成「已验证到第 N 步」；
3. 若某条判据不成立，**先记成事故**，不要就地降低判据 ——
   「判据改小一点让它通过」是把门禁变成装饰。
