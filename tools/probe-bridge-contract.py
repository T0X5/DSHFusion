#!/usr/bin/env python3
"""为 3.3 的端点子集生成**实测契约表** —— 只探测只读端点。

为什么需要它
------------
[PHASE3.3-COMPAT-BOUNDARY.md] 的判据 ③ 写着：**兼容性依据只能是真机实测**，
不许拿读 `HttpShellService` 的源码当实现依据。而"实测"如果靠手敲 curl，
写进文档的就是手抄的 —— 本仓库为"手抄的数字"栽过不止一次。

所以契约表由这个脚本生成，**行数、字段、错误码都不是我写的**。

⚠️ 最重要的限制：只读白名单
------------------------------
**"探测"和"执行"在会改状态的端点上没有区别**（事故第 46 条：
我用 `PUT /contents/…` 探测写权限，于是真的写了）。
所以这里**只允许明确只读的端点**；其余一律拒绝并退 2，**不提供 --force**。

退出码
------
0 成功（表格已生成）  2 参数/白名单错误  3 桥不可达
"""

from __future__ import annotations

import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BRIDGE = "http://127.0.0.1:3090"
HEADERS_FILE = pathlib.Path("/root/.dsh/.bridge_headers")

# **只读白名单。** 加进来之前先问：这次调用会不会真的改变设备状态？
# 会 → 不进来。`/app/ui/screenshot` 会往应用截图目录写 PNG，所以它**不在**这里。
READ_ONLY = [
    ("/app/version", {}, "桥协议版本与 App 版本"),
    ("/app/help", {}, "端点清单正文（判断清单是否穷举）"),
    ("/app/ui/dump", {}, "当前页面结构"),
    ("/app/apps", {"limit": "5"}, "已装应用（取前 5 条）"),
    ("/app/vscreen/status", {}, "虚拟屏状态"),
]

# **永不自动探测**：这些端点的探测动作本身就是危险动作。
# 不是「以后再测」，是**不由脚本测** —— 需要人亲自在场时手工做一次。
NEVER = {
    "/exec?cmd=": "能以 shell 身份跑任意命令",
    "/app/ask": "会弹窗阻塞，等待用户回答（脚本会一直卡住）",
    "/app/ui/tap": "在用户当前页面上盲点，点中什么都不知道",
}

# **允许的单条写操作探测**（需显式 --allow-write <path>，一次只准一条）。
# 每一条都必须写明「它会做什么」与「怎么撤」。
WRITE_PROBES = {
    "/app/notify": {
        "params": {"title": "DSHFusion 契约探测", "text": "3.3 契约表：这是唯一一条写操作探测"},
        "does": "往通知栏发一条消息",
        "undo": "用户在通知栏滑掉即可；不改变任何设备状态",
    },
}

# 明确**不探测**的（写进表里，让读者知道它为什么是空的）
NOT_PROBED = [
    ("/app/ui/tap", "/app/ui/screenshot", "/app/ui/input", "/app/ui/key", "/app/ui/swipe"),
    ("/app/launch", "/app/overlay"),   # notify 已探测（写探测，见文档）；ask 在 NEVER 里
    ("/app/vscreen/create", "/app/vscreen/launch", "/app/vscreen/see", "/app/vscreen/close"),
    ("/exec?cmd=",),  # ⚠️ 逗号不能省：`(x)` 是字符串，`(x,)` 才是元组
]


def token() -> str:
    """从宿主的凭据头文件里取 X-Token。**不经过 argv，也不进 URL。**"""
    if not HEADERS_FILE.is_file():
        sys.exit(3)
    for line in HEADERS_FILE.read_text(encoding="utf-8").splitlines():
        if line.lower().startswith("x-token:"):
            value = line.split(":", 1)[1].strip()
            if value:
                return value
    sys.exit(3)


