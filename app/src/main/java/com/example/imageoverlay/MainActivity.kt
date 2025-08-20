package com.example.imageoverlay

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.fragment.app.Fragment
import com.example.imageoverlay.util.ConfigPathUtil
import com.example.imageoverlay.model.ConfigRepository

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.imageoverlay.util.ConfigPathUtil.checkAndFixRoot(this)
        setContentView(R.layout.activity_main)
        com.example.imageoverlay.model.ConfigRepository.load(this)
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_quick_use -> {
                    switchFragment(QuickUseFragment())
                    true
                }
                R.id.nav_config -> {
                    switchFragment(ConfigFragment())
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
        // 默认显示快速使用
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_quick_use
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
} 