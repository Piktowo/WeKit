#include "native_crash_handler.h"
#include <csignal>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <cstring>
#include <ctime>
#include <cstdio>
#include <cstdlib>
#include <android/log.h>
#include <unwind.h>
#include <dlfcn.h>
#include <ucontext.h>

#define LOG_TAG "NativeCrashHandler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局变量
static char g_crash_log_dir[512] = {0};
static struct sigaction g_old_handlers[32];
static bool g_handler_installed = false;
static volatile bool g_is_handling_crash = false;

// 需要捕获的信号
static const int SIGNALS_TO_CATCH[] = {
    SIGSEGV,  // 段错误
    SIGABRT,  // abort()
    SIGFPE,   // 浮点异常
    SIGILL,   // 非法指令
    SIGBUS,   // 总线错误
    SIGTRAP,  // 跟踪陷阱
};

static const int SIGNAL_COUNT = sizeof(SIGNALS_TO_CATCH) / sizeof(SIGNALS_TO_CATCH[0]);

// 信号名称映射
static const char* get_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGFPE: return "SIGFPE";
        case SIGILL: return "SIGILL";
        case SIGBUS: return "SIGBUS";
        case SIGTRAP: return "SIGTRAP";
        default: return "UNKNOWN";
    }
}

// 信号描述映射
static const char* get_signal_description(int sig) {
    switch (sig) {
        case SIGSEGV: return "Segmentation fault (invalid memory access)";
        case SIGABRT: return "Abort signal (abnormal termination)";
        case SIGFPE: return "Floating point exception (division by zero, etc.)";
        case SIGILL: return "Illegal instruction";
        case SIGBUS: return "Bus error (invalid memory alignment)";
        case SIGTRAP: return "Trace/breakpoint trap";
        default: return "Unknown signal";
    }
}

// 堆栈回溯结构
struct BacktraceState {
    void** frames;
    size_t frame_count;
    size_t max_frames;
};

// 堆栈回溯回调
static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    BacktraceState* state = static_cast<BacktraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(context);

    if (pc && state->frame_count < state->max_frames) {
        state->frames[state->frame_count++] = reinterpret_cast<void*>(pc);
    }

    return _URC_NO_REASON;
}

// 获取堆栈回溯
static size_t capture_backtrace(void** frames, size_t max_frames) {
    BacktraceState state;
    state.frames = frames;
    state.frame_count = 0;
    state.max_frames = max_frames;

    _Unwind_Backtrace(unwind_callback, &state);

    return state.frame_count;
}

// 格式化时间
static void format_time(char* buffer, size_t size) {
    time_t now = time(nullptr);
    struct tm* tm_info = localtime(&now);
    strftime(buffer, size, "%Y-%m-%d %H:%M:%S", tm_info);
}

