#!/usr/bin/env bash
# 在本机**真编译、真运行** :backup 的纯 JVM 部分（主源码 + 测试）。
#
# 为什么需要这个脚本：
#   `:backup` 里的 Java 源码一行 Android API 都不用（`CredentialProjection` 等
#   只依赖 Gson / SnakeYAML）。它们的单元测试本来也只能由 CI 跑 ——
#   而 CI 一轮十几分钟，且**只看得到编译与测试的最终结论**。
#   本脚本把这部分搬到本机，于是「改一行 → 知道对不对」从十几分钟变成两秒。
#
# 覆盖范围（**如实声明，别当成全量**）：
#   ✅ backup/src/main 与 backup/src/test 下的**全部 .java**（编译 + 运行）
#   ❌ 同目录下的 **.kt**（V5Archive、DshBackupScope、DshBackupExclusions 及其测试）
#      —— 本机没有 kotlinc。这几条仍然只有 CI 能跑。
#   ❌ Android 侧（lint、assembleDebug、APK 产物核验）—— 只有 CI 能跑。
#
# 用法：bash tools/run-backup-jvm-tests.sh
# 退出码：0 全过；1 编译或测试失败；2 依赖 jar 取不到（**不静默跳过**）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${DSHFUSION_JVM_CACHE:-$ROOT/.jvm-test-cache}"
OUT="$CACHE/classes"
MAVEN=https://repo1.maven.org/maven2

# 版本必须与 gradle/libs.versions.toml 一致；不一致时下面的断言会拦。
GSON_V=2.13.1
SNAKEYAML_V=2.4
JUNIT_V=4.13.2
HAMCREST_V=1.3

JARS=(
  "$MAVEN/junit/junit/$JUNIT_V/junit-$JUNIT_V.jar"
  "$MAVEN/org/hamcrest/hamcrest-core/$HAMCREST_V/hamcrest-core-$HAMCREST_V.jar"
  "$MAVEN/com/google/code/gson/gson/$GSON_V/gson-$GSON_V.jar"
  "$MAVEN/org/yaml/snakeyaml/$SNAKEYAML_V/snakeyaml-$SNAKEYAML_V.jar"
)

# 单一事实来源：版本目录里写的是什么，这里就必须取什么。
for pair in "gson = \"$GSON_V\"" "snakeyaml = \"$SNAKEYAML_V\"" "junit = \"$JUNIT_V\""; do
  if ! grep -qF "$pair" "$ROOT/gradle/libs.versions.toml"; then
    echo "ERROR: gradle/libs.versions.toml 里找不到 $pair —— 版本目录变了，本脚本的版本要跟着改。" >&2
    exit 2
  fi
done

mkdir -p "$CACHE/lib"
CP=""
for url in "${JARS[@]}"; do
  jar="$CACHE/lib/$(basename "$url")"
  if [[ ! -s "$jar" ]]; then
    echo "取依赖：$(basename "$url")"
    if ! curl -fsSL -o "$jar.part" "$url"; then
      echo "ERROR: 取不到 $url。" >&2
      echo "  Maven Central 不可达 —— **这不是「跳过」，是失败**：本机测试没有运行。" >&2
      rm -f "$jar.part"
      exit 2
    fi
    mv "$jar.part" "$jar"
  fi
  CP="${CP:+$CP:}$jar"
done

rm -rf "$OUT" && mkdir -p "$OUT"

# 用数组而不是 `$(find …)`：未加引号的命令替换会触发 SC2046，
# 而那种写法在文件名带空格时会把一个路径拆成两个参数（这里不会，但不值得赌）。
mapfile -t MAIN_SRC_ALL < <(find "$ROOT/backup/src/main" -name '*.java')

