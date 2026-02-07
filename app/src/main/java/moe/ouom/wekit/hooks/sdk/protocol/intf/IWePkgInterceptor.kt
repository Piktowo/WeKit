package moe.ouom.wekit.hooks.sdk.protocol.intf

interface IWePkgInterceptor {
    fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? = null
    fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? = null
}
