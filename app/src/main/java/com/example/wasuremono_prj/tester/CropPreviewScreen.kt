package com.example.wasuremono_prj.tester

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import com.example.wasuremono_prj.image_cropper.cropImage // 拡張関数のインポート
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wasuremono_prj.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity // 追加
@Composable
fun CropPreviewScreen() {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 1. リソースから画像を読み込む (res/drawable/test_image)
    val original = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.test_image_416x416)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center           // 縦方向の中央（これを追加！）
    ) {
        if (original != null) {
            // 2. 切り抜き実行
            val cropped = remember(original) {
                try {
                    original.cropImage(0, 11, 300, 300)
                } catch (e: Exception) {
                    null
                }
            }

            Text("元画像の一部を切り抜いて表示:")
            Spacer(Modifier.height(16.dp))

            if (cropped != null) {
                // 3. Imageコンポーネントで表示
                Text("切り抜きサイズ (${cropped.width}px${cropped.height} px) で表示:")

                // ピクセルを dp に変換
                val widthInDp = with(density) { cropped.width.toDp() }
                val heightInDp = with(density) { cropped.height.toDp() }

                Image(
                    bitmap = cropped.asImageBitmap(),
                    contentDescription = "Cropped Image",
                    // 固定値(200.dp)ではなく、計算したdpを指定
                    modifier = Modifier.size(widthInDp, heightInDp)
                )
            } else {
                Text("切り抜きに失敗しました。座標を確認してください。")
            }

            Spacer(Modifier.height(16.dp))
            Text("元画像 (${original.width}x${original.height} px) そのまま表示:")
            Spacer(Modifier.height(16.dp))



            // 元画像もピクセルサイズで表示する場合
            val orgWidthInDp = with(density) { original.width.toDp() }
            val orgHeightInDp = with(density) { original.height.toDp() }

            Image(
                bitmap = original.asImageBitmap(),
                contentDescription = "Original Image",
                modifier = Modifier.size(orgWidthInDp, orgHeightInDp)
            )

        } else {
            Text("画像が見つかりません。res/drawable に test_image を入れてください。")
        }
    }
}