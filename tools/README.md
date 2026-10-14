# tools/ — 工具清单

阶段 0 的全部工具。每个都能独立运行，都不依赖 Android 工具链。

> **可执行位提示**：`git clone` 会保留可执行位；但 **ZIP 归档不保留 Unix 权限位**，
> 从 ZIP 解压后 `./tools/<tool>.sh` 会报 `Permission denied`（exit 126）。
> 两种解决办法，任选其一：
> - 用 `bash tools/<tool>.sh` 显式调用（`verify-phase0.sh` 内部已经这样做，所以它不受影响）；
> - 或先 `chmod +x tools/*.sh tools/*.py`。

---

### `check-import-fidelity.py` — 导入保真度

逐 blob 对照上游审计副本的 git 索引，确认「原样导入」这个承诺成立。

比对索引 blob 而不是工作区内容：工作区会被 `.gitattributes` 的换行转换和本地
`chmod` 影响，blob 才是别人 clone 之后真正拿到的东西。

差异必须能对上 `governance/divergences.json` 的登记，否则报错。

> 它在 2026-10-13 首次运行时就抓到一个真问题：本项目 `.gitattributes` 的
> `* text=auto eol=lf` 把 `gradlew.bat` 从 CRLF 规范化成了 LF，blob 与上游不同。
> 改成 `text eol=crlf` **仍然不对**（那只影响检出），最终要用 `-text`。

用法：`python3 tools/check-import-fidelity.py [--list-diffs]`
退出码：0 一致或上游未导入；1 有未登记的差异；2 配置错误。

### `check-exec-bits.py` — 脚本可执行位

读 git 索引里的模式，对照 `governance/inherited-lint-baseline.json` 双向检查：

- 不在继承基线的 `.sh` → 必须 `100755`（拦住本项目自己的疏漏）
- 在继承基线的 `.sh` → 必须 `100644`（拦住基线失真）

单方向检查做不到第二点：只查「非可执行就报错」的话，基线里的文件被 `chmod`
成可执行反而放行，而真实状态已经和上游不一致。

> 这个逻辑原先内联在 `verify-phase0.sh` 里，CI 另有一份更旧的实现。
> 两份实现漂移的后果就是套件修好了、CI 仍红 —— 所以只留一份。

用法：`python3 tools/check-exec-bits.py [--list]`
退出码：0 全部符合；1 有不符项；2 不在 git 仓库里。


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

### `check-workflows.py`

用**真实的 YAML 解析器**（PyYAML）校验 `.github/workflows/*.yml`，并断言
GitHub Actions 工作流必需的结构。

```bash
python3 tools/check-workflows.py
python3 tools/check-workflows.py --verbose
python3 tools/check-workflows.py --dir <目录>   # 测试用
```

**退出码**：`0` 通过；`1` 有解析失败或结构问题；`2` 环境缺少 PyYAML。

为什么需要它：在接入本脚本之前，两个工作流**只被结构性检查过**
（顶层键是否存在、有没有制表符），从未被真正的解析器读过。那无法发现
缩进错误、重复键、类型错误 —— 而这类问题会让工作流**静默失效**。

与 actionlint 的分工：
- 本脚本负责「YAML 合法 + 结构完整」，只需 PyYAML，本地随时可跑；
- actionlint 负责「语义正确」（表达式引用的 context、action 输入名、`run:` 块里的
  shell 问题），它是 Go 二进制，在 CI 里单独安装（见 `contracts.yml`）。

两者都不能被对方替代：actionlint 不校验"这个文件到底能不能被解析成工作流"，
PyYAML 不校验"这个表达式引用的 context 是否存在"。

> **YAML 1.1 陷阱**：PyYAML 把 `on:` 解析成布尔 `True`（`on`/`yes`/`true` 都是真值），
> 所以按字符串取顶层键会误报"缺少顶层键 on"。本脚本对此做了兼容处理。

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

#### `--record` 的四项校验

`--record` 是**唯一会改动 `governance/upstreams.json` 的动作**，因此它有四道校验，
任何一道不过就拒绝推进 `locked_commit`（退出码 1）：

