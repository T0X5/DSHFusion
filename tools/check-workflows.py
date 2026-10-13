#!/usr/bin/env python3
"""工作流 YAML 验证。

用真实的 YAML 解析器（PyYAML）检查 `.github/workflows/*.yml`，并断言
GitHub Actions 工作流必需的结构。比"结构正则检查"可靠得多 ——
正则检查无法发现缩进错误、重复键、类型错误这类真正会让工作流静默失效的问题。

分工：
  - 本脚本负责「YAML 合法 + 结构完整」（本地与 CI 都能跑，只需 PyYAML）；
  - actionlint 负责「语义正确」（表达式、action 输入、shell 脚本），
    它是 Go 二进制，在 CI 里单独安装（见 contracts.yml）。

为什么需要两者：actionlint 不校验"这个文件到底能不能被解析成工作流"，
PyYAML 不校验"这个表达式引用的 context 是否存在"。

用法：
    python3 tools/check-workflows.py
    python3 tools/check-workflows.py --verbose

退出码：0 通过；1 有问题（逐条打印）；2 环境缺少 PyYAML。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"

REQUIRED_TOP_KEYS = ("name", "on", "jobs")

failures: list[str] = []


def top_key(doc: dict, name: str):
    """取顶层键。

    YAML 1.1 把 `on` 解析成布尔 True（`on`/`yes`/`true` 都是真值），
    所以按名字取不到时要回退到 True。这个坑会让朴素的检查器误报
    "缺少顶层键 on"。
    """
    if name in doc:
        return doc[name]
    if name == "on" and True in doc:
        return doc[True]
    return None


def check_workflow(path: Path, verbose: bool) -> None:
    import yaml

    try:
        rel = path.relative_to(ROOT)
    except ValueError:
        rel = path          # 仓库外（测试夹具）
    text = path.read_text(encoding="utf-8")

    # 1) 能被真实解析器解析
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        failures.append(f"{rel}: YAML 解析失败\n{exc}")
        return

    if not isinstance(doc, dict):
        failures.append(f"{rel}: 顶层不是映射（实际是 {type(doc).__name__}）")
        return

    # 2) 必需顶层键
    for key in REQUIRED_TOP_KEYS:
        if top_key(doc, key) is None:
            failures.append(f"{rel}: 缺少顶层键 `{key}`")

    if top_key(doc, "permissions") is None:
        failures.append(
            f"{rel}: 缺少顶层 `permissions` —— 不显式声明权限会拿到默认的宽权限")

    # 3) 触发器非空
    triggers = top_key(doc, "on")
    if not triggers:
        failures.append(f"{rel}: `on` 为空，工作流不会被触发")
    elif verbose:
        names = list(triggers.keys()) if isinstance(triggers, dict) else [str(triggers)]
        print(f"  {rel}: 触发 {names}")

    # 4) 每个 job 的结构
    jobs = top_key(doc, "jobs")
    if not isinstance(jobs, dict) or not jobs:
        failures.append(f"{rel}: `jobs` 为空或不是映射")
        return

    for job_id, job in jobs.items():
        prefix = f"{rel}: job `{job_id}`"
        if not isinstance(job, dict):
            failures.append(f"{prefix}: 不是映射")
            continue
        if "runs-on" not in job:
            failures.append(f"{prefix}: 缺少 `runs-on`")
        steps = job.get("steps")
        if not isinstance(steps, list) or not steps:
            failures.append(f"{prefix}: `steps` 为空或不是列表")
            continue
        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                failures.append(f"{prefix}: step {index} 不是映射")
                continue
            if "uses" not in step and "run" not in step:
                failures.append(f"{prefix}: step {index} 既无 `uses` 也无 `run`")
            if "uses" in step and "run" in step:
                failures.append(f"{prefix}: step {index} 同时有 `uses` 与 `run`")
            name = step.get("name") or step.get("uses") or f"step {index}"
            if verbose:
                print(f"    · {name}")

    # 5) 制表符会让 YAML 解析器直接报错，但给出更明确的信息
    if "\t" in text:
        failures.append(f"{rel}: 含制表符（YAML 不允许用 tab 缩进）")

    # 6) 表达式里的 `${{ }}` 必须成对
    opens = text.count("${{")
    closes = text.count("}}")
    if opens != closes:
        failures.append(f"{rel}: `${{{{` 与 `}}}}` 数量不匹配（{opens} vs {closes}）")


def main() -> int:
    parser = argparse.ArgumentParser(description="工作流 YAML 验证")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--dir", default=None,
                        help="工作流目录（默认 <仓库>/.github/workflows；测试用）")
    args = parser.parse_args()

    try:
        import yaml  # noqa: F401
    except ImportError:
        print(
            "ERROR: 需要 PyYAML。安装方式：\n"
            "    python3 -m pip install PyYAML\n"
            "（若被 PEP 668 拦下，在隔离容器里可加 --break-system-packages）",
            file=sys.stderr,
        )
        return 2

    global WORKFLOWS
    if args.dir:
        WORKFLOWS = Path(args.dir).resolve()

    files = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
    if not files:
        print(f"ERROR: 在 {WORKFLOWS} 下找不到任何工作流文件", file=sys.stderr)
        return 1

    if args.verbose:
        print(f"检查 {len(files)} 个工作流文件")

    for path in files:
        check_workflow(path, args.verbose)

    if failures:
        print(f"\n工作流验证失败（{len(failures)} 项）：\n", file=sys.stderr)
        for message in failures:
            print(f"  ✗ {message}", file=sys.stderr)
        print("", file=sys.stderr)
        return 1

    print(f"工作流验证通过：{len(files)} 个文件可被 YAML 解析器解析，结构完整。")
    print("（语义检查由 actionlint 负责，见 .github/workflows/contracts.yml）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
