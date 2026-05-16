package com.example.wasuremono_prj.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.wasuremono_prj.data.Config
import com.example.wasuremono_prj.data.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

class ObjectDetector(private val context: Context) : ImageAnalysis.Analyzer {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var lastTime = System.currentTimeMillis()

    // 呼び出し元（UI側）に結果を返すためのコールバック関数
    var onResults: ((detections: List<Detection>, fps: Float) -> Unit)? = null

    init {
        initLiteRT()
    }

    private fun initLiteRT() {
        try {
            val model = FileUtil.loadMappedFile(context, Config.MODEL_PATH)
            val compatList = CompatibilityList()
            val options = Interpreter.Options().apply {
                if (compatList.isDelegateSupportedOnThisDevice) {
                    Log.d("LiteRT", "GPU Delegation is valid on this device")
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
                useXNNPACK = true
            }
            interpreter = Interpreter(model, options)
            labels = listOf("key", "wallet")
            Log.d("LiteRT", "Loaded labels size = ${labels.size}")
        } catch (e: Exception) {
            Log.e("LiteRT", "Model init failed: ${e.message}")
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        Log.d("YOLO", "rotation: ${imageProxy.imageInfo.rotationDegrees}")
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotatedBitmap = rotateBitmap(imageProxy.toBitmap(), rotation)
        val interp = interpreter

        val (letterboxedBitmap, scale, pad) = letterbox(rotatedBitmap, Config.MODEL_INPUT_SIZE)
        val (dx, dy) = pad

        if (interp != null) {
            // 画像の前処理
            val processor = ImageProcessor.Builder()
                .add(
                    ResizeOp(
                        Config.MODEL_INPUT_SIZE,
                        Config.MODEL_INPUT_SIZE,
                        ResizeOp.ResizeMethod.BILINEAR
                    )
                )
                .add(NormalizeOp(0f, 255f))
                .build()

            var tensor = TensorImage(interp.getInputTensor(0).dataType())
            tensor.load(letterboxedBitmap)
            tensor = processor.process(tensor)

            Log.d("YOLO", interp.getOutputTensor(0).shape().joinToString())

            val output = Array(1) { Array(300) { FloatArray(6) } }
            interp.run(tensor.buffer, output)
//
//            // 結果の解析
            val rawResult = mutableListOf<Detection>()
            for (i in 0 until 300) {
                val score = output[0][i][4]
                if (score < Config.CONFIDENCE_THRESHOLD) continue

                val cls = output[0][i][5].toInt()
                val label = labels.getOrNull(cls) ?: "Unknown"

                val inputSize = Config.MODEL_INPUT_SIZE
                val x1Px = (output[0][i][0] * inputSize - dx) / scale
                val y1Px = (output[0][i][1] * inputSize - dy) / scale
                val x2Px = (output[0][i][2] * inputSize - dx) / scale
                val y2Px = (output[0][i][3] * inputSize - dy) / scale
                
                val x1 = x1Px / rotatedBitmap.width
                val y1 = y1Px / rotatedBitmap.height
                val x2 = x2Px / rotatedBitmap.width
                val y2 = y2Px / rotatedBitmap.height

                rawResult.add(Detection(label, score, floatArrayOf(x1, y1, x2, y2)))
                Log.d("YOLO_RECT", "x1: $x1, y1: $y1, x2: $x2, y2: $y2")
            }

            val result = nms(rawResult, 0.5f)
            val now = System.currentTimeMillis()
            val fps = 1000f / (now - lastTime)
            lastTime = now

            onResults?.invoke(result, fps)
        }
        letterboxedBitmap.recycle()
        imageProxy.close()
    }

    private fun letterbox(bitmap: Bitmap, size: Int): Triple<Bitmap, Float, Pair<Float, Float>> {
        val width = bitmap.width
        val height = bitmap.height

        val scale = minOf(size / width.toFloat(), size / height.toFloat())

        val newW = (width * scale).toInt()
        val newH = (height * scale).toInt()

        val resized = bitmap.scale(newW, newH, true)

        val padded = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)

        val dx = (size - newW) / 2f
        val dy = (size - newH) / 2f

        canvas.drawBitmap(resized, dx, dy, null)

        return Triple(padded, scale, dx to dy)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun nms(
        detections: List<Detection>,
        iouThreshold: Float = 0.5f
    ): List<Detection> {

        val result = mutableListOf<Detection>()

        // クラスごとに分ける
        val grouped = detections.groupBy { it.label }

        for ((_, dets) in grouped) {

            val sorted = dets.sortedByDescending { it.score }.toMutableList()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result.add(best)

                val iterator = sorted.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    if (iou(best, other) > iouThreshold) {
                        iterator.remove()
                    }
                }
            }
        }

        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.box[0], b.box[0])
        val y1 = maxOf(a.box[1], b.box[1])
        val x2 = minOf(a.box[2], b.box[2])
        val y2 = minOf(a.box[3], b.box[3])

        val interW = maxOf(0f, x2 - x1)
        val interH = maxOf(0f, y2 - y1)
        val interArea = interW * interH

        val areaA = (a.box[2] - a.box[0]) * (a.box[3] - a.box[1])
        val areaB = (b.box[2] - b.box[0]) * (b.box[3] - b.box[1])

        return interArea / (areaA + areaB - interArea + 1e-6f)
    }

    // 使い終わったらメモリを解放する
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
