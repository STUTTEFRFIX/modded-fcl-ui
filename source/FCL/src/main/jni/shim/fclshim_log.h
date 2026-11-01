/* 内部日志：只在 libfclshim.so 内部用，不导出 */
#ifndef FCLSHIM_LOG_H
#define FCLSHIM_LOG_H

#ifdef __ANDROID__
#include <android/log.h>
#define SHIM_TAG "FCLShim"
#define SHIM_LOG(...) __android_log_print(ANDROID_LOG_INFO, SHIM_TAG, __VA_ARGS__)
#else
#include <stdio.h>
#define SHIM_LOG(...) do { fprintf(stderr, "[FCLShim] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

#endif /* FCLSHIM_LOG_H */
