package com.tongpin.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

fun importProfileAvatar(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_AVATAR_INPUT_BYTES) { "请选择不超过 10 MB 的图片" }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: error("无法读取这张图片，请重新选择")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(acceptableAvatarDimensions(bounds.outWidth, bounds.outHeight, false)) { "图片尺寸过大或格式无法识别，请换一张" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > AVATAR_EDGE * 2) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("无法读取这张图片，请换一张")
    val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    try {
        val edge = minOf(oriented.width, oriented.height)
        val square = Bitmap.createBitmap(oriented, (oriented.width - edge) / 2, (oriented.height - edge) / 2, edge, edge)
        val thumbnail = Bitmap.createScaledBitmap(square, AVATAR_EDGE, AVATAR_EDGE, true)
        try {
            var quality = 90
            var result: ByteArray
            do {
                val output = ByteArrayOutputStream()
                require(thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "头像处理失败，请重试" }
                result = output.toByteArray()
                quality -= 15
            } while (result.size > 48 * 1024 && quality >= 30)
            require(result.size <= 48 * 1024) { "这张图片无法生成头像，请换一张" }
            return Base64.encodeToString(result, Base64.NO_WRAP).also { require(it.length <= MAX_AVATAR_STORED_CHARS) }
        } finally {
            if (thumbnail !== square && thumbnail !== oriented) thumbnail.recycle()
            if (square !== oriented) square.recycle()
        }
    } finally { if (oriented !== decoded) oriented.recycle(); decoded.recycle() }
}

/** A damaged backup can contain arbitrary image bytes; never decode without bounds. */
fun decodeProfileAvatar(value: String?): Bitmap? {
    if (value == null || value.length !in 1..MAX_AVATAR_STORED_CHARS) return null
    return runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!acceptableAvatarDimensions(bounds.outWidth, bounds.outHeight, true)) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
