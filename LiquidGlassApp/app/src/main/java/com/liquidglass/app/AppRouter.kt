package com.liquidglass.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * 全局路由单例 —— 通知点击跳转的桥梁。
 *
 * 通知点击后，[com.liquidglass.app.MainActivity.handleNotificationIntent] 解析 Intent
 * 携带的 action/target，映射为目标路由名（对应 [com.liquidglass.app.ui.Screen].name）
 * 写入 [pendingRoute]；HomeScreen 观察 [pendingRoute] 变化并跳转到对应功能页，
 * 消费后清空。从而实现"点通知直达对应功能页"，而非仅跳到首页/封面。
 *
 * 修复"通知点击黑屏/不跳转"：
 * - 增加 @Volatile backupRoute 作为备份，防止 Compose snapshot 在冷启动+加载页+
 *   权限门槛多层异步下丢失 pendingRoute 状态
 * - navigate() 同时写 MutableState 和 volatile 备份
 * - consumeRoute() 优先读 MutableState，为空时读 volatile 备份
 */
object AppRouter {
    /** 待跳转的路由名（Screen 枚举名），null 表示无待处理跳转。 */
    val pendingRoute: MutableState<String?> = mutableStateOf(null)

    /** volatile 备份：冷启动时 pendingRoute 可能在 HomeScreen 首次组合前被设置，
     *  Compose snapshot 在某些时序下可能未正确传播，volatile 备份确保不丢失。 */
    @Volatile
    private var backupRoute: String? = null

    /** 设置待跳转路由并立即触发 HomeScreen 消费。 */
    fun navigate(route: String) {
        backupRoute = route
        pendingRoute.value = route
    }

    /**
     * 消费待跳转路由：优先读 MutableState，为空时读 volatile 备份。
     * 返回路由名并清空两个存储，确保只消费一次。
     */
    fun consumeRoute(): String? {
        val route = pendingRoute.value ?: backupRoute
        if (route != null) {
            pendingRoute.value = null
            backupRoute = null
        }
        return route
    }
}
