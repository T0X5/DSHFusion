#!/usr/bin/env python3
"""应用标识一致性检查 —— 不编译也能拦住「namespace 改错」这一类错误。

## 为什么需要这个工具

2026-10-13，`phase-1/import-dshbox-base` 的首次真实 CI 运行报出 36 个
`Unresolved reference 'R'`。原因是我把 `app/build.gradle.kts` 的
`namespace` 从 `com.dshbox.app` 改成了 `com.dshfusion.app`，
而代码里有 36 处 `import com.dshbox.app.R`。

同一个原因还会造成一个 CI **看不到**的后果：`AndroidManifest.xml` 里有
3 处相对类名（`.DshApp`、`.MainActivity`、`.service.SandboxService`），
它们相对 `namespace` 解析。namespace 一改，编译期不一定报错，
但装上手机点开就 `ClassNotFoundException`。

本环境没有 JDK / Android SDK / Gradle，跑不了真正的构建。但这一类错误
**完全可以从静态文本判定**，没必要等 CI。这个工具就是那道本地防线。

## 检查内容

1. `namespace` 与源码中 `import <pkg>.R` 的包名一致
2. `namespace` 与源码中 `import <pkg>.BuildConfig` 的包名一致
3. `AndroidManifest.xml` 的相对类名能在源码里找到对应文件
4. `applicationId` 与 `namespace` 各自的值被显式报告（便于人工确认阶段范围）

## 退出码

0 = 一致；1 = 不一致；2 = 环境/配置错误（例如找不到 build.gradle.kts）
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 阶段 1 的约定：applicationId 独立，namespace 保持上游值。
# 见 governance/divergences.json 的 DIV-0001。
UPSTREAM_NAMESPACE = "com.dshbox.app"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def gradle_value(text: str, key: str) -> str | None:
    m = re.search(rf'{key}\s*=?\s*"([^"]+)"', text)
    return m.group(1) if m else None


def source_files(app_dir: Path) -> list[Path]:
    out: list[Path] = []
    for sub in ("main/java", "main/kotlin"):
        d = app_dir / "src" / sub
        if d.is_dir():
            out.extend(sorted(d.rglob("*.kt")))
            out.extend(sorted(d.rglob("*.java")))
    return out


def manifest_relative_names(manifest: Path) -> list[str]:
    """取出 manifest 里的相对类名，例如 `.MainActivity` -> `MainActivity`。

    只认以 `.` 开头的写法：完全限定名不受 namespace 影响，不需要检查。
    """
    text = read(manifest)
    names: list[str] = []
    for m in re.finditer(r'android:name="(\.?[\w.$]+)"', text):
        value = m.group(1)
        if value.startswith("."):
            names.append(value.lstrip("."))
    return names


def main() -> int:
    ap = argparse.ArgumentParser(description="应用标识一致性检查")
    ap.add_argument("--quiet", action="store_true", help="只输出结论")
    args = ap.parse_args()

    gradle = ROOT / "app" / "build.gradle.kts"
    if not gradle.is_file():
        print(f"找不到 {gradle} —— 阶段 1 基线尚未导入？", file=sys.stderr)
        return 2

    text = read(gradle)
    namespace = gradle_value(text, "namespace")
    app_id = gradle_value(text, "applicationId")

    if namespace is None:
        print("app/build.gradle.kts 未声明 namespace —— 上游写法可能已变", file=sys.stderr)
        return 2

    problems: list[str] = []

    if not args.quiet:
        print(f"namespace      = {namespace}")
        print(f"applicationId  = {app_id}")
        print()

    # ---- 检查 1/2：R 与 BuildConfig 的 import 包名 ----
    files = source_files(ROOT / "app")
    if not files:
        print("app/src/main 下没有 Kotlin/Java 源码 —— 基线尚未导入？", file=sys.stderr)
        return 2

    r_imports: dict[str, list[str]] = {}
    bc_imports: dict[str, list[str]] = {}
    for f in files:
        rel = f.relative_to(ROOT).as_posix()
        for line in read(f).splitlines():
            m = re.match(r"\s*import\s+([\w.]+)\.(R|BuildConfig)\s*$", line)
            if not m:
                continue
            pkg, kind = m.group(1), m.group(2)
            (r_imports if kind == "R" else bc_imports).setdefault(pkg, []).append(rel)

    if not args.quiet:
        print(f"源码文件数: {len(files)}")
        for pkg, users in sorted(r_imports.items()):
            mark = "OK " if pkg == namespace else "✗  "
            print(f"  {mark} import {pkg}.R            （{len(users)} 个文件）")
        for pkg, users in sorted(bc_imports.items()):
            mark = "OK " if pkg == namespace else "✗  "
            print(f"  {mark} import {pkg}.BuildConfig  （{len(users)} 个文件）")
        print()

    for pkg, users in r_imports.items():
        if pkg != namespace:
            problems.append(
                f"{len(users)} 个文件 `import {pkg}.R`，但 namespace 是 {namespace} —— "
                f"这些文件会报 Unresolved reference 'R'。"
                f"例如 {users[0]}"
            )
    for pkg, users in bc_imports.items():
        if pkg != namespace:
            problems.append(
                f"{len(users)} 个文件 `import {pkg}.BuildConfig`，但 namespace 是 {namespace}。"
                f"例如 {users[0]}"
            )

    # ---- 检查 3：manifest 相对类名可解析 ----
    manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    if manifest.is_file():
        pkg_dirs = {
            (ROOT / "app" / "src" / "main" / "java").as_posix(),
            (ROOT / "app" / "src" / "main" / "kotlin").as_posix(),
        }
        rel_names = manifest_relative_names(manifest)
        if not args.quiet:
            print(f"AndroidManifest 相对类名: {len(rel_names)} 处")
        for name in rel_names:
            # `.service.SandboxService` -> namespace + 相对路径
            candidates = [
                Path(d) / (namespace.replace(".", "/")) / (name.replace(".", "/") + ext)
                for d in pkg_dirs
                for ext in (".kt", ".java")
            ]
            found = any(c.is_file() for c in candidates)
            if not args.quiet:
                print(f"  {'OK ' if found else '✗  '} .{name}  →  {namespace}.{name}")
            if not found:
                problems.append(
                    f"AndroidManifest 的 `.`{name} 解析为 {namespace}.{name}，"
                    f"但在 app/src/main 下找不到对应源码文件。"
                    f"若 namespace 被改过，这就是装上就崩的那一类错误。"
                )
        if not args.quiet:
            print()

    # ---- 检查 4：阶段范围提醒（不是失败，只是显式报告）----
    if namespace != UPSTREAM_NAMESPACE and not args.quiet:
        print(
            f"注意：namespace 已偏离上游值 {UPSTREAM_NAMESPACE}。\n"
            "      阶段 1 刻意只改 applicationId、不改 namespace —— 改它等于\n"
            "      启动被推迟到阶段 4 的全量重命名。见 DIV-0001。"
        )

    if problems:
        print("发现不一致：")
        for i, p in enumerate(problems, 1):
            print(f"  {i}. {p}")
        return 1

    print("应用标识一致：namespace 与 R / BuildConfig 引用、manifest 相对类名全部对得上。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
