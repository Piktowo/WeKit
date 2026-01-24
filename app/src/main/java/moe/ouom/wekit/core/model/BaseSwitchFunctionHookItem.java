package moe.ouom.wekit.core.model;

import android.view.View;

import androidx.annotation.NonNull;

import de.robv.android.xposed.XC_MethodHook;
import moe.ouom.wekit.config.ConfigManager;
import moe.ouom.wekit.constants.Constants;
import moe.ouom.wekit.loader.startup.HybridClassLoader;
import moe.ouom.wekit.util.common.SyncUtils;
import moe.ouom.wekit.util.log.WeLogger;

public abstract class BaseSwitchFunctionHookItem extends BaseHookItem {

    private boolean enabled;
    private final int targetProcess = targetProcess();
    private boolean isLoaded = false;


    public View.OnClickListener getOnClickListener() {
        return null;
    }

    /**
     * 目标进程
     */
    public int targetProcess() {
        return SyncUtils.PROC_MAIN;
    }


    public int getTargetProcess() {
        return targetProcess;
    }


    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;

        this.enabled = enabled;
        if (!enabled) {
            if (isLoaded) {
                WeLogger.i("[BaseSwitchFunctionHookItem] Unloading HookItem: " + getPath());
                try {
                    this.unload(HybridClassLoader.getHostClassLoader());
                    isLoaded = false;
                } catch (Throwable e) {
                    WeLogger.e("[BaseSwitchFunctionHookItem] Unload HookItem Failed", e);
                }
            }
        } else {
            WeLogger.i("[BaseSwitchFunctionHookItem] Loading HookItem: " + getPath());
            this.startLoad();
            isLoaded = true;
        }
    }

    protected final void tryExecute(@NonNull XC_MethodHook.MethodHookParam param, @NonNull HookAction hookAction) {
        if (isEnabled()) {
            super.tryExecute(param, hookAction);
            WeLogger.i("[BaseSwitchFunctionHookItem] Loading HookItem: " + getPath());
        }
    }

    public boolean configIsEnable() {
        return ConfigManager.getDefaultConfig().getBooleanOrFalse(Constants.PrekXXX+this.getPath());
    }

}
