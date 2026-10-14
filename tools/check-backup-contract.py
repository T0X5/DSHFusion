#!/usr/bin/env python3
"""备份模块「测试与容器不能各说各话」核验。

## 为什么需要它

阶段 2.1 第一次真实编译时，`V5ArchiveTest` 里的魔数是**手抄**的：

    out.write("DSHDATA".toByteArray(Charsets.US_ASCII))

而 `BackupArchive.MAGIC` 是 8 个字符的 `DSHADATA`。差一个字节，后果是
5 条用例同时红：`hasMagic` 直接失败，4 条 `forge` 负面用例则因为
**夹具本身不合规**而抛 IOException，断言到的错误码完全不是要测的那个。

本机当时跑的所有检查（套件 155 项、导入核验、偏离重放、隐私审计）**没有一条**
能发现它 —— 它们查的都是「文件在不在、字节变没变、字符串在不在」，
没有一条把**测试夹具**和**容器源码**对起来。

所以这里做三件正交的核对：

1. `V5Archive.MAGIC_SIZE` 必须等于 `BackupArchive.MAGIC` 的真实长度；
2. 测试目录里**不得**手抄魔数字面量（应当从真实写入器取，见 `magic` 属性）；
3. 测试断言的每一个错误码，都必须是导入的容器/数据源源码里**真的会抛**的码。

第 3 条同时防住另一类错：把 `assertEquals("PAYLOAD_CHECKSUM", …)` 拼错成
`PAYLOAD_CHECKSUMS` —— 那种错在测试里表现为「永远失败」，不在测试里表现为
「永远发现不了」。这里让它在核验阶段就爆出来。

退出码：0 = 通过；1 = 有问题。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
IMPORTED = ROOT / "backup" / "src" / "main" / "java" / "com" / "deepseekharness" / "app"
OWN_MAIN = ROOT / "backup" / "src" / "main" / "java" / "com" / "dshbox" / "app" / "backup"
TESTS = ROOT / "backup" / "src" / "test"

FAILURES: list[str] = []


def fail(message: str) -> None:
    FAILURES.append(message)


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


# ---- 1. 魔数长度必须一致 ---------------------------------------------------

def magic_bytes_from_container() -> bytes:
    """从 BackupArchive.java 的 `MAGIC` 字面量还原真实魔数。"""
    source = read(IMPORTED / "backup" / "BackupArchive.java")
    match = re.search(r"MAGIC\s*=\s*\{([^}]*)\}", source)
    if not match:
        raise SystemExit("找不到 BackupArchive.MAGIC —— 核验脚本已过期，请同步更新")
    chars = re.findall(r"'((?:\\.|[^'])*)'", match.group(1))
    if not chars:
        raise SystemExit("BackupArchive.MAGIC 解析不出字符字面量")
    return "".join(chars).encode("utf-8")


def check_magic_size() -> bytes:
    real = magic_bytes_from_container()
    source = read(OWN_MAIN / "V5Archive.kt")
    match = re.search(r"MAGIC_SIZE\s*(?::\s*Int)?\s*=\s*(\d+)", source)
    if not match:
        fail("V5Archive.kt 里找不到 MAGIC_SIZE 声明")
        return real
    declared = int(match.group(1))
    if declared != len(real):
        fail(
            f"V5Archive.MAGIC_SIZE = {declared}，而 BackupArchive.MAGIC 是 "
            f"{len(real)} 字节（{real.decode('utf-8', 'replace')!r}）"
        )
    return real


# ---- 2. 测试里不得手抄魔数 -------------------------------------------------

def check_no_hardcoded_magic(real: bytes) -> None:
    """测试**代码**里不得出现手抄的魔数。

    只在代码里查，注释里允许提（`V5ArchiveTest` 的 `magic` 属性就写明了当初抄错成
    `DSHDATA`）。这一条必须把注释剥掉再查 —— 第一版没剥，而且正则写成
    `DSH[A-Z]{2,5}ATA`（要求 `ATA` 前至少 2 个字符），**恰好匹配不了 7 字符的
    `DSHDATA`**：把真实缺陷注入回去后它照样报通过。是故障注入把它抓出来的。
    """
    code_literal = re.compile(r'"(?:DSH[A-Z]*|[A-Z]*ATA)"')
    # 换个写法手抄也要挡：byteArrayOf(0x44, 0x53, ...) 这种按字节摊开的魔数
    byte_literal = re.compile(r"byteArrayOf\(([^)]*)\)")

    for path in sorted(TESTS.rglob("*.kt")) + sorted(TESTS.rglob("*.java")):
        if path.name in {"BackupArchiveTest.java", "BackupJsonTest.java"}:
            continue  # 导入的原始测试，它们测的就是自己那份源码
        for number, line in enumerate(read(path).splitlines(), 1):
            # 剥掉注释：KDoc 的续行以 * 开头，`//` 之前的也算
            if line.strip().startswith(("*", "/*", "//")):
                continue
            code = line.split("//")[0]
            for hit in code_literal.findall(code):
                fail(
                    f"{path.relative_to(ROOT)}:{number} 代码里手抄了魔数字面量 {hit!r} —— "
                    f"应当从真实写入器取（见 V5ArchiveTest 的 magic 属性）"
                )
            for group in byte_literal.findall(code):
                raw = re.findall(r"0x([0-9A-Fa-f]{2})", group)
                if raw and bytes(int(x, 16) for x in raw) == real:
                    fail(
                        f"{path.relative_to(ROOT)}:{number} 用 byteArrayOf 手抄了魔数 —— "
                        f"同样应当从真实写入器取"
                    )


# ---- 3. 测试断言的错误码必须真的会被抛出 -----------------------------------

def codes_the_source_can_throw() -> set[str]:
    codes: set[str] = set()
    for path in sorted(IMPORTED.rglob("*.java")):
        codes.update(
            re.findall(r'(?:IOException|EOFException)\("([A-Z][A-Z0-9_]*)"\)', read(path))
        )
    return codes


def check_asserted_codes(known: set[str]) -> None:
    # 断言必须直接比对抛出的那个 message
    pattern = re.compile(r'assertEquals\(\s*"([A-Z][A-Z0-9_]*)"\s*,\s*[\w.]*\.message\s*\)')
    seen: set[str] = set()
    for path in sorted(TESTS.rglob("*.kt")):
        for number, line in enumerate(read(path).splitlines(), 1):
            for code in pattern.findall(line):
                seen.add(code)
                if code not in known:
                    fail(
                        f"{path.relative_to(ROOT)}:{number} 断言了容器不会抛的错误码 {code}"
                        f" —— 拼错或已被上游改名"
                    )
    if not seen:
        fail("没有任何「断言具体错误码」的用例 —— 备份验收要求明确分类，这不该为空")


# Android 的 `java.nio.file.Files` **没有** Java 11 加的 `readString` / `writeString`。
#
# 这不是猜的：第一次真实编译 `:backup:compileDebugUnitTestJavaWithJavac` 报了 16 处
# `cannot find symbol: method writeString(Path,String)`，因为 AGP 8.9.2 的单元测试
# 编译按 `android.jar`（API 36）解析 `java.nio.file`，而 DSHA 用的 AGP 9.1.1 按 JDK 解析。
#
# 为什么值得在**本机**查：本机没有 JDK/SDK/Gradle，Kotlin 与 Java 都编不了，
# 所以这类错平时只能等 CI 告诉 —— 而 CI 一轮十几分钟。把它变成静态检查之后，
# 上游同步若把这几份测试文件还原成逐字节副本，本机立刻就能发现。
#
# 诚实的边界：**只覆盖已知会犯的这一类**，不是「所有 android.jar 缺失 API」的通用检查。
# 通用检查需要 android.jar 的 API 清单，本机没有。
#
# 这个边界不是敷衍 —— 那一轮 CI 的报错单**本身就是证据**：javac 会一次报出全部错误，
# 而它只报了 `writeString` / `readString` 两个方法 16 处；
# 同一批文件里的 `Path.of(...)`（JvmBackupFileSystem，Java 11）、
# `"[".repeat(66)`（BackupArchiveTest，Java 11）、`List.of(...)`（BackupScopes，Java 9）
# **一处都没报**，说明它们在 android.jar 36 里是有的。
# 所以「已知会缺」的集合是有证据的两项，而不是「我猜的 Java 11 方法列表」。
JAVA11_ONLY_FILES_APIS = ("Files.readString(", "Files.writeString(")


def check_no_jdk_only_apis() -> int:
    hits: list[str] = []
    count = 0
    for path in sorted((ROOT / "backup" / "src").rglob("*.java")):
        for number, line in enumerate(read(path).splitlines(), 1):
            for api in JAVA11_ONLY_FILES_APIS:
                if api in line and not line.lstrip().startswith(("*", "//")):
                    hits.append(f"{path.relative_to(ROOT)}:{number} 用了 {api}…")
                    count += 1
    for item in hits:
        fail(item + " —— Android 的 java.nio.file.Files 没有这个方法，CI 会红")
    if count:
        fail("请改用 Files.write(p, s.getBytes(StandardCharsets.UTF_8)) / "
             "new String(Files.readAllBytes(p), StandardCharsets.UTF_8)")
    return count


def main() -> int:
    real = check_magic_size()
    check_no_hardcoded_magic(real)
    known = codes_the_source_can_throw()
    check_asserted_codes(known)
    check_no_jdk_only_apis()

    if FAILURES:
        print("备份模块契约核验失败：", file=sys.stderr)
        for item in FAILURES:
            print(f"  ✗ {item}", file=sys.stderr)
        return 1
    print(
        f"备份模块契约核验通过：魔数 {real.decode('utf-8', 'replace')!r}"
        f"（{len(real)} 字节）三处一致；测试未手抄魔数；"
        f"断言的错误码均在容器可抛出的 {len(known)} 个码之内；"
        f"未使用 android.jar 缺失的 {len(JAVA11_ONLY_FILES_APIS)} 个 Files API。"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
