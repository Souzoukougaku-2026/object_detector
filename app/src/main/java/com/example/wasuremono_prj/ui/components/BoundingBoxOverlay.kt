package com.example.wasuremono_prj.ui.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.wasuremono_prj.data.Config
import com.example.wasuremono_prj.data.Detection

@Composable
fun BoundingBoxOverlay(detections: List<Detection>) {
    val density = LocalDensity.current
    val textPxSize = with(density) { 16.dp.toPx() }
    val textPadding = with(density) { 6.dp.toPx() }

    val textPaint = remember {
        Paint().apply {
            color = Color.White.toArgb() // ComposeのColorをArgbに変換
            textSize = textPxSize
            typeface = Typeface.DEFAULT_BOLD // 太字でわかりやすく
            isAntiAlias = true // 文字を滑らかに
        }
    }
    val bgPaint = remember {
        Paint().apply {
            color = Color.Gray.copy(alpha = 0.8f).toArgb() // 半透明のグレーで、背後の映像も見やすく
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val previewWidth = size.width
        val previewHeight = size.height
        val modelInputSize = Config.MODEL_INPUT_SIZE.toFloat()

        // スケール計算（既存）
        val scaleX = previewWidth / modelInputSize
        val scaleY = previewHeight / modelInputSize
        val scale = maxOf(scaleX, scaleY)

        val offsetX = (previewWidth - modelInputSize * scale) / 2f
        val offsetY = (previewHeight - modelInputSize * scale) / 2f

        detections.forEach { detection ->
            val box = detection.box

            // 90度の回転
            val left = (1.0f - box[2]) * modelInputSize * scale + offsetX
            val top = box[1] * modelInputSize * scale + offsetY
            val right = (1.0f - box[0]) * modelInputSize * scale + offsetX
            val bottom = box[3] * modelInputSize * scale + offsetY

            // バウンティボックスの描画
            drawRect(
                color = Color.Red,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 8f)
            )

            // == ラベルの描画 ==

            val labelText = "${detection.label}: %.2f".format(detection.score)
            val textBounds = Rect()
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
            val textWidth = textPaint.measureText(labelText)
            val textHeight = textBounds.height() // 文字の高さ

            // 枠の線幅分、内側に寄せる
            val labelLeft = left + 8f
            val labelTop = top + 8f

            val bgRectF = RectF(
                labelLeft,
                labelTop,
                labelLeft + textWidth + textPadding * 2,
                labelTop + textHeight + textPadding * 2
            )

            // 9. ネイティブCanvasにアクセスして描画
            drawContext.canvas.nativeCanvas.apply {
                // 角を少し丸めて見栄えを良く (半径12f程度)
                drawRoundRect(bgRectF, 12f, 12f, bgPaint)

                // 文字を描画
                // ネイティブのdrawTextは、文字の「左下（ベースライン）」が基準点になるため、
                // 背景矩形の中に収まるようにy座標を調整する
                drawText(
                    labelText,
                    labelLeft + textPadding,
                    labelTop + textHeight + textPadding, // 文字の高さ分、下に下げる
                    textPaint
                )
            }
        }
    }
}