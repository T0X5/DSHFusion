#!/usr/bin/env python3
"""生成 DSHFusion 自有品牌图形（DIV-0002）。

为什么必须有这个脚本
--------------------
上游 DSHBox 的 TRADEMARK.md 保留其应用图标与品牌图形的全部权利，
并明确禁止使用其**设计构造方法**开发近似标识。因此这 8 个文件必须自绘，
不能沿用、不能改色、不能"照着画一个差不多的"。

把生成过程写成脚本而不是丢 8 个二进制文件进仓库，是为了让"这图形是原创的"
这件事**可验证**：任何人可以重跑本脚本，得到逐字节相同的文件，
也可以读代码确认构图元素与上游无关。

设计
----
「融合」的直白表达：两个等大圆环交叠，交叠处（透镜区）用强调色填实。
- 两个环    = 两个上游（DSHBox 为主干，DSHA 为来源）
- 透镜核心  = 融合产生的新事物（DSHFusion 自己）
构图只用到圆，不涉及任何来自上游的图形元素或构造手法。

三种产物
--------
brand_icon.png        带圆角矩形底 + 渐变，用于应用图标
ic_pilot_brand.png    透明底，仅标记，用于界面内品牌位
ic_splash_mark.png    透明底，标记 + 外圈，用于启动页

实现
----
无 Pillow 依赖：用有符号距离场（SDF）解析求交，配 smoothstep 做抗锯齿，
PNG 由标准库 zlib 手写编码。全部确定性，无随机数。

用法
----
    python3 tools/make-brand-assets.py            # 写入 app/src/main/res
    python3 tools/make-brand-assets.py --check    # 只校验现有文件是否是本脚本产物
    python3 tools/make-brand-assets.py --out DIR  # 写到别处

退出码：0 成功；1 校验不一致或用法错误。
"""

from __future__ import annotations

import argparse
import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------- 调色板
# 深靛蓝 → 紫罗兰渐变。与上游配色无关，是本项目自己的选择。
BG_TOP = (0x15, 0x1E, 0x52)
BG_BOTTOM = (0x7C, 0x3A, 0xED)
RING = (0xFF, 0xFF, 0xFF)
CORE = (0x34, 0xD3, 0x99)      # 强调色：翡翠绿，在白色环与深底上都清晰

# 透明底标记（ic_pilot_brand / ic_splash_mark）的环色渐变。
#
# 为什么不用白色：LaunchScreen.kt 按明暗主题自适应，而标记是**原样绘制不加 tint**
# （LaunchScreen.kt:100-104）。应用同时提供 LightColors 与 DarkColors，
# 浅色主题的 windowBackground 是白色（values/themes.xml）。
# 纯白标记画在白底上等于消失 —— 上游 ic_splash_mark 主体正是纯白
# （实测最常见像素 255,255,255,255），这是它自带的一个缺陷。
# 既然图形本来就要自绘，就没有必要继承它。
#
# 这组渐变跨越明暗两端：深端在白底上可见，浅端在深底上可见，
# 于是同一张素材在两种主题下都能读出形状。
RING_GRAD_TOP = (0x4C, 0x1D, 0x95)     # 深紫罗兰：白底上清晰
RING_GRAD_BOTTOM = (0xC4, 0xB5, 0xFD)  # 浅紫罗兰：深底上清晰

# 构图几何（归一化坐标，中心为原点）
RING_R = 0.52          # 环半径
RING_W = 0.145         # 环粗
A_CX, A_CY = -0.195, -0.125   # 环 A 圆心
B_CX, B_CY = 0.195, 0.125     # 环 B 圆心

# 标记在最远方向上的半径：圆心偏移 + 环半径 + 半个环粗。
# 渲染时用它把构图坐标整体缩放进画布，取值必须覆盖所有可见像素，
# 否则图形会被裁掉。改动上面的几何常量后必须重算这一项。
MARK_EXTENT = math.hypot(A_CX, A_CY) + RING_R + RING_W * 0.5


# ---------------------------------------------------------------- PNG 编码
def write_png(path: Path, width: int, height: int, pixels: bytes) -> None:
    """pixels 为 RGBA 字节流，长度 width*height*4。"""

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)  # filter type 0
        raw += pixels[y * stride : (y + 1) * stride]

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


# ---------------------------------------------------------------- 渲染
def smoothstep(edge0: float, edge1: float, x: float) -> float:
    if edge0 == edge1:
        return 0.0 if x < edge0 else 1.0
    t = (x - edge0) / (edge1 - edge0)
    t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
    return t * t * (3.0 - 2.0 * t)


