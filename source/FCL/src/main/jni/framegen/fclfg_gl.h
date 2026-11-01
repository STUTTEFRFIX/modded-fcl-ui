/*
 * fclfg_gl.h —— GL(EGL) 路径的接缝：gl_bridge 调它一次，它决定这一帧怎么上屏
 */
#ifndef FCLFG_GL_H
#define FCLFG_GL_H

#ifdef __cplusplus
extern "C" {
#endif

/*
 * 尝试接管一次 swap。
 *   dpy/surf : gl_bridge 当前的 EGL display/surface
 *   swap_fn  : 真正的 eglSwapBuffers（gl_bridge 的 eglSwapBuffers_p）
 * 返回 1 = 已由本层完成 swap（可能多呈现了 1~3 个插值帧，调用方不要再 swap）
 * 返回 0 = 未接管，调用方照原样 swap（未启用 / 初始化失败 / 出错回退）
 */
int fclfg_gl_try_present(void *dpy, void *surf, void *swap_fn);

#ifdef __cplusplus
}
#endif

#endif /* FCLFG_GL_H */
