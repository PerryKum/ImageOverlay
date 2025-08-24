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
    val configs: MutableList<Config> = mutableListOf(),
    var defaultConfigName: String? = null,
    var boundPackageName: String? = null
) : Serializable 