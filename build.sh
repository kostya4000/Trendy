#!/usr/bin/env bash
# Сборка APK без Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Работает в Termux (aarch64), нужны пакеты: openjdk-21 aapt2 d8 apksigner aapt zipalign zip.
set -euo pipefail
cd "$(dirname "$0")"

if [ -d "/data/data/com.termux/files/usr/bin" ]; then
  export PATH="/data/data/com.termux/files/usr/bin:$PATH"
fi

SDK_JAR="${ANDROID_JAR:-}"
if [ -z "$SDK_JAR" ]; then
  for candidate in \
    ".sdk/android.jar" \
    "$HOME/android-sdk/android.jar" \
    "${ANDROID_HOME:-}/platforms/android-36/android.jar" \
    "${ANDROID_HOME:-}/platforms/android-35/android.jar" \
    "${ANDROID_SDK_ROOT:-}/platforms/android-36/android.jar"; do
    if [ -f "$candidate" ]; then
      SDK_JAR="$candidate"
      break
    fi
  done
fi
if [ ! -f "$SDK_JAR" ]; then
  echo "android.jar не найден. Положите его в .sdk/android.jar или задайте ANDROID_JAR." >&2
  exit 1
fi

for tool in aapt2 javac jar d8 zipalign apksigner keytool; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Не найден инструмент: $tool" >&2
    exit 1
  fi
done

VERSION_NAME="1.0"
VERSION_CODE="1"
if [ -f "version.properties" ]; then
  # Единый источник версии для build.sh и Gradle.
  . ./version.properties
  VERSION_NAME="${versionName:-$VERSION_NAME}"
  VERSION_CODE="${versionCode:-$VERSION_CODE}"
fi
APK_NAME="Trendy-${VERSION_NAME}.apk"
KEYSTORE="keystore.jks"
KEY_ALIAS="trendy"
STORE_PASS="trendy123"
KEY_PASS="trendy123"

rm -rf build/gen build/classes build/dex build/res.zip build/classes.jar \
       build/app-unsigned.apk build/aligned.apk build/dex-tmp \
       build/AndroidManifest.xml
mkdir -p build/gen build/classes build/dex

# Gradle/AGP 8 берёт имя пакета из namespace и не допускает package в исходном
# манифесте. Для ручной сборки aapt2 подставляем package во временную копию.
sed '0,/<manifest /s//<manifest package="com.promodj.trendy" /' \
  app/src/main/AndroidManifest.xml > build/AndroidManifest.xml

echo "==> compile resources"
aapt2 compile --dir app/src/main/res -o build/res.zip

echo "==> link resources"
aapt2 link \
  -o build/app-unsigned.apk \
  -I "$SDK_JAR" \
  --manifest build/AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 23 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  build/res.zip

echo "==> compile java"
find app/src/main/java build/gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$SDK_JAR" \
  -d build/classes \
  @build/sources.txt

echo "==> dex"
jar cf build/classes.jar -C build/classes .
d8 --release --lib "$SDK_JAR" --min-api 23 --output build/dex build/classes.jar
cp build/app-unsigned.apk build/with-dex.apk
(cd build/dex && zip -q ../with-dex.apk classes.dex)

echo "==> align"
zipalign -f 4 build/with-dex.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  echo "==> generate keystore"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=Trendy, OU=Unofficial client, O=PromoDJ Trendy, C=RU" >/dev/null 2>&1
fi

echo "==> sign"
apksigner sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "build/$APK_NAME" \
  build/aligned.apk

echo "==> verify"
apksigner verify --verbose "build/$APK_NAME" | sed -n '1,12p'
rm -f build/app-unsigned.apk build/with-dex.apk

ls -lh "build/$APK_NAME"
echo "Готово: $(pwd)/build/$APK_NAME"
