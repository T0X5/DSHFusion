#!/usr/bin/env python3
"""工作流里**内联 Python 的语法检查**。

## 为什么需要这个

`tools/check-workflows.py` 只证明「YAML 解析得开、结构完整」，而
`actionlint` 也不看内联脚本的**宿主语言**语法。于是 `.github/workflows/*.yml`
里那一段几十行的 `python3 - <<'PY'` **从来没有被任何本地检查编译过** ——
它只在 CI 上跑，而 CI 跑到那一步要十几分钟。

代价是实测过的：2.5 的计数步骤里，我把新条目**追加在一个包含 `):` 终止符的锚点之后**，
于是元组提前闭合，`python3` 报 `SyntaxError: unmatched ')'`。
本地所有检查全绿，CI 在**第 13 步**才红 —— 而红的位置离真正的原因（我的编辑）
隔了十几分钟和一层 YAML。

⇒ 这一节做的事：把每个 `run:` 里的 heredoc 抽出来，**用 `compile()` 编译一遍**。
它不执行任何东西，也不联网。

⚠️ 它证明的只有「语法对」。**语义**（那些断言是否成立）不在这里，也不可能在这里 ——
所以下面每处都如实标注。
"""

from __future__ import annotations

import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

# `python3 - <<'PY'` … 换行 `PY`（定界符在行首，允许前后空白）
HEREDOC = re.compile(
    r"python3?\s+-\s+<<\s*'(?P<tag>[A-Za-z_][A-Za-z0-9_]*)'\s*\n(?P<body>.*?)\n(?P=tag)\s*$",
    re.S | re.M,
)


def walk_steps(node, path):
    """递归找出所有带 `run` 的 step，产出 (描述, 脚本)。"""
    if isinstance(node, dict):
        if isinstance(node.get("run"), str):
            yield path, node["run"]
        for key, value in node.items():
            yield from walk_steps(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from walk_steps(value, f"{path}[{index}]")


def main() -> int:
    problems: list[str] = []
    checked = 0
    files = sorted(WORKFLOWS.glob("*.yml"))
    if not files:
        print("找不到任何工作流文件", file=sys.stderr)
        return 1

    for workflow in files:
        try:
            document = yaml.safe_load(workflow.read_text(encoding="utf-8"))
        except yaml.YAMLError as error:  # 交给 check-workflows.py 报，这里不重复
            print(f"跳过 {workflow.name}（YAML 解析失败：{error}）")
            continue
        for where, script in walk_steps(document, workflow.name):
            for index, match in enumerate(HEREDOC.finditer(script)):
                checked += 1
                body = match.group("body")
                label = f"{where} 里第 {index + 1} 段内联 Python（{len(body.splitlines())} 行）"
                try:
                    compile(body, f"<{label}>", "exec")
                except SyntaxError as error:
                    problems.append(
                        f"{label} 第 {error.lineno} 行语法错误：{error.msg}\n"
                        f"      {error.text.strip() if error.text else ''}"
                    )

    if problems:
        print("工作流里的内联 Python 有语法错误：", file=sys.stderr)
        for item in problems:
            print(f"  ✗ {item}", file=sys.stderr)
        print(
            "\n这些脚本只在 CI 上跑，而跑到它们要十几分钟 —— "
            "所以它们必须在这里就被编译一遍（见 docs/INCIDENTS.md 第 36 条）。",
            file=sys.stderr,
        )
        return 1

    print(
        f"工作流内联 Python 语法检查通过：{len(files)} 个工作流，"
        f"{checked} 段内联脚本可被 Python 编译器解析。"
    )
    print("（只证明语法对；那些断言成不成立要真跑，不在这里。）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
