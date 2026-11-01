/*
 * fclfg_gl.c —— 插帧宿主的 GL(EGL) 后端
 *
 * 接缝：ctxbridges/gl_bridge.c :: gl_swap_buffers()
 *       GL4ES / NGGL4ES / ANGLE / Zink 都走这一条 EGL 出口。
 *
 * 一帧的流程（由 fclfg_core 调度，本文件只实现采集/呈现/占位插值）：
 *   采集 FB0 → 纹理 A/B（GPU 侧 glCopyTexImage2D，绝不 glReadPixels）
 *   每个插值槽：调管线 → 画进 FB0 → eglSwapBuffers（阻塞到 vsync，天然节拍）
 *   最后：把真实帧画进 FB0 → eglSwapBuffers
 *
 * 两个必须知道的坑：
 *   1. EGL 双缓冲在 swap 之后后缓冲内容未定义，"再 swap 一次"不会重显旧帧，
 *      所以**每次呈现前都要把内容重画进 FB0**。
 *   2. 采集假设 swap 时刻 FB0 里就是最终画面。GL4ES 有内部 FBO 路径，
 *      某些配置下画面可能不在 FB0 —— 探针见 塞进FCL-集成设计.md §3.2。
 *      真遇到就置 broken 并永久回退，不会花屏。
 *
 * 当前插值实现是**占位**（帧重复：把 A 画到 OUT），用来验证管线以外的全部环节。
 * 真正的插值由管线的 interpolate 回调替换本文件的 stub_interpolate。
 */
#include "fclfg_gl.h"

#if defined(__aarch64__)

#include "fclfg.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define TAG "FCLFG"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef EGLBoolean (*eglSwapBuffers_fn)(EGLDisplay, EGLSurface);

static struct {
    int state;              /* 0=未初始化 1=可用 -1=已损坏（永久回退） */
    int stub_logged;
    int black_checked;
    int frame_count;
    int w, h;
    EGLDisplay dpy;
    EGLSurface surf;
    eglSwapBuffers_fn swap;

    GLuint tex[2];          /* A / B */
    GLuint tex_out;         /* OUT */
    GLuint fbo_out;
    GLuint prog;
    GLuint vbo;
    GLint loc_pos;
    GLint loc_tex;
} g;

/* ---------------- GL 小工具 ---------------- */

static const char *VS =
        "attribute vec2 aPos;\n"
        "varying vec2 vUv;\n"
        "void main() {\n"
        "    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);\n"
        "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
        "}\n";

static const char *FS =
        "precision mediump float;\n"
        "varying vec2 vUv;\n"
        "uniform sampler2D uTex;\n"
        "void main() { gl_FragColor = texture2D(uTex, vUv); }\n";

static GLuint compile_shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    if (s == 0) return 0;
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[256];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        LOGI("着色器编译失败：%s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static int build_program(void) {
    GLuint vs = compile_shader(GL_VERTEX_SHADER, VS);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, FS);
    if (vs == 0 || fs == 0) return -1;

    g.prog = glCreateProgram();
    glAttachShader(g.prog, vs);
    glAttachShader(g.prog, fs);
    glLinkProgram(g.prog);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok = 0;
    glGetProgramiv(g.prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[256];
        glGetProgramInfoLog(g.prog, sizeof(log), NULL, log);
        LOGI("着色器链接失败：%s", log);
        glDeleteProgram(g.prog);
        g.prog = 0;
        return -1;
    }

    g.loc_pos = glGetAttribLocation(g.prog, "aPos");
    g.loc_tex = glGetUniformLocation(g.prog, "uTex");

    static const GLfloat quad[] = {-1.f, -1.f, 3.f, -1.f, -1.f, 3.f};  /* 覆盖全屏的大三角 */
    glGenBuffers(1, &g.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return 0;
}

static GLuint make_texture(void) {
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g.w, g.h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    return t;
}

static int ensure_size(EGLDisplay dpy, EGLSurface surf) {
    EGLint w = 0, h = 0;
    if (eglQuerySurface(dpy, surf, EGL_WIDTH, &w) != EGL_TRUE) return -1;
    if (eglQuerySurface(dpy, surf, EGL_HEIGHT, &h) != EGL_TRUE) return -1;
    if (w <= 0 || h <= 0) return -1;
    if (w == g.w && h == g.h && g.tex[0] != 0) return 0;

    /* 尺寸变了：重建资源 */
    if (g.tex[0]) glDeleteTextures(2, g.tex);
    if (g.tex_out) glDeleteTextures(1, &g.tex_out);
    if (g.fbo_out) glDeleteFramebuffers(1, &g.fbo_out);

    g.w = w;
    g.h = h;
    g.tex[0] = make_texture();
    g.tex[1] = make_texture();
    g.tex_out = make_texture();

    glGenFramebuffers(1, &g.fbo_out);
    GLint prev_fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g.fbo_out);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g.tex_out, 0);
    const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint) prev_fbo);
    if (st != GL_FRAMEBUFFER_COMPLETE) {
        LOGI("OUT 的 FBO 不完整（0x%x），插帧关闭", st);
        return -1;
    }
    const char *gl_renderer = (const char *) glGetString(GL_RENDERER);
    const char *gl_version = (const char *) glGetString(GL_VERSION);
    LOGI("插帧资源就绪：%dx%d · GL_RENDERER=%s · GL_VERSION=%s", g.w, g.h,
         gl_renderer != NULL ? gl_renderer : "?", gl_version != NULL ? gl_version : "?");
    return 0;
}