| # | 校验 | 拦下的情形 |
|---|---|---|
| 1 | 提交对象存在于镜像且是 commit | 提交号写错、把 tree/blob 的 SHA 当提交、镜像里根本没有 |
| 2 | 提交属于该上游的默认分支历史 | 提交来自别的分支、别的仓库、或已被 force-push 丢弃 |
| 3 | 当前 `locked_commit` 是它的祖先 | 上游 force-push 导致关系回退；并算出领先几个提交 |
| 4 | 存在针对**该提交**的审核记录 | 没做人工审核就想推进 |

第 4 项按**内容**查找审核记录（在全部摘要里搜 `record_commit: <提交>`），
不按文件名推断 —— 摘要文件名用的是「运行日期」，而提交日期可能不同
（从旧提交开始集成、跨天合并都会错位）。若记录里写了 `range:`，还会校验它与
实际范围一致，防止记录与提交错配。

**为什么不提供强制跳过的开关**：跳过校验会让未集成的提交在下次摘要里静默消失。
真需要人工干预时，直接编辑 `upstreams.json` 并写明理由 —— 手动编辑会留下 diff 痕迹。

成功时会把这次推进追加到 `locked_commit_history`，留下可审计的轨迹
（谁、从哪个提交、到哪个提交、集成几个提交、依据哪份审核记录）。

### `apply-divergences.sh`

合并上游后重放已登记的偏离。上游版本会覆盖我们对上游文件的修改
（applicationId、应用名、资产路径等），本脚本按 `governance/divergences.json`
把它们重新施加回去。

```bash
tools/apply-divergences.sh --check    # 只报告每处偏离的施加状态
tools/apply-divergences.sh            # 施加 type=identity/config 的偏离
```

**退出码**：
- `0` 全部偏离要么已生效、要么被明确判定为「待上游导入」，没有静默遗漏；
- `1` 有偏离无法施加（目标文件缺失、固定字符串未匹配、替换后未生效、
  或该偏离类型没有实现施加逻辑）。

阶段 0 会明确报告「上游代码尚未导入」并列出待施加的偏离，同时检查
`divergences.json` 的 `applied` 是否为空 —— 若上游还没导入却登记了 `applied`，
说明有偏离被错误标记为「已施加」，它会在下次同步时被跳过。这种情况报错退出。

**每一处修改都必须通过三项检查**：前置（目标文件存在且能匹配固定字符串）、
动作（替换成功）、后置（改完之后偏离声明的目标状态真的成立）。
任何一项不过就**还原该文件**并报错，不留半成品。

> **一个真实教训**：早先版本的 `app_name` 偏离只检查「旧值 `DSHBox` 不存在了」
> 就算成功。上游一旦把 `app_name` 改名或删掉，这个判据会**假性成立** ——
> 脚本报"已生效"，而目标状态其实从未达成。现在改为**正向确认目标值存在**，
> 这个漏洞是靠 `verify-phase0.sh` 的 `strings-moved` 夹具场景发现的。

**不做破坏性操作**：`website/` 这类目录型偏离不会自动删除，只打印提示，
由人确认后执行 `git rm -r website/`。

---

## ⚠️ 关于套件自身的一个事故（值得每个写检查的人读）

`verify-phase0.sh` 里的 `ok()` / `fail()` 两个计数器函数**一度根本不存在**。

调用它们的地方不会报错退出 —— 脚本没有 `set -e`，`ok: command not found`
只打到 stderr，不改变退出码。于是那些断言**静默变成了空操作**，
而套件仍然打印「全部通过」。

代价：`5d`（APK 产物身份）与 `5e`（许可状态）两节共 11 项断言全部没执行过。
发现方式是加 `5f` 时看到输出里的 `fail: command not found`，
再一查才意识到前面两节也是同样的空转。

补上函数后，套件总数从 **92 项跳到 113 项** —— 多出来的 17 项不是新增的检查，
而是**原本就写着、却从来没跑过**的检查。

由此得到一条规则，已写进脚本注释：**检查器本身必须有测试。**
一个什么都不查的检查，比没有检查更危险 —— 它提供虚假的信心。
本套件现在对每个工具都做故障注入，原因就在这里。

