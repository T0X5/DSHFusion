#!/usr/bin/env python3
"""Kotlin 侧的两类「本机抓得住、CI 要十几分钟」的错。

## 为什么需要这个工具

本机**没有 kotlinc**（见 `docs/INCIDENTS.md` 第 21 条），所以 `.kt` 的编译结论
只能由 CI 给出 —— 而一轮 CI 十几分钟。2.6 第 ④ 段第一次真跑就红了两处：

```
BackupScreen.kt:97:51 Unresolved reference 'vectorResource'.
BackupScreen.kt:191:29 Unresolved reference 'it'.
```

两处都是**机械性的**，都不需要类型信息就能判：一个是「用了扩展函数却没 import」，
一个是「把 lambda 参数从 `it` 改成了具名，却漏改了一处引用」。
它们与本项目第 20 条的悬空限定名（`java.nio.file.new`）是同一族：
**替换发生了，但替换的结果不合法** —— 而那种错在肉眼扫一遍时最难看见。

所以在 CI 之前先在本机把这两类扫掉。**它不替代编译**，它只是把
「必然编译不过」的形态提前拦下来。

## 检查一：扩展函数用了却没 import

只看一张**白名单**里的名字（Compose 与 Android 的资源扩展）。不猜、不推断 ——
名单外的名字一律不管，因为「同名本地函数」会让推断变成误报。
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

# 名字 → 提供它的完整 import 路径。
#
# ⚠️ 只放**扩展函数**（或必须 import 的顶层函数）。放进来一个新名字之前先问：
# 「同一个包里的同名本地函数会不会让它误报？」会的话就不要放。
KNOWN_EXTENSIONS = {
    "vectorResource": "androidx.compose.ui.res.vectorResource",
    "stringResource": "androidx.compose.ui.res.stringResource",
    "pluralStringResource": "androidx.compose.ui.res.pluralStringResource",
    "colorResource": "androidx.compose.ui.res.colorResource",
    "dimensionResource": "androidx.compose.ui.res.dimensionResource",
    "painterResource": "androidx.compose.ui.res.painterResource",
    "booleanResource": "androidx.compose.ui.res.booleanResource",
    "integerResource": "androidx.compose.ui.res.integerResource",
}

# ⚠️ **不要往上面那张表里加 `getValue` / `setValue`。** 我第一次就加了，然后
# 8 个文件误报 —— 因为 `Map.getValue(key)` 是 **stdlib 的扩展**（`kotlin.collections`，
# 默认导入），不是 Compose 的。判据是「同一个名字在别的包里有没有一个
# **自动导入**的同名扩展」；有就别放进来。
#
# 这段警告的第一版就写在上面那张表的正上方，而我**在同一轮里违反了它** ——
# 「写在注释里的规矩」拦不住任何人，包括写下它的人。所以这里连名字一起列出来，
# 让下一个想加它的人在搜索时就会看到。


def strip_comments(text: str) -> str:
    """去掉块注释与行注释。

    这一步**必须**有：本项目的检查器已经被自己的散文命中过**两次**
    （第 35 条与它的补记）—— 检查的输入空间里不该有关于被查对象的说明文字。
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def check_extension_imports(path: pathlib.Path, code: str) -> list[str]:
    problems = []
    imports = set(re.findall(r"^import\s+([\w.]+)", code, re.M))
    for name, module in KNOWN_EXTENSIONS.items():
        # 只认「调用」形态：`name(` 前面可以是 `.`（`ImageVector.vectorResource(`）
        # 或直接跟在非标识符处。**`name` 出现在注释或字符串里不算** —— 已剥注释；
        # 字符串里的形态这里不细究，因为白名单里的名字出现在字符串里极少见。
        used = re.search(rf"(?<![\w]){re.escape(name)}\s*\(", code)
        if not used:
            continue
        if module in imports:
            continue
        # 同文件里自己定义了一个同名函数？那就不是「缺 import」。
        if re.search(rf"\bfun\s+{re.escape(name)}\b", code):
            continue
        problems.append(
            f"{path}: 用了 `{name}(` 但没有 `import {module}` —— "
            "Kotlin 不会自动导入包级扩展函数，这会编译不过"
        )
    return problems


LET_START = re.compile(r"\??\.let\s*\{\s*(\w+)\s*->")


def check_stale_it(path: pathlib.Path, code: str) -> list[str]:
    """具名 lambda 里残留的 `it.`。

    形态：把 `x?.let { ... it.foo ... }` 改成 `x?.let { name -> ... }` 时漏改了引用。
    Kotlin 对具名参数的 lambda **不再提供 `it`**，所以那一定编译不过。

    实现按花括号配平切出每个 `let { name ->` 的区间，再看区间里有没有裸 `it.`。
    """
    problems = []
    lines = code.split("\n")
    i = 0
    while i < len(lines):
        match = LET_START.search(lines[i])
        if not match:
            i += 1
            continue
        name = match.group(1)
        depth = lines[i].count("{") - lines[i].count("}")
        j = i
        while j + 1 < len(lines) and depth > 0:
            j += 1
            depth += lines[j].count("{") - lines[j].count("}")
            if j == i:
                continue
            # 裸 `it.` —— 前面不是标识符字符或点（排除 `bit.`、`x.it.`）。
            if re.search(r"(?<![\w.])it\.", lines[j]):
                # 嵌套的另一个 `let {` 会重新引入 `it`；那种情况下这行是合法的。
                # 保守起见只在「区间里没有任何嵌套 let」时才报。
                nested = any(
                    LET_START.search(lines[k]) for k in range(i + 1, j + 1)
                )
                if not nested:
                    problems.append(
                        f"{path}:{j + 1}: 这个 `.let {{ {name} -> ... }}` 里还有裸的 `it.` —— "
                        "改成具名参数后 Kotlin 不再提供 `it`，这会编译不过"
                    )
        i = j + 1 if j > i else i + 1
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description="Kotlin 侧的两类机械错误（不替代编译）")
    ap.add_argument("root", nargs="?", default=".", help="仓库根目录")
    ap.add_argument("--dir", help="只扫这个目录（测试用）")
    args = ap.parse_args()

    root = pathlib.Path(args.root).resolve()
    target = pathlib.Path(args.dir).resolve() if args.dir else root

    files = sorted(target.rglob("*.kt"))
    files = [f for f in files if "/build/" not in str(f) and "/.git/" not in str(f)]
    if not files:
        print("错误：一个 .kt 都没找到 —— 扫一个空集合是假通过。", file=sys.stderr)
        return 2

    problems: list[str] = []
    for path in files:
        code = strip_comments(path.read_text(encoding="utf-8"))
        rel = path.relative_to(root) if str(path).startswith(str(root)) else path
        problems += check_extension_imports(rel, code)
        problems += check_stale_it(rel, code)

    if problems:
        print("Kotlin 机械错误检查失败：")
        for p in problems:
            print(f"  ✗ {p}")
        print(
            "\n这两类都不需要类型信息就能判，而且**本机没有 kotlinc** —— "
            "让它们活到 CI 就是十几分钟一轮（见 docs/INCIDENTS.md 第 20 条的同族）。"
        )
        return 1

    print(
        f"Kotlin 机械错误检查通过：{len(files)} 个 .kt 文件，"
        f"无「用了扩展函数却没 import」、无「具名 lambda 里残留 it.」。"
    )
    print("（只查这两类形态；编译结论仍然只能由 CI 给出。）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
