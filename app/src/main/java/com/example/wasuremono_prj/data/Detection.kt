package com.example.wasuremono_prj.data

data class Detection(
    val label: String,
    val score: Float,
    val box: FloatArray
)