## 关于 authority：一条容易被漏掉的身份轴

`applicationId` 决定「装的是哪个应用」，但 **ContentProvider 的 authority 是另一条
独立的轴** —— Android 要求它全局唯一。两个已安装应用声明同一个 authority 时，
后装的那个安装失败 -13（`INSTALL_FAILED_CONFLICTING_PROVIDER`），
**跟 applicationId 是否不同无关**。

这不是理论风险：阶段 1 改了 applicationId 之后 CI 全绿，真机上却装不上去，
因为 FileProvider 的 authority 写死成了上游的值。见 DIV-0011。

现在有两条检查盯着它，一条在源码层（构建前就报），一条在产物层（证明值真进了 APK）：

```bash
python3 tools/check-android-identity.py    # 源码：manifest 与代码里的 authority
python3 tools/check-apk-identity.py a.apk  # 产物：APK 里每个 authority
```

**判断 authority 对不对，标准不是「值等于某个期望值」，而是「这个值是否派生自
applicationId」。** 前者只对当前配置有效，后者在任何 applicationId 下都成立。

## 产物核验类

### `check-apk-identity.py`

核验**构建产物** APK 的应用身份，而不只是构建配置：`applicationId`、
`versionCode` / `versionName`、`application` 类、以及 `android:label`
在**各语言下**解析成的应用名。顺带报告 APK 里有没有运行环境层。

```bash
tools/check-apk-identity.py <apk> [--expect-label DSHFusion]
```

只用标准库（`zipfile` + `struct`）解析二进制 `AndroidManifest.xml` 与
`resources.arsc`，不依赖 aapt2 / apktool。

**为什么需要它**：配置写对了不等于打进 APK 的东西对了 —— 中间还有资源合并、
资源 ID 分配、字符串池去重。阶段 1 实测过这个区别：
`versionCode`/`versionName` 在 gradle 里是上游的 `8` / `1.4.0`，
配置与产物一致但两者都错；而 `android:label` 是资源引用 `0x7f0e001f`，
不解 `resources.arsc` 就不知道它各语言下解析成什么。
已接进 `android.yml`（DIV-0008），在 `assembleDebug` 之后运行。

**断言 7（DIV-0015）：`dsh` 层必须是目标架构（arm64）的。**
会流式解开 APK 里嵌的层，检查 7 个按平台分发的包是否齐全、有没有 x64 混入。
这一条是真机失败之后补的 —— 2026-10-14 的产物**通过了当时全部断言**，
包括专为「层能不能用」写的断言 6，然后在真机上报
`host preparation failed: No usable native binding found for
node-addon-require-builtin-linux-arm64-gnu`。原因是 CI 跑在 x86_64 上，
npm 按宿主平台装了整套 `-linux-x64` 可选依赖。

没有 `zstd` 命令时这条**判失败、不跳过** —— 「核验不了」不等于「没问题」。

退出码：0 通过；1 有身份断言失败；2 用法错误或 APK 无法解析。

### `make-apk-identity-fixture.py`

造合成 APK 供上面那个检查器做隔离测试 —— 否则只能测到 argparse，
测不到断言。

```bash
tools/make-apk-identity-fixture.py <输出 apk> <场景>
```

场景：`good`（应通过）、`upstream-id`、`upstream-ver`、`wrong-ns`、
`wrong-label`、`bad-authority`、`no-layers`、`sidecar-only`、`gz-stripped`、
`x64-layer`（都应退出 1）、`arm64-layer`（应通过）、`no-manifest`（应退出 2）。

`x64-layer` / `arm64-layer` 成对存在，用来测断言 7：两者**只差层里那 7 个包**，
所以「一个失败、一个通过」才能说明失败的原因是架构，
而不是碰巧被别的断言拦下。它们需要 `zstd` 来造**真的**层
（断言 7 会解压它），没有 `zstd` 时退出 3 表示跳过 —— 不造假层，
假层会让这两个场景永远「通过」。

