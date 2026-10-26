package com.example.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.ai.edge.litertlm.Content
import java.io.ByteArrayOutputStream
import java.io.InputStream

class Prepared(val parts: List<Content>, val preview: ByteArray)

object MediaUtil {
    const val IMAGE_SIDE = 768    // длинная сторона фото
    const val FRAME_SIDE = 448    // длинная сторона кадра видео
    const val FRAMES = 6          // сколько кадров берём из видео

    fun prepare(ctx: Context, uri: Uri, question: String): Prepared {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        val q = question.ifBlank { "Опиши, что видишь." }

        if (mime.startsWith("video")) {
            val frames = videoFrames(ctx, uri)
            require(frames.isNotEmpty()) { "не удалось достать кадры из видео" }
            val times = frames.joinToString { fmt(it.first) }
            val parts = buildList<Content> {
                add(Content.Text("Это ${frames.size} кадров одного видео по порядку (моменты: $times). " +
                    "Отвечай по-русски."))
                frames.forEach { add(Content.ImageBytes(it.second)) }
                add(Content.Text(q))
            }
            return Prepared(parts, frames.first().second)
        }

        val jpeg = imageJpeg(ctx, uri)
        val parts = listOf<Content>(
            Content.ImageBytes(jpeg),
            Content.Text("$q Отвечай по-русски."),
        )
        return Prepared(parts, jpeg)
    }

    private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    private fun imageJpeg(ctx: Context, uri: Uri): ByteArray {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > IMAGE_SIDE * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("не удалось прочитать изображение")
        val rot = cr.openInputStream(uri)!!.use { exifRotation(it) }
        return encode(bmp, rot, IMAGE_SIDE)
    }

    private fun exifRotation(s: InputStream): Int = try {
        when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Throwable) { 0 }

    /** Кадры из середин равных отрезков видео: (время мс, JPEG). */
    private fun videoFrames(ctx: Context, uri: Uri): List<Pair<Long, ByteArray>> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val n = if (dur <= 0) 1 else FRAMES
            val out = mutableListOf<Pair<Long, ByteArray>>()
            for (i in 0 until n) {
                val t = if (n == 1) 0L else dur * (2 * i + 1) / (2 * n)
                val bmp = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                out += t to encode(bmp, 0, FRAME_SIDE)
            }
            return out
        } finally {
            r.release()
        }
    }

    private fun encode(src: Bitmap, rotation: Int, maxSide: Int): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return bos.toByteArray()
    }
}
