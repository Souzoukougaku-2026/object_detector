package com.example.wasuremono_prj.detector

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

// 1. 色を表すEnum
enum class MyColor {
    RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, PINK,
    BLACK, WHITE, GRAY, UNKNOWN
}


fun Bitmap.detectDominantColor(): MyColor {
    val palette = Palette.from(this)
        .generate()

    val rgb = palette.dominantSwatch?.rgb ?: return MyColor.UNKNOWN
    
    return classifyColor(rgb)
}

private fun classifyColor(rgb: Int): MyColor {
    val hsv = FloatArray(3)
    Color.colorToHSV(rgb, hsv)

    val h = hsv[0] // 0.0 - 360.0 (色相: Hue)
    val s = hsv[1] // 0.0 - 1.0 (彩度: Saturation)
    val v = hsv[2] // 0.0 - 1.0 (明度: Value)

    return when {
        // 無彩色の判定（黒・白・灰色）
        v < 0.15 -> MyColor.BLACK
        v > 0.95 -> MyColor.WHITE
        s < 0.2 -> MyColor.GRAY

        // 有彩色の判定（色相環の角度に基づく）
        h in 0.0..15.0 || h in 330.0..360.0 -> MyColor.RED
        h in 15.0..45.0 -> MyColor.ORANGE
        h in 45.0..70.0 -> MyColor.YELLOW
        h in 70.0..160.0 -> MyColor.GREEN
        h in 160.0..250.0 -> MyColor.BLUE
        h in 250.0..290.0 -> MyColor.PURPLE
        h in 290.0..330.0 -> MyColor.PINK
        else -> MyColor.UNKNOWN
    }
}