// 写入崩溃日志
static void write_crash_log(int sig, siginfo_t* info, void* context) {
    // 防止递归崩溃
    if (g_is_handling_crash) {
        LOGE("Recursive crash detected, aborting");
        _exit(1);
    }
    g_is_handling_crash = true;

    // 生成日志文件名
    char filename[256];
    time_t now = time(nullptr);
    struct tm* tm_info = localtime(&now);
    snprintf(filename, sizeof(filename), "%s/crash_%04d%02d%02d_%02d%02d%02d_%03d.log",
             g_crash_log_dir,
             tm_info->tm_year + 1900, tm_info->tm_mon + 1, tm_info->tm_mday,
             tm_info->tm_hour, tm_info->tm_min, tm_info->tm_sec,
             (int)(now % 1000));

    // 打开日志文件
    int fd = open(filename, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        LOGE("Failed to create crash log file: %s", filename);
        return;
    }

    char buffer[4096];
    int len;

    // 写入崩溃时间
    char time_str[64];
    format_time(time_str, sizeof(time_str));
    len = snprintf(buffer, sizeof(buffer),
                   "========================================\n"
                   "WeKit Native Crash Report\n"
                   "========================================\n\n"
                   "Crash Time: %s\n"
                   "Crash Type: NATIVE\n\n", time_str);
    write(fd, buffer, len);

    // 写入信号信息
    len = snprintf(buffer, sizeof(buffer),
                   "========================================\n"
                   "Signal Information\n"
                   "========================================\n"
                   "Signal: %d (%s)\n"
                   "Description: %s\n"
                   "Signal Code: %d\n"
                   "Fault Address: %p\n\n",
                   sig, get_signal_name(sig), get_signal_description(sig),
                   info->si_code, info->si_addr);
    write(fd, buffer, len);

    // 写入寄存器信息
    if (context) {
        ucontext_t* uc = static_cast<ucontext_t*>(context);
        len = snprintf(buffer, sizeof(buffer),
                       "========================================\n"
                       "Register State\n"
                       "========================================\n");
        write(fd, buffer, len);

#if defined(__aarch64__)
        // ARM64 寄存器
        for (int i = 0; i < 31; i++) {
            len = snprintf(buffer, sizeof(buffer), "x%-2d: %016llx\n",
                          i, (unsigned long long)uc->uc_mcontext.regs[i]);
            write(fd, buffer, len);
        }
        len = snprintf(buffer, sizeof(buffer),
                      "sp:  %016llx\n"
                      "pc:  %016llx\n\n",
                      (unsigned long long)uc->uc_mcontext.sp,
                      (unsigned long long)uc->uc_mcontext.pc);
        write(fd, buffer, len);
#elif defined(__arm__)
        // ARM32 寄存器
        for (int i = 0; i < 13; i++) {
            len = snprintf(buffer, sizeof(buffer), "r%-2d: %08lx\n",
                          i, (unsigned long)uc->uc_mcontext.arm_r0 + i * sizeof(long));
            write(fd, buffer, len);
        }
        len = snprintf(buffer, sizeof(buffer),
                      "sp:  %08lx\n"
                      "lr:  %08lx\n"
                      "pc:  %08lx\n\n",
                      (unsigned long)uc->uc_mcontext.arm_sp,
                      (unsigned long)uc->uc_mcontext.arm_lr,
                      (unsigned long)uc->uc_mcontext.arm_pc);
        write(fd, buffer, len);
#endif
    }

    // 写入堆栈回溯
    len = snprintf(buffer, sizeof(buffer),
                   "========================================\n"
                   "Stack Trace\n"
                   "========================================\n");
    write(fd, buffer, len);

    void* frames[128];
    size_t frame_count = capture_backtrace(frames, 128);

    for (size_t i = 0; i < frame_count; i++) {
        Dl_info info;
        if (dladdr(frames[i], &info)) {
            const char* symbol = info.dli_sname ? info.dli_sname : "<unknown>";
            const char* fname = info.dli_fname ? info.dli_fname : "<unknown>";
            uintptr_t offset = (uintptr_t)frames[i] - (uintptr_t)info.dli_saddr;

            len = snprintf(buffer, sizeof(buffer),
                          "#%02zu pc %p  %s (%s+%zu)\n",
                          i, frames[i], fname, symbol, offset);
        } else {
            len = snprintf(buffer, sizeof(buffer),
                          "#%02zu pc %p  <unknown>\n",
                          i, frames[i]);
        }
        write(fd, buffer, len);
    }

    // 写入内存映射信息
    len = snprintf(buffer, sizeof(buffer),
                   "\n========================================\n"
                   "Memory Maps\n"
                   "========================================\n");
    write(fd, buffer, len);

    // 读取 /proc/self/maps
    int maps_fd = open("/proc/self/maps", O_RDONLY);
    if (maps_fd >= 0) {
        char maps_buffer[8192];
        ssize_t n;
        while ((n = read(maps_fd, maps_buffer, sizeof(maps_buffer))) > 0) {
            write(fd, maps_buffer, n);
        }
        close(maps_fd);
    }

    // 写入结束标记
    len = snprintf(buffer, sizeof(buffer),
                   "\n========================================\n"
                   "End of Crash Report\n"
                   "========================================\n");
    write(fd, buffer, len);

    close(fd);

    // 创建待处理标记文件
    char flag_file[512];
    snprintf(flag_file, sizeof(flag_file), "%s/pending_crash.flag", g_crash_log_dir);
    int flag_fd = open(flag_file, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (flag_fd >= 0) {
        const char* log_filename = strrchr(filename, '/');
        if (log_filename) {
            log_filename++; // 跳过 '/'
            write(flag_fd, log_filename, strlen(log_filename));
        }
        close(flag_fd);
    }

    LOGI("Native crash log saved to: %s", filename);
}

// 信号处理器
static void signal_handler(int sig, siginfo_t* info, void* context) {
    LOGE("========================================");
    LOGE("!!! Native crash detected !!!");
    LOGE("Signal: %d (%s)", sig, get_signal_name(sig));
    LOGE("Description: %s", get_signal_description(sig));
    LOGE("Fault Address: %p", info->si_addr);
    LOGE("========================================");

    // 写入崩溃日志
    write_crash_log(sig, info, context);

    LOGI("Crash log written, calling original handler...");

    // 调用原始处理器
    struct sigaction* old_handler = &g_old_handlers[sig];
    if (old_handler->sa_flags & SA_SIGINFO) {
        if (old_handler->sa_sigaction) {
            LOGI("Calling original sa_sigaction handler");
            old_handler->sa_sigaction(sig, info, context);
        }
    } else {
        if (old_handler->sa_handler &&
            old_handler->sa_handler != SIG_DFL &&
            old_handler->sa_handler != SIG_IGN) {
            LOGI("Calling original sa_handler");
            old_handler->sa_handler(sig);
        }
    }

    // 如果没有原始处理器，恢复默认行为并重新触发
    LOGI("Restoring default signal handler and re-raising signal");
    signal(sig, SIG_DFL);
    raise(sig);
}

// 安装崩溃拦截器
jboolean install_native_crash_handler(JNIEnv* env, const char* crash_log_dir) {
    if (g_handler_installed) {
        LOGI("Native crash handler already installed");
        return JNI_TRUE;
    }

    if (!crash_log_dir || strlen(crash_log_dir) == 0) {
        LOGE("Invalid crash log directory");
        return JNI_FALSE;
    }

    // 保存日志目录
    strncpy(g_crash_log_dir, crash_log_dir, sizeof(g_crash_log_dir) - 1);
    g_crash_log_dir[sizeof(g_crash_log_dir) - 1] = '\0';

    // 确保目录存在
    mkdir(g_crash_log_dir, 0755);

    // 设置信号处理器
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sa.sa_sigaction = signal_handler;

    bool success = true;
    for (int i = 0; i < SIGNAL_COUNT; i++) {
        int sig = SIGNALS_TO_CATCH[i];
        if (sigaction(sig, &sa, &g_old_handlers[sig]) != 0) {
            LOGE("Failed to install handler for signal %d (%s)", sig, get_signal_name(sig));
            success = false;
        } else {
            LOGI("Installed handler for signal %d (%s)", sig, get_signal_name(sig));
        }
    }

    if (success) {
        g_handler_installed = true;
        LOGI("Native crash handler installed successfully");
    }

    return success ? JNI_TRUE : JNI_FALSE;
}

// 卸载崩溃拦截器
void uninstall_native_crash_handler() {
    if (!g_handler_installed) {
        return;
    }

    for (int i = 0; i < SIGNAL_COUNT; i++) {
        int sig = SIGNALS_TO_CATCH[i];
        sigaction(sig, &g_old_handlers[sig], nullptr);
    }

    g_handler_installed = false;
    LOGI("Native crash handler uninstalled");
}

// 触发测试崩溃
void trigger_test_crash(int crash_type) {
    LOGI("========================================");
    LOGI("Triggering test crash: type=%d", crash_type);
    LOGI("Handler installed: %s", g_handler_installed ? "YES" : "NO");
    LOGI("Crash log dir: %s", g_crash_log_dir);
    LOGI("========================================");

    switch (crash_type) {
        case 0: { // SIGSEGV - 空指针访问
            LOGI("Triggering SIGSEGV (null pointer dereference)...");
            volatile int* null_ptr = nullptr;
            *null_ptr = 42;
            break;
        }
        case 1: { // SIGABRT
            LOGI("Triggering SIGABRT (abort)...");
            abort();
            break;
        }
        case 2: { // SIGFPE - 除零错误
            LOGI("Triggering SIGFPE (division by zero)...");
            volatile int zero = 0;
            volatile int result = 42 / zero;
            (void)result;
            break;
        }
        case 3: { // SIGILL - 非法指令
            LOGI("Triggering SIGILL (illegal instruction)...");
#if defined(__aarch64__) || defined(__arm__)
            __asm__ volatile(".word 0xf7f0a000"); // 非法指令
#else
            __asm__ volatile("ud2"); // x86/x64 非法指令
#endif
            break;
        }
        case 4: { // SIGBUS - 总线错误（未对齐访问）
            LOGI("Triggering SIGBUS (bus error)...");
            char buffer[10];
            volatile long* unaligned = (long*)(buffer + 1);
            *unaligned = 0x1234567890ABCDEF;
            break;
        }
        default:
            LOGE("Unknown crash type: %d", crash_type);
            break;
    }

    // 如果执行到这里，说明崩溃没有发生
    LOGE("Test crash did not occur! This should not happen.");
}
