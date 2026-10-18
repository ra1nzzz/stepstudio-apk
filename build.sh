#!/usr/bin/env bash
#
# Step Studio Android 构建脚本（不依赖 Gradle / Android Studio 工程结构）
#
# 依赖：
#   - JDK 17（javac / keytool）
#   - Android SDK：build-tools（34.0.0+）、platforms/android-34
#
# 用法：
#   ANDROID_HOME=/path/to/android-sdk ./build.sh
#
# 可选环境变量：
#   ANDROID_HOME        Android SDK 根目录（必填，或用 ANDROID_SDK）
#   KEYSTORE            keystore 路径；提供则输出已签名 APK
#   KS_ALIAS            key alias，默认 stepstudio
#   KS_PASS             keystore 与 key 口令
#   VERSION_CODE        默认 1
#   VERSION_NAME        默认 1.0.0
#
# 输出：out/StepStudio.apk
#
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK:-}}"
OUT="$APP_DIR/out"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"

if [ -z "$SDK" ]; then
  echo "错误：请通过 ANDROID_HOME 指定 Android SDK 路径" >&2
  exit 1
fi
if [ ! -d "$SDK/platforms/android-34" ]; then
  echo "错误：缺少 $SDK/platforms/android-34（sdkmanager \"platforms;android-34\"）" >&2
  exit 1
fi

ANDROID_JAR="$SDK/platforms/android-34/android.jar"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
if [ -z "$BUILD_TOOLS" ]; then
  echo "错误：未找到 build-tools（sdkmanager \"build-tools;34.0.0\"）" >&2
  exit 1
fi
export PATH="$BUILD_TOOLS:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== 1/6 编译资源（aapt2 compile）"
aapt2 compile --dir "$APP_DIR/res" -o "$OUT/res.zip"

echo "== 2/6 链接资源（aapt2 link）"
aapt2 link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "== 3/6 编译 Java（javac）"
find "$APP_DIR/src" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 4/6 生成 dex（d8）"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
d8 --lib "$ANDROID_JAR" --min-api 21 --output "$OUT/dex" @"$OUT/classes.txt"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"

echo "== 5/6 打包 dex"
python3 - "$OUT/base.apk" "$OUT/classes.dex" <<'PY'
import shutil, sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
shutil.copy(apk, tmp)
with zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
shutil.move(tmp, apk)
PY

echo "== 6/6 对齐与签名"
zipalign -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [ -n "${KEYSTORE:-}" ] && [ -f "${KEYSTORE:-}" ]; then
  apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "${KS_ALIAS:-stepstudio}" \
    --ks-pass "pass:${KS_PASS:?请设置 KS_PASS}" \
    --key-pass "pass:${KS_PASS:?请设置 KS_PASS}" \
    --out "$OUT/StepStudio.apk" "$OUT/aligned.apk"
  apksigner verify --print-certs "$OUT/StepStudio.apk" | head -3
else
  cp "$OUT/aligned.apk" "$OUT/StepStudio-unsigned.apk"
  echo "提示：未提供 KEYSTORE，已输出未签名包 $OUT/StepStudio-unsigned.apk" >&2
fi

echo "完成：$OUT"
ls -lh "$OUT"/*.apk