/* 把纹理画到当前绑定的 framebuffer 上（全屏） */
static void draw_texture(GLuint tex) {
    glUseProgram(g.prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(g.loc_tex, 0);

    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    glEnableVertexAttribArray((GLuint) g.loc_pos);
    glVertexAttribPointer((GLuint) g.loc_pos, 2, GL_FLOAT, GL_FALSE, 0, 0);

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    glDisableVertexAttribArray((GLuint) g.loc_pos);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

/* ---------------- fclfg 后端回调 ---------------- */

/* 黑帧自检：第 30 帧抽样 4 个点，判断 FB0 里到底有没有画面。
   只跑一次——这是 GL4ES 内部 FBO 那个未知的现场判据（见集成设计 §3.2）。 */
static void black_frame_check(void) {
    if (g.black_checked) return;
    if (++g.frame_count < 30) return;
    g.black_checked = 1;

    unsigned char px[4] = {0, 0, 0, 0};
    const int w = g.w, h = g.h;
    int non_zero = 0;
    const int xs[2] = {w / 4, w * 3 / 4};
    const int ys[2] = {h / 4, h * 3 / 4};
    for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 2; j++) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glReadPixels(xs[i], ys[j], 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            if (px[0] || px[1] || px[2]) non_zero++;
        }
    }
    if (non_zero == 0) {
        LOGI("警告：FB0 抽样全黑——画面可能不在默认帧缓冲里。"
             "若画面确实黑，请给启动参数加 LIBGL_BLITFULLSCREEN=1");
    } else {
        LOGI("FB0 抽样正常（%d/4 非黑），采集路径有效", non_zero);
    }
}

/* 采集：把当前 FB0 拷进对应槽的纹理（GPU 侧拷贝） */
static int gl_capture(void *user, int slot) {
    (void) user;
    GLint prev_fbo = 0, prev_tex = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, g.tex[slot & 1]);
    glCopyTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 0, 0, g.w, g.h, 0);

    glBindTexture(GL_TEXTURE_2D, (GLuint) prev_tex);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint) prev_fbo);

    black_frame_check();
    return glGetError() == GL_NO_ERROR ? 0 : -1;
}

/* 呈现：把槽里的画面画进 FB0，然后 swap（阻塞到 vsync） */
static int gl_present(void *user, int slot) {
    (void) user;
    GLint prev_fbo = 0, prev_viewport[4] = {0};
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    glGetIntegerv(GL_VIEWPORT, prev_viewport);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, g.w, g.h);
    draw_texture(slot == FCLFG_SLOT_OUT ? g.tex_out : g.tex[slot & 1]);

    glViewport(prev_viewport[0], prev_viewport[1], prev_viewport[2], prev_viewport[3]);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint) prev_fbo);

    if (g.swap(g.dpy, g.surf) != EGL_TRUE) return -1;
    return 0;
}

/* 占位插值：帧重复（把 A 画进 OUT）。真插值由管线替换这里。 */
static int gl_interpolate(void *user, int slot_a, int slot_b, float t, int slot_out) {
    (void) user; (void) slot_b; (void) t; (void) slot_out;
    if (!g.stub_logged) {
        g.stub_logged = 1;
        LOGI("插帧管线未接入，当前为占位实现（帧重复）——只验证调度与呈现链路");
    }
    GLint prev_fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g.fbo_out);
    glViewport(0, 0, g.w, g.h);
    draw_texture(g.tex[slot_a & 1]);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint) prev_fbo);
    return glGetError() == GL_NO_ERROR ? 0 : -1;
}

static fclfg_backend_t g_backend = {
        .capture = gl_capture,
        .present = gl_present,
        .interpolate = gl_interpolate,
        .user = NULL,
};

int fclfg_gl_try_present(void *dpy, void *surf, void *swap_fn) {
    if (dpy == NULL || surf == NULL || swap_fn == NULL) return 0;
    if (g.state < 0) return 0;
    if (!fclfg_enabled()) return 0;

    /* 读配置那一刻就要判断：倍率 <=1 时 fclfg_frame 会直通，
       但直通也要由我们 swap，所以这里只做初始化与接管。 */
    const fclfg_stats_t *st = fclfg_stats();
    if (st != NULL && st->multiplier < 2 && !g.stub_logged) {
        /* 倍率无效时直接放行给原路径，避免多一次无意义调用 */
        return 0;
    }

    g.dpy = (EGLDisplay) dpy;
    g.surf = (EGLSurface) surf;
    g.swap = (eglSwapBuffers_fn) swap_fn;

    if (g.state == 0) {
        if (build_program() != 0) {
            g.state = -1;
            LOGI("初始化失败，插帧永久回退（游戏不受影响）");
            return 0;
        }
        g.state = 1;
    }
    if (ensure_size(g.dpy, g.surf) != 0) {
        g.state = -1;
        return 0;
    }

    const int presented = fclfg_frame(&g_backend, 0);
    if (presented <= 0) {
        /* 一帧都没呈现说明出了问题：把真实帧补上，避免黑屏 */
        if (gl_present(NULL, FCLFG_SLOT_B) != 0) g.state = -1;
        return 1;
    }
    return 1;
}

#else /* 非 arm64：本工程只在 arm64 链接 EGL/GLESv2，其它 ABI 直接放行 */

int fclfg_gl_try_present(void *dpy, void *surf, void *swap_fn) {
    (void) dpy; (void) surf; (void) swap_fn;
    return 0;
}

#endif
