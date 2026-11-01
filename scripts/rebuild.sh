#!/bin/sh
# 一键重建「最新 FCL + 去地区限制」arm64 APK（cccODroid 本机）
# 前提：…/fcl-build/{tools/gradle,sdk} 已按 README §3 准备好
set -eu

REPO="${REPO:-/storage/emulated/0/DeepSeek agent/FCL 库/FoldCraftLauncher}"
B="${B:-/data/user/0/cc.codroid/files/home/workspace/fcl-build}"
D="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk}"
GRADLE="$B/tools/gradle/bin/gradle"

[ -x "$GRADLE" ] || { echo "缺少 Gradle：$GRADLE（见 README §3）" >&2; exit 1; }
[ -f "$B/sdk/ndk/27.0.12077973/source.properties" ] || { echo "缺少 NDK（见 README §3.2）" >&2; exit 1; }

echo "== 1/5 拉取上游 main"
git -C "$REPO" fetch origin main

echo "== 2/5 导出干净源码到 $B/src"
rm -rf "$B/src"
mkdir -p "$B/src"
git -C "$REPO" archive origin/main | tar -x -C "$B/src"

echo "== 3/5 打补丁"
cd "$B/src"
patch -p1 < "$D/patches/0001-remove-terracotta-node-region-filter.patch"
patch -p1 < "$D/patches/0002-noregion-build-identity.patch"
patch -p1 < "$D/patches/0003-noregion-res-identity.patch"

echo "== 4/5 写构建配置 / 生成签名密钥"
cp "$D/scripts/local.properties.example" local.properties
cat "$D/scripts/gradle.properties.append" >> gradle.properties
if [ ! -f key-store.jks ]; then
  keytool -genkeypair -keystore key-store.jks -alias FCL-Key \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass FCL-Build-2026 -keypass FCL-Build-2026 \
    -dname "CN=FCL Build, OU=cccodroid, O=FCL, L=Beijing, ST=Beijing, C=CN"
fi

echo "== 5/5 Gradle 构建（arm64）"
mkdir -p "$B/logs"
JAVA_HOME="$JAVA_HOME" "$GRADLE" -p "$B/src" --console=plain \
  :FCL:assembleDebug -Darch=arm64 --max-workers=4 \
  2>&1 | tee "$B/logs/rebuild-$(date +%Y%m%d-%H%M).log"

APK="$B/src/FCL/build/outputs/apk/debug/FCL-debug-1.3.4.1-noregion-arm64-v8a.apk"
[ -f "$APK" ] || { echo "构建结束但没找到 APK：$APK" >&2; exit 1; }
echo "完成：$APK"
md5sum "$APK"
