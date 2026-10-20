#!/usr/bin/env bash
set -euo pipefail
ROOT=${OFFICE_BUILD_ROOT:-/opt/office-local}
ABI=${1:-arm64-v8a}
SUITE=${OFFICE_SUITE_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
case "$ABI" in x86_64|arm64-v8a) ;; *) exit 2 ;; esac
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$ROOT/android-sdk"
export GRADLE_USER_HOME="$ROOT/gradle-cache"
export PATH="$JAVA_HOME/bin:$ROOT/node-v22.20.0-linux-x64/bin:$PATH"
test -s "$ROOT/engine-$ABI/android/jniLibs/$ABI/liblo-native-code.so"
python3 "$SUITE/tools/patch_security.py" "$ROOT/upstream"
# Incremental make ensures an existing engine also contains the security patch.
make -C "$ROOT/engine-$ABI" -j"${OFFICE_JOBS:-8}"
python3 "$SUITE/tools/install_suite.py" "$ROOT/upstream"
cd "$ROOT/upstream"
./autogen.sh
./configure --enable-androidapp --with-lo-builddir="$ROOT/engine-$ABI" --with-android-abi="$ABI" \
    --with-app-package-name=dev.diogo.officelocal --with-app-name='Office Local' \
    --with-vendor='Office Local' --disable-tests --disable-debug
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug \
    --max-workers=4 --console=plain
echo "SUITE_READY $ABI (development signature)"
