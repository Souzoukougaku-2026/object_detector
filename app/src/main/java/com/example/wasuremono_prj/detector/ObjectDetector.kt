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

class ObjectDetector(private val context: Context) : ImageAnalysis.Analyzer {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var lastTime = System.currentTimeMillis()

    // 固定バッファ。再利用してGCを減らす
    private val outputBuffer = Array(1) { Array(9) { FloatArray(3549) } }

    // 検出結果の一時格納用（リサイズして使い回すことで、毎フレームのList生成を抑制）
    private val detectionPool = ArrayList<Detection>(100)

    var onResults: ((detections: List<Detection>, fps: Float) -> Unit)? = null

    init {
        initLiteRT()
    }

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
            labels = listOf("cellphone", "earphone_case", "earphones", "key", "wallet")
            Log.d("LiteRT", "Loaded labels size = ${labels.size}")
        } catch (e: Exception) {
            Log.e("LiteRT", "Model init failed", e)
        }
    }

    private val processor = ImageProcessor.Builder()
        .add(NormalizeOp(0f, 255f))
        .build()

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

            tensor =
                TensorImage(
                    interp.getInputTensor(0).dataType()
                )

            tensor.load(letterboxedBitmap)

            tensor = processor.process(tensor)
        }

        val outputs = mapOf(0 to outputBuffer)

        logTime("4_inference") {

            interp.runForMultipleInputsOutputs(
                arrayOf(tensor.buffer),
                outputs
            )
        }

        lateinit var finalResults: List<Detection>

        logTime("5_parse+nms") {

            detectionPool.clear()

            val rawData = outputBuffer[0]

            for (i in 0 until 3549) {

                var maxScore = 0f
                var classId = -1

                for (c in 0 until 5) {

                    val score = rawData[4 + c][i]

                    if (score > maxScore) {
                        maxScore = score
                        classId = c
                    }
                }

                if (maxScore >
                    Config.CONFIDENCE_THRESHOLD
                ) {

                    val cx = rawData[0][i]
                    val cy = rawData[1][i]
                    val w = rawData[2][i]
                    val h = rawData[3][i]

                    val x1 =
                        ((cx - w / 2f) / 416f)
                            .coerceIn(0f, 1f)

                    val y1 =
                        ((cy - h / 2f) / 416f)
                            .coerceIn(0f, 1f)

                    val x2 =
                        ((cx + w / 2f) / 416f)
                            .coerceIn(0f, 1f)

                    val y2 =
                        ((cy + h / 2f) / 416f)
                            .coerceIn(0f, 1f)

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
                if (calculateIoU(first.box, next.box) > 0.45f) {
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
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}