# ── 本机没有 android.jar，所以平台相关的源码在这里编不了 ─────────────────
#
# ⚠️ **排除必须是显式的、有数的、且与一张声明表对照的。**
# 一个静默的 `-not -name ...` 会让「本机全绿」的覆盖面悄悄小于它宣称的覆盖面 ——
# 那正是本项目反复吃亏的那个形态（第 6/13/24/26 条）。
# 所以这里：① 按**依赖**（import 了 android./androidx.）而不是按文件名认；
# ② 把认出来的那份与下面这张表**逐一对照**，多一个少一个都判失败。
#
# 被排除的那份**不是没人管**：它们的正文由 check-dsha-imports.py 逐字节核验，
# 并由 governance/dsha-imports.json 里的 `must_contain`/`must_not_contain` 断言钉住
# 「用的是什么平台 API」与「没有引入原生库」。编译正确性由 CI 负责。
ANDROID_ONLY_DECLARED=(
  "AndroidBackupFileSystem.java"
)
MAIN_SRC=()
ANDROID_ONLY_FOUND=()
for f in "${MAIN_SRC_ALL[@]}"; do
  if grep -qE '^import (android|androidx)\.' "$f"; then
    ANDROID_ONLY_FOUND+=("$(basename "$f")")
  else
    MAIN_SRC+=("$f")
  fi
done
mapfile -t ANDROID_ONLY_FOUND < <(printf '%s\n' "${ANDROID_ONLY_FOUND[@]}" | sort)
mapfile -t ANDROID_ONLY_DECLARED < <(printf '%s\n' "${ANDROID_ONLY_DECLARED[@]}" | sort)
if [[ "${ANDROID_ONLY_FOUND[*]}" != "${ANDROID_ONLY_DECLARED[*]}" ]]; then
  echo "ERROR: 本机编不了的源码集合与声明的不一致。" >&2
  echo "  实测：${ANDROID_ONLY_FOUND[*]:-（空）}" >&2
  echo "  声明：${ANDROID_ONLY_DECLARED[*]:-（空）}" >&2
  echo "  新增了平台相关的源码，或者某个源码**新引入了** android 依赖。" >&2
  echo "  请同步更新 ANDROID_ONLY_DECLARED，并给它补 verify 断言。" >&2
  echo "  不允许让它静默地从本机编译里消失。" >&2
  exit 2
fi
mapfile -t TEST_SRC < <(find "$ROOT/backup/src/test" -name '*.java')
if [[ ${#MAIN_SRC[@]} -eq 0 || ${#TEST_SRC[@]} -eq 0 ]]; then
  echo "ERROR: 没找到源码（main=${#MAIN_SRC[@]} test=${#TEST_SRC[@]}）—— 编译一个空集合是假通过。" >&2
  exit 1
fi

echo "编译主源码（.java，${#MAIN_SRC[@]} 个；另有 ${#ANDROID_ONLY_FOUND[@]} 个因 Android 平台依赖本机编不了：${ANDROID_ONLY_FOUND[*]:-无}）…"
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$CP" "${MAIN_SRC[@]}"
echo "编译测试源码（.java，${#TEST_SRC[@]} 个）…"
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$CP:$OUT" "${TEST_SRC[@]}"

CLASSES=$(cd "$OUT" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
if [[ -z "$CLASSES" ]]; then
  echo "ERROR: 一个测试类都没找到 —— 那说明这次「通过」什么也没验证。" >&2
  exit 1
fi
COUNT=$(echo "$CLASSES" | wc -l)
echo "运行 $COUNT 个 Java 测试类…"
# shellcheck disable=SC2086
# -Dfile.encoding=UTF-8：JDK 17 的 System.out 默认跟平台走，
# 不显式指定时中文输出在部分环境会变成乱码（不影响判定，但看日志的人会误判）。
java -Dfile.encoding=UTF-8 -cp "$CP:$OUT" org.junit.runner.JUnitCore $CLASSES

# 行为核验：不是「字符串在不在」，而是「跑一遍看结果对不对」。
# 显式用 -encoding UTF-8 编译（本机平台默认是 US-ASCII，中文注释会编不过）；
# 这也正是**不能**改用 `java xxx.java` 单文件启动的原因 —— 那条路不接受 -encoding。
PROBE="$ROOT/tools/check-credential-projection.java"
if [[ -f "$PROBE" ]]; then
  echo
  echo "编译并运行凭据投影的行为核验（tools/check-credential-projection.java）…"
  javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$CP:$OUT" "$PROBE"
  java -Dfile.encoding=UTF-8 -cp "$CP:$OUT" CheckCredentialProjection
fi

echo
echo "本机 Java 测试通过（$COUNT 个类）。"
echo "⚠️ 这不覆盖 .kt 的 Kotlin 测试与 Android 侧（lint / assembleDebug / APK 核验）—— 那些只有 CI 能跑。"
