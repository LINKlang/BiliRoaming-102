package com.bilibili.lib.accounts

import android.content.Context

class BiliAccounts {
    fun getAccessKey(): String = "fixture-access-key"

    companion object {
        private val account = BiliAccounts()

        @JvmStatic
        fun get(context: Context?): BiliAccounts = account
    }
}
