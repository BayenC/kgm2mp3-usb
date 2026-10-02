#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
toolchain_dir="${KGX_TOOLCHAIN:-/private/tmp/kgx2mp3-toolchain}"
export JAVA_HOME="$toolchain_dir/jdk/Contents/Home"
export ANDROID_HOME="$toolchain_dir/sdk"
export ANDROID_SDK_ROOT="$toolchain_dir/sdk"
export ANDROID_USER_HOME="$toolchain_dir/android-user"
export GRADLE_USER_HOME="$toolchain_dir/gradle-home"
cd "$project_dir"
exec python3 "$project_dir/tools/build_network.py" gradle "$toolchain_dir/gradle-8.13/bin/gradle" "$@"
