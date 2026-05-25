package com.example.imageoverlay.util

import android.content.Context

/**
 * 由 [com.example.imageoverlay.UsageStatsListener] 根据 UsageEvents 维护前台栈：
 * - MOVE_TO_FOREGROUND：入栈（去重后置于栈顶）
 * - MOVE_TO_BACKGROUND：从栈中移除该包名
 *
 * 栈顶为当前前台应用；是否仍「在玩游戏」可看栈里是否仍含该包名。
 */
object ForegroundAppUtil {

    private val foregroundStack = ArrayDeque<String>()
    private val lock = Any()

    fun onMoveToForeground(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        synchronized(lock) {
            foregroundStack.remove(packageName)
            foregroundStack.addLast(packageName)
        }
    }

    fun onMoveToBackground(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        synchronized(lock) {
            foregroundStack.remove(packageName)
        }
    }

    /** 悬浮球启动等场景：尚未收到事件前，先把绑定包名压栈 */
    fun seedForeground(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            foregroundStack.remove(packageName)
            foregroundStack.addLast(packageName)
        }
    }

    /** 当前前台（栈顶） */
    fun getTopForegroundPackage(): String? = synchronized(lock) {
        foregroundStack.lastOrNull()
    }

    /** 栈中是否仍包含该包名（未收到其退到后台事件） */
    fun containsPackage(packageName: String): Boolean = synchronized(lock) {
        packageName in foregroundStack
    }

    /** 从栈顶向下找第一个满足条件的包名 */
    fun findTopMatching(predicate: (String) -> Boolean): String? = synchronized(lock) {
        foregroundStack.asReversed().firstOrNull(predicate)
    }

    /** 兼容旧调用：等同栈顶 */
    fun getRecentForegroundPackage(context: Context, lookbackMs: Long = 0L): String? {
        return getTopForegroundPackage()
    }
}