> 加了断言 7 之后，原先所有场景用的 `_FAKE_ZSTD`（只有帧头、没有内容）不够用了：
> `good` 也开始失败，因为假帧头解不开。那不是断言太严，是夹具失真 ——
> 一个解不开的层本来就不该算合格产物。现在带 zstd 层的场景都造真层。

手写 AXML 而不是用 aapt2：本机没有 Android SDK，CI 里也不该为造夹具装一整套；
手写反而让「检查器读的到底是什么字节」完全明确。

## 构建类

这些工具**产出文件**，不参与 CI 的通过/失败判定。

### `build-dsh-layer.sh`

构建 DSH 运行环境层（`dsh/<version>.tar.zst` + `.sha256`），补上审计中的
**运行环境风险 R1**：上游把 DSH 层当独立发布产物（`bundle.yaml` 明确写
「bundle 不得包含 DSH」），却没有生成它的脚本 —— `runtime-bundle/scripts/install_dsh.sh`
是设计成在 Debian 沙箱**内部**跑的片段，没有调用方，克隆仓库后无法直接执行。
结果是新克隆一份代码构建出的 APK 不含 DSH 层，装上也起不了 DSH。

```bash
tools/build-dsh-layer.sh [输出目录]      # 默认 ./runtime/android-assets
```

- **版本取自 `governance/upstreams.json`** 的 `tracked_version`，不在这里写死默认值。
  上游 `install_dsh.sh` 默认 `0.1.0-rc.6`、实际适配 `0.2.0-rc.2`（风险 R3，已由 DIV-0010 取消默认值）
  因此取消默认值（DIV-0010）：未显式指定 `DSH_VERSION` 就报错退出。
  注意这不是「消除了 R3」—— 新脚本只是新增了一条正确路径，
  原来那个默认值仍在上游文件里，直到 DIV-0010 才处理。
- 装完后**核对版本**，不一致直接失败 —— 版本对不上的层会让「已适配 X」变成假话。
- 有 `node lib/bin.js --version` 冒烟测试（`DSH_SKIP_SMOKE=1` 可跳过）。
- **必须有 `zstd`，没有就退出 2。** 不退回 gzip，见下面 `build-runtime-assets.sh`
  一节里「为什么必须是 zstd」—— 一句话：**AGP 会解压 `.gz` 资源并去掉扩展名**，
  于是 `.tar.gz` 层进 APK 后变成应用侧找不到的 `.tar`，等于白建。
- **必须显式声明目标平台（DIV-0015）。** 此处原先写着
  「不需要 arm64 主机：`@deepseek-ai/dsh` 是纯 JS 的 npm 包，无原生扩展、
  无 `os`/`cpu` 约束」—— **后半句是错的，而且它就是这个 bug 的成因**。
  `@deepseek-ai/dsh` 包本身确实是纯 JS，但依赖树里有 7 个按平台分发的可选包
  （`optionalDependencies`），npm 按**构建宿主**挑选：

  ```
  node-addon-require-builtin-linux-<arch>-gnu   ← dsh-app-boot 启动时必需
  @deepseek-ai/node-addon-system-linux-<arch>
  @img/sharp-linux-<arch> / @img/sharp-libvips-linux-<arch>
  @koromix/koffi-linux-<arch>
  @vscode/ripgrep-linux-<arch>
  sherpa-onnx-linux-<arch>
  ```

  在 x86_64 上建层会得到整套 `-linux-x64`，装到 arm64 手机上 DSH 会在
  host preparation 阶段致命退出。修法是 npm 的交叉挑选参数
  （`--os=linux --cpu=arm64 --libc=glibc`，npm ≥ 10.4），
  由 `DSH_TARGET_ARCH` 控制（默认 `arm64`）。
  装完有**平台自检**：7 个包必须齐全、且不得混入另一架构，否则拒绝产出。
  CI 上的 `check-apk-identity.py` 断言 7 会对产物再查一遍。
- 压缩优先用系统 `zstd` CLI（`-19 -T0`）；没有 CLI 时退回 node 的
  `zlib.createZstdCompress`，两者都没有才退 2 —— **绝不产出 gzip**。
  ⚠️ node 的 zstd **忽略 `level` 参数**（实测 level 1/3/9/19/22 输出字节数完全相同），
  所以走那条路产物会大约 32%（84973267 B → 111809043 B）。脚本会打印警告。
  CI 有 CLI，不走这条路。

