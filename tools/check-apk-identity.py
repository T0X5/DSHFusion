#!/usr/bin/env python3
"""check-apk-identity.py — 核验**构建产物**的应用身份，而不是构建配置。

## 为什么需要它

`tools/check-android-identity.py` 读的是 `app/build.gradle.kts` 与源码，
能拦住 namespace / R 引用 / manifest 相对类名这一类错误。但它管不到一件事：
**最后打进 APK 的到底是什么**。AGP 会把 `versionName`、`applicationId`、
`android:label` 都编译进二进制，中间经过资源合并、资源 ID 分配、
字符串池去重等步骤 —— 配置写对了不等于产物对了。

这个区别不是理论上的。阶段 1 就真的踩过：
  - `versionCode` / `versionName` 在 gradle 里是 8 / 1.4.0（上游的值），
    配置和产物一致，但两者都错 —— 只有真去看 APK 才发现设置页会显示「v1.4.0」。
  - `android:label` 是资源引用 `0x7f0e001f`，光看 manifest 二进制根本不知道
    它解析成什么。必须解 `resources.arsc` 才能确认 DIV-0002 真的生效了。

所以这个脚本做的是「对产物做断言」：解出 APK 的 AndroidManifest 与
resources.arsc，检查应用标识、版本号、各语言下的应用名。

## 用法

    tools/check-apk-identity.py <apk 路径>
    tools/check-apk-identity.py <apk 路径> --expect-label DSHFusion

不给 `--expect-label` 时用默认值 `DSHFusion`。

## 依赖

只用标准库（`zipfile` + `struct`）。不依赖 aapt2 / apktool —— CI 的
Android SDK 里有 aapt2，但多一个外部依赖就多一处「本地跑不了」。
二进制格式的解析在这里是可控的，而且解错会立刻表现为断言失败，不会静默通过。

## 退出码

0 = 全部通过；1 = 有断言失败；2 = 用法错误或 APK 无法解析。
"""

import argparse
import struct
import sys
import zipfile
from pathlib import Path

# Android 二进制 XML / 资源表的块类型
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201

TYPE_STRING = 0x03
TYPE_REFERENCE = 0x01

# manifest 里我们关心的属性的资源 ID（AOSP 固定值）
ATTR_ANDROID_NAME = 0x01010003
ATTR_ANDROID_VERSION_CODE = 0x0101021B
ATTR_ANDROID_VERSION_NAME = 0x0101021C
ATTR_ANDROID_LABEL = 0x01010001

NO_ENTRY = 0xFFFFFFFF


class ParseError(Exception):
    """APK 内容无法按预期解析。这类失败必须显式暴露，不能当成"检查通过"。"""


# --------------------------------------------------------------------------
# 字符串池
# --------------------------------------------------------------------------

