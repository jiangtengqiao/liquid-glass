package com.liquidglass.app

import android.content.Context

/**
 * 全局应用上下文持有者。
 *
 * 在 [MainActivity.onCreate] 最早期注册，供需要 Context 的单例
 * （如 NetEaseApiClient）在尚未显式 init 时自愈，避免 lateinit 竞态崩溃。
 */
object ContextProvider {
    @Volatile
    var appContext: Context? = null
        private set

    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }
}
