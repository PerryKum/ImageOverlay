package com.example.imageoverlay.model

import java.io.Serializable

data class Config(
    val configName: String,
    var imageUri: String,
    var active: Boolean = false
) : Serializable

data class Group(
    val groupName: String,
    val remark: String,
    val configs: MutableList<Config> = mutableListOf()
) : Serializable 