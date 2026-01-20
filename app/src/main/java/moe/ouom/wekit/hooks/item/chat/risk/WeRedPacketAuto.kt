package moe.ouom.wekit.hooks.item.chat.risk

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.core.net.toUri
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.config.ConfigManager
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.constants.Constants.Companion.TYPE_LUCKY_MONEY_B
import moe.ouom.wekit.constants.Constants.Companion.TYPE_LUCKY_MONEY_C
import moe.ouom.wekit.dexkit.TargetManager
import moe.ouom.wekit.hooks._base.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks._core.annotation.HookItem
import moe.ouom.wekit.ui.creator.dialog.item.WeRedPacketConfigDialog
import moe.ouom.wekit.util.log.Logger
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@SuppressLint("DiscouragedApi")
@HookItem(path = "聊天与消息/自动抢红包", desc = "监听消息并自动拆开红包")
class WeRedPacketAuto : BaseClickableFunctionHookItem() {

    private var clsReceiveLuckyMoney: Class<*>? = null
    private var clsOpenLuckyMoney: Class<*>? = null

    // 仅保存方法引用
    private var methodGetMgr: Method? = null
    private var methodSend: Method? = null

    private val currentRedPacketMap = ConcurrentHashMap<String, RedPacketInfo>()

    data class RedPacketInfo(
        val sendId: String,
        val nativeUrl: String,
        val talker: String,
        val msgType: Int,
        val channelId: Int,
        val ver: String,
        val headImg: String = "",
        val nickName: String = ""
    )

    override fun entry(classLoader: ClassLoader) {
        if (!initClasses(classLoader)) {
            Logger.e("WeRedPacketAuto: 初始化失败，缺少关键类或方法")
            return
        }
        hookDatabaseInsert(classLoader)
        hookReceiveCallback()
    }

    private fun initClasses(classLoader: ClassLoader): Boolean {
        try {
            val clsReceiveName = TargetManager.requireClassName(TargetManager.KEY_CLASS_LUCKY_MONEY_RECEIVE)
            val clsOpenName = TargetManager.requireClassName(TargetManager.KEY_CLASS_LUCKY_MONEY_OPEN)

            if (clsReceiveName.isNotEmpty()) clsReceiveLuckyMoney = XposedHelpers.findClass(clsReceiveName, classLoader)
            if (clsOpenName.isNotEmpty()) clsOpenLuckyMoney = XposedHelpers.findClass(clsOpenName, classLoader)

            // 获取网络队列单例的方法 (NetSceneQueue.getInstance)
            methodGetMgr = TargetManager.requireMethod(TargetManager.KEY_METHOD_GET_SEND_MGR)

            // 此时不调用 invoke，防止 crash
            Logger.d("WeRedPacketAuto: Classes & Methods Reflected (Lazy Init Ready)")
            return clsReceiveLuckyMoney != null && clsOpenLuckyMoney != null && methodGetMgr != null
        } catch (e: Throwable) {
            Logger.e("WeRedPacketAuto: initClasses error", e)
            return false
        }
    }

    /**
     * 发包方法查找逻辑
     * 1. 参数数量 = 1
     * 2. 参数类型 = NetScene
     * 3. 返回类型 = boolean (doScene 返回 bool，cancel 返回 void)
     */
    @Synchronized
    private fun getOrFindSendMethod(queueObj: Any, netSceneClass: Class<*>): Method? {
        if (methodSend != null) return methodSend

        Logger.i("WeRedPacketAuto: 开始动态查找 doScene 方法...")
        val queueClass = queueObj.javaClass

        // 打印类名辅助调试，确认我们拿到了正确的 Queue 对象
        Logger.d("WeRedPacketAuto: Queue Class -> ${queueClass.name}")

        val candidates = ArrayList<Method>()

        for (method in queueClass.declaredMethods) {
            if (!Modifier.isPublic(method.modifiers)) continue
            val params = method.parameterTypes

            // 筛选条件：1个参数
            if (params.size == 1) {
                val paramType = params[0]

                // 筛选条件：参数是 NetScene (或其父类)，且不是基本类型/String
                if (paramType.isAssignableFrom(netSceneClass) && !paramType.isPrimitive && paramType != String::class.java) {

                    // 筛选条件：返回类型必须是 Boolean
                    // doScene 通常返回 boolean，而 cancel 通常返回 void
                    if (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == Boolean::class.java) {
                        Logger.i("WeRedPacketAuto: 候选方法 (Boolean) -> ${method.name}(${paramType.name})")
                        candidates.add(method)
                    } else {
                        Logger.w("WeRedPacketAuto: 忽略方法 (非Boolean) -> ${method.name}(${paramType.name}) return ${method.returnType.name}")
                    }
                }
            }
        }

        // 决策逻辑
        if (candidates.size == 1) {
            methodSend = candidates[0]
            Logger.i("WeRedPacketAuto: 最终锁定方法 -> ${methodSend?.name}")
            return methodSend
        } else if (candidates.size > 1) {
            methodSend = candidates[0]
            Logger.w("WeRedPacketAuto: 发现多个候选方法，默认使用第一个 -> ${methodSend?.name}")
            return methodSend
        }

        Logger.e("WeRedPacketAuto: 未找到符合 public boolean func(NetScene) 的方法！")
        return null
    }

