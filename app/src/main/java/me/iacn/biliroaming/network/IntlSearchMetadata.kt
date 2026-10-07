package me.iacn.biliroaming.network

import com.google.protobuf.MessageLite
import me.iacn.biliroaming.resolver.ResolverMetadata
import java.util.Base64

internal object IntlSearchMetadata {
    fun headers(accessKey: String, mid: Long): Map<String, String> {
        fun MessageLite.encoded() = Base64.getEncoder().encodeToString(toByteArray())
        val locale = ResolverMetadata.LocaleIds.newBuilder().setLanguage("zh").setScript("Hans").setRegion("SG").build()
        return mapOf(
            "resolver_mode" to "INTL",
            "User-Agent" to "Mozilla/5.0 BiliDroid/6.6.0 os/android model/android mobi_app/android_i build/9130300 channel/pink_overseas innerVer/9130300 osVer/15 network/2",
            "grpc-accept-encoding" to "gzip,identity",
            "x-bili-device-bin" to ResolverMetadata.Device.newBuilder().setAppId(14).setBuild(9130300)
                .setMobiApp("android_i").setPlatform("android").setChannel("pink_overseas").setOsver("15").setVersionName("6.6.0").build().encoded(),
            "x-bili-metadata-bin" to ResolverMetadata.Metadata.newBuilder().setAccessKey(accessKey).setMobiApp("android_i")
                .setDevice("android").setBuild(9130300).setChannel("pink_overseas").setPlatform("android").build().encoded(),
            "x-bili-locale-bin" to ResolverMetadata.Locale.newBuilder().setCLocale(locale).setSLocale(locale)
                .setTimezone("Asia/Bangkok").build().encoded(),
            "x-bili-network-bin" to ResolverMetadata.Network.newBuilder().setType(1).build().encoded(),
            "x-bili-fawkes-req-bin" to ResolverMetadata.Fawkes.newBuilder().setAppkey("android_i").setEnv("prod")
                .setSessionId("resolver").build().encoded(),
            "x-bili-restriction-bin" to "", "x-bili-exps-bin" to "", "x-bili-mid" to mid.toString(),
            "x-bili-metadata-ip-region" to "TH", "x-bili-metadata-legal-region" to "TH", "bili-http-engine" to "cronet",
        ) + if (accessKey.isNotEmpty()) mapOf("authorization" to "identify_v1 $accessKey") else emptyMap()
    }
}
