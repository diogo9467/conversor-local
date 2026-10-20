#!/usr/bin/env bash
set -euo pipefail
ROOT=${OFFICE_BUILD_ROOT:-/opt/office-local}
ABI=${1:-x86_64}
MODE=${2:-build}
case "$ABI" in x86_64|arm64-v8a) ;; *) exit 2 ;; esac
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$ROOT/android-sdk"
export GRADLE_USER_HOME="$ROOT/gradle-cache"
export PATH="$JAVA_HOME/bin:$ROOT/node-v22.20.0-linux-x64/bin:$PATH"
export GRADLE_OPTS='-Dorg.gradle.vfs.watch=false'
ENGINE="$ROOT/engine-$ABI"
if [ "$MODE" != prepare ]; then
    test -s "$ENGINE/android/jniLibs/$ABI/liblo-native-code.so"
fi
cd "$ROOT/upstream"
./autogen.sh
./configure --enable-androidapp --with-lo-builddir="$ENGINE" --with-android-abi="$ABI" \
    --with-app-package-name=dev.diogo.officelocal --with-app-name='Office Local' \
    --with-vendor='Office Local' --disable-tests --enable-debug
if [ "$MODE" = prepare ]; then
    make -C browser -j4
    exit
fi
make -j8
cd android
./gradlew assembleDebug assembleDebugAndroidTest --console=plain
echo "EDITOR_READY $ABI"
