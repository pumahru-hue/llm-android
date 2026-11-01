package com.example.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** kind: "photo" или "video"; для видео jpegs — кадры, times — моменты в мс. */
class Prepared(val kind: String, val jpegs: List<ByteArray>, val times: List<Long>)

object MediaUtil {
    const val IMAGE_SIDE = 768    // длинная сторона фото
    const val FRAME_SIDE = 448    // длинная сторона кадра видео
    const val FRAMES = 6          // сколько кадров берём из видео

    fun prepare(ctx: Context, uri: Uri): Prepared {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("video")) {
            val frames = videoFrames(ctx, uri)
            require(frames.isNotEmpty()) { "не удалось достать кадры из видео" }
            return Prepared("video", frames.map { it.second }, frames.map { it.first })
        }
        return Prepared("photo", listOf(imageJpeg(ctx, uri)), emptyList())
    }

    /** То, что реально уходит в модель: (интро для видео) + картинки + вопрос. */
    fun contents(p: Prepared, question: String): Contents {
        val parts = ArrayList<Content>()
        if (p.kind == "video") {
            parts += Content.Text("Это ${p.jpegs.size} кадров одного видео по порядку " +
                "(моменты: ${p.times.joinToString { fmt(it) }}).")
        }
        p.jpegs.forEach { parts += Content.ImageBytes(it) }
        parts += Content.Text(question)
        return Contents.of(*parts.toTypedArray())
    }

    /** Интро (для видео) + картинки. */
    fun parts(p: Prepared): List<Content> {
        val out = ArrayList<Content>()
        if (p.kind == "video") {
            out += Content.Text("Это ${p.jpegs.size} кадров одного видео по порядку " +
                "(моменты: ${p.times.joinToString { fmt(it) }}).")
        }
        p.jpegs.forEach { out += Content.ImageBytes(it) }
        return out
    }

    /** Перекодирует JPEG в меньший размер (для повторно прикладываемых картинок). */
    fun downscale(jpeg: ByteArray, maxSide: Int): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        if (maxOf(bmp.width, bmp.height) <= maxSide) return jpeg
        return encode(bmp, 0, maxSide)
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
