package com.example.imageoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.imageoverlay.model.Config
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.model.Group

class FloatingBallService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingBallView: View? = null
    private var configPopupView: View? = null
    private var overlayView: View? = null
    private var currentGroup: Group? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var popupParams: WindowManager.LayoutParams? = null
    
    // 桌面检测相关
    private val homeCheckHandler = Handler(Looper.getMainLooper())
    private val homeCheckIntervalMs = 1000L
    private var lastHomeCheckTime = 0L
    private var launcherStableSince = 0L
    private val launcherPackages = setOf(
        "com.android.launcher", "com.android.launcher2", "com.android.launcher3",
        "com.google.android.launcher", "com.google.android.apps.nexuslauncher",
        "com.samsung.android.launcher", "com.huawei.android.launcher",
        "com.miui.home", "com.oneplus.launcher", "com.oppo.launcher",
        "com.vivo.launcher", "com.meizu.flyme.launcher", "com.bbk.launcher2",
        "com.sec.android.app.launcher", "com.lge.launcher2", "com.lge.launcher3",
        "com.htc.launcher", "com.sonyericsson.home", "com.cyanogenmod.trebuchet",
        "com.teslacoilsw.launcher", "com.nova.launcher", "com.launcher.settings",
        "com.android.settings", "com.android.systemui"
    )
    
    // 拖动相关变量
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    
    // 悬浮球状态
    private var isOnLeftSide = false
    private var isExpanded = false // 悬浮窗是否展开
    

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // 立即创建前台服务通知
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "floating_ball_channel")
                .setContentTitle("悬浮球服务")
                .setContentText("悬浮球已启动")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            startForeground(2, notification)

            val packageName = intent?.getStringExtra("packageName")
            if (packageName != null) {
                // 根据包名找到对应的组
                currentGroup = ConfigRepository.getGroups().find { group ->
                    group.boundPackageName == packageName
                }
                
                if (currentGroup != null) {
                    showFloatingBall()
                    android.util.Log.d("FloatingBallService", "为应用 $packageName 显示悬浮球，组: ${currentGroup!!.groupName}")
                    // 启动桌面检测
                    startHomeDetection()
                } else {
                    android.util.Log.w("FloatingBallService", "未找到应用 $packageName 对应的组配置")
                    stopSelf()
                }
            } else {
                android.util.Log.w("FloatingBallService", "包名为空，停止服务")
                stopSelf()
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "onStartCommand异常", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun showFloatingBall() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            // 创建悬浮球视图
            val inflater = LayoutInflater.from(this)
            floatingBallView = inflater.inflate(R.layout.floating_ball, null)
            
            val ballIcon = floatingBallView?.findViewById<ImageView>(R.id.ballIcon)
            val ballText = floatingBallView?.findViewById<TextView>(R.id.ballText)
            
            // 设置悬浮球参数
            ballParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            // 设置悬浮球初始位置（右侧中间）
            ballParams?.gravity = Gravity.TOP or Gravity.START
            ballParams?.x = getScreenWidth() - 100 // 距离右边缘100px
            ballParams?.y = getScreenHeight() / 2 - 50 // 屏幕中间
            isOnLeftSide = false
            
            // 设置触摸事件监听器
            floatingBallView?.setOnTouchListener { view, event ->
                handleTouchEvent(view, event)
            }
            
            windowManager?.addView(floatingBallView, ballParams)
            android.util.Log.d("FloatingBallService", "悬浮球显示成功")
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "显示悬浮球失败", e)
        }
    }



    private fun removeFloatingBall() {
        try {
            if (floatingBallView != null && windowManager != null) {
                windowManager?.removeView(floatingBallView)
                floatingBallView = null
            }
            hideConfigPopup()
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "移除悬浮球失败", e)
        }
    }
    
    // 手动销毁悬浮球（用户点击手动销毁按钮时调用）
    private fun destroyFloatingBall() {
        try {
            android.util.Log.d("FloatingBallService", "开始手动销毁悬浮球")
            
            // 隐藏弹窗
            hideConfigPopup()
            
            // 移除悬浮球
            removeFloatingBall()
            
            // 停止服务
            stopSelf()
            
            android.util.Log.d("FloatingBallService", "手动销毁悬浮球完成")
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "手动销毁悬浮球失败", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("FloatingBallService", "服务销毁，清理资源")
        // 停止桌面检测
        try {
            homeCheckHandler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {}
        
        try {
            // 清理所有视图
            if (floatingBallView != null && windowManager != null) {
                try {
                    windowManager?.removeView(floatingBallView)
                } catch (e: Exception) {
                    android.util.Log.e("FloatingBallService", "移除悬浮球视图失败", e)
                }
                floatingBallView = null
            }
            
            if (configPopupView != null && windowManager != null) {
                try {
                    windowManager?.removeView(configPopupView)
                } catch (e: Exception) {
                    android.util.Log.e("FloatingBallService", "移除弹窗视图失败", e)
                }
                configPopupView = null
            }
            
            if (overlayView != null && windowManager != null) {
                try {
                    windowManager?.removeView(overlayView)
                } catch (e: Exception) {
                    android.util.Log.e("FloatingBallService", "移除覆盖层视图失败", e)
                }
                overlayView = null
            }
            
            // 清理参数
            ballParams = null
            popupParams = null
            
            android.util.Log.d("FloatingBallService", "资源清理完成")
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "资源清理异常", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_ball_channel", "悬浮球服务", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    // 处理触摸事件
    private fun handleTouchEvent(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ballParams?.x ?: 0
                initialY = ballParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()
                
                // 如果移动距离超过阈值，开始拖动
                if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                    isDragging = true
                }
                
                if (isDragging) {
                    val newX = initialX + deltaX
                    val newY = initialY + deltaY
                    
                    // 限制在屏幕范围内
                    val screenWidth = getScreenWidth()
                    val screenHeight = getScreenHeight()
                    val ballWidth = 100 // 悬浮球宽度
                    val ballHeight = 100 // 悬浮球高度
                    
                    ballParams?.x = newX.coerceIn(0, screenWidth - ballWidth)
                    ballParams?.y = newY.coerceIn(0, screenHeight - ballHeight)
                    
                    windowManager?.updateViewLayout(floatingBallView, ballParams)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // 点击事件：开启/关闭悬浮窗
                    if (isExpanded) {
                        hideConfigPopup()
                    } else {
                        showConfigPopup()
                    }
                } else {
                    // 拖动结束，自动吸附到屏幕边缘
                    snapToEdge()
                }
                return true
            }
        }
        return false
    }
    
    // 自动吸附到屏幕左右边缘
    private fun snapToEdge() {
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        val ballWidth = 100
        val ballHeight = 100
        val currentX = ballParams?.x ?: 0
        val currentY = ballParams?.y ?: 0
        
        // 只判断左右两边
        val distanceToLeft = currentX
        val distanceToRight = screenWidth - currentX - ballWidth
        
        if (distanceToLeft < distanceToRight) {
            // 吸附到左边
            ballParams?.x = 0
            isOnLeftSide = true
        } else {
            // 吸附到右边
            ballParams?.x = screenWidth - ballWidth
            isOnLeftSide = false
        }
        
        // 限制Y坐标在屏幕范围内
        ballParams?.y = currentY.coerceIn(0, screenHeight - ballHeight)
        
        windowManager?.updateViewLayout(floatingBallView, ballParams)
        android.util.Log.d("FloatingBallService", "悬浮球吸附到${if (isOnLeftSide) "左边" else "右边"}")
    }
    
    // 获取屏幕宽度
    private fun getScreenWidth(): Int {
        val displayMetrics = resources.displayMetrics
        return displayMetrics.widthPixels
    }
    
    // 获取屏幕高度
    private fun getScreenHeight(): Int {
        val displayMetrics = resources.displayMetrics
        return displayMetrics.heightPixels
    }
    
    // 更新悬浮球外观
    private fun updateFloatingBallAppearance() {
        val ballIcon = floatingBallView?.findViewById<ImageView>(R.id.ballIcon)
        val ballText = floatingBallView?.findViewById<TextView>(R.id.ballText)
        
        // 悬浮球始终显示完整图标，隐藏文字
        ballIcon?.setImageResource(R.drawable.ic_floating_ball)
        ballText?.visibility = View.GONE
    }

    /**
     * 启动桌面检测：如果用户回到桌面/启动器，则自动移除悬浮球并停止服务
     */
    private fun startHomeDetection() {
        val checkRunnable = object : Runnable {
            override fun run() {
                try {
                    if (isLauncherInForeground()) {
                        if (launcherStableSince == 0L) launcherStableSince = System.currentTimeMillis()
                        val stable = System.currentTimeMillis() - launcherStableSince >= 2500L
                        if (stable) {
                            android.util.Log.d("FloatingBallService", "检测到桌面稳定在前台，自动关闭悬浮球")
                            destroyFloatingBall()
                            return
                        }
                    } else {
                        launcherStableSince = 0L
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FloatingBallService", "桌面检测异常", e)
                }
                homeCheckHandler.postDelayed(this, homeCheckIntervalMs)
            }
        }
        // 避免重复启动
        homeCheckHandler.removeCallbacksAndMessages(null)
        homeCheckHandler.postDelayed(checkRunnable, homeCheckIntervalMs)
    }

    /**
     * 判断当前前台是否为桌面/启动器
     */
    private fun isLauncherInForeground(): Boolean {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 5000
        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var lastForegroundPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForegroundPackage = event.packageName
            }
        }
        val pkg = lastForegroundPackage ?: return false
        // 排除 SystemUI，避免误判锁屏、通知下拉等
        if (pkg == "com.android.systemui") return false
        return launcherPackages.contains(pkg) || pkg.contains("launcher") || pkg.contains("home")
    }
    
    // 显示配置弹窗
    private fun showConfigPopup() {
        try {
            if (configPopupView != null) {
                hideConfigPopup()
            }
            
            val inflater = LayoutInflater.from(this)
            configPopupView = inflater.inflate(R.layout.config_popup, null)
            
            val configList = configPopupView?.findViewById<LinearLayout>(R.id.configList)
            val groupTitle = configPopupView?.findViewById<TextView>(R.id.groupTitle)
            
            // 设置组标题
            groupTitle?.text = currentGroup?.groupName ?: "配置组"
            
            // 清空现有配置项
            configList?.removeAllViews()
            
            // 添加配置项
            currentGroup?.configs?.forEach { config ->
                val configItem = inflater.inflate(R.layout.config_popup_item, null)
                val configName = configItem.findViewById<TextView>(R.id.configName)
                val configStatus = configItem.findViewById<TextView>(R.id.configStatus)
                
                configName.text = config.configName
                configStatus.text = if (config.active) "已激活" else "未激活"
                configStatus.setTextColor(
                    if (config.active) 
                        getColor(android.R.color.holo_green_dark)
                    else 
                        getColor(android.R.color.darker_gray)
                )
                
                // 设置点击事件
                configItem.setOnClickListener {
                    toggleConfig(config)
                }
                
                configList?.addView(configItem)
            }
            
            // 设置手动销毁按钮点击事件
            val manualDestroyView = configPopupView?.findViewById<TextView>(R.id.manualDestroy)
            manualDestroyView?.setOnClickListener {
                android.util.Log.d("FloatingBallService", "用户点击手动销毁")
                destroyFloatingBall()
            }
            // 设置下划线
            manualDestroyView?.paintFlags = manualDestroyView?.paintFlags?.or(android.graphics.Paint.UNDERLINE_TEXT_FLAG) ?: 0
            
            // 设置弹窗参数（固定尺寸和位置）
            popupParams = WindowManager.LayoutParams(
                (getScreenWidth() * 0.6).toInt(), // 屏幕宽度的60%
                (getScreenHeight() * 0.6).toInt(), // 屏幕高度的60%
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            
            // 设置弹窗位置（屏幕正中间）
            popupParams?.gravity = Gravity.CENTER
            popupParams?.x = 0
            popupParams?.y = 0
            
            // 设置全屏透明覆盖层来检测外部点击
            overlayView = View(this).apply {
                setBackgroundColor(0x00000000) // 完全透明
            }
            
            val overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            overlayView?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // 检查点击位置是否在悬浮球区域
                    val ballX = ballParams?.x ?: 0
                    val ballY = ballParams?.y ?: 0
                    val ballWidth = 100
                    val ballHeight = 100
                    
                    val touchX = event.rawX.toInt()
                    val touchY = event.rawY.toInt()
                    
                    // 如果点击在悬浮球区域，不关闭悬浮窗
                    if (touchX >= ballX && touchX <= ballX + ballWidth &&
                        touchY >= ballY && touchY <= ballY + ballHeight) {
                        return@setOnTouchListener false
                    }
                    
                    // 点击外部区域，关闭悬浮窗
                    hideConfigPopup()
                }
                true
            }
            
             // 先添加覆盖层（底层），再添加悬浮窗（上层），最后添加悬浮球（最上层）
             windowManager?.addView(overlayView, overlayParams)
             windowManager?.addView(configPopupView, popupParams)
             
             // 重新添加悬浮球到最上层，确保可以点击
             if (floatingBallView != null && ballParams != null) {
                 try {
                     windowManager?.removeView(floatingBallView)
                     windowManager?.addView(floatingBallView, ballParams)
                 } catch (e: Exception) {
                     android.util.Log.e("FloatingBallService", "重新添加悬浮球失败", e)
                 }
             }
            isExpanded = true
            
            android.util.Log.d("FloatingBallService", "配置弹窗显示成功")
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "显示配置弹窗失败", e)
        }
    }
    
    // 隐藏配置弹窗
    private fun hideConfigPopup() {
        try {
            if (configPopupView != null && windowManager != null) {
                windowManager?.removeView(configPopupView)
                configPopupView = null
                isExpanded = false
                
                // 移除覆盖层
                try {
                    if (overlayView != null && windowManager != null) {
                        windowManager?.removeView(overlayView)
                        overlayView = null
                    }
                } catch (e: Exception) {
                    // 忽略覆盖层移除错误
                }
                
                android.util.Log.d("FloatingBallService", "配置弹窗隐藏成功")
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "隐藏配置弹窗失败", e)
        }
    }
    
    // 切换配置
    private fun toggleConfig(config: Config) {
        try {
            // 标记手动操作
            ConfigRepository.markManualOperation()
            
            // 获取当前组名
            val groupName = currentGroup?.groupName ?: return
            
            if (config.active) {
                // 关闭当前配置
                config.active = false
                val stopIntent = Intent(this, OverlayService::class.java)
                stopService(stopIntent)
                android.util.Log.d("FloatingBallService", "关闭配置: ${config.configName}")
            } else {
                // 设置为全局默认配置和组默认配置
                ConfigRepository.switchDefaultConfig(this, groupName, config)
                android.util.Log.d("FloatingBallService", "设置配置为默认: ${config.configName}")
                
                // 根据自动开启开关决定是否启动遮罩
                if (ConfigRepository.isAutoStartOverlayEnabled(this)) {
                    // 自动开启开关开启，启动遮罩
                    val startIntent = Intent(this, OverlayService::class.java)
                    startIntent.putExtra("imageUri", config.imageUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(startIntent)
                    } else {
                        startService(startIntent)
                    }
                    android.util.Log.d("FloatingBallService", "启动配置: ${config.configName}")
                } else {
                    // 自动开启开关关闭，只设置配置不启动遮罩
                    android.util.Log.d("FloatingBallService", "自动开启开关关闭，只设置配置不启动遮罩")
                }
            }
            
            // 刷新弹窗显示
            hideConfigPopup()
            showConfigPopup()
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "切换配置失败", e)
        }
    }
    
}
