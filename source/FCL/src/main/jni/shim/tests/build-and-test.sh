#!/bin/sh
# 宿主侧验证：编译垫片层 + 符号契约 + 行为测试（不需要 NDK，也不需要 Android 设备）
# 用法：sh tests/build-and-test.sh [输出目录]
set -eu

SHIM_DIR=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-/tmp/fclshim-host}
CC=${CC:-gcc}

mkdir -p "$OUT"

echo "== 1/3 构建 libfclshim.so（与 Gradle 相同的可见性/导出策略）"
# 不定义任何测试宏；-fvisibility=hidden 与 CMakeLists 里一致
$CC -shared -fPIC -O2 -g -fvisibility=hidden -Wall -Wextra \
    -I"$SHIM_DIR" \
    -o "$OUT/libfclshim.so" \
    "$SHIM_DIR/fclshim.c" \
    "$SHIM_DIR/fclshim_loader.c" \
    "$SHIM_DIR/modules/mod_futex_waitv.c" \
    "$SHIM_DIR/modules/mod_chmod.c"

$CC -O2 -g -Wall -Wextra -o "$OUT/test_shim" "$SHIM_DIR/tests/test_shim.c" -ldl -lpthread

echo "== 2/3 符号契约"
sh "$SHIM_DIR/check-shim-symbols.sh" "$OUT/libfclshim.so"

echo "== 3/3 行为测试"
echo "-- 模式 A：不点名任何模块（必须保持 Android 原生语义）"
FCL_SHIM_MODULES= "$OUT/test_shim" "$OUT/libfclshim.so"
echo "-- 模式 B：futex_waitv + chmod"
FCL_SHIM_MODULES=futex_waitv,chmod "$OUT/test_shim" "$OUT/libfclshim.so"

echo
echo "全部通过。产物：$OUT"
