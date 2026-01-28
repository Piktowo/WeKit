package moe.ouom.wekit.loader.core.hooks;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.TestLooperManager;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;

import moe.ouom.wekit.config.RuntimeConfig;
import moe.ouom.wekit.constants.PackageConstants;
import moe.ouom.wekit.util.common.ModuleRes;
import moe.ouom.wekit.util.log.WeLogger;

/**
 * Activity 占位 Hook 实现
 * 允许模块启动未在宿主 Manifest 中注册的 Activity
 */
public class ActivityProxyHooks {

    private static boolean __stub_hooked = false;

    public static class ActProxyMgr {
        public static final String ACTIVITY_PROXY_INTENT = "wekit_target_intent";

        // 宿主中的替身 Activity (Stub)
        // 这个 Activity 必须在微信的 AndroidManifest.xml 中真实存在
        public static final String STUB_DEFAULT_ACTIVITY = "com.tencent.mm.plugin.facedetect.ui.FaceTransparentStubUI";

        /**
         * 判断是否为模块内的 Activity (需要 Hook 的)
         */
        public static boolean isModuleProxyActivity(String className) {
            // 这里判断包名是否属于你的模块
            // 只要是以 moe.ouom.wekit 开头的 Activity 都走代理逻辑
            return className != null && className.startsWith("moe.ouom.wekit");
        }
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    public static void initForStubActivity(Context ctx) {
        if (__stub_hooked) {
            return;
        }
        try {
            // 获取 ActivityThread 实例
            Class<?> clazz_ActivityThread = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = clazz_ActivityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object sCurrentActivityThread = currentActivityThread.invoke(null);

            // Hook Instrumentation
            Field mInstrumentation = clazz_ActivityThread.getDeclaredField("mInstrumentation");
            mInstrumentation.setAccessible(true);
            Instrumentation instrumentation = (Instrumentation) mInstrumentation.get(sCurrentActivityThread);
            if (!(instrumentation instanceof ProxyInstrumentation)) {
                mInstrumentation.set(sCurrentActivityThread, new ProxyInstrumentation(instrumentation));
            }

            // Hook Handler (mH)
            Field field_mH = clazz_ActivityThread.getDeclaredField("mH");
            field_mH.setAccessible(true);
            Handler oriHandler = (Handler) field_mH.get(sCurrentActivityThread);
            Field field_mCallback = Handler.class.getDeclaredField("mCallback");
            field_mCallback.setAccessible(true);
            Handler.Callback current = (Handler.Callback) field_mCallback.get(oriHandler);
            if (current == null || !current.getClass().getName().equals(ProxyHandlerCallback.class.getName())) {
                field_mCallback.set(oriHandler, new ProxyHandlerCallback(current));
            }

            // Hook AMS (IActivityManager / IActivityTaskManager)
            hookIActivityManager();

            // Hook PackageManager
            hookPackageManager(ctx, sCurrentActivityThread, clazz_ActivityThread);

            __stub_hooked = true;
            WeLogger.i("ActivityProxyHooks", "Activity Proxy Hooks installed successfully.");
        } catch (Exception e) {
            WeLogger.e("ActivityProxyHooks", "Failed to init stub activity hooks", e);
        }
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static void hookIActivityManager() throws Exception {
        Class<?> activityManagerClass;
        Field gDefaultField;
        // 兼容 Android 8.0 以前和以后的获取方式
        try {
            activityManagerClass = Class.forName("android.app.ActivityManagerNative");
            gDefaultField = activityManagerClass.getDeclaredField("gDefault");
        } catch (Exception err1) {
            activityManagerClass = Class.forName("android.app.ActivityManager");
            gDefaultField = activityManagerClass.getDeclaredField("IActivityManagerSingleton");
        }
        gDefaultField.setAccessible(true);
        Object gDefault = gDefaultField.get(null);

        Class<?> singletonClass = Class.forName("android.util.Singleton");
        Field mInstanceField = singletonClass.getDeclaredField("mInstance");
        mInstanceField.setAccessible(true);
        Object mInstance = mInstanceField.get(gDefault);

        // 创建 IActivityManager 代理
        Object amProxy = Proxy.newProxyInstance(
                ActivityProxyHooks.class.getClassLoader(),
                new Class[]{Class.forName("android.app.IActivityManager")},
                new IActivityManagerHandler(mInstance));
        mInstanceField.set(gDefault, amProxy);

        // 兼容 Android 10+ (Q) 的 ActivityTaskManager
        try {
            Class<?> activityTaskManagerClass = Class.forName("android.app.ActivityTaskManager");
            Field fIActivityTaskManagerSingleton = activityTaskManagerClass.getDeclaredField("IActivityTaskManagerSingleton");
            fIActivityTaskManagerSingleton.setAccessible(true);
            Object singleton = fIActivityTaskManagerSingleton.get(null);

            // 触发 Singleton 加载
            singletonClass.getMethod("get").invoke(singleton);

            Object mDefaultTaskMgr = mInstanceField.get(singleton);
            Object proxy2 = Proxy.newProxyInstance(
                    ActivityProxyHooks.class.getClassLoader(),
                    new Class[]{Class.forName("android.app.IActivityTaskManager")},
                    new IActivityManagerHandler(mDefaultTaskMgr));
            mInstanceField.set(singleton, proxy2);
        } catch (Exception ignored) {
            // Android 9 及以下没有这个类，忽略
        }
    }

    private static void hookPackageManager(Context ctx, Object sCurrentActivityThread, Class<?> clazz_ActivityThread) {
        try {
            Field sPackageManagerField = clazz_ActivityThread.getDeclaredField("sPackageManager");
            sPackageManagerField.setAccessible(true);
            Object packageManagerImpl = sPackageManagerField.get(sCurrentActivityThread);

            Class<?> iPackageManagerInterface = Class.forName("android.content.pm.IPackageManager");

            // 既替换 ActivityThread 中的缓存，也替换 Application Context 中的缓存
            PackageManager pm = ctx.getPackageManager();
            Field mPmField = pm.getClass().getDeclaredField("mPM");
            mPmField.setAccessible(true);

            Object pmProxy = Proxy.newProxyInstance(
                    iPackageManagerInterface.getClassLoader(),
                    new Class[]{iPackageManagerInterface},
                    new PackageManagerInvocationHandler(packageManagerImpl));

            sPackageManagerField.set(sCurrentActivityThread, pmProxy);
            mPmField.set(pm, pmProxy);
        } catch (Exception e) {
            WeLogger.e("ActivityProxyHooks", "Failed to hook PackageManager (Non-fatal)", e);
        }
    }

    /**
     * AMS 动态代理：拦截 startActivity，将目标 Intent 替换为 Stub Activity
     */
    public static class IActivityManagerHandler implements InvocationHandler {
        private final Object mOrigin;

        public IActivityManagerHandler(Object origin) {
            mOrigin = origin;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("startActivity".equals(method.getName())) {
                int index = -1;
                // 寻找参数中的 Intent
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Intent) {
                        index = i;
                        break;
                    }
                }

                if (index != -1) {
                    Intent raw = (Intent) args[index];
                    ComponentName component = raw.getComponent();

                    // 如果是模块的 Activity，进行拦截
                    if (component != null && ActProxyMgr.isModuleProxyActivity(component.getClassName())) {
                        Intent wrapper = new Intent();
                        // 指向宿主中真实存在的 Activity (Stub)
                        wrapper.setClassName(component.getPackageName(), ActProxyMgr.STUB_DEFAULT_ACTIVITY);
                        // 保存原始 Intent
                        wrapper.putExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT, raw);

                        // 替换参数
                        args[index] = wrapper;
                        WeLogger.d("ActivityProxyHooks", "Hijacked startActivity: " + component.getClassName() + " -> " + ActProxyMgr.STUB_DEFAULT_ACTIVITY);
                    }
                }
            }
            try {
                return method.invoke(mOrigin, args);
            } catch (InvocationTargetException ite) {
                throw ite.getTargetException();
            }
        }
    }

    /**
     * Handler 代理：在 Activity 启动消息处理前，将 Intent 还原
     */
    public static class ProxyHandlerCallback implements Handler.Callback {
        private final Handler.Callback mNextCallbackHook;

        public ProxyHandlerCallback(Handler.Callback next) {
            mNextCallbackHook = next;
        }

        @Override
        public boolean handleMessage(Message msg) {
            // LAUNCH_ACTIVITY (Android < 9.0)
            if (msg.what == 100) {
                handleLaunchActivity(msg);
            }
            // EXECUTE_TRANSACTION (Android >= 9.0)
            else if (msg.what == 159) {
                handleExecuteTransaction(msg);
            }

            if (mNextCallbackHook != null) {
                return mNextCallbackHook.handleMessage(msg);
            }
            return false;
        }

        private void handleLaunchActivity(Message msg) {
            try {
                Object record = msg.obj;
                Field intentField = record.getClass().getDeclaredField("intent");
                intentField.setAccessible(true);
                Intent intent = (Intent) intentField.get(record);
                if (intent != null && intent.hasExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT)) {
                    Intent realIntent = intent.getParcelableExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT);
                    if (realIntent != null) {
                        // 还原为真实的 Module Intent
                        intentField.set(record, realIntent);
                    }
                }
            } catch (Exception e) {
                WeLogger.e("ActivityProxyHooks", "handleLaunchActivity error", e);
            }
        }

        private void handleExecuteTransaction(Message msg) {
            try {
                Object transaction = msg.obj;
                Method getCallbacks = transaction.getClass().getDeclaredMethod("getCallbacks");
                getCallbacks.setAccessible(true);
                List<?> callbacks = (List<?>) getCallbacks.invoke(transaction);
                if (callbacks != null) {
                    for (Object item : callbacks) {
                        if (item.getClass().getName().contains("LaunchActivityItem")) {
                            Field intentField = item.getClass().getDeclaredField("mIntent");
                            intentField.setAccessible(true);
                            Intent intent = (Intent) intentField.get(item);
                            if (intent != null && intent.hasExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT)) {
                                Intent realIntent = intent.getParcelableExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT);
                                if (realIntent != null) {
                                    // 还原为真实的 Module Intent
                                    intentField.set(item, realIntent);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                WeLogger.e("ActivityProxyHooks", "handleExecuteTransaction error", e);
            }
        }
    }

    /**
     * Instrumentation 代理：负责实例化 Activity 和注入资源
     */
    @SuppressLint("NewApi")
    public static class ProxyInstrumentation extends Instrumentation {
        private final Instrumentation mBase;

        public ProxyInstrumentation(Instrumentation base) {
            mBase = base;
        }

        /**
         * 实例化 Activity
         * 如果系统 ClassLoader 找不到类，则尝试使用模块 ClassLoader 加载
         */
        @Override
        public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            try {
                return mBase.newActivity(cl, className, intent);
            } catch (ClassNotFoundException | InstantiationException e) {
                if (ActProxyMgr.isModuleProxyActivity(className)) {
                    // 使用模块的 ClassLoader
                    return (Activity) Objects.requireNonNull(ActivityProxyHooks.class.getClassLoader()).loadClass(className).newInstance();
                }
                throw e;
            }
        }

        // 兼容新版 Android 的 newActivity 重载
        @Override
        public Activity newActivity(Class<?> clazz, Context context, IBinder token, Application application, Intent intent, ActivityInfo info, CharSequence title, Activity parent, String id, Object lastNonConfigurationInstance) throws InstantiationException, IllegalAccessException {
            return mBase.newActivity(clazz, context, token, application, intent, info, title, parent, id, lastNonConfigurationInstance);
        }

        /**
         * 关键重写：Activity 创建时注入资源
         */
        @Override
        public void callActivityOnCreate(Activity activity, Bundle icicle) {
            checkAndInjectResources(activity);
            mBase.callActivityOnCreate(activity, icicle);
        }

        @Override
        public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
            checkAndInjectResources(activity);
            mBase.callActivityOnCreate(activity, icicle, persistentState);
        }

        private void checkAndInjectResources(Activity activity) {
            if (ActProxyMgr.isModuleProxyActivity(activity.getClass().getName())) {
                // 注入模块资源，防止资源 ID 找不到导致 Crash
                ModuleRes.init(activity, PackageConstants.PACKAGE_NAME_SELF);
            }
        }

        // 以下为 Instrumentation 的完整委托实现 //

        @Override public void onCreate(Bundle arguments) { mBase.onCreate(arguments); }
        @Override public void start() { mBase.start(); }
        @Override public void onStart() { mBase.onStart(); }
        @Override public boolean onException(Object obj, Throwable e) { return mBase.onException(obj, e); }
        @Override public void sendStatus(int resultCode, Bundle results) { mBase.sendStatus(resultCode, results); }
        @Override public void addResults(Bundle results) { mBase.addResults(results); }
        @Override public void finish(int resultCode, Bundle results) { mBase.finish(resultCode, results); }
        @Override public void setAutomaticPerformanceSnapshots() { mBase.setAutomaticPerformanceSnapshots(); }
        @Override public void startPerformanceSnapshot() { mBase.startPerformanceSnapshot(); }
        @Override public void endPerformanceSnapshot() { mBase.endPerformanceSnapshot(); }
        @Override public void onDestroy() { mBase.onDestroy(); }
        @Override public Context getContext() { return mBase.getContext(); }
        @Override public ComponentName getComponentName() { return mBase.getComponentName(); }
        @Override public Context getTargetContext() { return mBase.getTargetContext(); }
        @Override public String getProcessName() { return mBase.getProcessName(); }
        @Override public boolean isProfiling() { return mBase.isProfiling(); }
        @Override public void startProfiling() { mBase.startProfiling(); }
        @Override public void stopProfiling() { mBase.stopProfiling(); }
        @Override public void setInTouchMode(boolean inTouch) { mBase.setInTouchMode(inTouch); }
        @Override public void waitForIdle(Runnable recipient) { mBase.waitForIdle(recipient); }
        @Override public void waitForIdleSync() { mBase.waitForIdleSync(); }
        @Override public void runOnMainSync(Runnable runner) { mBase.runOnMainSync(runner); }
        @Override public Activity startActivitySync(Intent intent) { return mBase.startActivitySync(intent); }
        @NonNull
        @Override public Activity startActivitySync(@NonNull Intent intent, Bundle options) { return mBase.startActivitySync(intent, options); }
        @Override public void addMonitor(ActivityMonitor monitor) { mBase.addMonitor(monitor); }
        @Override public ActivityMonitor addMonitor(IntentFilter filter, ActivityResult result, boolean block) { return mBase.addMonitor(filter, result, block); }
        @Override public ActivityMonitor addMonitor(String cls, ActivityResult result, boolean block) { return mBase.addMonitor(cls, result, block); }
        @Override public boolean checkMonitorHit(ActivityMonitor monitor, int minHits) { return mBase.checkMonitorHit(monitor, minHits); }
        @Override public Activity waitForMonitor(ActivityMonitor monitor) { return mBase.waitForMonitor(monitor); }
        @Override public Activity waitForMonitorWithTimeout(ActivityMonitor monitor, long timeOut) { return mBase.waitForMonitorWithTimeout(monitor, timeOut); }
        @Override public void removeMonitor(ActivityMonitor monitor) { mBase.removeMonitor(monitor); }
        @Override public boolean invokeMenuActionSync(Activity targetActivity, int id, int flag) { return mBase.invokeMenuActionSync(targetActivity, id, flag); }
        @Override public boolean invokeContextMenuAction(Activity targetActivity, int id, int flag) { return mBase.invokeContextMenuAction(targetActivity, id, flag); }
        @Override public void sendStringSync(String text) { mBase.sendStringSync(text); }
        @Override public void sendKeySync(KeyEvent event) { mBase.sendKeySync(event); }
        @Override public void sendKeyDownUpSync(int key) { mBase.sendKeyDownUpSync(key); }
        @Override public void sendCharacterSync(int keyCode) { mBase.sendCharacterSync(keyCode); }
        @Override public void sendPointerSync(MotionEvent event) { mBase.sendPointerSync(event); }
        @Override public void sendTrackballEventSync(MotionEvent event) { mBase.sendTrackballEventSync(event); }
        @Override public Application newApplication(ClassLoader cl, String className, Context context) throws ClassNotFoundException, IllegalAccessException, InstantiationException { return mBase.newApplication(cl, className, context); }
        @Override public void callApplicationOnCreate(Application app) { mBase.callApplicationOnCreate(app); }
        @Override public void callActivityOnDestroy(Activity activity) { mBase.callActivityOnDestroy(activity); }
        @Override public void callActivityOnRestoreInstanceState(@NonNull Activity activity, @NonNull Bundle savedInstanceState) { mBase.callActivityOnRestoreInstanceState(activity, savedInstanceState); }
        @Override public void callActivityOnRestoreInstanceState(@NonNull Activity activity, Bundle savedInstanceState, PersistableBundle persistentState) { mBase.callActivityOnRestoreInstanceState(activity, savedInstanceState, persistentState); }
        @Override public void callActivityOnPostCreate(@NonNull Activity activity, Bundle savedInstanceState) { mBase.callActivityOnPostCreate(activity, savedInstanceState); }
        @Override public void callActivityOnPostCreate(@NonNull Activity activity, @Nullable Bundle savedInstanceState, @Nullable PersistableBundle persistentState) { mBase.callActivityOnPostCreate(activity, savedInstanceState, persistentState); }
        @Override public void callActivityOnNewIntent(Activity activity, Intent intent) { mBase.callActivityOnNewIntent(activity, intent); }
        @Override public void callActivityOnStart(Activity activity) { mBase.callActivityOnStart(activity); }
        @Override public void callActivityOnRestart(Activity activity) { mBase.callActivityOnRestart(activity); }
        @Override public void callActivityOnResume(Activity activity) { mBase.callActivityOnResume(activity); }
        @Override public void callActivityOnStop(Activity activity) { mBase.callActivityOnStop(activity); }
        @Override public void callActivityOnSaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { mBase.callActivityOnSaveInstanceState(activity, outState); }
        @Override public void callActivityOnSaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState, PersistableBundle outPersistentState) { mBase.callActivityOnSaveInstanceState(activity, outState, outPersistentState); }
        @Override public void callActivityOnPause(Activity activity) { mBase.callActivityOnPause(activity); }
        @Override public void callActivityOnUserLeaving(Activity activity) { mBase.callActivityOnUserLeaving(activity); }
        @Override public void startAllocCounting() { mBase.startAllocCounting(); }
        @Override public void stopAllocCounting() { mBase.stopAllocCounting(); }
        @Override public Bundle getAllocCounts() { return mBase.getAllocCounts(); }
        @Override public Bundle getBinderCounts() { return mBase.getBinderCounts(); }
        @Override public UiAutomation getUiAutomation() { return mBase.getUiAutomation(); }
        @Override public UiAutomation getUiAutomation(int flags) { return mBase.getUiAutomation(flags); }
        @Override public TestLooperManager acquireLooperManager(Looper looper) { return mBase.acquireLooperManager(looper); }
    }

    /**
     * PackageManager 代理：拦截 getActivityInfo，为模块 Activity 返回伪造的 ActivityInfo
     */
    public static class PackageManagerInvocationHandler implements InvocationHandler {
        private final Object mTarget;

        public PackageManagerInvocationHandler(Object target) {
            if (target == null) throw new NullPointerException("IPackageManager is null");
            mTarget = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getActivityInfo".equals(method.getName())) {
                ComponentName component = (ComponentName) args[0];
                int flags = 0;
                if (args[1] instanceof Number) {
                    flags = ((Number) args[1]).intValue();
                }

                if (component != null && ActProxyMgr.isModuleProxyActivity(component.getClassName())) {
                    // 构造并返回伪造的 ActivityInfo
                    return CounterfeitActivityInfoFactory.makeProxyActivityInfo(component.getClassName(), flags);
                }
            }
            try {
                return method.invoke(mTarget, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }

    /**
     * 工厂类：生成伪造的 ActivityInfo
     */
    public static class CounterfeitActivityInfoFactory {
        public static ActivityInfo makeProxyActivityInfo(String className, int flags) {
            ActivityInfo ai = new ActivityInfo();
            ai.name = className;
            ai.packageName = PackageConstants.PACKAGE_NAME_WECHAT; // 必须假装是宿主的包名
            ai.enabled = true;
            ai.exported = false;
            ai.processName = PackageConstants.PACKAGE_NAME_WECHAT;

            // 复制宿主的 ApplicationInfo
            try {
                ai.applicationInfo = RuntimeConfig.getHostApplicationInfo();
                if (ai.applicationInfo == null) {
                    // Fallback
                    ai.applicationInfo = new ApplicationInfo();
                    ai.applicationInfo.packageName = PackageConstants.PACKAGE_NAME_WECHAT;
                }
            } catch (Exception e) {
                ai.applicationInfo = new ApplicationInfo();
            }

            ai.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            return ai;
        }
    }
}