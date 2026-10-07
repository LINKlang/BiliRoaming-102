package me.iacn.biliroaming.network

import me.iacn.biliroaming.utils.sPrefs
import java.net.URI
import java.security.MessageDigest

internal data class ResolverConfig(val modern: Boolean, val servers: Map<String, String>) {
    fun server(area: String): String? = servers[area]?.trim()?.takeIf { it.isNotEmpty() }
    val configured: List<String> get() = AREAS.filter { server(it) != null }
    val scope: String by lazy {
        val input = "$modern|" + AREAS.joinToString("|") { "$it=${server(it).orEmpty()}" }
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun cacheKey(id: String): String = if (modern) "resolver2:$scope:$id" else id

    fun endpoint(area: String, path: String): String {
        val base = URI(normalizeAddress(requireNotNull(server(area)) { "未设置解析服务器" }))
        return base.resolve(base.rawPath.trimEnd('/') + "/" + path.trimStart('/')).toASCIIString()
    }

    companion object {
        const val ENABLE_KEY = "use_new_resolver_protocol"
        val AREAS = listOf("cn", "hk", "tw", "th")
        fun serverKey(area: String, modern: Boolean): String = if (modern) "new_${area}_server" else "${area}_server"
        fun mode(area: String): String = if (area == "th") "INTL" else area.uppercase()

        fun normalizeAddress(address: String): String {
            val text = address.trim()
            if (text.isEmpty()) return ""
            val uri = try { URI(if (text.contains("://")) text else "https://$text") }
            catch (_: Exception) { throw IllegalArgumentException("请输入有效 HTTP(S) 服务器地址") }
            require(uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.port in -1..65535 && uri.port != 0) { "请输入有效 HTTP(S) 服务器地址" }
            return uri.toASCIIString().trimEnd('/')
        }
    }
}

/** Request configuration is fixed until the host App restarts. */
internal object ResolverSettings {
    val current: ResolverConfig by lazy {
        val modern = sPrefs.getBoolean(ResolverConfig.ENABLE_KEY, false)
        ResolverConfig(modern, ResolverConfig.AREAS.associateWith {
            sPrefs.getString(ResolverConfig.serverKey(it, modern), "").orEmpty()
        })
    }
}
