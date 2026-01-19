package moe.ouom.wekit.ui.creator.dialog

import android.content.Context
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.util.common.Utils.jumpUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainSettingsDialog(context: Context) : BaseRikkaDialog(context, "WeKit") {

    // 定义优先级 映射关系 (值 -> 显示文本)
    private val priorityMap = mapOf(
        10000 to "高优先级",
        50 to "智能",
        -10000 to "低优先级"
    )

    override fun initPreferences() {
        addCategory("设定")
        val categories = listOf(
            "聊天与消息" to "ic_twotone_message_24",
            "资料卡" to "ic_profile",
            "优化与修复" to "ic_baseline_auto_fix_high_24",
            "开发者选项" to "ic_baseline_developer_mode_24",
            "娱乐功能" to "ic_baseline_free_breakfast_24"
        )
        categories.forEach { (name, iconName) ->
            addPreference(title = name, iconName = iconName,
                onClick = { anchor, summaryView ->
                    CategorySettingsDialog(context, name).show()
                })
        }

        addCategory("调试")
        addSwitchPreference(
            key = Constants.PrekEnableLog,
            title = "日志记录",
            summary = "反馈问题前必须开启日志记录",
            iconName = "ic_baseline_border_color_24",
            useFullKey = true
        )

        // ==========================================
        // 兼容 (Compatibility)
        // ==========================================
        addCategory("兼容")

        // 使用 addSelectPreference 替代手动实现
        val priorityKey = "${Constants.PrekCfgXXX}wekit_hook_priority"

        addSelectPreference(
            key = priorityKey,
            title = "XC_MethodHook 优先级",
            summary = "当前设定", // 当配置的值不在 map 中时，会显示 "当前设定: [值]"
            options = priorityMap,
            defaultValue = 50,
            iconName = "ic_outline_alt_route_24",
            useFullKey = true // 因为 key 已经包含了前缀 PrekCfgXXX，所以必须设为 true
        )

        // ==========================================
        // 关于 (About)
        // ==========================================
        addCategory("关于")
        addPreference(title = "版本", summary = BuildConfig.VERSION_NAME)

        val buildTimeStr = try {
            val date = Date(BuildConfig.BUILD_TIMESTAMP)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
        } catch (_: Exception) { "N/A" }
        addPreference(title = "编译时间", summary = buildTimeStr)
        addPreference("Build UUID", BuildConfig.BUILD_UUID)

        addPreference(
            title = "GitHub",
            summary = "cwuom/wekit",
            iconName = "ic_github",
            onClick = { anchor, summaryView -> jumpUrl(context, "https://github.com/cwuom/wekit") }
        )
        addPreference(
            title = "Telegram",
            summary = "@ouom_pub",
            iconName = "ic_telegram",
            onClick = { anchor, summaryView -> jumpUrl(context, "https://t.me/ouom_pub") }
        )
    }
}