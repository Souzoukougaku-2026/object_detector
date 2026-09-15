package com.example.wasuremono_prj.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.example.wasuremono_prj.data.Config
import com.example.wasuremono_prj.data.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import kotlin.collections.indexOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
class ObjectDetector(private val context: Context) : ImageAnalysis.Analyzer {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var lastTime = System.currentTimeMillis()

    // 固定バッファ。再利用してGCを減らす
    private val outputBuffer = Array(1) { Array(10) { FloatArray(8400) } }

    // 検出結果の一時格納用（リサイズして使い回すことで、毎フレームのList生成を抑制）
    private val detectionPool = ArrayList<Detection>(100)
    private val inputSize = Config.MODEL_INPUT_SIZE

    private val inputBuffer =
        ByteBuffer.allocateDirect(1 * 3 * inputSize * inputSize * 4)
            .order(ByteOrder.nativeOrder())

    private val pixelBuffer = IntArray(inputSize * inputSize)
    var onResults: ((detections: List<Detection>, fps: Float) -> Unit)? = null

    init {
        initLiteRT()
    }
    private val floatArrayBuffer = FloatArray(3 * inputSize * inputSize)
    private val inv255 = 1f / 255f // 除算を乗算に変換して高速化
    private fun initLiteRT() {
        try {
            val model = FileUtil.loadMappedFile(context, Config.MODEL_PATH)


            val compatList = CompatibilityList()
            val options = Interpreter.Options().apply {
                try {

                    val delegateOptions = GpuDelegate.Options().apply {

                        isPrecisionLossAllowed = true

                        setSerializationParams(
                            context.codeCacheDir.absolutePath,
                            "yolo_v1"
                        )

                        setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    }

                    this.addDelegate(GpuDelegate(delegateOptions))

                    Log.d("LiteRT", "GPU Delegation is valid on this device")
                } catch (e: Exception) {
                    this.setNumThreads(4)
                    useXNNPACK = true
                    Log.e("LiteRT", "Failed to initialize GPU Delegate, CPU activate", e)
                }
            }



            interpreter = Interpreter(model, options)
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)
            Log.d("MODEL_SHAPE", "Input Tensor Shape: ${inputTensor?.shape()?.contentToString()}")
            Log.d("MODEL_SHAPE", "Output Tensor Shape: ${outputTensor?.shape()?.contentToString()}")
            Log.d("MODEL_SHAPE", "Output Tensor DataType: ${outputTensor?.dataType()}")

            labels = listOf( "bottle", "headphone", "key","smartphone", "umbrella","wallet")
            Log.d("LiteRT", "Loaded labels size = ${labels.size}")
        } catch (e: Exception) {
            Log.e("LiteRT", "Model init failed", e)
        }
    }


    override fun analyze(imageProxy: ImageProxy) {

        val totalStart = System.nanoTime()

        val interp = interpreter

        if (interp == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees

        lateinit var originalBitmap: Bitmap

        logTime("1_toBitmap") {
            originalBitmap = imageProxy.toBitmap()
        }

        lateinit var letterboxedBitmap: Bitmap

        logTime("2_letterbox") {

            letterboxedBitmap =
                finalLetterbox(
                    originalBitmap,
                    rotation,
                    Config.MODEL_INPUT_SIZE
                ).first
        }

        lateinit var tensor: TensorImage

        logTime("3_tensor_prepare") {
            bitmapToChwBuffer(
                letterboxedBitmap,
                inputBuffer,
                pixelBuffer,
                inputSize
            )
        }

        val outputs = mapOf(0 to outputBuffer)

        logTime("4_inference") {

            interp.runForMultipleInputsOutputs(
                arrayOf(inputBuffer),
                outputs
            )
        }
        logRawOutput(outputBuffer[0])
        lateinit var finalResults: List<Detection>

        logTime("5_parse+nms") {

            detectionPool.clear()

            val rawData = outputBuffer[0]

            for (i in 0 until 8400) {

                var maxScore = 0f
                var classId = -1

                for (c in 0 until 6) {
                    val score = rawData[4 + c][i]
                    if (score > maxScore) {
                        maxScore = score
                        classId = c
                    }
                }

                // しきい値を超えたものを処理
                if (maxScore > Config.CONFIDENCE_THRESHOLD) {

                    // このモデルの出力座標は、すでに 0.0 〜 1.0 に正規化されている
                    val cx = rawData[0][i]
                    val cy = rawData[1][i]
                    val w = rawData[2][i]
                    val h = rawData[3][i]

                    // ★修正：Config.MODEL_INPUT_SIZE で割る処理を削除
                    val x1 = (cx - w / 2f).coerceIn(0f, 1f)
                    val y1 = (cy - h / 2f).coerceIn(0f, 1f)
                    val x2 = (cx + w / 2f).coerceIn(0f, 1f)
                    val y2 = (cy + h / 2f).coerceIn(0f, 1f)

                    detectionPool.add(
                        Detection(
                            labels[classId],
                            maxScore,
                            floatArrayOf(
                                x1,
                                y1,
                                x2,
                                y2
                            )
                        )
                    )
                }
            }

            finalResults = nms(detectionPool)
        }

        val now = System.currentTimeMillis()

        val fps = 1000f / (now - lastTime)

        lastTime = now

        logTime("6_callback") {

            onResults?.invoke(finalResults, fps)
        }

        logTime("7_recycle") {

            letterboxedBitmap.recycle()
            originalBitmap.recycle()

            imageProxy.close()
        }

        val totalMs =
            (System.nanoTime() - totalStart) /
                    1_000_000.0

        Log.d(
            "TIME_DEBUG",
            "TOTAL : ${"%.2f".format(totalMs)} ms"
        )
    }

    private fun finalLetterbox(bitmap: Bitmap, rotation: Int, size: Int): Triple<Bitmap, Float, Pair<Float, Float>> {
        val srcW = bitmap.width
        val srcH = bitmap.height

        val rotatedW = if (rotation % 180 == 90) srcH else srcW
        val rotatedH = if (rotation % 180 == 90) srcW else srcH

        val scale = minOf(size / rotatedW.toFloat(), size / rotatedH.toFloat())
        val newW = rotatedW * scale
        val newH = rotatedH * scale

        val dx = (size - newW) / 2f
        val dy = (size - newH) / 2f

        val destBitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(destBitmap)
        canvas.drawColor(android.graphics.Color.BLACK)

        val drawMatrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        val rect = android.graphics.RectF(0f, 0f, srcW.toFloat(), srcH.toFloat())
        drawMatrix.mapRect(rect)

        drawMatrix.postTranslate(-rect.left, -rect.top)
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)

        canvas.drawBitmap(bitmap, drawMatrix, null)

        return Triple(destBitmap, scale, dx to dy)
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val selected = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            selected.add(first)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(first.bbox, next.bbox) > 0.45f) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun calculateIoU(box1: FloatArray, box2: FloatArray): Float {
        val x1 = maxOf(box1[0], box2[0])
        val y1 = maxOf(box1[1], box2[1])
        val x2 = minOf(box1[2], box2[2])
        val y2 = minOf(box1[3], box2[3])

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
        val area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])

        return intersection / (area1 + area2 - intersection)
    }
    /**
     * モデルから出力された生のバッファデータを確認するためのデバッグログ
     */
    private fun logRawOutput(rawData: Array<FloatArray>) {
        Log.d("RAW_DEBUG", "--- Inference Raw Data Snippet (Total 8400 columns) ---")

        var matchCount = 0
        // 8400個のアンカー（グリッド）を走査
        for (i in 0 until 8400) {
            val cx = rawData[0][i]
            val cy = rawData[1][i]
            val w = rawData[2][i]
            val h = rawData[3][i]

            // クラススコア（4番目〜9番目のインデックス）を取得
            val scores = FloatArray(6) { c -> rawData[4 + c][i] }
            val classId = scores.indices.maxByOrNull { scores[it] } ?: 0
            val maxScore = scores[classId]
            // スコアが少しでも高いもの（例: 0.1以上）をピックアップして生データを表示
            // ログが溢れるのを防ぐため、最初の10件程度に制限
            if (maxScore > 0.1f && matchCount < 10) {
                matchCount++
                val scoresString = scores.joinToString(", ") { "%.3f".format(it) }
                Log.d(
                    "RAW_DEBUG",
                    "Index: $i | RawBox[cx=%.2f, cy=%.2f, w=%.2f, h=%.2f] | MaxClass: ${labels.getOrNull(classId)} (Score: %.3f) | AllScores: [$scoresString]".format(cx, cy, w, h, maxScore)
                )
            }
        }

//        if (matchCount == 0) {
//            Log.d("RAW_DEBUG", "スコアが0.1を超えるアンカーはありませんでした。完全に空か、値が異常に低い可能性があります。")
//            // 完全に空の場合は、先頭の3件だけ無理やり出力して形状や値の傾向を確認する
//            for (i in 0 until 3) {
//                Log.d("RAW_DEBUG", "Index $i (No Match) -> cx=${rawData[0][i]}, cy=${rawData[1][i]}, w=${rawData[2][i]}, h=${rawData[3][i]}")
//            }
//        }
        Log.d("RAW_DEBUG", "------------------------------------------------------")
    }
    private inline fun logTime(
        name: String,
        block: () -> Unit
    ): Double {

        val start = System.nanoTime()

        block()

        val end = System.nanoTime()

        val ms = (end - start) / 1_000_000.0

        Log.d("TIME_DEBUG", "$name : ${"%.2f".format(ms)} ms")

        return ms
    }
    private fun bitmapToChwBuffer(
        bitmap: Bitmap,
        buffer: ByteBuffer,
        pixels: IntArray,
        size: Int
    ) {
        buffer.rewind()
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        val planeSize = size * size
        val rOffset = 0
        val gOffset = planeSize
        val bOffset = planeSize * 2

        // FloatArray に連続して直接書き込む
        for (i in 0 until planeSize) {
            val pixel = pixels[i]

            floatArrayBuffer[rOffset + i] = ((pixel shr 16) and 0xFF) * inv255
            floatArrayBuffer[gOffset + i] = ((pixel shr 8) and 0xFF) * inv255
            floatArrayBuffer[bOffset + i] = (pixel and 0xFF) * inv255
        }

        // 一括で ByteBuffer (asFloatBuffer) に転送
        buffer.asFloatBuffer().put(floatArrayBuffer)
    }
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}