实测（arm64 层，zstd CLI -19）：**84973267 B（81.0 MB）**，
sha256 `2183017cc8f1122f410194fd9ab0e0b59a2e52de27e12dc801e42d02d4c4b238`。
与上游 v1.4.0 Release 里的官方层逐包对照，**520 个真实包完全一致**。

退出码：0 成功；1 构建/核对/冒烟/平台自检失败；2 用法或环境错误（缺 node、缺 zstd、版本读不到）。

### `build-runtime-assets.sh`

组装**能内嵌进 APK 的**运行环境层：`android-side` + `dsh`，各带 `.sha256` 侧车。
这是补 `build-dsh-layer.sh` 之后剩下的那一半 —— 光有层、没人把它放进 APK，等于没有。

```bash
tools/build-runtime-assets.sh [输出目录]   # 默认 <repo>/runtime/android-assets
```

输出布局**刻意与 `assets.srcDirs` 对齐**，所以 `DSHFUSION_RUNTIME_ASSETS`
指到这个目录就能直接打包：

```
<输出目录>/runtime/android-side.tar.zst    → APK 内 assets/runtime/android-side.tar.zst
<输出目录>/dsh/<版本>.tar.zst              → APK 内 assets/dsh/<版本>.tar.zst
```

- `android-side` 从 `app/src/main/jniLibs/arm64-v8a` 组装（4 个文件）：
  `libproot.so`→`bin/proot`、`libproot-loader.so`→`libexec/proot/loader`、
  `libandroid-shmem.so`→`lib/libandroid-shmem.so`、`libtalloc.so`→`lib/libtalloc.so`。
  这四个路径必须与 `DefaultSandboxManager` 的 `prootBinary()` / `prootLibDir()` /
  `prootLoaderFile()` 一致，否则装上了也起不来。
- **显式 `chmod 755` 全部四个文件**，不信任检出后的工作区模式：
  实测 jniLibs 在工作区是 `-rwx------`（700）而 git 索引记的是 `100755`，
  直接打包会让产物随检出方式漂移。
- 带**布局自检**：解出条目列表与预期 4 项逐项比对，多一个少一个都失败。
  （这个自检自己出过一次假阴性：`tar -tzf | sed 's#^\./##'` 会把开头的
  `./` 条目留成**空行**，而空行不匹配 `grep -v '/$'`。已补 `sed '/^$/d'`。）
- 带**魔数自检**：产物头 4 字节必须是 `28 b5 2f fd`。
- `dsh` 层委托 `build-dsh-layer.sh`，不重复实现。
- `SKIP_DSH=1` 只建 android-side（本地快速验证用）。
- 环境变量：`DSHFUSION_RUNTIME_ASSETS`（默认输出目录）、`ANDROID_SIDE_SRC`、
  `DSH_VERSION`。

实测（gzip 时代，**这批数据对应的产物是坏的**，保留作记录）：
`android-side.tar.gz` 125930 B、`dsh/0.2.0-rc.2.tar.gz` 124380096 B。

#### 为什么必须是 zstd —— 以及一条「想当然」是怎么害人的

我一度在这里写下过：「`BundleManager.extractTarGz` 按**魔数**分派格式
（zstd / bzip2 / xz / 纯 tar，gzip 是 else 兜底分支），从不看扩展名。
所以 `.tar.gz` 是一等受支持格式，不是『退而求其次』。」

**这段话本身没有错，但它答错了问题。** 应用侧确实按魔数嗅探 ——
可层不是直接交给应用侧的，它得先过 **AGP 的资源打包**，而
**AGP 会解压 `.gz` 资源并去掉 `.gz` 扩展名**：

```
runtime/android-side.tar.gz  (125930 B, gzip)
        ↓ 过一遍 AGP
assets/runtime/android-side.tar  (337920 B, 裸 tar)
```

而应用侧的查找条件（`OnlineRuntimeImportManager.kt:769`）是

```kotlin
names.firstOrNull { it.startsWith("android-side.tar.") }
```

