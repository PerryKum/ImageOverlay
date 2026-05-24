package com.example.imageoverlay.util

object LauncherUtil {

    private val launcherPackages = setOf(
        "com.android.launcher", "com.android.launcher2", "com.android.launcher3",
        "com.google.android.launcher", "com.google.android.apps.nexuslauncher",
        "com.samsung.android.launcher", "com.huawei.android.launcher",
        "com.miui.home", "com.oneplus.launcher", "com.oppo.launcher",
        "com.vivo.launcher", "com.meizu.flyme.launcher", "com.bbk.launcher2",
        "com.sec.android.app.launcher", "com.lge.launcher2", "com.lge.launcher3",
        "com.htc.launcher", "com.sonyericsson.home", "com.cyanogenmod.trebuchet",
        "com.teslacoilsw.launcher", "com.nova.launcher", "com.launcher.settings"
    )

    /** 仅匹配已知桌面包名，避免包名含 launcher/home 的游戏被误判 */
    fun isLauncherPackage(packageName: String): Boolean {
        return launcherPackages.contains(packageName)
    }
}
