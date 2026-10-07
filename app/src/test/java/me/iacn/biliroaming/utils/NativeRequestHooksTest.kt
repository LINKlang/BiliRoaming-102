package me.iacn.biliroaming.utils

import com.bilibili.nativelibrary.LibBili
import com.bilibili.nativelibrary.SignedQuery
import me.iacn.biliroaming.Configs
import me.iacn.biliroaming.biliAccounts
import me.iacn.biliroaming.class_
import me.iacn.biliroaming.copy
import me.iacn.biliroaming.hookInfo
import me.iacn.biliroaming.method
import me.iacn.biliroaming.signQuery
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NativeRequestHooksTest {
    private val empty = Configs.HookInfo.getDefaultInstance()
    private val loader = javaClass.classLoader!!

    @Before
    fun resetSigner() {
        LibBili.received = null
        LibBili.failure = null
        LibBili.result = SignedQuery("ep_id=96480&cid=11198631&sign=fixture-signature")
    }

    @Test
    fun accessKeyNameAloneDoesNotMakeCacheUsable() {
        val broken = hookInfo {
            biliAccounts = biliAccounts { getAccessKey = method { name = "getAccessKey" } }
        }
        val hooks = NativeRequestHooks.resolve(broken, listOf(loader))
        assertTrue(hooks.isComplete)
        assertFalse(hooks.matchesCache(broken))
        assertTrue(hooks.matchesCache(hooks.update(broken)))
    }

    @Test
    fun staleMethodAndClassNamesAreRecovered() {
        val broken = hookInfo {
            signQuery = signQuery {
                class_ = class_ { name = "missing.Signer" }
                method = method { name = "missingMethod" }
            }
            biliAccounts = biliAccounts {
                class_ = class_ { name = "missing.Accounts" }
                get = method { name = "missingGet" }
                getAccessKey = method { name = "getAccessKey" }
            }
        }
        val hooks = NativeRequestHooks.resolve(broken, listOf(loader))
        assertFalse(hooks.matchesCache(broken))
        assertTrue(hooks.matchesCache(hooks.update(broken)))
    }

    @Test
    fun completeCacheIsAcceptedAfterSerialization() {
        val info = NativeRequestHooks.resolve(empty, listOf(loader)).update(empty)
        val loaded = Configs.HookInfo.parseFrom(info.toByteArray())
        assertTrue(NativeRequestHooks.resolve(loaded, listOf(loader)).matchesCache(loaded))
    }

    @Test
    fun invokesTheSingleMapNativeEntryAndPreservesRequestParameters() {
        val parameters = linkedMapOf("ep_id" to "96480", "cid" to "11198631", "access_key" to "fixture")
        val hooks = NativeRequestHooks.resolve(empty, listOf(loader))
        assertEquals("signQuery", hooks.signQuery!!.name)
        assertEquals(1, hooks.signQuery.parameterCount)
        assertEquals("ep_id=96480&cid=11198631&sign=fixture-signature", hooks.sign(parameters))
        assertSame(parameters, LibBili.received)
        assertEquals("fixture", parameters["access_key"])
    }

    @Test
    fun respectsPreviouslyDiscoveredMethodNames() {
        val info = hookInfo {
            signQuery = signQuery {
                class_ = class_ { name = LegacySigner::class.java.name }
                method = method { name = "renamed" }
            }
        }
        val hooks = NativeRequestHooks.resolve(info, listOf(loader))
        assertEquals(LegacySigner::class.java, hooks.signQuery!!.declaringClass)
        assertTrue(hooks.sign(emptyMap()).contains("sign="))
    }

    @Test
    fun accountMethodsRecoverTogether() {
        val hooks = NativeRequestHooks.resolve(empty, listOf(loader))
        val account = hooks.getAccounts!!.invoke(null, null)
        assertEquals("fixture-access-key", hooks.getAccessKey!!.invoke(account))
    }

    @Test
    fun doesNotTurnMissingSignerIntoNullQuery() {
        val hooks = NativeRequestHooks.resolve(empty, listOf(blockedLoader()))
        assertFalse(hooks.isComplete)
        assertThrows(NativeRequestHooks.SigningException::class.java) { hooks.sign(emptyMap()) }
    }

    @Test
    fun canUseHostLoaderWhenAnotherLoaderCannotSeeHostClasses() {
        val hooks = NativeRequestHooks.resolve(empty, listOf(blockedLoader(), loader))
        assertTrue(hooks.isComplete)
    }

    @Test
    fun rejectsNullEmptyAndUnsignedNativeResults() {
        val hooks = NativeRequestHooks.resolve(empty, listOf(loader))
        for (query in listOf(null, "", "null", "ep_id=96480", "ep_id=96480&sign=", "ep_id=96480&sign=null")) {
            LibBili.result = query?.let(::SignedQuery)
            assertThrows(NativeRequestHooks.SigningException::class.java) { hooks.sign(emptyMap()) }
        }
    }

    @Test
    fun nativeFailureDoesNotExposeCredentials() {
        LibBili.failure = IllegalArgumentException("access_key=private-value")
        val hooks = NativeRequestHooks.resolve(empty, listOf(loader))
        val error = assertThrows(NativeRequestHooks.SigningException::class.java) { hooks.sign(emptyMap()) }
        assertTrue(error.message!!.contains("IllegalArgumentException"))
        assertFalse(error.message!!.contains("private-value"))
        assertNull(error.cause)
    }

    private fun blockedLoader(): ClassLoader = object : ClassLoader(loader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("com.bilibili.")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    class LegacySigner {
        companion object {
            @JvmStatic
            fun renamed(parameters: Map<String, String>): SignedQuery =
                SignedQuery("ep_id=${parameters["ep_id"] ?: "96480"}&sign=fixture-signature")
        }
    }
}
