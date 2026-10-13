#!/usr/bin/env python3
"""make-apk-identity-fixture.py — 造一个合成 APK，用来测 check-apk-identity.py。

## 为什么需要

`check-apk-identity.py` 的价值全在它的断言上。如果只测「文件不存在时退出 2」，
那测的是 argparse，不是检查逻辑。要测断言就必须有一个 APK，而真实的 CI 产物
不在仓库里（也不该在）。

所以这里**手写一个最小但格式合法**的 APK：二进制 AndroidManifest.xml（AXML）
用 `zipfile` 打包，`resources.arsc` 可选。

## 为什么手写而不是用 aapt2

本机没有 Android SDK，CI 里也不该为了造夹具去装一整套。AXML 的格式是稳定的，
手写一份反而让「检查器读的到底是什么字节」变得完全明确。

## 用法

    tools/make-apk-identity-fixture.py <输出 apk> [场景]

场景：
    good          applicationId=com.dshfusion.app、versionCode=1、versionName=0.1.0
                  → 期望 check-apk-identity.py 退出 0
    upstream-id   applicationId=com.dshbox.app → 期望退出 1
    upstream-ver  versionCode=8、versionName=1.4.0 → 期望退出 1
    wrong-ns      application 类不在 com.dshbox.app 下 → 期望退出 1
    wrong-label   应用名字面量不是 DSHFusion → 期望退出 1
    no-manifest   包里没有 AndroidManifest.xml → 期望退出 2

退出码：0 生成成功；2 用法错误。
"""

import struct
import sys
import zipfile
from pathlib import Path

# ---- AXML 常量 ----
RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_END_NAMESPACE_TYPE = 0x0101

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10

# AOSP 固定的系统属性资源 ID
ATTR_LABEL = 0x01010001
ATTR_NAME = 0x01010003
ATTR_VERSION_CODE = 0x0101021B
ATTR_VERSION_NAME = 0x0101021C

NO_INDEX = 0xFFFFFFFF


def build_string_pool(strings: list[str]) -> bytes:
    """构造一个 UTF-16 的 ResStringPool（不做样式、不做 UTF-8 压缩）。"""
    offsets = []
    blob = bytearray()
    for s in strings:
        offsets.append(len(blob))
        encoded = s.encode("utf-16-le")
        blob += struct.pack("<H", len(s))
        blob += encoded
        blob += b"\x00\x00"  # 结尾 NUL，格式要求
    while len(blob) % 4:
        blob += b"\x00"

    header_size = 28
    offsets_size = 4 * len(strings)
    strings_start = header_size + offsets_size
    total = strings_start + len(blob)

    out = bytearray()
    out += struct.pack("<HHI", RES_STRING_POOL_TYPE, header_size, total)
    out += struct.pack("<IIIII", len(strings), 0, 0, strings_start, 0)
    for o in offsets:
        out += struct.pack("<I", o)
    out += blob
    return bytes(out)


def build_resource_map(res_ids: list[int]) -> bytes:
    """ResXMLTree_resourceMap：第 i 项对应字符串池第 i 条的名字资源 ID。"""
    header_size = 8
    total = header_size + 4 * len(res_ids)
    out = bytearray()
    out += struct.pack("<HHI", RES_XML_RESOURCE_MAP_TYPE, header_size, total)
    for rid in res_ids:
        out += struct.pack("<I", rid)
    return bytes(out)


def build_start_element(
    name_idx: int,
    attrs: list[tuple[int, int, int, int, int]],
    ns_idx: int = NO_INDEX,
) -> bytes:
    """构造一个 ResXMLTree 开始元素。

    attrs 每项是 (attr_name_string_idx, attr_name_res_id, raw_string_idx,
                  value_type, value_data)。
    """
    header_size = 16  # ResXMLTree_node
    attr_start = 20   # ResXMLTree_attrExt 的大小
    attr_size = 20    # 单个 ResXMLTree_attribute
    attr_count = len(attrs)
    ext_size = attr_start + attr_size * attr_count
    total = header_size + ext_size

    out = bytearray()
    out += struct.pack("<HHI", RES_XML_START_ELEMENT_TYPE, header_size, total)
    out += struct.pack("<II", 0xFFFFFFFF, 0xFFFFFFFF)  # lineNumber, comment
    # ResXMLTree_attrExt
    out += struct.pack("<iI", ns_idx if ns_idx != NO_INDEX else -1, name_idx)
    out += struct.pack("<HHHHHH", attr_start, attr_size, attr_count, 0, 0, 0)
    for a_name, a_resid, a_raw, v_type, v_data in attrs:
        # ns 与 name 是有符号的 ResStringPool_ref；rawValue 是无符号的，
        # NO_INDEX(0xFFFFFFFF) 只有按 "I" 才能装下。
        out += struct.pack("<iiI", -1, a_name, a_raw)  # ns, name, rawValue
        out += struct.pack("<HBBI", 8, 0, v_type, v_data)  # Res_value
    del a_resid
    return bytes(out)