def coverage(d: float, aa: float) -> float:
    """SDF 距离 d < 0 表示在形状内部；返回 0..1 覆盖率。

    d 与 aa 必须同处一个坐标系（本脚本统一用画布单位）。
    """
    return 1.0 - smoothstep(-aa, aa, d)


def render_mark(
    size: int,
    *,
    background: str | None,
    outer_ring: bool = False,
    margin: float = 0.06,
) -> bytes:
    """渲染标记。background: None=透明底, 'gradient'=圆角矩形渐变底。

    坐标系约定（这里出过两个 bug，务必看清）：
      画布单位 —— nx, ny ∈ [-1, 1]，1 单位 = size/2 像素
      构图单位 —— mx, my，标记的设计空间，范围约 ±MARK_EXTENT

    mx = nx / k，即 k 是「1 构图单位 = k 个画布单位」的放大系数。
    所有 SDF 距离在判定前都乘以 k 换算成画布单位，
    抗锯齿宽度 aa 也只用画布单位算 —— 否则两者尺度不同，
    环会被切成一堆断线（这正是第一版的错误）。
    """
    # k：让标记的 ±MARK_EXTENT 恰好落进留白后的可视范围
    k = (1.0 - margin * 2.0) / MARK_EXTENT
    # 抗锯齿过渡宽度：约 1.6 像素，换算到画布单位
    aa = 1.6 * (2.0 / size)

    out = bytearray(size * size * 4)
    inv = 1.0 / size

    for py in range(size):
        # 像素中心，映射到 [-1, 1]
        ny = (py + 0.5) * inv * 2.0 - 1.0
        for px in range(size):
            nx = (px + 0.5) * inv * 2.0 - 1.0

            # 构图坐标（仅用于算距离，判定前一律乘 k）
            mx = nx / k
            my = ny / k

            r = g = b = 0.0
            a = 0.0

            # --- 底层：圆角矩形渐变（仅 brand_icon），直接在画布单位里算
            if background == "gradient":
                rad = 0.46
                qx = abs(nx) - (1.0 - rad)
                qy = abs(ny) - (1.0 - rad)
                dx = max(qx, 0.0)
                dy = max(qy, 0.0)
                d = math.hypot(dx, dy) + min(max(qx, qy), 0.0) - rad
                cov = coverage(d, aa)
                if cov > 0.0:
                    t = (ny + 1.0) * 0.5  # 纵向渐变
                    br = BG_TOP[0] + (BG_BOTTOM[0] - BG_TOP[0]) * t
                    bg = BG_TOP[1] + (BG_BOTTOM[1] - BG_TOP[1]) * t
                    bb = BG_TOP[2] + (BG_BOTTOM[2] - BG_TOP[2]) * t
                    r, g, b, a = br, bg, bb, cov

            # --- 透镜核心：两圆交集（距离换算到画布单位再判定）
            da = (math.hypot(mx - A_CX, my - A_CY) - RING_R) * k
            db = (math.hypot(mx - B_CX, my - B_CY) - RING_R) * k
            cov_lens = coverage(max(da, db), aa)
            if cov_lens > 0.0:
                cr, cg, cb = CORE
                r = r * (1 - cov_lens) + cr * cov_lens
                g = g * (1 - cov_lens) + cg * cov_lens
                b = b * (1 - cov_lens) + cb * cov_lens
                a = a + (1.0 - a) * cov_lens

            # --- 两个环（画在透镜之上）
            # 底色自带品牌渐变时用白环；透明底用跨明暗的紫罗兰渐变环。
            half_w = RING_W * 0.5 * k
            d_ring = min(abs(da) - half_w, abs(db) - half_w)
            cov_ring = coverage(d_ring, aa)
            if cov_ring > 0.0:
                if background == "gradient":
                    rr, rg, rb = RING
                else:
                    t = (ny + 1.0) * 0.5
                    rr = RING_GRAD_TOP[0] + (RING_GRAD_BOTTOM[0] - RING_GRAD_TOP[0]) * t
                    rg = RING_GRAD_TOP[1] + (RING_GRAD_BOTTOM[1] - RING_GRAD_TOP[1]) * t
                    rb = RING_GRAD_TOP[2] + (RING_GRAD_BOTTOM[2] - RING_GRAD_TOP[2]) * t
                r = r * (1 - cov_ring) + rr * cov_ring
                g = g * (1 - cov_ring) + rg * cov_ring
                b = b * (1 - cov_ring) + rb * cov_ring
                a = a + (1.0 - a) * cov_ring

            # --- 外圈（仅 splash），同样在画布单位里算
            if outer_ring:
                d_out = abs(math.hypot(nx, ny) - 0.93) - 0.022
                cov_out = coverage(d_out, aa)
                if cov_out > 0.0:
                    t = (ny + 1.0) * 0.5
                    orr = RING_GRAD_TOP[0] + (RING_GRAD_BOTTOM[0] - RING_GRAD_TOP[0]) * t
                    og = RING_GRAD_TOP[1] + (RING_GRAD_BOTTOM[1] - RING_GRAD_TOP[1]) * t
                    ob = RING_GRAD_TOP[2] + (RING_GRAD_BOTTOM[2] - RING_GRAD_TOP[2]) * t
                    al = cov_out * 0.45  # 半透明，不抢主体
                    r = r * (1 - al) + orr * al
                    g = g * (1 - al) + og * al
                    b = b * (1 - al) + ob * al
                    a = a + (1.0 - a) * al

            i = (py * size + px) * 4
            out[i] = int(r + 0.5)
            out[i + 1] = int(g + 0.5)
            out[i + 2] = int(b + 0.5)
            out[i + 3] = int(a * 255.0 + 0.5)

    return bytes(out)


