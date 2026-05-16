package com.example.wasuremono_prj.data

object Config {
    const val MODEL_PATH = "ssdmobilenetv3.tflite"
    const val LABEL_PATH = "labelmap.txt"
    const val CONFIDENCE_THRESHOLD = 0.5f
    val MODEL_INPUT_SIZE = if (MODEL_PATH.contains("v1")) 300 else 320
}
