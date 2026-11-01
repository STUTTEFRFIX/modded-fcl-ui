#!/bin/sh
# 符号契约：libfclshim.so 的动态导出面必须完全等于白名单。
#
# 为什么要有这个东西（借自 DroidDeck tools/linuxfs/check-preloads.sh）：
#   垫片层的失败模式是「悄悄把 libc 的符号盖掉」，编译期看不出来，只在某台设备上随机崩。
#   把「必须导出什么 / 禁止导出什么」写成脚本挂进 CI，比写在 README 里可靠。
#
# 用法：scripts/check-shim-symbols.sh <libfclshim.so>
set -eu

LIB=${1:?用法: check-shim-symbols.sh <libfclshim.so>}

# 允许出现在动态符号表里的全部符号
ALLOW="chmod fchmod futex_waitv fclshim_active_modules fclshim_api_version fclshim_bootstrap"

# 出现即失败：这些是 HotSpot / bionic 的地基，垫住就是随机崩溃
FORBID="mmap mmap64 munmap mprotect socket bind connect open openat fopen fclose read write close ioctl syscall dlsym dlopen malloc calloc free realloc pthread_create sigaction signal getenv setenv clock_gettime nanosleep"

# 必须存在的符号（漏了说明模块没编进去）
REQUIRE="fclshim_api_version fclshim_bootstrap futex_waitv chmod fchmod"

actual=$(nm -D --defined-only "$LIB" 2>/dev/null \
    | awk '($2=="T"||$2=="D"||$2=="B"||$2=="R"||$2=="W"){print $3}' \
    | sed 's/@.*//' | sort -u)

if [ -z "$actual" ]; then
    echo "✗ 读不出动态符号表：$LIB" >&2
    exit 1
fi

rc=0

extra=$(printf '%s\n' "$actual" | while read -r s; do
    [ -z "$s" ] && continue
    case " $ALLOW " in
        *" $s "*) ;;
        *) echo "$s" ;;
    esac
done)
if [ -n "$extra" ]; then
    echo "✗ 白名单外的导出符号（会污染 JVM 的符号解析）：" >&2
    printf '   - %s\n' $extra >&2
    rc=1
fi

bad=$(printf '%s\n' "$actual" | while read -r s; do
    [ -z "$s" ] && continue
    case " $FORBID " in
        *" $s "*) echo "$s" ;;
    esac
done)
if [ -n "$bad" ]; then
    echo "✗ 禁止导出的地基符号出现了：" >&2
    printf '   - %s\n' $bad >&2
    rc=1
fi

for need in $REQUIRE; do
    if ! printf '%s\n' "$actual" | grep -qx "$need"; then
        echo "✗ 缺少必需符号：$need" >&2
        rc=1
    fi
done

if [ $rc -eq 0 ]; then
    echo "✓ 符号契约通过，导出面共 $(printf '%s\n' "$actual" | grep -c . ) 个：$(printf '%s ' $actual)"
fi
exit $rc