# ---------------------------------------------------------------- 产物清单
# 密度 → 尺寸，与上游既有文件名一一对应（文件路径必须沿用，否则 Android 找不到）
TARGETS: list[tuple[str, int, dict]] = [
    ("app/src/main/res/mipmap-xxxhdpi/brand_icon.png", 1024,
     dict(background="gradient", margin=0.0)),
    ("app/src/main/res/drawable-mdpi/ic_pilot_brand.png", 52,
     dict(background=None)),
    ("app/src/main/res/drawable-hdpi/ic_pilot_brand.png", 78,
     dict(background=None)),
    ("app/src/main/res/drawable-xhdpi/ic_pilot_brand.png", 104,
     dict(background=None)),
    ("app/src/main/res/drawable-xxhdpi/ic_pilot_brand.png", 156,
     dict(background=None)),
    ("app/src/main/res/drawable-xxxhdpi/ic_pilot_brand.png", 208,
     dict(background=None)),
    ("app/src/main/res/drawable-xxhdpi/ic_splash_mark.png", 480,
     dict(background=None, outer_ring=True, margin=0.10)),
    ("app/src/main/res/drawable-xxxhdpi/ic_splash_mark.png", 640,
     dict(background=None, outer_ring=True, margin=0.10)),
]


def generate(out_dir: Path) -> list[Path]:
    written = []
    for rel, size, kw in TARGETS:
        p = out_dir / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        px = render_mark(size, **kw)
        write_png(p, size, size, px)
        written.append(p)
        print(f"  已生成 {size:>4}x{size:<4} {p.relative_to(out_dir)}")
    return written


def check(out_dir: Path) -> int:
    """重算一遍，与磁盘上现有文件逐字节比较。"""
    bad = []
    for rel, size, kw in TARGETS:
        p = out_dir / rel
        if not p.exists():
            print(f"  [缺失] {rel}")
            bad.append(rel)
            continue
        expect = render_mark(size, **kw)
        # 重新编码后逐字节比较
        tmp = Path("/tmp/_brand_check.png")
        write_png(tmp, size, size, expect)
        got = p.read_bytes()
        want = tmp.read_bytes()
        tmp.unlink(missing_ok=True)
        if got == want:
            print(f"  [一致] {size:>4}x{size:<4} {rel}")
        else:
            print(f"  [不一致] {rel}（当前 {len(got)} B，本脚本产物 {len(want)} B）")
            bad.append(rel)
    if bad:
        print(f"\n{len(bad)} 个文件与本脚本产物不一致。")
        return 1
    print("\n全部 8 个文件都是本脚本的产物。")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 DSHFusion 自有品牌图形")
    ap.add_argument("--out", default=str(ROOT), help="输出根目录（默认仓库根）")
    ap.add_argument("--check", action="store_true",
                    help="只校验现有文件是否为本脚本产物，不写文件")
    args = ap.parse_args()

    out_dir = Path(args.out).resolve()
    if args.check:
        print("校验品牌图形是否为 tools/make-brand-assets.py 的产物：")
        return check(out_dir)

    print("生成 DSHFusion 自有品牌图形（构图：两环交叠 + 透镜核心）：")
    generate(out_dir)
    print("\n完成。注意：这些图形是原创的，不含任何上游品牌元素。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
