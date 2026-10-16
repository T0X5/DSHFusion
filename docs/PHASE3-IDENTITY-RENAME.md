# 阶段 3.0：身份收回（全量包名重命名）—— 审计与执行计划

> **状态：审计完成，尚未动代码。**
> 本文件是「先审计再动笔」的产物：把**范围、分类、风险、验证**先写清楚，
> 再决定怎么改。**不要跳过它直接 sed。**

## 一、为什么做

用户 2026-10-16 的定位修正：**上游从「母体」变成「来源」**。
而「看着像 fork」最直接的来源就是包名仍是 `com.dshbox.app`。

`applicationId` 早在阶段 1 就改成了 `com.dshfusion.app`（DIV-0001），
但 **`namespace` 与全部 Java/Kotlin 包路径仍是上游的** —— 那一处当时是**故意留的**
（理由：一次性重命名会让后续每次上游同步都冲突）。现在提前执行。

## 二、实测范围（2026-10-16）

| 项 | 数量 |
|---|---|
| 受影响文件 | **279 个** |
| 总出现次数 | **1069 次**（不是当初估的 721） |
| 要改名的目录 | **52 个** |

按文件类型：

| 类型 | 文件 | 次数 |
|---|---|---|
| `.kt` | 224 | 757 |
| `.sh` | 6 | 107 |
| `.json` | 3 | 75 |
| `.md` | 12 | 64 |
| `.py` | 6 | 30 |
| `.java` | 15 | 15 |
| `.yml` / `.kts` / `.pro` / `.xml` / `.mjs` / `.js` / `.gitignore` | 各 1–5 | 各 1–8 |

## 三、🔴 分类：**每一类的正确动作都不同**

这是本次改动**最容易做错**的地方。1069 处不是同一件事。

### A. 真要改的包与名字空间（改）

- 4+ 个 `build.gradle.kts` 的 `namespace`：
  `com.dshbox.app` / `.backup` / `.bridge` / `.common` / `.sandbox`
- 224 个 `.kt` + 15 个 `.java` 的 `package` / `import` 语句
- 52 个目录路径（`app/src/main/java/com/dshbox/app/**` 等）

### B. 指向我们自己的引用（改）

- `app/proguard-rules.pro`：`-keep class com.dshbox.app.bridge.**`
  → 必须跟着走，否则 release 混淆会**静默失效**（而这正是它要防的事）
- `app/src/debug/AndroidManifest.xml`：
  `<action android:name="com.dshbox.app.dev.action.INSTALL_RUNTIME" />`
  → 这是**我们自己的** action 名

### C. 检查器里的「基线常量」（改，**而且它的期望要翻过来**）

- `tools/check-android-identity.py:42` `UPSTREAM_NAMESPACE = "com.dshbox.app"`
  → 它是**对照基线**。改名后这个常量必须指向新的期望，
  否则那条检查会**从「通过」变成「失败」**，而失败原因看起来像「代码错了」
- `tools/check-apk-identity.py:453` 的注释与断言：
  「namespace 在阶段 1 刻意保持 `com.dshbox.app`；若这里变成 `com.dshfusion.app`…」
  → **这条断言的期望必须整体翻转**

### D. ⚠️ 插件测试夹具里指向上游 app 的（**多半不能改**）

- `plugin-manager/dshbox-plugins/assets/plugins/mobile-pilot/test/device-readings.test.mjs:135`
  ——描述**真机上装着的 DSHBox**（`com.dshbox.app`）的状态。
  **那是上游 app，不是我们**；改了就是伪造测试夹具。
- `.../pilot-tools.test.mjs:203`：`app.launch({ package: 'com.dshbox.app' })`
  ——它在测「按包名启动应用」这个能力，用哪个包名是夹具选择。**需要逐个判断**。

### E. ⚠️ 登记文件里的**历史陈述**（**不能改**）

- `governance/divergences.json`：「applicationId 由 `com.dshbox.app` 改为 `com.dshfusion.app`」
  ——这是**历史事实**。改掉它等于篡改偏离记录。
- `governance/dsha-imports.json` 的 `why` 字段里的同类叙述。

### F. 登记文件里的**真实路径**（**必须改**）

- `governance/dsha-imports.json` 的 `local_path` / `verified_by` / `not_imported[].path`
  ——这些是**真实文件路径**，改名后旧路径就不存在了。
  （该文件 34 处命中里，**一部分属 E、一部分属 F** —— 这就是不能盲 sed 的原因）

## 四、执行顺序（草案）

1. **先落在分支**（已完成：`phase-3.0/identity`）
2. **目录改名**：从最深的目录往上（52 个）
3. **源码替换**：`.kt` / `.java` 的 `package` + `import`（224 + 15 个文件）
4. **构建文件**：4 个 `build.gradle.kts` 的 `namespace`
5. **我们自己指向自己的**：`proguard-rules.pro`、`debug/AndroidManifest.xml`
6. **检查器**：C 类两处，**连期望一起改**
7. **登记文件的路径部分**（F 类），**只改路径、不动 why 里的叙述**（E 类）
8. **文档**：`AGENTS.md` / `README.md` / `docs/*` 里描述现状的地方
9. **D 类逐个判断** —— 列出每一处、写明「为什么改/不改」
10. **登记为一条偏离**（见第五节）

## 五、怎么登记（关键设计决定）

`tools/check-import-fidelity.py` 要求**每一处与上游的差异都能对上一条已登记的偏离**
的 `target_files`。改名会动 224+ 个上游文件 —— 所以必须登记。

**方案**：登记**一条**偏离 `DIV-0025`（`type: identity`），
`target_files` 列出全部被改名的上游文件（生成），
`reapply` 交给 `tools/apply-divergences.sh` 的一个新 handler：
**「把上游带来的 `com.dshbox.app` 系统性改回 `com.dshfusion.app`」**。

于是每次上游同步后，这条偏离可重放，而**不需要逐文件维护**。

> 这与既有做法一致：DIV-0001 就是「applicationId 与版本线」的同类偏离，
> 由 `handle_div_0001` 重放。

## 六、验证（缺一不可）

| 检查 | 证明什么 |
|---|---|
| `./gradlew assembleDebug`（CI） | 改完**编得过** |
| `check-android-identity.py` | namespace 对得上 R 引用与 manifest 相对类名 |
| `check-apk-identity.py` | **产物**里的名字对 |
| `check-import-fidelity.py` | 差异**全部已登记** |
| `apply-divergences.sh --check` | 登记的偏离**真的生效了**（反向） |
| `verify-phase0.sh` | 331 项不因改名而失效 |
| 全库 `grep -r 'com\.dshbox\.app'` | 剩下的**全部**属于 D 类或 E 类，且每一处都有理由 |
| **真机安装 + 跑起来** | **改名最容易在真机上暴露**（authority / action / 无障碍服务名） |

## 七、已知风险

1. **改名后与已装的 DSHFusion 冲突？** 不会 —— `applicationId` 早就是 `com.dshfusion.app`，
   签名也没变，是覆盖安装。但**无障碍服务名**等服务身份要一并检查（见事故第 10 条）。
2. **`.mjs` / `.js` 里指向上游 app 的**（D 类）改错 → **插件运行时行为变化**，
   而单元测试未必覆盖。
3. **`dsha-imports.json` 的 F 类路径**漏改 → `check-dsha-imports.py` 会红
   （**这是好事**：它会当场指出路径不存在）。
4. **`check-apk-identity.py` 的期望没翻** → 它会红，而**红的原因是检查器自己过期了**
   （第 27/44 条那一族：**先问「红的是被查对象还是我的查询」**）。
