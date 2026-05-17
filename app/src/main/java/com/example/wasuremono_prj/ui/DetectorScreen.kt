package com.example.wasuremono_prj.ui

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.wasuremono_prj.data.Detection
import com.example.wasuremono_prj.detector.ObjectDetector
import com.example.wasuremono_prj.ui.components.BoundingBoxOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@Composable
fun DetectorScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    // 画面に表示するための状態（State）
    val detections = remember { mutableStateListOf<Detection>() }
    var fps by remember { mutableFloatStateOf(0f) }

    // カメラと物体認識のセットアップ処理
    LaunchedEffect(Unit) {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider =
                        previewView.surfaceProvider
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .build()

            try {

                cameraProvider.unbindAll()

                // まずPreviewだけ表示
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

            } catch (e: Exception) {
                Log.e("CameraX", "Binding failed", e)
            }

            // ===== GPU初期化は裏 =====

            executor.execute {

                val detector = ObjectDetector(context)

                detector.onResults = {
                        newDetections,
                        currentFps ->

                    ContextCompat
                        .getMainExecutor(context)
                        .execute {

                            detections.clear()
                            detections.addAll(newDetections)
                            fps = currentFps
                        }
                }

                // warmup完了後にAnalyzer attach
                imageAnalysis.setAnalyzer(
                    executor,
                    detector
                )

                Log.d("LiteRT", "Analyzer attached")
            }

        }, ContextCompat.getMainExecutor(context))
    }
    // 画面の描画
    Box(Modifier.fillMaxSize()) {
        // カメラのプレビュー
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 検出枠の描画（Canvas）
        BoundingBoxOverlay(detections = detections)
    }


    // FPSとラベルのテキスト表示
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding() // ステータスバー（時計など）に被らないようにする
    ) {
        Text("FPS: %.1f".format(fps), color = Color.Yellow)
        detections.forEach {
            Text("${it.label}: %.2f".format(it.score), color = Color.Green)
        }
    }
}