`"android-side.tar"` **不以 `"android-side.tar."` 开头**（少那个点）。
层进了 APK，应用永远看不见 —— **等于没建。**

所以结论不是「gzip 压缩率差一点」，而是 **`.tar.gz` 根本不是能过 AGP 的容器**。
脚本因此**失败关闭**：没有 zstd 就退 2 并解释原因，绝不静默降级。
一个必然被剥坏的产物，比一个明确的报错危险得多。

上游发布包用的就是 `.tar.zst`。这一点**以产物为准、不以脚本默认值为准**：
真机上装的官方 DSHBox 里是 `assets/runtime/android-side.tar.zst`（113245 B）、
`assets/dsh/0.2.0-rc.2.tar.zst`（85893395 B）和 `libzstd-jni-1.5.7-15.so`；
而上游 `tools/pack_runtime.sh` 的默认值却是 gzip，注释还说 zstd-jni 解析不到 ——
**那段注释是过时的**，DSHFusion 自己也把 `libs/zstd-jni-1.5.7-15-classes.jar`
接进了 `app` 与 `sandbox-manager`。

顺带一提，zstd 还更小：113245 B vs gzip 的 125930 B。

退出码：0 成功；1 构建或自检失败；2 用法或环境错误（**缺 zstd 走这条**）。

### `make-brand-assets.py`

生成自有品牌图形 8 个（应用图标 1 个密度、助手标识 5 个密度、启动标记 2 个密度）。
DSHBox 的 `TRADEMARK.md` 禁止复用其名称与品牌图形，**也禁止使用其设计构造方法**，
所以这些图必须自绘。用纯 Python（`zlib`，不依赖 Pillow）渲染 SDF。

```bash
tools/make-brand-assets.py            # 生成
tools/make-brand-assets.py --check    # 重新渲染并逐字节比对，CI 用这个
```

`--check` 会在临时目录里重渲染再比对，不落盘到仓库 —— 这样 CI 能验证
「提交的图确实由脚本生成」，而不会因为 CI 自己改了文件而变脏。

退出码：0 一致；1 不一致或生成失败。

## 验证类

### `verify-phase0.sh`

阶段验证套件。对每个工具跑**成功路径与失败路径**，记录真实命令、输出与退出码。

```bash
bash tools/verify-phase0.sh
bash tools/verify-phase0.sh 2>&1 | tee verification.log
```

**退出码**：`0` 全部通过；`1` 有失败项。

覆盖 21 组检查。项数随环境伸缩：依赖齐全、有审计副本与上游镜像时 **113 项**；
干净克隆里 **60 项**。其中 **3 项**依赖可选依赖
（PyYAML、actionlint、shellcheck），其余依赖上游审计副本或镜像存在：

| 组 | 内容 | 条件 |
|---|---|---|
| 1 | 契约检查的成功路径 + 篡改许可原件 / 非法偏离类型的失败路径 | 无条件 |
| 2 | 引用检查的成功路径 + 断链 / 不存在路径的失败路径 | 无条件 |
| 3 | 同步工具的参数校验失败路径 + 幂等成功路径 | 无条件 |
| 3b | `--record` 的四项校验：提交对象、上游来源、祖先关系、审核记录 | 无条件（隔离夹具） |
| 4 | 偏离重放的阶段 0 行为 | 无条件 |
| 4b | 偏离重放的成功路径 + 7 种失败场景（夹具） | 无条件（隔离夹具） |
| 4c | 锚点预检：真实上游 1 项 + 夹具 6 项 | 真实上游项依赖审计副本；夹具项无条件 |
| 4d | 阶段 1 导入器：分支保护、上游校验、完整导入、导入后自洽 | 整段依赖审计副本 |
| 4e | 继承 lint 基线的两种失败模式（新增告警 / 基线过期） | 无条件 |
| 4f | 导入保真度：逐 blob 对照上游，差异必须已登记 | 需审计副本 |
| 5 | 静态检查：shell 语法、Python 编译、JSON 解析、许可哈希、隐私审计 | 无条件 |
| 5b | 工作流验证：PyYAML 解析 + actionlint + shellcheck | 需对应工具 |
| 6 | 上游仓库工作树仍干净（只读审计承诺） | 需审计副本 |
| 7 | `.sh` 索引模式对照继承基线（双向），并验证检查本身会失败 | 非 git 检出时 SKIP |

