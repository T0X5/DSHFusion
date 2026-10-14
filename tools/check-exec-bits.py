#!/usr/bin/env python3
"""核对脚本可执行位（读 git 索引，不读工作区权限）。

为什么需要单独一个检查
----------------------
验证套件通过解释器显式调用脚本（`python3 tools/x.py`、`bash tools/y.sh`），
所以**永远测不到可执行位**。而 CI 会检查 `-x`。于是会出现
「本地全绿、CI 失败」——2026-10-13 首次推送就撞上了这个盲区
（`tools/make-anchor-fixture.sh` 索引模式是 100644）。

为什么读索引而不是工作区
------------------------
索引里记录的模式才是别人 clone 之后拿到的模式。工作区权限可以被本地
`chmod` 掩盖，索引不会。

为什么是双向对照而不是「全部必须可执行」
----------------------------------------
阶段 1 起 `tools/` 里有上游原样导入的脚本。上游 DSHBox 自己的索引里就有
13 个 `.sh` 是 `100644`、16 个是 `100755` —— 这不是导入丢了权限，可用
`git -C <上游> ls-files -s '*.sh'` 复核。

于是判定规则是：
  - 不在继承基线里的 `.sh`  → 必须 `100755`（拦住本项目自己的疏漏）
  - 在继承基线里的 `.sh`    → 必须 `100644`（拦住基线失真，
                              也拦住有人悄悄改动继承状态却不更新基线）

单方向检查做不到第二点：只查「非可执行就报错」的话，一旦有人把基线里的
文件 chmod 成可执行，检查反而放行，而真实状态已经和上游不一致了。

这个逻辑原先内联在 tools/verify-phase0.sh 里，CI 另有一份更旧的实现。
两份实现漂移的后果就是套件修好了、CI 仍红。抽成工具后两边共用同一份。

用法：
    python3 tools/check-exec-bits.py
    python3 tools/check-exec-bits.py --list

退出码：
  0  全部符合
  1  有不符项
  2  环境错误（不在 git 仓库里）
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "governance" / "inherited-lint-baseline.json"


def main() -> int:
    ap = argparse.ArgumentParser(description="核对脚本可执行位（对照继承基线）")
    ap.add_argument("--list", action="store_true", help="列出全部 .sh 及其模式")
    args = ap.parse_args()

    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "-s"],
            capture_output=True, text=True, check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError) as exc:
        print(f"无法读取 git 索引：{exc}")
        return 2

    inherited: set[str] = set()
    if BASELINE.exists():
        data = json.loads(BASELINE.read_text(encoding="utf-8"))
        inherited = set(data.get("exec_bit", {}).get("inherited_non_executable", []))

    bad: list[tuple[str, str, str]] = []
    listed: list[tuple[str, str, bool]] = []
    for line in out.splitlines():
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        if not path.endswith(".sh"):
            continue
        want = "100644" if path in inherited else "100755"
        listed.append((path, mode, path in inherited))
        if mode != want:
            bad.append((path, mode, want))

    if args.list:
        for path, mode, is_inh in sorted(listed):
            tag = "继承" if is_inh else "本项目"
            print(f"  {mode}  [{tag}] {path}")
        print()

    print(f"检查了 {len(listed)} 个 .sh（其中 {len(inherited)} 个继承自上游、"
          f"索引模式应为 100644）")

    if bad:
        print(f"\n✗ {len(bad)} 个脚本的可执行位与预期不符：")
        for path, got, want in bad:
            reason = ("继承自上游，应与上游一致保持 100644"
                      if path in inherited else "本项目脚本，必须可执行")
            print(f"    {path}")
            print(f"      索引模式 {got}，应为 {want} —— {reason}")
        print("\n  修正：git update-index --chmod=+x <path>（或 -x）")
        print("  注意 chmod +x 只改工作区，不改索引。")
        return 1

    print("✓ 全部符合。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
