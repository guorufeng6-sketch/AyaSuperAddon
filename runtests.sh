#!/usr/bin/env bash
# 纯逻辑单测运行器（不依赖 Android 运行时，只需要 android.jar 参与编译）。
#
# 为什么单独一个脚本：build.sh 里那份 javac 清单是 v1 时代的，只编 4~5 个类，
# 现在有 10 个测试类，漏一个就整批编译失败。这里集中维护清单，加测试只需改两行。
#
# 用法：bash android-addon/runtests.sh
set -uo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-D:/android-toolchain/android-sdk}"
AJAR="$SDK/platforms/android-36/android.jar"
[ -f "$AJAR" ] || { echo "android.jar not found: $AJAR" >&2; exit 1; }

# 用绝对路径调 JDK —— 本机 PATH 里没有 javac（沙箱会吃掉 PATH 追加），别依赖它
JDK="${TURBOIO_JDK:-D:/android-toolchain/jdk-17.0.20.1+1}"
JAVAC="$JDK/bin/javac.exe"
JAVA="$JDK/bin/java.exe"
[ -x "$JAVAC" ] || { echo "javac not found: $JAVAC" >&2; exit 1; }

# 被测的纯逻辑源码（不碰 Android API；ChatPolicy 里少量 android.* 引用只在编译期需要 android.jar）
#
# ★ 为什么还要 -sourcepath src（见下面的 javac）：
#   v3r19 起纯逻辑层之间开始互相复用 —— 例如 `Memo` / `ReaderCmd` 都用
#   `VoicePager.normalize`（"与 ASR 链路同一把尺子"是刻意的，不能各写一份），
#   而 VoicePager 又引用 ReaderUI。手工维护这个传递闭包只会漏，
#   交给 javac 自己按 sourcepath 拉取即可；反正 android.jar 已经挂在 -cp 上，
#   Android 类只参与**编译**（测试运行时从不加载它们）。
PURE="
src/com/turboio/addon/ChatPolicy.java
src/com/turboio/addon/NavCore.java
src/com/turboio/addon/NavGuide.java
src/com/turboio/addon/NavVoice.java
src/com/turboio/addon/NavSessionPolicy.java
src/com/turboio/addon/NavSimulation.java
src/com/turboio/addon/NlpRouter.java
src/com/turboio/addon/Countdown.java
src/com/turboio/addon/TextPage.java
src/com/turboio/addon/Weather.java
src/com/turboio/addon/LocalCapabilities.java
src/com/turboio/addon/NavPlace.java
src/com/turboio/addon/NavRoute.java
src/com/turboio/addon/NavFix.java
src/com/turboio/addon/NavReroute.java
src/com/turboio/addon/ReaderCmd.java
src/com/turboio/addon/ReaderBook.java
src/com/turboio/addon/Memo.java
src/com/turboio/addon/LocalIntent.java
src/com/turboio/addon/Sport.java
src/com/turboio/addon/News.java
src/com/turboio/addon/WebSearch.java
"

# 清单里允许出现还不存在的文件（正在写的那个类），跳过即可
SRCS=""
for s in $PURE; do [ -f "$s" ] && SRCS="$SRCS $s"; done

rm -rf build/test && mkdir -p build/test
# MSYS 会把 -cp 里的 ; 和路径改写，必须关掉参数转换
MSYS2_ARG_CONV_EXCL="*" "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -sourcepath src -cp "$AJAR" -d build/test $SRCS tests/*.java || exit 1

fail=0
for f in tests/*Test.java; do
  t=$(basename "$f" .java)
  if "$JAVA" -cp build/test "$t"; then :; else fail=1; fi
done
[ "$fail" = 0 ] || { echo "SOME TESTS FAILED" >&2; exit 1; }