    private fun hookDatabaseInsert(classLoader: ClassLoader) {
        try {
            val clsSQLite = XposedHelpers.findClass(Constants.CLAZZ_SQLITE_DATABASE, classLoader)

            val mInsertWithOnConflict = XposedHelpers.findMethodExact(
                clsSQLite,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType
            )

            hookAfter(mInsertWithOnConflict) { param ->
                val table = param.args[0] as String
                if (table != "message") return@hookAfter
                val values = param.args[2] as ContentValues
                val type = values.getAsInteger("type") ?: 0

                if (type == TYPE_LUCKY_MONEY_B || type == TYPE_LUCKY_MONEY_C) {
                    handleRedPacket(values)
                }
            }
        } catch (e: Throwable) {
            Logger.e("WeRedPacketAuto: Hook database failed", e)
        }
    }

    private fun handleRedPacket(values: ContentValues) {
        try {
            val config = ConfigManager.getDefaultConfig()
            if (values.getAsInteger("isSend") == 1 && !config.getBoolPrek("red_packet_self")) return

            val content = values.getAsString("content") ?: return
            val talker = values.getAsString("talker") ?: ""

            var xmlContent = content
            if (!content.startsWith("<") && content.contains(":")) {
                xmlContent = content.substring(content.indexOf(":") + 1).trim()
            }

            val nativeUrl = extractXmlParam(xmlContent, "nativeurl")
            if (nativeUrl.isEmpty()) return

            val uri = nativeUrl.toUri()
            val msgType = uri.getQueryParameter("msgtype")?.toIntOrNull() ?: 1
            val channelId = uri.getQueryParameter("channelid")?.toIntOrNull() ?: 1
            val sendId = uri.getQueryParameter("sendid") ?: ""
            val headImg = extractXmlParam(xmlContent, "headimgurl")
            val nickName = extractXmlParam(xmlContent, "sendertitle")

            if (sendId.isEmpty()) return

            Logger.i("WeRedPacketAuto: 发现红包 sendId=$sendId")

            currentRedPacketMap[sendId] = RedPacketInfo(
                sendId = sendId,
                nativeUrl = nativeUrl,
                talker = talker,
                msgType = msgType,
                channelId = channelId,
                ver = "v1.0",
                headImg = headImg,
                nickName = nickName
            )

            val isRandomDelay = config.getBoolPrek("red_packet_delay_random")
            val customDelay = config.getStringPrek("red_packet_delay_custom", "0")?.toLongOrNull() ?: 0L
            val delayTime = if (isRandomDelay) Random.nextLong(500, 5000) else customDelay

            Thread {
                try {
                    if (delayTime > 0) Thread.sleep(delayTime)

                    if (clsReceiveLuckyMoney != null) {
                        val req = XposedHelpers.newInstance(
                            clsReceiveLuckyMoney,
                            msgType, channelId, sendId, nativeUrl, 1, "v1.0", talker
                        )
                        sendRequest(req)
                    }
                } catch (e: Throwable) {
                    Logger.e("WeRedPacketAuto: 发送拆包请求失败", e)
                }
            }.start()

        } catch (e: Throwable) {
            Logger.e("WeRedPacketAuto: 解析红包数据失败", e)
        }
    }

    private fun hookReceiveCallback() {
        if (clsReceiveLuckyMoney == null) return

        try {
            val mOnGYNetEnd = XposedHelpers.findMethodExact(
                clsReceiveLuckyMoney,
                "onGYNetEnd",
                Int::class.javaPrimitiveType,
                String::class.java,
                JSONObject::class.java
            )

            hookAfter(mOnGYNetEnd) { param ->
                val json = param.args[2] as? JSONObject ?: return@hookAfter
                val sendId = json.optString("sendId")
                val timingIdentifier = json.optString("timingIdentifier")

                if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@hookAfter

                val info = currentRedPacketMap[sendId] ?: return@hookAfter
                Logger.i("WeRedPacketAuto: 拆包成功，准备开包 ($sendId)")

                Thread {
                    try {
                        val openReq = XposedHelpers.newInstance(
                            clsOpenLuckyMoney,
                            info.msgType, info.channelId, info.sendId, info.nativeUrl,
                            info.headImg, info.nickName, info.talker,
                            "v1.0", timingIdentifier, ""
                        )
                        sendRequest(openReq)
                        currentRedPacketMap.remove(sendId)
                    } catch (e: Throwable) {
                        Logger.e("WeRedPacketAuto: 开包失败", e)
                    }
                }.start()
            }
        } catch (e: Throwable) {
            Logger.e("WeRedPacketAuto: Hook onGYNetEnd failed", e)
        }
    }

    private fun extractXmlParam(xml: String, tag: String): String {
        val pattern = "<$tag><!\\[CDATA\\[(.*?)\\]\\]></$tag>".toRegex()
        val match = pattern.find(xml)
        if (match != null) return match.groupValues[1]
        val patternSimple = "<$tag>(.*?)</$tag>".toRegex()
        val matchSimple = patternSimple.find(xml)
        return matchSimple?.groupValues?.get(1) ?: ""
    }

    private fun sendRequest(netScene: Any) {
        try {
            val queueObj = methodGetMgr?.invoke(null) ?: return
            val method = getOrFindSendMethod(queueObj, netScene.javaClass)

            if (method == null) return

            method.invoke(queueObj, netScene)
            Logger.i("WeRedPacketAuto: 网络请求已发送 -> ${method.name}")

        } catch (e: Throwable) {
            Logger.e("WeRedPacketAuto: 网络请求发送失败", e)
        }
    }

    override fun unload(classLoader: ClassLoader) {}

    override fun onClick(context: Context?) {
        super.onClick(context)
        context?.let { WeRedPacketConfigDialog(it).show() }
    }
}