def build_end_element(name_idx: int, ns_idx: int = NO_INDEX) -> bytes:
    header_size = 16
    total = header_size + 8
    out = bytearray()
    out += struct.pack("<HHI", RES_XML_END_ELEMENT_TYPE, header_size, total)
    out += struct.pack("<II", 0xFFFFFFFF, 0xFFFFFFFF)
    out += struct.pack("<iI", ns_idx if ns_idx != NO_INDEX else -1, name_idx)
    return bytes(out)


def build_axml(scenario: str) -> bytes:
    """拼出一份最小的 AndroidManifest.xml。"""
    if scenario == "upstream-id":
        package = "com.dshbox.app"
    else:
        package = "com.dshfusion.app"

    if scenario == "upstream-ver":
        version_code, version_name = 8, "1.4.0"
    else:
        version_code, version_name = 1, "0.1.0"

    if scenario == "wrong-ns":
        app_class = "com.dshfusion.app.DshApp"
    else:
        app_class = "com.dshbox.app.DshApp"

    if scenario == "wrong-label":
        label = "DSHBox"
    else:
        label = "DSHFusion"

    # 字符串池。索引顺序是刻意安排的：资源映射按字符串索引对齐。
    strings = [
        "android",            # 0  命名空间 URI（这里不真正使用）
        "manifest",           # 1
        "package",            # 2
        "versionCode",        # 3
        "versionName",        # 4
        "application",        # 5
        "name",               # 6
        "label",              # 7
        package,              # 8
        version_name,         # 9
        app_class,            # 10
        label,                # 11
    ]
    S = {s: i for i, s in enumerate(strings)}

    # 资源映射：第 i 项对应字符串池第 i 条。
    res_map = [0] * len(strings)
    res_map[S["versionCode"]] = ATTR_VERSION_CODE
    res_map[S["versionName"]] = ATTR_VERSION_NAME
    res_map[S["name"]] = ATTR_NAME
    res_map[S["label"]] = ATTR_LABEL

    chunks = bytearray()
    chunks += build_start_element(
        S["manifest"],
        [
            # (attr name idx, res id, raw string idx, value type, value data)
            (S["versionCode"], ATTR_VERSION_CODE, NO_INDEX, TYPE_INT_DEC, version_code),
            (S["versionName"], ATTR_VERSION_NAME, S[version_name], TYPE_STRING, S[version_name]),
            (S["package"], 0, S[package], TYPE_STRING, S[package]),
        ],
    )
    chunks += build_start_element(
        S["application"],
        [
            (S["name"], ATTR_NAME, S[app_class], TYPE_STRING, S[app_class]),
            # 应用名用字面量字符串：走「不解资源表」那条分支。
            (S["label"], ATTR_LABEL, S[label], TYPE_STRING, S[label]),
        ],
    )
    chunks += build_end_element(S["application"])
    chunks += build_end_element(S["manifest"])

    body = build_string_pool(strings) + build_resource_map(res_map) + bytes(chunks)

    header_size = 8
    out = bytearray()
    out += struct.pack("<HHI", RES_XML_TYPE, header_size, header_size + len(body))
    out += body
    return bytes(out)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    out_path = Path(sys.argv[1])
    scenario = sys.argv[2] if len(sys.argv) > 2 else "good"

    known = {"good", "upstream-id", "upstream-ver", "wrong-ns", "wrong-label", "no-manifest"}
    if scenario not in known:
        print(f"未知场景 {scenario!r}；可用：{sorted(known)}", file=sys.stderr)
        return 2

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        if scenario != "no-manifest":
            z.writestr("AndroidManifest.xml", build_axml(scenario))
        # 塞一个无关文件，让 zip 结构更像真 APK（且 no-manifest 场景下非空）
        z.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 64)

    print(f"已生成 {out_path}（场景 {scenario}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
