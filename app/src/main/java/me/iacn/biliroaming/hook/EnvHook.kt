package me.iacn.biliroaming.hook

import android.content.SharedPreferences
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import java.lang.reflect.Proxy
import java.util.regex.Pattern

class EnvHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        Log.d("startHook: Env")

        // EnvContext
        instance.preBuiltConfigClass?.let {
            val hooker: HookCallback = hooker@ { chain ->
                val result = chain.proceed()
                @Suppress("UNCHECKED_CAST")
                val map = result as? MutableMap<String, String?> ?: return@hooker result
                for (config in configSet) {
                    config.getEncryptedValue()?.let { map[config.key] = it }
                        ?: map.remove(config.key)
                }
                result
            }
            // v8.28.0 - ?
            runCatching { it.hookMethod(instance.getPreBuiltConfigMethod(), callback = hooker) }
            // ? - v8.48.0 ..
            runCatching { it.hookMethod(instance.getPreBuiltConfigMethod(), it, callback = hooker) }
        }

        // TypedContext
        instance.dataSPClass?.let {
            val hooker: HookCallback = hooker@ { chain ->
                val result = chain.proceed()
                val sp = result as? SharedPreferences ?: return@hooker result
                if (!sp.contains("bv.enable_bv")) return@hooker result
                for (config in configSet) {
                    config.getEncryptedValue()?.let {
                        sp.edit().putString(config.key, it).apply()
                    } ?: sp.edit().remove(config.key).apply()
                }
                result
            }
            // v8.28.0 - ?
            runCatching { it.hookMethod(instance.getDataSPMethod(), callback = hooker) }
            // ? - v8.48.0 ..
            runCatching { it.hookMethod(instance.getDataSPMethod(), it, callback = hooker) }
        }

        "com.bilibili.lib.blconfig.internal.OverrideConfig".findClassOrNull(mClassLoader)
            ?.hookAllConstructors { chain ->
                val delegate = chain.args.getOrNull(0) ?: return@hookAllConstructors chain.proceed()
                val realConfig = chain.args.getOrNull(1) // may be null on 8.97.0+ (z12=true)
                val delegateClass = delegate.javaClass
                chain.args[0] = Proxy.newProxyInstance(
                    delegateClass.classLoader,
                    delegateClass.interfaces
                ) { _, m, a ->
                    val args = a ?: emptyArray()
                    if (m.name == "getConfig") {
                        var result: Any? = null
                        val key = args[0]
                        for (config in configSet) {
                            if (config.key == key) {
                                result = if (realConfig != null) {
                                    realConfig.callMethodOrNull("get", *args)
                                } else config.getPlainValue()
                            }
                        }
                        result ?: m(delegate, *args)
                    } else {
                        m(delegate, *args)
                    }
                }
                chain.proceed()
            }

//        // Disable tinker
//        "com.tencent.tinker.loader.app.TinkerApplication".findClass(mClassLoader)?.hookAllConstructors { chain ->
//            chain.args[0] = 0
//            chain.proceed()
//        }
    }

    override fun lateInitHook() {
        Log.d("lateHook: Env")
        if (sPrefs.getBoolean("enable_av", false)) {
            val compatClass = "com.bilibili.droid.BVCompat".findClassOrNull(mClassLoader)
            compatClass?.declaredFields?.forEach { f ->
                runCatchingOrNull {
                    val field = compatClass.getStaticObjectField(f.name)
                    if (field is Pattern && field.pattern() == "av[1-9]\\d*") {
                        compatClass.setStaticObjectField(
                            f.name,
                            Pattern.compile("(av[1-9]\\d*)|(BV1[1-9A-NP-Za-km-z]{9})", field.flags())
                        )
                    }
                }
                if (f.type == Boolean::class.javaPrimitiveType) {
                    runCatching {
                        f.isAccessible = true
                        f.setBoolean(null, false)
                    }.onFailure { Log.e(it) }
                }
            }
        }
    }

    companion object {

        private val encryptedValueMap = hashMapOf(
            "0" to "Irb5O7Q8Ka0ojD4qqScgqg==",
            "1" to "Y260Cyvp6HZEboaGO+YGMw=="
        )

        class ConfigTuple(
            val key: String,
            val config: String,
            val trueValue: String?,
            val falseValue: String?,
            val plainTrueValue: String? = null,
            val plainFalseValue: String? = null
        ) {
            fun getEncryptedValue(): String? =
                if (sPrefs.getBoolean(config, false)) trueValue else falseValue

            fun getPlainValue(): String? =
                if (sPrefs.getBoolean(config, false)) plainTrueValue else plainFalseValue
        }

        val configSet = listOf(
            ConfigTuple(
                "bv.enable_bv",
                "enable_av",
                encryptedValueMap["0"],
                encryptedValueMap["1"],
                "0",
                "1"
            ),
        )
    }
}
