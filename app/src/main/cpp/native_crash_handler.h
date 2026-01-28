#ifndef WEKIT_NATIVE_CRASH_HANDLER_H
#define WEKIT_NATIVE_CRASH_HANDLER_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 安装 Native 崩溃拦截器
 * @param env JNI 环境
 * @param crash_log_dir 崩溃日志目录路径
 * @return 是否安装成功
 */
jboolean install_native_crash_handler(JNIEnv* env, const char* crash_log_dir);

/**
 * 卸载 Native 崩溃拦截器
 */
void uninstall_native_crash_handler();

/**
 * 触发测试崩溃
 * @param crash_type 崩溃类型：
 *                   0 = SIGSEGV (空指针访问)
 *                   1 = SIGABRT (abort)
 *                   2 = SIGFPE (除零错误)
 *                   3 = SIGILL (非法指令)
 *                   4 = SIGBUS (总线错误)
 */
void trigger_test_crash(int crash_type);

#ifdef __cplusplus
}
#endif

#endif // WEKIT_NATIVE_CRASH_HANDLER_H
