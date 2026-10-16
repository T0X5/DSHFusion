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
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
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
ATTR_ANDROID_AUTHORITIES = 0x01010018
# `android:permission`：`<service>` 上用它声明 BIND_JOB_SERVICE（AOSP 固定值）。
ATTR_ANDROID_PERMISSION = 0x01010006

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
            if tag == "provider" and attr_count:
                # 收集 ContentProvider 的 authority。
                #
                # 为什么单独处理：authority 是**独立于 applicationId 与 namespace
                # 的第三条身份轴**。Android 要求它全局唯一，两个已安装应用声明同一个
                # authority 时安装直接失败 -13（INSTALL_FAILED_CONFLICTING_PROVIDER）——
                # 跟 applicationId 是否不同无关。
                #
                # 阶段 1 只改了 applicationId，没有发现这个：真机上
                # `com.dshbox.app.fileprovider` 与已装的 DSHBox 撞车，安装失败。
                # 见 DIV-0011。
                base = node + attr_start
                for i in range(attr_count):
                    a_off = base + i * attr_size
                    _a_ns, a_name, a_raw, _a_typed = struct.unpack_from(
                        "<iiiI", axml, a_off
                    )
                    if not (a_name < len(resource_ids)
                            and resource_ids[a_name] == ATTR_ANDROID_AUTHORITIES):
                        continue
                    v_type, v_data = struct.unpack_from("<BI", axml, a_off + 15)
                    if 0 <= a_raw < len(strings):
                        auth = strings[a_raw]
                    elif v_type == TYPE_STRING and v_data < len(strings):
                        auth = strings[v_data]
                    else:
                        auth = f"<{v_type:#04x}:{v_data:#010x}>"
                    result.setdefault("authorities", []).append(auth)

            elif tag == "service" and attr_count:
                # 收集 `<service>` 的 `android:name` 与 `android:permission`。
                #
                # 为什么要它：`JobService` **必须在 manifest 里带
                # `android:permission="android.permission.BIND_JOB_SERVICE"`**，
                # 否则系统不会把任务派给它 —— 而失败是**静默的**：
                # `JobScheduler.schedule()` 返回 SUCCESS，之后永远不被拉起。
                # 源码里的 manifest 有它（`verify-phase0.sh` 已查），但那是**源码**；
                # 合并后的产物 manifest 才是设备上生效的那一份。
                # 与断言 9 同一条理由：**声明在位不等于产物里有。**
                base = node + attr_start
                name = permission = ""
                for i in range(attr_count):
                    a_off = base + i * attr_size
                    _a_ns, a_name, a_raw, _a_typed = struct.unpack_from("<iiiI", axml, a_off)
                    attr_id = (
                        resource_ids[a_name]
                        if a_name < len(resource_ids) and resource_ids[a_name]
                        else 0
                    )
                    if attr_id not in (ATTR_ANDROID_NAME, ATTR_ANDROID_PERMISSION):
                        continue
                    v_type, v_data = struct.unpack_from("<BI", axml, a_off + 15)
                    if 0 <= a_raw < len(strings):
                        value = strings[a_raw]
                    elif v_type == TYPE_STRING and v_data < len(strings):
                        value = strings[v_data]
                    else:
                        value = ""
                    if attr_id == ATTR_ANDROID_NAME:
                        name = value
                    else:
                        permission = value
                if name:
                    result.setdefault("services", []).append((name, permission))

            elif tag in ("manifest", "application") and attr_count:
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
    ap.add_argument(
        "--allow-missing-runtime-layers",
        action="store_true",
        help=(
            "允许 APK 不含运行环境层（默认不允许）。"
            "只在「明知这是纯编译检查、不打算装机」时用；"
            "装了这种 APK 会起不了 DSH，且在线获取运行环境也会在下载完成后失败。"
        ),
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
    auths = manifest.get("authorities", [])
    if auths:
        print(f"authority      : {', '.join(auths)}")
    print()

    # --- 断言 1：应用标识必须是 DSHFusion 自己的，不许还是上游的 ---
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
    # namespace 在阶段 1 刻意保持 com.dshfusion.app；若这里变成 com.dshfusion.app，
    # 说明有人动了 namespace，而那会让 R 引用与 manifest 相对类名失效。
    app_name = str(app.get("name", ""))
    if app_name and not app_name.startswith("com.dshfusion.app"):
        problems.append(
            f"application 类是 {app_name}，期望在 com.dshfusion.app 下"
            "（阶段 1 刻意不改 namespace）"
        )

    # --- 断言 4：每个 ContentProvider 的 authority 都必须从 applicationId 派生 ---
    #
    # 这条断言是**真机装不上之后补的**，加它的理由值得写清楚：
    #
    # applicationId 只是「安装身份」的一条轴。ContentProvider 的 authority 是
    # 另一条**独立**的轴 —— Android 要求它全局唯一，两个已安装应用声明同一个
    # authority 时，后装的那个直接失败 -13（INSTALL_FAILED_CONFLICTING_PROVIDER），
    # 跟 applicationId 是否不同**完全无关**。
    #
    # 阶段 1 把 applicationId 从 com.dshfusion.app 改成 com.dshfusion.app，
    # 三条断言全过，CI 全绿 —— 但 APK 里 FileProvider 的 authority 是写死的
    # `com.dshfusion.app.fileprovider`，与已装的 DSHBox 撞车，真机上装不上去。
    # 前面三条断言都查不到它，因为它们查的都是「值对不对」，
    # 而这条要查的是「**这个值是不是从 applicationId 派生的**」。
    authorities = manifest.get("authorities", [])
    if not authorities:
        problems.append(
            "APK 里没有任何 ContentProvider authority —— 期望至少有 FileProvider 一个；"
            "manifest 解析可能出了问题"
        )
    for auth in authorities:
        if not auth.startswith(pkg + "."):
            problems.append(
                f"authority「{auth}」不是从 applicationId 派生的（期望以 {pkg}. 开头）—— "
                "authority 全局唯一，与已安装的上游应用撞车会让安装失败 -13"
                "（INSTALL_FAILED_CONFLICTING_PROVIDER）"
            )

    # --- 断言 5：应用名（解 resources.arsc 才能知道）---
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

    # --- 断言 6：APK 必须含**真正可用的**运行环境层 ---
    #
    # 这条原先只是「备注」，于是它如实备注了「不含任何运行环境层」，
    # 然后 CI 照样全绿。检查发现了问题却没把它变成失败 —— 缺层能一路
    # 走到真机上，这是原因之一。
    #
    # 为什么缺层是硬错误，而不是一句提示：
    #   OnlineRuntimeImportManager.prepareAndroidSide()（第 458 行）在
    #   assets/runtime/android-side.tar.* 缺失时返回
    #       AppError("ONLINE_ASIDE", "cannot prepare android-side layer")
    #   而这一步在整条流程的**下载之后**才执行。
    #   所以没有这两层时，用户先等几百 MB 下载完，然后看到失败 ——
    #   「在线获取运行环境」这条本来用于补救的路，本身也走不通。
    #   2026-10-14 真机安装成功后才发现。
    #
    # ⚠️ 第一版这条断言**被侧车文件骗过去了**，必须记下来。
    # 它当时写的是 `n.startswith("assets/runtime/android-side.tar.")` ——
    # `android-side.tar.gz.sha256` 也满足这个前缀。于是 CI 全绿，
    # 而 APK 里**根本没有层本体**，只有一个 64 字节的哈希侧车。
    # 教训：断言必须钉住「应用侧会去用的那个东西」，而不是「名字长得像的东西」。
    #
    # 同时，第一版还漏掉了一个更隐蔽的失败：**AGP 会解压 `.gz` 资源并去掉
    # `.gz` 扩展名**。实测（run 38513950774 的产物）：
    #     runtime/android-side.tar.gz （125930 B, gzip）→
    #     assets/runtime/android-side.tar （337920 B, 裸 tar）
    # 而应用侧的查找条件是 `it.startsWith("android-side.tar.")`（带那个点），
    # `android-side.tar` 匹配不上 —— 层进了 APK，但应用永远看不见它。
    # 所以这里既要查名字的**形状**，也要查内容的**魔数**。
    import re as _re

    # 名字必须精确匹配应用侧的查找模式：`<层名>.tar.<单一扩展名>`。
    # 用 `[A-Za-z0-9]+` 而不是 `\w+`：扩展名里不允许再有别的点，
    # 这样 `android-side.tar.gz.sha256` 自然被排除，不需要额外写排除规则。
    aside_pat = _re.compile(r"^assets/runtime/android-side\.tar\.[A-Za-z0-9]+$")
    dsh_pat = _re.compile(r"^assets/dsh/[^/]+\.tar\.[A-Za-z0-9]+$")
    aside = sorted(n for n in names if aside_pat.match(n))
    dsh = sorted(n for n in names if dsh_pat.match(n))

    missing = []
    if not aside:
        # 分别诊断「完全没有」与「只有侧车 / 扩展名被剥掉」，
        # 因为这两种情况的修法完全不同，笼统报「缺层」会让人查错方向。
        sidecars = [n for n in names if n.startswith("assets/runtime/android-side.tar")]
        if sidecars:
            missing.append(
                "assets/runtime/android-side.tar.<ext>（目录里有 "
                + "、".join(sidecars)
                + "，但没有应用侧会查找的层本体 —— 注意 AGP 会把 `.gz` 解压并"
                "去掉扩展名，`.tar.gz` 会变成应用匹配不上的 `.tar`）"
            )
        else:
            missing.append("assets/runtime/android-side.tar.<ext>")
    if not dsh:
        sidecars = [n for n in names if n.startswith("assets/dsh/") and ".tar" in n]
        if sidecars:
            missing.append(
                "assets/dsh/<version>.tar.<ext>（目录里有 " + "、".join(sidecars) + "）"
            )
        else:
            missing.append("assets/dsh/<version>.tar.<ext>")

    # 魔数：`BundleManager.extractTarGz()` 按魔数分派容器格式，不看扩展名。
    # 名字对了但内容不是它认识的格式，解包一样会失败 —— 而且失败发生在设备上。
    # 这里复刻它的判定顺序：zstd / bzip2 / xz / plain-tar，gzip 兜底。
    def _container_kind(blob: bytes) -> str:
        if blob[:4] == b"\x28\xb5\x2f\xfd":
            return "zstd"
        if blob[:3] == b"BZh":
            return "bzip2"
        if blob[:6] == b"\xfd7zXZ\x00":
            return "xz"
        if blob[257:262] == b"ustar":
            return "plain-tar"
        if blob[:2] == b"\x1f\x8b":
            return "gzip"
        return ""

    with zipfile.ZipFile(path) as _z:
        for label, group in (("android-side", aside), ("dsh", dsh)):
            if not group:
                continue
            name = group[0]
            kind = _container_kind(_z.read(name)[:512])
            if not kind:
                problems.append(
                    f"运行环境层 {label}（{name}）不是 BundleManager 认识的容器格式 —— "
                    "魔数既不是 zstd / bzip2 / xz，也不是 tar 或 gzip。"
                    "层进得了 APK，但在设备上解不开。"
                )
            else:
                notes.append(f"运行环境层 {label}：{name}（容器 {kind}）")

    # --- 断言 7：dsh 层必须是**目标架构**的，不能是构建宿主的架构 ---
    #
    # 这条是 2026-10-14 真机失败之后补的。断言 6 的三条（层在不在、名字对不对、
    # 容器格式认不认）当时**全部通过** —— 层真的解开了、node 也真的起来了，
    # 然后 DSH 在 host preparation 阶段致命退出：
    #
    #     dsh: host preparation failed: No usable native binding found for
    #          node-addon-require-builtin-linux-arm64-gnu (auto)
    #
    # 因为层是 CI 在 x86_64 runner 上 `npm install` 出来的，npm 按**宿主**平台
    # 挑了整套 `-linux-x64` 的可选依赖。层的格式完全正确，内容是错的架构。
    #
    # 这是同一个病的第四次发作，而且每次都停在比「能不能用」更浅的一层：
    #   ① 「缺层」只记成备注，不判失败 → 缺层的 APK 一路走到真机
    #   ② 断言被 `.sha256` 侧车骗过（名字长得像）
    #   ③ AGP 解压 `.gz` 并剥掉扩展名（格式看着对，应用却找不到）
    #   ④ 层是 x64 的（内容存在、格式正确、架构错）
    # 所以这一条不看名字也不看格式，**看层里面的包**。
    #
    # 断言清单来自上游发布产物的逐包对照：官方 DSHBox v1.4.0 的
    # `0.2.0-rc.2.tar.zst`（sha256 4197ec52…）与在 x64 上建出来的层，
    # 恰好是 7 个包一一对换（arm64 ↔ x64）。这 7 个就是全部按平台分发的
    # 可选依赖，一个不多一个不少。
    if dsh:
        target_arch = "arm64"
        host_arch = "x64"
        platform_pkg_tmpl = (
            "node-addon-require-builtin-linux-{a}-gnu",
            "@deepseek-ai/node-addon-system-linux-{a}",
            "@img/sharp-linux-{a}",
            "@img/sharp-libvips-linux-{a}",
            "@koromix/koffi-linux-{a}",
            "@vscode/ripgrep-linux-{a}",
            "sherpa-onnx-linux-{a}",
        )
        pkg_dir_re = _re.compile(r"^\.?/?node_modules/(@[^/]+/[^/]+|[^/]+)$")

        def _layer_pkg_dirs(apk_path: Path, entry: str):
            """流式解开 zstd 层，返回 node_modules 下的包目录名集合。

            返回 None 表示这台机器上没有 zstd，无法核验（调用方按失败处理）。
            """
            if shutil.which("zstd") is None:
                return None
            with zipfile.ZipFile(apk_path) as z:
                blob = z.read(entry)
            with tempfile.NamedTemporaryFile(suffix=".tar.zst", delete=False) as tf:
                tf.write(blob)
                tmp = tf.name
            try:
                with open(tmp, "rb") as fin:
                    proc = subprocess.Popen(
                        ["zstd", "-dc"],
                        stdin=fin,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL,
                    )
                    found = set()
                    with tarfile.open(fileobj=proc.stdout, mode="r|") as tar:
                        for member in tar:
                            m = pkg_dir_re.match(member.name)
                            if m:
                                found.add(m.group(1))
                    proc.stdout.close()
                    proc.wait()
                    return found
            finally:
                os.unlink(tmp)

        pkg_dirs = _layer_pkg_dirs(path, dsh[0])
        if pkg_dirs is None:
            problems.append(
                "无法核验 dsh 层的目标架构：这台机器上没有 zstd 命令。"
                "层若是在 x86_64 上建的，npm 会装成 x64 的可选依赖，"
                "arm64 真机上 DSH 会启动到一半致命退出 —— 这一条不能跳过，"
                "装上 zstd 后重跑。"
            )
        else:
            absent = [
                t.format(a=target_arch)
                for t in platform_pkg_tmpl
                if t.format(a=target_arch) not in pkg_dirs
            ]
            present_wrong = [
                t.format(a=host_arch)
                for t in platform_pkg_tmpl
                if t.format(a=host_arch) in pkg_dirs
            ]
            if absent or present_wrong:
                problems.append(
                    f"dsh 层不是 {target_arch} 架构的："
                    + (f"缺少 {'、'.join(absent)}；" if absent else "")
                    + (f"混入了 {'、'.join(present_wrong)}；" if present_wrong else "")
                    + "层是在 x86_64 构建机上建的，npm 按宿主平台挑了可选依赖。"
                    "修法：build-dsh-layer.sh 的 npm install 必须带 "
                    f"--os=linux --cpu={target_arch} --libc=glibc。"
                )
            else:
                notes.append(
                    f"dsh 层平台核验：{target_arch} 的 {len(platform_pkg_tmpl)} "
                    f"个平台包齐全，且无 {host_arch} 混入"
                )

    if missing:
        hint = (
            "先跑 tools/build-runtime-assets.sh，再用 DSHFUSION_RUNTIME_ASSETS "
            "指向它的输出目录重新构建。注意该脚本要求 zstd —— gzip 产物会被 AGP "
            "解压并去掉扩展名，等于没建。"
        )
        if args.allow_missing_runtime_layers:
            notes.append(
                "缺运行环境层（已按 --allow-missing-runtime-layers 放行）："
                + "；".join(missing)
            )
        else:
            problems.append(
                "缺可用的运行环境层：" + "；".join(missing)
                + "。这种 APK 装得上，但起不了 DSH，而且「在线获取运行环境」"
                "也会在下载完成后失败（prepareAndroidSide 硬失败）。" + hint
            )

    # --- 断言 9：阶段 2 的类必须**真的在产物里** ---
    #
    # 为什么需要这一条：`settings.gradle.kts` 的 `include(":backup")` 已经足以让
    # Gradle 单独编译那个模块并跑它的测试，而 `testDebugUnitTest` 对**所有**模块
    # 逐个跑、不看有没有人依赖它。于是「138 条测试全绿」与「代码在 APK 里」
    # 是两件事，而输出上完全一样 —— 从 2.1 到 2.5 一直是后者为假（DIV-0021）。
    #
    # 依赖声明在位（`apply-divergences.sh` 的 DIV-0021 已查）**不等于**产物里有这些类：
    # R8/打包配置、`compileOnly`、构建变体都可能让声明落空。所以对**产物**再查一遍。
    #
    # debug 构建不混淆，类是编进 `classes*.dex` 的，**不是** zip 里的独立条目 ——
    # 所以在 dex 字节里找类型描述符（`L<内部名>;`）。
    # 找一个字符串是弱断言（可能是残留），所以钉的是**整条链**上的几个代表性类：
    # 上游导入的容器 / 我们的适配层 / 本阶段新加的导出编排。
    LATE_PHASE_CLASSES = [
        "Lcom/deepseekharness/app/backup/BackupArchive;",
        "Lcom/deepseekharness/app/backup/HostDataTransaction;",
        "Lcom/dshfusion/app/backup/HostRestoreMapping;",
        "Lcom/dshfusion/app/backup/DshBackupExporter;",
        "Lcom/dshfusion/app/backup/DshAutomaticBackupRetention;",
    ]
    with zipfile.ZipFile(path) as _z:
        dex = [n for n in _z.namelist() if n.endswith(".dex")]
        if not dex:
            problems.append(
                "产物里没有 classes*.dex —— 无法核验阶段 2 的类是否真的被打包。"
                "注意：「找不到 dex」与「类不在 dex 里」是两件事，不能混。"
            )
        else:
            blob = b"".join(_z.read(n) for n in dex)
            absent = [c for c in LATE_PHASE_CLASSES if c.encode() not in blob]
            if absent:
                problems.append(
                    "阶段 2 的类没有出现在产物的 "
                    + "/".join(dex)
                    + " 里："
                    + "、".join(c[1:-1].replace("/", ".") for c in absent)
                    + "。依赖声明在位不等于产物里有这些类 —— "
                    "这正是 DIV-0021 之前四个阶段的真实状态（测试全绿而代码不在 APK 里）。"
                )
            else:
                notes.append(
                    f"阶段 2 的类在产物里（{len(dex)} 个 dex，抽查 {len(LATE_PHASE_CLASSES)} 个代表类）"
                )

    # --- 断言 10：`JobService` 必须在**产物**的 manifest 里带 BIND_JOB_SERVICE ---
    #
    # 为什么单独一条：源码 manifest 里有它（5u 节已查），但设备上生效的是**合并后**的
    # manifest。而缺了它的失败是**静默的** —— `JobScheduler.schedule()` 照常返回 SUCCESS，
    # 只是任务永远不被拉起，于是「自动备份开着、再也不备份」。
    # 与断言 9 同一条理由：**声明在位不等于产物里有。**
    services = {name: permission for name, permission in manifest.get("services", [])}
    job_service = next(
        (name for name in services if name.endswith(".AutomaticBackupJobService")), None
    )
    if job_service is None:
        problems.append(
            "产物的 manifest 里没有 AutomaticBackupJobService —— "
            "自动备份的心跳永远不会被系统拉起，而 `schedule()` 会照常报成功。"
            "（源码 manifest 里有、合并后没有：这正是「声明在位不等于产物里有」。）"
        )
    elif "BIND_JOB_SERVICE" not in services[job_service]:
        problems.append(
            f"{job_service} 缺少 android:permission=BIND_JOB_SERVICE（实际「{services[job_service]}」）"
            " —— 系统不会把任务派给它，而登记会照常返回 SUCCESS"
        )
    else:
        notes.append(f"JobService 在产物 manifest 里且带 BIND_JOB_SERVICE：{job_service}")

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
