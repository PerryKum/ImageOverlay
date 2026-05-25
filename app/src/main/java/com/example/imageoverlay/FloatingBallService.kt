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
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.view.GestureDetector
import com.example.imageoverlay.model.Config
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.model.Group
import com.example.imageoverlay.util.AppStateUtil
import com.example.imageoverlay.util.DisplayUtil
import com.example.imageoverlay.util.ForegroundAppUtil
import com.example.imageoverlay.util.OverlayToggler
import com.example.imageoverlay.util.PermissionUtil
import com.google.android.material.tabs.TabLayout

class FloatingBallService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingBallView: View? = null
    private var configPopupView: View? = null
    private var overlayView: View? = null
    private var boundPackageName: String? = null
    private val expandedPopupGroupIds = mutableSetOf<String>()
    private var ballParams: WindowManager.LayoutParams? = null
    private var popupParams: WindowManager.LayoutParams? = null
    
    // 桌面检测相关
    private val homeCheckHandler = Handler(Looper.getMainLooper())
    private val homeCheckIntervalMs = 1000L
    private var lastHomeCheckTime = 0L
    private var launcherStableSince = 0L
    // 拖动相关变量
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var collapsedAtTouchDown = false
    private var dragExpandedFromPeek = false

    // 悬浮球状态
    private var isOnLeftSide = false
    private var isExpanded = false // 配置弹窗是否展开
    private var isBallCollapsed = false // 贴边收起态
    private var popupScreenType: String = "main"
    private val uiHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        if (!isExpanded && !isBallCollapsed) {
            collapseBall()
        }
    }
    private var expandedSizePx = 0
    private var touchWidthPx = 0
    private var peekHeightPx = 0
    private var peekDrawWidthPx = 0
    private var touchSlopPx = 0
    private var autoCollapseDelayMs = 3000L
    private lateinit var tapDetector: GestureDetector
    

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

            if (!ConfigRepository.isFloatingBallEnabled(this)) {
                android.util.Log.d("FloatingBallService", "悬浮球功能已关闭")
                stopSelf()
                return START_NOT_STICKY
            }

            if (!PermissionUtil.checkOverlayPermission(this)) {
                android.util.Log.w("FloatingBallService", "无悬浮窗权限，无法显示悬浮球")
                stopSelf()
                return START_NOT_STICKY
            }

            if (AppStateUtil.isInAppActive(applicationContext)) {
                android.util.Log.d("FloatingBallService", "本应用内不显示悬浮球")
                stopSelf()
                return START_NOT_STICKY
            }

            val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
            if (packageName.isNullOrBlank() || packageName == applicationContext.packageName) {
                android.util.Log.w("FloatingBallService", "无有效绑定应用包名，停止服务")
                stopSelf()
                return START_NOT_STICKY
            }

            val foreground = ForegroundAppUtil.getRecentForegroundPackage(this)
            if (foreground == applicationContext.packageName) {
                android.util.Log.d("FloatingBallService", "本应用在前台，停止服务")
                stopSelf()
                return START_NOT_STICKY
            }

            if (!ConfigRepository.hasBoundGroupForPackage(packageName)) {
                android.util.Log.w("FloatingBallService", "包名未绑定配置组: $packageName")
                stopSelf()
                return START_NOT_STICKY
            }

            boundPackageName = packageName

            if (floatingBallView != null) {
                removeFloatingBall()
            }
            showFloatingBall()
            startHomeDetection()
            android.util.Log.d("FloatingBallService", "悬浮球已显示 package=$packageName")
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "onStartCommand异常", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun showFloatingBall() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val inflater = LayoutInflater.from(this)
            floatingBallView = inflater.inflate(R.layout.floating_ball, null)

            initBallDimensions()
            updatePeekAppearance()

            ballParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop
            tapDetector = GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        if (isDragging) return false
                        handleBallTap()
                        return true
                    }
                }
            )
            ballParams?.gravity = Gravity.TOP or Gravity.START
            isOnLeftSide = false
            isBallCollapsed = false
            ballParams?.y = getScreenHeight() / 2 - expandedSizePx / 2

            floatingBallView?.apply {
                isClickable = true
                isFocusable = false
                setOnTouchListener { _, event -> handleTouchEvent(event) }
            }
            disableTouchOnBallChildren(floatingBallView)

            syncBallWindowLayout()
            windowManager?.addView(floatingBallView, ballParams)
            scheduleAutoCollapse(4000L)
            android.util.Log.d("FloatingBallService", "悬浮球显示成功")
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "显示悬浮球失败", e)
            stopSelf()
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
            uiHandler.removeCallbacks(autoCollapseRunnable)
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
    
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                cancelAutoCollapse()
                collapsedAtTouchDown = isBallCollapsed
                dragExpandedFromPeek = false
                initialX = ballParams?.x ?: 0
                initialY = ballParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()
                val slop = touchSlopPx

                if (kotlin.math.abs(deltaX) > slop || kotlin.math.abs(deltaY) > slop) {
                    isDragging = true
                }

                if (isDragging) {
                    if (collapsedAtTouchDown && isBallCollapsed) {
                        expandBall()
                        dragExpandedFromPeek = true
                        collapsedAtTouchDown = false
                        initialX = ballParams?.x ?: initialX
                        initialY = ballParams?.y ?: initialY
                    }

                    val screenWidth = getScreenWidth()
                    val screenHeight = getScreenHeight()
                    val ballWidth = expandedSizePx
                    val ballHeight = expandedSizePx
                    val newX = initialX + deltaX
                    val newY = initialY + deltaY
                    ballParams?.width = ballWidth
                    ballParams?.height = ballHeight
                    ballParams?.x = newX.coerceIn(-ballWidth / 2, screenWidth - ballWidth / 2)
                    ballParams?.y = newY.coerceIn(0, screenHeight - ballHeight)
                    windowManager?.updateViewLayout(floatingBallView, ballParams)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    snapToEdge()
                    scheduleAutoCollapse()
                }
            }
        }

        if (::tapDetector.isInitialized) {
            tapDetector.onTouchEvent(event)
        }
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            isDragging = false
            dragExpandedFromPeek = false
        }
        return true
    }

    private fun disableTouchOnBallChildren(root: View?) {
        if (root !is ViewGroup) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.isClickable = false
            child.isFocusable = false
            disableTouchOnBallChildren(child)
        }
    }

    private fun handleBallTap() {
        uiHandler.post {
            try {
                if (isBallCollapsed) {
                    expandBall()
                    return@post
                }
                if (isExpanded) {
                    hideConfigPopup()
                } else {
                    showConfigPopup(resetTabToBoundGroup = true)
                }
            } catch (e: Exception) {
                android.util.Log.e("FloatingBallService", "处理点击失败", e)
            }
        }
    }

    private fun snapToEdge() {
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        val ballWidth = currentBallWidth()
        val ballHeight = currentBallHeight()
        val currentX = ballParams?.x ?: 0
        val currentY = ballParams?.y ?: 0

        val centerX = currentX + ballWidth / 2
        isOnLeftSide = centerX < screenWidth / 2

        ballParams?.y = currentY.coerceIn(0, screenHeight - ballHeight)
        syncBallWindowLayout()
        android.util.Log.d("FloatingBallService", "悬浮球吸附到${if (isOnLeftSide) "左边" else "右边"}")
    }

    private fun initBallDimensions() {
        expandedSizePx = dp(R.dimen.floating_ball_expanded_size)
        touchWidthPx = dp(R.dimen.floating_ball_touch_width)
        peekHeightPx = dp(R.dimen.floating_ball_peek_height)
        peekDrawWidthPx = dp(R.dimen.floating_ball_peek_draw_width)
    }

    private fun dp(dimRes: Int): Int {
        return resources.getDimensionPixelSize(dimRes)
    }

    private fun currentBallWidth(): Int =
        if (isBallCollapsed) touchWidthPx else expandedSizePx

    /** 收起态贴在屏幕边缘时的 X，保证贴边条完全在屏内 */
    private fun collapsedBallX(screenWidth: Int): Int =
        if (isOnLeftSide) 0 else screenWidth - touchWidthPx

    private fun currentBallHeight(): Int =
        if (isBallCollapsed) peekHeightPx else expandedSizePx

    private fun scheduleAutoCollapse(delayMs: Long = autoCollapseDelayMs) {
        uiHandler.removeCallbacks(autoCollapseRunnable)
        if (isExpanded) return
        uiHandler.postDelayed(autoCollapseRunnable, delayMs)
    }

    private fun cancelAutoCollapse() {
        uiHandler.removeCallbacks(autoCollapseRunnable)
    }

    private fun collapseBall() {
        if (isBallCollapsed) return
        if (isExpanded) hideConfigPopup()

        val params = ballParams ?: return
        val view = floatingBallView ?: return
        floatingBallView?.animate()?.cancel()
        floatingBallView?.translationX = 0f
        floatingBallView?.translationY = 0f

        updatePeekAppearance()

        val screenWidth = getScreenWidth()
        val startW = expandedSizePx
        val endW = touchWidthPx
        val startH = expandedSizePx
        val endH = peekHeightPx
        val anchorY = params.y

        isBallCollapsed = true
        floatingBallView?.findViewById<FrameLayout>(R.id.ballExpanded)?.visibility = View.GONE
        floatingBallView?.findViewById<FrameLayout>(R.id.ballCollapsed)?.visibility = View.VISIBLE

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                val w = (startW + (endW - startW) * f).toInt()
                val h = (startH + (endH - startH) * f).toInt()
                params.width = w
                params.height = h
                params.x = if (isOnLeftSide) 0 else screenWidth - w
                params.y = anchorY.coerceIn(0, getScreenHeight() - h)
                windowManager?.updateViewLayout(view, params)
            }
            start()
        }
    }

    private fun expandBall() {
        if (!isBallCollapsed) return
        val params = ballParams ?: return
        val view = floatingBallView ?: return
        floatingBallView?.animate()?.cancel()
        floatingBallView?.translationX = 0f
        floatingBallView?.translationY = 0f

        val screenWidth = getScreenWidth()
        val startW = touchWidthPx
        val endW = expandedSizePx
        val startH = peekHeightPx
        val endH = expandedSizePx
        val anchorY = params.y

        isBallCollapsed = false
        floatingBallView?.findViewById<FrameLayout>(R.id.ballCollapsed)?.visibility = View.GONE
        floatingBallView?.findViewById<FrameLayout>(R.id.ballExpanded)?.visibility = View.VISIBLE

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                val w = (startW + (endW - startW) * f).toInt()
                val h = (startH + (endH - startH) * f).toInt()
                params.width = w
                params.height = h
                params.x = if (isOnLeftSide) 0 else screenWidth - w
                params.y = anchorY.coerceIn(0, getScreenHeight() - h)
                windowManager?.updateViewLayout(view, params)
            }
            start()
        }
        scheduleAutoCollapse()
    }

    private fun syncBallWindowLayout() {
        val params = ballParams ?: return
        val view = floatingBallView ?: return
        val screenWidth = getScreenWidth()
        if (isBallCollapsed) {
            params.width = touchWidthPx
            params.height = peekHeightPx
            params.x = collapsedBallX(screenWidth)
        } else {
            params.width = expandedSizePx
            params.height = expandedSizePx
            params.x = if (isOnLeftSide) 0 else screenWidth - expandedSizePx
        }
        params.y = params.y.coerceIn(0, getScreenHeight() - params.height)
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "更新悬浮球布局失败", e)
        }
    }

    private fun bringBallToFront() {
        val view = floatingBallView ?: return
        val params = ballParams ?: return
        try {
            windowManager?.removeView(view)
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "置顶悬浮球失败", e)
        }
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
    
    private fun updatePeekAppearance() {
        val peekCap = floatingBallView?.findViewById<FrameLayout>(R.id.peekCap) ?: return
        val chevron = floatingBallView?.findViewById<ImageView>(R.id.collapseChevron)
        val capLp = peekCap.layoutParams as? FrameLayout.LayoutParams ?: return
        if (isOnLeftSide) {
            capLp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            peekCap.setBackgroundResource(R.drawable.floating_ball_peek_cap_left)
            chevron?.setImageResource(R.drawable.ic_chevron_expand_right)
        } else {
            capLp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            peekCap.setBackgroundResource(R.drawable.floating_ball_peek_cap_right)
            chevron?.setImageResource(R.drawable.ic_chevron_expand_left)
        }
        peekCap.layoutParams = capLp
    }

    private fun isTouchOnBall(rawX: Float, rawY: Float): Boolean {
        val x = ballParams?.x ?: return false
        val y = ballParams?.y ?: return false
        val w = currentBallWidth()
        val h = currentBallHeight()
        val tx = rawX.toInt()
        val ty = rawY.toInt()
        return tx in x..(x + w) && ty in y..(y + h)
    }

    /**
     * 启动桌面检测：如果用户回到桌面/启动器，则自动移除悬浮球并停止服务
     */
    private fun startHomeDetection() {
        val checkRunnable = object : Runnable {
            override fun run() {
                try {
                    if (shouldAutoHideFloatingBall()) {
                        if (launcherStableSince == 0L) launcherStableSince = System.currentTimeMillis()
                        val stable = System.currentTimeMillis() - launcherStableSince >= 2500L
                        if (stable) {
                            android.util.Log.d("FloatingBallService", "应隐藏悬浮球（桌面或本应用）")
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

    private fun shouldAutoHideFloatingBall(): Boolean {
        if (AppStateUtil.isInAppActive(applicationContext)) return true
        val foreground = ForegroundAppUtil.getRecentForegroundPackage(this) ?: return true
        if (foreground == applicationContext.packageName) return true
        val boundPkg = boundPackageName
        if (boundPkg.isNullOrBlank()) return true
        if (foreground == boundPkg) return false
        return true
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_FORCE_SHOW = "forceShow"
    }

    private fun themedInflater(): LayoutInflater {
        val themed = ContextThemeWrapper(applicationContext, R.style.Theme_ImageOverlay)
        return LayoutInflater.from(themed)
    }

    private fun styleConfigPopupTabs(tabLayout: TabLayout) {
        tabLayout.setBackgroundColor(Color.TRANSPARENT)
        tabLayout.setSelectedTabIndicatorColor(Color.WHITE)
        tabLayout.setTabTextColors(
            Color.parseColor("#99FFFFFF"),
            Color.WHITE
        )
        tabLayout.tabRippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
        tabLayout.post {
            for (i in 0 until tabLayout.tabCount) {
                tabLayout.getTabAt(i)?.view?.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun showConfigPopup(resetTabToBoundGroup: Boolean = false) {
        try {
            if (windowManager == null) {
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            }
            val previousScreenType = popupScreenType
            hideConfigPopup()

            if (resetTabToBoundGroup) {
                expandedPopupGroupIds.clear()
                popupScreenType = "main"
            } else {
                popupScreenType = previousScreenType
            }

            val inflater = themedInflater()
            configPopupView = inflater.inflate(R.layout.config_popup, null)

            updateForegroundPackageHeader()

            setupConfigPopupTabs(inflater)

            val configList = configPopupView?.findViewById<LinearLayout>(R.id.configList)
            refreshConfigPopupList(configList, inflater)

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
                (getScreenWidth() * 0.6).toInt(),
                (getScreenHeight() * 0.6).toInt(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

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
                    hideConfigPopup()
                }
                false
            }

            windowManager?.addView(overlayView, overlayParams)
            windowManager?.addView(configPopupView, popupParams)
            isExpanded = true
            cancelAutoCollapse()
            bringBallToFront()

            android.util.Log.d("FloatingBallService", "配置弹窗已添加")

            android.util.Log.d("FloatingBallService", "配置弹窗显示成功")
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "显示配置弹窗失败", e)
        }
    }
    
    private fun updateForegroundPackageHeader() {
        val tvPackage = configPopupView?.findViewById<TextView>(R.id.tvForegroundPackage) ?: return
        tvPackage.visibility = View.VISIBLE
        val pkg = boundPackageName
        tvPackage.text = if (pkg.isNullOrBlank()) "" else formatForegroundAppLabel(pkg)
    }

    private fun setupConfigPopupTabs(inflater: LayoutInflater) {
        val tabLayout = configPopupView?.findViewById<TabLayout>(R.id.tabLayout) ?: return
        val tvScreenLabel = configPopupView?.findViewById<TextView>(R.id.tvScreenLabel)
        val hasSecondary = DisplayUtil.hasSecondaryDisplay(this)
        if (!hasSecondary) {
            tabLayout.visibility = View.GONE
            tvScreenLabel?.visibility = View.VISIBLE
            tvScreenLabel?.text = getString(R.string.floating_ball_tab_main)
            popupScreenType = "main"
            updateForegroundPackageHeader()
            return
        }

        tvScreenLabel?.visibility = View.GONE
        tabLayout.visibility = View.VISIBLE
        styleConfigPopupTabs(tabLayout)
        tabLayout.clearOnTabSelectedListeners()
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.floating_ball_tab_main)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.floating_ball_tab_secondary)))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                popupScreenType = if (tab?.position == 1) "secondary" else "main"
                updateForegroundPackageHeader()
                val configList = configPopupView?.findViewById<LinearLayout>(R.id.configList)
                refreshConfigPopupList(configList, inflater)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        val tabIndex = if (popupScreenType == "secondary") 1 else 0
        tabLayout.getTabAt(tabIndex)?.select()
        styleConfigPopupTabs(tabLayout)
    }

    private fun formatForegroundAppLabel(packageName: String): String {
        return try {
            val label = packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
            getString(R.string.floating_ball_foreground_app, label, packageName)
        } catch (_: Exception) {
            getString(R.string.floating_ball_foreground_app, packageName, packageName)
        }
    }

    private fun groupExpandKey(group: Group): String =
        group.id.ifBlank { "${group.groupName}_${group.screenType}" }

    private fun refreshConfigPopupList(configList: LinearLayout?, inflater: LayoutInflater) {
        configList?.removeAllViews()
        val pkg = boundPackageName
        if (pkg.isNullOrBlank()) {
            return
        }

        val groups = ConfigRepository.getBoundGroupsForScreenType(pkg, popupScreenType)
        if (groups.isEmpty()) {
            configList?.addView(TextView(this).apply {
                text = getString(R.string.floating_ball_no_bound_groups)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@FloatingBallService, android.R.color.darker_gray))
                setPadding(8, 8, 8, 8)
            })
            return
        }

        groups.forEach { group ->
            val expandKey = groupExpandKey(group)
            if (!expandedPopupGroupIds.contains(expandKey)) {
                expandedPopupGroupIds.add(expandKey)
            }

            val groupRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val header = inflater.inflate(R.layout.config_popup_group_header, groupRoot, false)
            val tvExpand = header.findViewById<TextView>(R.id.tvExpand)
            val tvGroupName = header.findViewById<TextView>(R.id.tvGroupName)
            tvGroupName.text = group.groupName

            val children = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 0, 0, 4)
            }

            fun applyExpandedState() {
                val expanded = expandedPopupGroupIds.contains(expandKey)
                tvExpand.text = if (expanded) "▼" else "▶"
                children.visibility = if (expanded) View.VISIBLE else View.GONE
            }
            applyExpandedState()

            header.setOnClickListener {
                if (expandedPopupGroupIds.contains(expandKey)) {
                    expandedPopupGroupIds.remove(expandKey)
                } else {
                    expandedPopupGroupIds.add(expandKey)
                }
                applyExpandedState()
            }

            if (group.configs.isEmpty()) {
                children.addView(TextView(this).apply {
                    text = "（无配置）"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@FloatingBallService, android.R.color.darker_gray))
                    setPadding(0, 4, 0, 8)
                })
            } else {
                for (config in group.configs) {
                    val configItem = inflater.inflate(R.layout.config_popup_item, children, false)
                    configItem.findViewById<TextView>(R.id.configName).text = config.configName
                    val activeOnThisScreen = config.active &&
                        ConfigRepository.isOverlayRunningForScreenType(this, group.screenType) &&
                        group.configs.any { it.configName == config.configName && it.active }
                    val statusView = configItem.findViewById<TextView>(R.id.configStatus)
                    statusView.text = if (activeOnThisScreen) "已激活" else "未激活"
                    statusView.setTextColor(
                        ContextCompat.getColor(
                            this@FloatingBallService,
                            if (activeOnThisScreen) android.R.color.holo_green_dark
                            else android.R.color.darker_gray
                        )
                    )
                    configItem.setOnClickListener { toggleConfig(group, config) }
                    children.addView(configItem)
                }
            }

            groupRoot.addView(header)
            groupRoot.addView(children)
            configList?.addView(groupRoot)
        }
    }

    private fun hideConfigPopup() {
        isExpanded = false
        expandedPopupGroupIds.clear()
        try {
            if (configPopupView != null) {
                windowManager?.removeView(configPopupView)
            }
        } catch (_: Exception) {
        }
        configPopupView = null
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
            }
        } catch (_: Exception) {
        }
        overlayView = null
        bringBallToFront()
        scheduleAutoCollapse(2500L)
        android.util.Log.d("FloatingBallService", "配置弹窗已关闭")
    }
    
    // 切换配置
    private fun toggleConfig(group: Group, config: Config) {
        try {
            ConfigRepository.markManualOperation()

            val groupId = group.id
            val screenType = group.screenType
            val activeOnThisScreen = config.active &&
                ConfigRepository.isOverlayRunningForScreenType(this, screenType) &&
                group.configs.any { it.configName == config.configName && it.active }

            if (activeOnThisScreen) {
                config.active = false
                ConfigRepository.setDefaultActive(this, false, screenType)
                OverlayService.stopDisplay(
                    this,
                    ConfigRepository.displayKeyForScreenType(this, screenType)
                )
                ConfigRepository.save(this)
                android.util.Log.d("FloatingBallService", "关闭配置: ${group.groupName}/${config.configName}")
            } else {
                ConfigRepository.switchDefaultConfig(this, groupId, config)
                android.util.Log.d("FloatingBallService", "设置配置: ${group.groupName}/${config.configName}")

                if (ConfigRepository.isAutoStartOverlayEnabled(this)) {
                    OverlayToggler.turnOnOverlayForBoundGroup(this, group, config)
                }
            }

            refreshConfigPopupInPlace()
            scheduleAutoCollapse(4000L)
        } catch (e: Exception) {
            android.util.Log.e("FloatingBallService", "切换配置失败", e)
        }
    }

    private fun refreshConfigPopupInPlace() {
        val view = configPopupView ?: return
        val configList = view.findViewById<LinearLayout>(R.id.configList)
        refreshConfigPopupList(configList, themedInflater())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        uiHandler.removeCallbacks(autoCollapseRunnable)
        super.onTaskRemoved(rootIntent)
    }
}

