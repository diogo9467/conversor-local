#!/usr/bin/env bash
set -euo pipefail
ROOT=${OFFICE_BUILD_ROOT:-/opt/office-local}
SUITE=${OFFICE_SUITE_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
ABI=${1:-x86_64}
JOBS=${OFFICE_JOBS:-12}
LANGUAGES=${OFFICE_LANGUAGES:-pt-BR}
case "$ABI" in
    x86_64) DISTRO=CPAndroidX86_64 ;;
    arm64-v8a) DISTRO=CPAndroidAarch64 ;;
    *) echo 'Expected x86_64 or arm64-v8a' >&2; exit 2 ;;
esac
test "$(git -C "$ROOT/upstream" rev-parse HEAD)" = 201368fc8ded1a0f28d1891c77e0410cb9416531
python3 "$SUITE/tools/patch_security.py" "$ROOT/upstream"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$ROOT/android-sdk"
export PATH="$JAVA_HOME/bin:$ROOT/node-v22.20.0-linux-x64/bin:$PATH"
mkdir -p "$ROOT/engine-$ABI"
cd "$ROOT/engine-$ABI"
cat > autogen.input <<EOF
--with-distro=$DISTRO
--with-android-ndk=$ANDROID_HOME/ndk/29.0.14206865
--with-android-sdk=$ANDROID_HOME
--with-android-api-level=26
--with-android-package-name=dev.diogo.officelocal
--with-vendor=Office Local
--with-lang=$LANGUAGES
--without-java
--without-help
--disable-dbgutil
--disable-werror
--with-parallelism=$JOBS
EOF
"$ROOT/upstream/engine/autogen.sh"
# Reconfiguration from en-US alone may leave this host tool out of cross-toolset.
make gb_Side=build -j"$JOBS" Executable_pocheck
make -j"$JOBS"
echo "ENGINE_READY $ABI"
