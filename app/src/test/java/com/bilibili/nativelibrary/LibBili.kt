package com.bilibili.nativelibrary

class LibBili private constructor() {
    companion object {
        var result: SignedQuery? = null
        var failure: RuntimeException? = null
        var received: Map<String, String>? = null

        @JvmStatic
        fun signQuery(parameters: Map<String, String>): SignedQuery? {
            received = parameters
            failure?.let { throw it }
            return result
        }

        @JvmStatic
        fun signQuery(parameters: Map<String, String>, first: Int, second: Int): SignedQuery =
            throw AssertionError("Wrong signer overload: ${parameters.size}, $first, $second")

        @JvmStatic
        fun signQuery(parameters: Map<String, String>, bytes: ByteArray): SignedQuery =
            throw AssertionError("Wrong signer overload: ${parameters.size}, ${bytes.size}")

        @JvmStatic
        fun unrelated(parameters: Map<String, String>): String = parameters.size.toString()
    }
}