> **第 7 组为什么存在**：本套件通过 `run_tool()` 用解释器显式调用脚本，
> 因此**永远测不到可执行位**。CI 的 `contracts.yml` 却会查 `-x`，
> 于是出现「本地全绿、CI 失败」的盲区 —— 2026-10-13 首次推送到 GitHub 时
> 就撞上了（`tools/make-anchor-fixture.sh` 索引模式为 `100644`）。
> 该组读 `git ls-files -s` 而不是工作区权限，因为索引里的模式才是别人 clone 后拿到的东西。

**条件项一律报告 SKIP，不报告 PASS。** 缺 PyYAML 或 actionlint 不代表工作流验证通过，
只代表这次没验证。脚本会打印「通过 / 失败 / 跳过 / 合计」四项计数，
并在有跳过项时提示 **"本次运行并未验证全部 N 项"**（N 按本次实际项数算），避免把跳过误读成通过。

因此结果有多种，都是正确的：依赖齐全且有审计副本时 **80 通过 / 0 失败 / 0 跳过**；
无审计副本、无 PyYAML 时约 **53 通过 / 0 失败 / 7 跳过**。

**3b 与 4b 为什么在夹具里跑**：`--record` 是唯一会改 `upstreams.json` 的动作，
它的失败路径（提交不存在、来源不对、关系回退、缺审核记录）在真实仓库上无法安全制造——
真实镜像的 HEAD 就是锁定提交，没有"更靠前的提交"可用。偏离重放的失败路径同样会污染主干。
两个夹具脚本（`make-record-fixture.sh`、`make-divergence-fixture.sh`）在临时目录里
造出这些情形，测试跑完即删，不碰真实仓库。

> 失败路径是重点：一个只验证"通过"的套件无法证明检查器真的会拦下问题。

### `make-record-fixture.sh`

为 `--record` 的四项校验构造隔离测试夹具。

```bash
tools/make-record-fixture.sh "$(mktemp -d)"
```

产出 `mirrors/DSHBox.git`（假上游：main 有推进、side 是旁支、orphan 无共同祖先、
rewound 在 main 上但非锁定提交后代）与 `repo/`（假 DSHFusion 仓库）。

为什么需要它：`--record` 的失败路径在真实仓库上**无法安全制造** ——
真实镜像的 HEAD 就是锁定提交，没有"更靠前的提交"可用。

### `make-divergence-fixture.sh`

为偏离重放构造「上游已导入」的测试夹具，按场景制造偏差。

```bash
tools/make-divergence-fixture.sh "$(mktemp -d)" <场景>
```

场景：`normal`（全部可施加）、`drift`（上游改了 `applicationId` 写法）、
`asset-missing`（`assets.srcDirs` 不存在）、`strings-moved`（`app_name` 条目消失）、
`brand-intact`（品牌图形仍是上游原样）、`brand-missing`（品牌图形文件缺失）、
`unknown-div`（混入未实现施加逻辑的偏离）、`applied-nonempty`（阶段 0 下 `applied` 非空）。

两个夹具脚本都只在临时目录里工作，测试跑完即删，不碰真实仓库。
> 4b 的 `strings-moved` 场景就是靠这套夹具发现的真实漏洞 —— 上游把 `app_name`
> 改名后，脚本原先会因为"找不到旧字符串"而报成功。
>
> `brand-intact` / `brand-missing` 同理，是 2026-10-13 阶段 1 前置审计发现的第二处假性通过：
> `DIV-0002` 原先只改 `app_name`、完全不看 8 个受 `TRADEMARK.md` §4 保护的品牌图形，
> 于是会报告「已生效」而把上游的品牌资产原样留在包里。
> 现在按 `divergences.json` 登记的上游 SHA-256 **正向核验**这些图形 ——
> 注意这里不能靠"旧值是否还在"判断，因为脚本本来就不改二进制文件。
