package com.example.wasuremono_prj.data

data class Detection(
    val classname: String,
    val score: Float,
    val bbox: FloatArray
)