def probe(path: str, params: dict[str, str], tok: str) -> dict:
    url = BRIDGE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"X-Token": tok})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read(200_000)
            status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read(200_000)
        status = error.code
    except (urllib.error.URLError, OSError):
        sys.exit(3)
    elapsed = (time.monotonic() - started) * 1000
    text = raw.decode("utf-8", "replace")
    shape, codes = describe(text)
    return {
        "path": path,
        "status": status,
        "ms": elapsed,
        "bytes": len(raw),
        "shape": shape,
        "codes": codes,
        "sample": text,
    }


def describe(text: str) -> tuple[str, str]:
    """描述返回的**形状**与出现的错误码 —— 不复制正文措辞。"""
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return "非 JSON", "—"
    if not isinstance(parsed, dict):
        return f"JSON {type(parsed).__name__}", "—"
    keys = sorted(parsed)
    inner = parsed.get("result")
    if isinstance(inner, str):
        try:
            nested = json.loads(inner)
        except json.JSONDecodeError:
            nested = None
        if isinstance(nested, dict):
            keys = sorted(set(keys) | {"<内层> " + k for k in nested})
            if nested.get("error"):
                return "JSON{result: JSON{…}}，内层 ok=false", str(nested["error"])
    codes = [w for w in ("POLICY_BLOCKED", "EXECUTION_UNKNOWN", "NO_PERMISSION",
                         "DISABLED", "UNAUTHORIZED") if w in text]
    return "JSON{" + ", ".join(keys) + "}", ", ".join(codes) if codes else "—"


def main(argv: list[str]) -> int:
    allowed = {p for p, _, _ in READ_ONLY}
    args = argv[1:]
    write_targets = []
    if "--allow-write" in args:
        index = args.index("--allow-write")
        if index + 1 >= len(args):
            print("拒绝：--allow-write 后面必须跟一个端点路径。", file=sys.stderr)
            return 2
        write_targets = [args[index + 1]]
        args = args[:index] + args[index + 2:]

    for path in write_targets:
        if path in NEVER:
            print(f"拒绝：{path} 永不自动探测 —— {NEVER[path]}。", file=sys.stderr)
            return 2
        if path not in WRITE_PROBES:
            print(f"拒绝：{path} 不在写探测名单里（只有 {', '.join(WRITE_PROBES)}）。",
                  file=sys.stderr)
            return 2

    requested = [a for a in args if not a.startswith("-")]
    for path in requested:
        if path in NEVER:
            print(f"拒绝：{path} 永不自动探测 —— {NEVER[path]}。", file=sys.stderr)
            return 2
        if path not in allowed:
            print(f"拒绝：{path} 不在只读白名单里。"
                  "「探测」与「执行」在这个端点上没有区别（事故 46）。", file=sys.stderr)
            return 2

    targets = [(p, q, w) for p, q, w in READ_ONLY if not requested or p in requested]
    for path in write_targets:
        spec = WRITE_PROBES[path]
        targets.append((path, spec["params"], "⚠️ 写操作：" + spec["does"]))
    tok = token()
    rows = []
    for path, params, why in targets:
        result = probe(path, params, tok)
        rows.append((result, why))

    print("| 端点 | HTTP | 耗时 | 返回形状 | 错误码 | 这是测什么的 |")
    print("|---|---:|---:|---|---|---|")
    for r, why in rows:
        print(f"| `{r['path']}` | {r['status']} | {r['ms']:.0f} ms "
              f"| {r['shape']} | {r['codes']} | {why} |")
    print()
    print(f"实测 {len(rows)} 条（脚本生成，**不是手抄的**）。")
    for path in write_targets:
        print(f"\n> ⚠️ `{path}` 是**写操作**探测：{WRITE_PROBES[path]['does']}。"
              f"撤法：{WRITE_PROBES[path]['undo']}。\n"
              f"> 永不自动探测（不是「以后再测」，是**不由脚本测**）："
              + "；".join(f"`{k}`——{v}" for k, v in NEVER.items()) + "。")
    print()
    print("**未探测**（会改状态，须单独安排一次并想好怎么撤）：")
    for group in NOT_PROBED:
        print("- " + " · ".join(f"`{p}`" for p in group))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
