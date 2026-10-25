package com.example.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Скачивает и распаковывает маленькую русскую модель Vosk (45 МБ) один раз. */
object VoskModel {
    private const val ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val DIR = "vosk-model-small-ru-0.22"

    suspend fun ensure(ctx: Context, say: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val root = File(ctx.filesDir, "vosk")
        val dir = File(root, DIR)
        if (dir.isDirectory && (dir.list()?.isNotEmpty() == true)) return@withContext dir

        root.mkdirs()
        val zip = File(ctx.cacheDir, "vosk-ru.zip")
        say("Скачиваю модель распознавания имени (45 МБ)…")
        val c = URL(ZIP_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.inputStream.use { i -> zip.outputStream().use { o -> i.copyTo(o) } }

        say("Распаковываю…")
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val out = File(root, e.name)
                if (!out.canonicalPath.startsWith(root.canonicalPath)) throw IOException("плохой архив")
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                z.closeEntry()
                e = z.nextEntry
            }
        }
        zip.delete()
        check(dir.isDirectory) { "после распаковки нет папки $DIR" }
        dir
    }
}
