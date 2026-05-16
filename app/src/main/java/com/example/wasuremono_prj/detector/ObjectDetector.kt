package com.example.wasuremono_prj.detector

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.wasuremono_prj.data.Config
import com.example.wasuremono_prj.data.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.metadata.MetadataExtractor

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

            // ラベルの読み込み（Assets優先、失敗したらMetadataから）
            try {
                labels = context.assets.open(Config.LABEL_PATH).bufferedReader().readLines()
            } catch (e: Exception) {
                Log.w("LiteRT", "Assetsからのラベル読み込みに失敗、Metadataを確認します。")
                val extractor = MetadataExtractor(model)
                if (extractor.associatedFileNames.contains(Config.LABEL_PATH)) {
                    val inputStream = extractor.getAssociatedFile(Config.LABEL_PATH)
                    labels = inputStream.bufferedReader().readLines()
                }
            }
            Log.d("LiteRT", "Loaded labels size = ${labels.size}")
        } catch (e: Exception) {
            Log.e("LiteRT", "Model init failed: ${e.message}")
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        val interp = interpreter

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
                .build()

            var tensor = TensorImage(interp.getInputTensor(0).dataType())
            tensor.load(bitmap)
            tensor = processor.process(tensor)

            // 出力用配列の準備（SSDモデル特有の構造）
            val locations = Array(1) { Array(100) { FloatArray(4) } }
            val classes = Array(1) { FloatArray(100) }
            val scores = Array(1) { FloatArray(100) }
            val num = FloatArray(1)

            val outputs = mapOf(
                0 to locations,
                1 to classes,
                2 to scores,
                3 to num
            )

            // 推論実行
            interp.runForMultipleInputsOutputs(arrayOf(tensor.buffer), outputs)

            // 結果の解析
            val result = mutableListOf<Detection>()
            val count = num[0].toInt()

            for (i in 0 until count) {
                val score = scores[0][i]
                if (score > Config.CONFIDENCE_THRESHOLD) {
                    val label = labels.getOrNull(classes[0][i].toInt()) ?: "Unknown"
                    result.add(Detection(label, score, locations[0][i]))
                }
            }

            // FPS計算
            val now = System.currentTimeMillis()
            val fps = 1000f / (now - lastTime)
            lastTime = now

            // UI側に結果を通知
            onResults?.invoke(result, fps)
        }
        // 次の画像を処理するために必ず閉じる
        imageProxy.close()
    }

    // 使い終わったらメモリを解放する
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
