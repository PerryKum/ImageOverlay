package com.example.imageoverlay.model

import java.io.Serializable

data class Config(
    val configName: String,
    var imageUri: String,
    var active: Boolean = false,
    var isDefault: Boolean = false
) : Serializable

data class Group(
    val groupName: String,
    val remark: String,
    var id: String = "",  // UUID，唯一标识，兼容旧数据（空串时在 load() 中自动分配）
    val configs: MutableList<Config> = mutableListOf(),
    var defaultConfigName: String? = null,
    var boundPackageName: String? = null,
    var screenType: String = "main"  // "main" 主屏 / "secondary" 副屏
) : Serializable 