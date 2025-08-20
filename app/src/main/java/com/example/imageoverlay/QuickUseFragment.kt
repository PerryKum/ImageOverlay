package com.example.imageoverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.imageoverlay.model.ConfigRepository

class QuickUseFragment : Fragment() {
    private var imageUri: Uri? = null
    private lateinit var imageView: ImageView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private var isOverlayActive = false
    private val PREFS = "quick_use_prefs"
    private val KEY_OVERLAY_ACTIVE = "is_overlay_active"
    private val KEY_IMAGE_URI = "image_uri"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_quick_use, container, false)
        imageView = view.findViewById(R.id.imageView)
        val btnSelect = view.findViewById<Button>(R.id.btnSelect)
        btnStart = view.findViewById(R.id.btnStart)
        btnStop = view.findViewById(R.id.btnStop)

        // 恢复状态
        val sp = requireContext().getSharedPreferences(PREFS, 0)
        isOverlayActive = sp.getBoolean(KEY_OVERLAY_ACTIVE, false)
        val imageUriStr = sp.getString(KEY_IMAGE_URI, null)
        imageUri = if (imageUriStr != null) Uri.parse(imageUriStr) else null
        imageView.setImageURI(imageUri)
        updateButtonState()

        btnSelect.setOnClickListener {
            if (isOverlayActive) {
                Toast.makeText(requireContext(), "请先关闭遮罩", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/png"
            startActivityForResult(intent, 100)
        }

        btnStart.setOnClickListener {
            // 检查是否有预设配置激活
            val hasActivePreset = ConfigRepository.getGroups().any { group ->
                group.configs.any { it.active }
            }
            if (hasActivePreset) {
                Toast.makeText(requireContext(), "请先关闭预设配置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imageUri != null) {
                if (Settings.canDrawOverlays(requireContext())) {
                    val intent = Intent(requireContext(), OverlayService::class.java)
                    intent.putExtra("imageUri", imageUri.toString())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(intent)
                    } else {
                        requireContext().startService(intent)
                    }
                    isOverlayActive = true
                    sp.edit().putBoolean(KEY_OVERLAY_ACTIVE, true).apply()
                    updateButtonState()
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + requireContext().packageName))
                    startActivity(intent)
                }
            } else {
                Toast.makeText(requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            val intent = Intent(requireContext(), OverlayService::class.java)
            requireContext().stopService(intent)
            isOverlayActive = false
            sp.edit().putBoolean(KEY_OVERLAY_ACTIVE, false).apply()
            updateButtonState()
        }

        return view
    }

    private fun updateButtonState() {
        btnStart.isEnabled = !isOverlayActive
        btnStop.isEnabled = isOverlayActive
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) {
            imageUri = data?.data
            imageView.setImageURI(imageUri)
            val sp = requireContext().getSharedPreferences(PREFS, 0)
            sp.edit().putString(KEY_IMAGE_URI, imageUri?.toString()).apply()
        }
    }
} 