def parse_string_pool(buf: bytes, off: int) -> tuple[list[str], int]:
    """解析 ResStringPool，返回 (字符串列表, 池结束偏移)。"""
    typ, header_size, size = struct.unpack_from("<HHI", buf, off)
    if typ != RES_STRING_POOL_TYPE:
        raise ParseError(f"偏移 {off} 处不是字符串池（块类型 0x{typ:04x}）")

    count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<IIIII", buf, off + 8
    )
    is_utf8 = bool(flags & (1 << 8))
    offsets = struct.unpack_from(f"<{count}I", buf, off + header_size)
    base = off + strings_start

    out: list[str] = []
    for o in offsets:
        p = base + o
        if is_utf8:
            # UTF-8 变体有两个长度字段，各自可能是 1 或 2 字节。
            n1 = buf[p]
            p += 2 if (n1 & 0x80) else 1
            n2 = buf[p]
            if n2 & 0x80:
                n2 = ((n2 & 0x7F) << 8) | buf[p + 1]
                p += 2
            else:
                p += 1
            out.append(buf[p:p + n2].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            p += 2
            out.append(buf[p:p + n * 2].decode("utf-16-le", "replace"))
    return out, off + size


# --------------------------------------------------------------------------
# 二进制 AndroidManifest.xml
# --------------------------------------------------------------------------

def parse_manifest(axml: bytes) -> dict:
    """从二进制 XML 里取出 package 与 application 上的关键属性。

    只做我们需要的部分：找到 <manifest> 与 <application> 的开始标签，
    读出带资源 ID 的属性值。不构建完整 DOM —— 那会把这里变成半个 apktool。
    """
    typ, header_size, _size = struct.unpack_from("<HHI", axml, 0)
    if typ != RES_XML_TYPE:
        raise ParseError(f"不是二进制 XML（块类型 0x{typ:04x}）")

    strings: list[str] = []
    resource_ids: list[int] = []
    result: dict = {"attrs": {}, "app_attrs": {}}
    off = header_size

    while off < len(axml):
        ctyp, chsize, csize = struct.unpack_from("<HHI", axml, off)
        if csize == 0:
            break

        if ctyp == RES_STRING_POOL_TYPE:
            strings, _ = parse_string_pool(axml, off)

        elif ctyp == RES_XML_RESOURCE_MAP_TYPE:
            # 把属性名映射到系统属性资源 ID；后面靠它区分 android:name 等。
            n = (csize - chsize) // 4
            resource_ids = list(struct.unpack_from(f"<{n}I", axml, off + chsize))

        elif ctyp == RES_XML_START_ELEMENT_TYPE:
            # ResXMLTree_node(16) + ResXMLTree_attrExt。
            # 注意用**本块的** chsize，不是根块的 header_size ——
            # 根块头是 8 字节，元素块头是 16 字节，差这 8 字节会让
            # 属性偏移整体错位，读出来的 name_idx 恒为 0xFFFFFFFF。
            node = off + chsize
            ns_idx, name_idx = struct.unpack_from("<iI", axml, node)
            del ns_idx
            tag = strings[name_idx] if name_idx < len(strings) else ""
            attr_start, attr_size, attr_count = struct.unpack_from(
                "<HHH", axml, node + 8
            )
            if tag in ("manifest", "application") and attr_count:
                base = node + attr_start
                for i in range(attr_count):
                    a_off = base + i * attr_size
                    a_ns, a_name, a_raw, a_typed = struct.unpack_from(
                        "<iiiI", axml, a_off
                    )
                    del a_ns
                    # 属性名可能靠资源 ID 标识（系统属性），也可能靠字符串索引。
                    if a_name < len(resource_ids) and resource_ids[a_name]:
                        attr_id = resource_ids[a_name]
                    else:
                        attr_id = 0
                    # ResXMLTree_attribute 布局：
                    #   ns(4) name(4) rawValue(4) typedValue(8)
                    # typedValue 是 Res_value：size(2) res0(1) dataType(1) data(4)
                    # 因此 typedValue 从 a_off+12 开始，dataType 在 +15、data 在 +16。
                    # 曾错写成 a_off+20（那已是下一个属性），结果读出的 dataType/data
                    # 全是垃圾值 —— 而垃圾值不会报错，只会静默给出错误的版本号。
                    v_off = a_off + 12
                    v_type, v_data = struct.unpack_from("<BI", axml, v_off + 3)

                    def value_text() -> str:
                        if a_raw >= 0 and a_raw < len(strings):
                            return strings[a_raw]
                        if v_type == TYPE_STRING and v_data < len(strings):
                            return strings[v_data]
                        return f"<{v_type:#04x}:{v_data:#010x}>"

                    key = None
                    if attr_id == ATTR_ANDROID_NAME:
                        key = "name"
                    elif attr_id == ATTR_ANDROID_VERSION_CODE:
                        key = "versionCode"
                    elif attr_id == ATTR_ANDROID_VERSION_NAME:
                        key = "versionName"
                    elif attr_id == ATTR_ANDROID_LABEL:
                        key = "label"
                    elif attr_id == 0 and a_name < len(strings):
                        # 非系统属性：直接按名字记下来，便于核对
                        key = strings[a_name]

                    if key:
                        entry = value_text()
                        if v_type == TYPE_REFERENCE and key in ("label", "icon", "theme"):
                            entry = f"<ref {v_data:#010x}>"
                        result["app_attrs" if tag == "application" else "attrs"][key] = entry
                        if key == "label" and v_type == TYPE_REFERENCE:
                            result["label_ref"] = v_data

                    # 顺带把 versionCode 的数值原样留下，避免字符串化后再解析
                    if attr_id == ATTR_ANDROID_VERSION_CODE:
                        result["versionCode_raw"] = v_data
        off += csize

    if not result["attrs"].get("package"):
        raise ParseError("没能在 manifest 里找到 package 属性")
    return result


# --------------------------------------------------------------------------
# resources.arsc
# --------------------------------------------------------------------------

def parse_config_lang(cfg: bytes) -> str:
    """把 ResTable_config 的语言/地区解成可读标签。"""
    if len(cfg) < 12:
        return "default"
    lang, region = cfg[8:10], cfg[10:12]
    if lang[0] & 0x80:
        # 三字母语言用 packed 编码，这里不展开 —— 只要不是默认值就够了。
        return "packed"
    if not (lang[0] or lang[1]):
        return "default"
    tag = chr(lang[0]) + chr(lang[1])
    if region[0] or region[1]:
        tag += "-" + chr(region[0]) + chr(region[1])
    return tag


def resolve_string_resource(arsc: bytes, res_id: int) -> list[tuple[str, str]]:
    """把字符串资源 ID 解成 [(语言标签, 字符串), ...]，覆盖所有语言配置。"""
    typ, header_size, _size = struct.unpack_from("<HHI", arsc, 0)
    if typ != RES_TABLE_TYPE:
        raise ParseError(f"不是资源表（块类型 0x{typ:04x}）")

    package_id = (res_id >> 24) & 0xFF
    type_id = (res_id >> 16) & 0xFF
    entry_id = res_id & 0xFFFF

    global_strings, _ = parse_string_pool(arsc, header_size)

    out: list[tuple[str, str]] = []
    off = header_size
    while off < len(arsc):
        ctyp, chsize, csize = struct.unpack_from("<HHI", arsc, off)
        if csize == 0:
            break
        if ctyp == RES_STRING_POOL_TYPE:
            _, off = parse_string_pool(arsc, off)
            continue
        if ctyp != RES_TABLE_PACKAGE_TYPE:
            off += csize
            continue

        this_pkg = struct.unpack_from("<I", arsc, off + 8)[0]
        if this_pkg != package_id:
            off += csize
            continue

        type_strings_off, _, key_strings_off, _ = struct.unpack_from(
            "<IIII", arsc, off + 268
        )
        key_strings, _ = parse_string_pool(arsc, off + key_strings_off)

        p, end = off + chsize, off + csize
        while p < end:
            t, hs, sz = struct.unpack_from("<HHI", arsc, p)
            if t == RES_TABLE_TYPE_TYPE and arsc[p + 8] == type_id:
                entry_count, entries_start = struct.unpack_from("<II", arsc, p + 12)
                cfg_size = struct.unpack_from("<I", arsc, p + 20)[0]
                cfg = arsc[p + 20:p + 20 + cfg_size]
                if entry_id < entry_count:
                    offsets = struct.unpack_from(f"<{entry_count}I", arsc, p + hs)
                    if offsets[entry_id] != NO_ENTRY:
                        ep = p + entries_start + offsets[entry_id]
                        key_idx = struct.unpack_from("<HHI", arsc, ep)[2]
                        v_type, v_data = struct.unpack_from("<BI", arsc, ep + 8 + 3)
                        if v_type == TYPE_STRING and v_data < len(global_strings):
                            key = (
                                key_strings[key_idx]
                                if key_idx < len(key_strings)
                                else f"#{key_idx}"
                            )
                            out.append((parse_config_lang(cfg), global_strings[v_data]))
                        else:
                            out.append(
                                (parse_config_lang(cfg), f"<type {v_type:#04x}>")
                            )
            p += sz
        off += csize
    return out


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(
        description="核验构建产物 APK 的应用身份（applicationId / 版本号 / 应用名）"
    )
    ap.add_argument("apk", help="APK 文件路径")
    ap.add_argument(
        "--expect-label",
        default="DSHFusion",
        help="期望的 android:label 解析结果（默认 DSHFusion）",
    )
    args = ap.parse_args()

    path = Path(args.apk)
    if not path.is_file():
        print(f"错误：找不到 APK：{path}", file=sys.stderr)
        return 2

    problems: list[str] = []
    notes: list[str] = []

    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            if "AndroidManifest.xml" not in names:
                print("错误：APK 里没有 AndroidManifest.xml", file=sys.stderr)
                return 2
            axml = z.read("AndroidManifest.xml")
            arsc = z.read("resources.arsc") if "resources.arsc" in names else None
    except (zipfile.BadZipFile, OSError) as exc:
        print(f"错误：无法读取 APK：{exc}", file=sys.stderr)
        return 2

    try:
        manifest = parse_manifest(axml)
    except ParseError as exc:
        print(f"错误：解析 AndroidManifest.xml 失败：{exc}", file=sys.stderr)
        return 2

    attrs = manifest["attrs"]
    app = manifest["app_attrs"]

    pkg = attrs.get("package", "")
    version_code = manifest.get("versionCode_raw", attrs.get("versionCode", "?"))
    version_name = attrs.get("versionName", "?")

    print(f"APK            : {path}")
    print(f"applicationId  : {pkg}")
    print(f"versionCode    : {version_code}")
    print(f"versionName    : {version_name}")
    print(f"application    : {app.get('name', '?')}")
    print()

    # --- 断言 1：应用标识必须是 DSHFusion 自己的 ---
    if pkg == "com.dshbox.app":
        problems.append(
            "applicationId 仍是上游的 com.dshbox.app —— 会与 DSHBox 冲突，无法并存安装"
        )
    elif pkg != "com.dshfusion.app":
        problems.append(f"applicationId 是 {pkg}，期望 com.dshfusion.app")

    # --- 断言 2：版本号必须是独立版本线 ---
    if str(version_name) == "1.4.0" and str(version_code) == "8":
        problems.append(
            "版本号仍是上游的 8 / 1.4.0 —— 设置页会显示「v1.4.0」，"
            "让人以为装的是 DSHBox v1.4.0"
        )

    # --- 断言 3：application 类必须是上游 namespace 下的 ---
    # namespace 在阶段 1 刻意保持 com.dshbox.app；若这里变成 com.dshfusion.app，
    # 说明有人动了 namespace，而那会让 R 引用与 manifest 相对类名失效。
    app_name = str(app.get("name", ""))
    if app_name and not app_name.startswith("com.dshbox.app"):
        problems.append(
            f"application 类是 {app_name}，期望在 com.dshbox.app 下"
            "（阶段 1 刻意不改 namespace）"
        )

    # --- 断言 4：应用名（解 resources.arsc 才能知道）---
    label_ref = manifest.get("label_ref")
    if label_ref is None:
        notes.append(
            f"android:label 是字面量 {app.get('label', '?')!r}，无需解资源表"
        )
        if args.expect_label and str(app.get("label")) != args.expect_label:
            problems.append(
                f"应用名是 {app.get('label')!r}，期望 {args.expect_label!r}"
            )
    elif arsc is None:
        problems.append("android:label 是资源引用，但 APK 里没有 resources.arsc")
    else:
        resolved = resolve_string_resource(arsc, label_ref)
        if not resolved:
            problems.append(
                f"android:label 指向 {label_ref:#010x}，但在资源表里解不出值"
            )
        else:
            print(f"应用名（android:label = {label_ref:#010x}）在各语言下的解析结果：")
            for lang, value in sorted(resolved):
                mark = "✅" if value == args.expect_label else "❌"
                print(f"  {lang:10} = {value!r}  {mark}")
            print()
            wrong = [(lang, v) for lang, v in resolved if v != args.expect_label]
            if wrong:
                detail = "、".join(f"{lang}={v!r}" for lang, v in wrong)
                problems.append(
                    f"{len(wrong)}/{len(resolved)} 个语言配置的应用名不是 "
                    f"{args.expect_label!r}：{detail}"
                )

    # --- 顺带核对：APK 里是否含运行环境层 ---
    layers = [
        n for n in names
        if n.endswith((".tar.zst", ".tar.gz")) or n.startswith("assets/dsh/")
    ]
    if layers:
        notes.append(f"含 {len(layers)} 个运行环境层文件：{layers[:5]}")
    else:
        notes.append(
            "不含任何运行环境层（无 .tar.zst / assets/dsh/）—— "
            "这个 APK 能安装，但起不了 DSH"
        )

    for n in notes:
        print(f"备注：{n}")

    if problems:
        print()
        for p in problems:
            print(f"✗ {p}", file=sys.stderr)
        print(f"\n{len(problems)} 项身份断言失败。", file=sys.stderr)
        return 1

    print("\nAPK 身份核验通过 ✔")
    return 0


if __name__ == "__main__":
    sys.exit(main())
