package com.example.wasuremono_prj.image_cropper

import android.graphics.Bitmap

fun Bitmap.cropImage(x: Int, y: Int, width: Int, height: Int): Bitmap {
    // check the coordinates
    require(0 <= x && x <= this.width - 1) {
        "you passed invalid x coordinate of the left-top corner"
    }
    require(0 <= y && y <= this.height - 1) {
        "you passed invalid y coordinate of the left-top corner"
    }
    require(width > 0) { "width should be more than 0" }
    require(height > 0) { "height should be more than 0" }


    require(x + width <= this.width) {
        "invalid width"
    }
    require(y + height <= this.height) {
        "invalid height"
    }

    return Bitmap.createBitmap(this, x, y, width, height)
}
