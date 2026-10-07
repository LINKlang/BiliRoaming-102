package me.iacn.biliroaming.utils

import me.iacn.biliroaming.Configs
import me.iacn.biliroaming.biliAccounts
import me.iacn.biliroaming.class_
import me.iacn.biliroaming.copy
import me.iacn.biliroaming.method
import me.iacn.biliroaming.signQuery
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** The host App's request APIs can be resolved without the native DEX scanner. */
internal class NativeRequestHooks private constructor(
    val signQuery: Method?,
    val getAccounts: Method?,
    val getAccessKey: Method?,
) {
    val isComplete: Boolean
        get() = signQuery != null && getAccounts != null && getAccessKey != null

    fun matchesCache(info: Configs.HookInfo): Boolean = isComplete &&
        info.signQuery.class_.name == signQuery!!.declaringClass.name &&
        info.signQuery.method.name == signQuery.name &&
        info.biliAccounts.class_.name == getAccounts!!.declaringClass.name &&
        info.biliAccounts.get.name == getAccounts.name &&
        info.biliAccounts.getAccessKey.name == getAccessKey!!.name

    fun update(info: Configs.HookInfo): Configs.HookInfo = info.copy {
        this@NativeRequestHooks.signQuery?.let { signer ->
            signQuery = signQuery {
                class_ = class_ { name = signer.declaringClass.name }
                method = method { name = signer.name }
            }
        }
        if (getAccounts != null && getAccessKey != null) {
            biliAccounts = biliAccounts {
                class_ = class_ { name = getAccounts.declaringClass.name }
                get = method { name = getAccounts.name }
                this.getAccessKey = method { name = this@NativeRequestHooks.getAccessKey.name }
            }
        }
    }

    fun sign(parameters: Map<String, String>): String {
        val signer = signQuery ?: throw SigningException("未找到 B 站原生签名方法")
        val result = try {
            signer.invoke(null, parameters)?.toString()
        } catch (e: Exception) {
            // Do not expose a host exception's message, which may include credentials.
            throw SigningException("B 站原生签名调用失败（${(e.cause ?: e).javaClass.simpleName}）")
        }
        if (result.isNullOrBlank() || result == "null" || result.split('&').none {
                it.startsWith("sign=") && it.substringAfter('=').let { value ->
                    value.isNotBlank() && value != "null"
                }
            }) {
            throw SigningException("B 站原生签名未返回有效请求参数")
        }
        return result
    }

    class SigningException(message: String) : IllegalStateException(message)

    companion object {
        private const val SIGNER_CLASS = "com.bilibili.nativelibrary.LibBili"
        private const val SIGNED_QUERY_CLASS = "com.bilibili.nativelibrary.SignedQuery"
        private const val ACCOUNTS_CLASS = "com.bilibili.lib.accounts.BiliAccounts"

        fun resolve(info: Configs.HookInfo, classLoaders: List<ClassLoader>): NativeRequestHooks {
            fun classes(vararg names: String): Sequence<Class<*>> = names.asSequence()
                .filter { it.isNotBlank() }.distinct().flatMap { name ->
                    classLoaders.distinct().asSequence().mapNotNull { loader ->
                        try {
                            Class.forName(name, false, loader)
                        } catch (_: ClassNotFoundException) {
                            null
                        } catch (_: LinkageError) {
                            null
                        }
                    }
                }

            val signer = classes(info.signQuery.class_.name, SIGNER_CLASS).mapNotNull { clazz ->
                clazz.declaredMethods.filter {
                    Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.contentEquals(arrayOf(Map::class.java)) &&
                        it.returnType.name == SIGNED_QUERY_CLASS
                }.let { methods ->
                    methods.firstOrNull { it.name == info.signQuery.method.name }
                        ?: methods.singleOrNull()
                }
            }.firstOrNull()?.apply { isAccessible = true }

            val accounts = classes(info.biliAccounts.class_.name, ACCOUNTS_CLASS).mapNotNull { clazz ->
                val getters = clazz.declaredMethods.filter {
                    Modifier.isStatic(it.modifiers) && it.returnType == clazz &&
                        it.parameterTypes.map { type -> type.name } == listOf("android.content.Context")
                }
                val get = getters.firstOrNull { it.name == info.biliAccounts.get.name }
                    ?: getters.firstOrNull { it.name == "get" } ?: getters.singleOrNull()
                val accessKey = clazz.declaredMethods.firstOrNull {
                    it.name == info.biliAccounts.getAccessKey.name && isAccessKeyGetter(it)
                } ?: clazz.declaredMethods.firstOrNull { it.name == "getAccessKey" && isAccessKeyGetter(it) }
                if (get != null && accessKey != null) get to accessKey else null
            }.firstOrNull()

            return NativeRequestHooks(signer,
                accounts?.first?.apply { isAccessible = true },
                accounts?.second?.apply { isAccessible = true })
        }

        private fun isAccessKeyGetter(method: Method): Boolean =
            !Modifier.isStatic(method.modifiers) && method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java
    }
}
