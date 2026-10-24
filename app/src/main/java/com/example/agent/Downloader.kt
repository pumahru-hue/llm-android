
package com.example.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Статус для экрана и уведомления. */
object DownloadState {
    val text = MutableStateFlow("")
    @Volatile var running = false
}

object Downloader {
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    /** Качает в target.part с докачкой. Возвращает .part файл. */
    suspend fun download(target: File, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val part = File(target.path + ".part")
            target.parentFile?.mkdirs()
            var result: File? = null
            var attempts = 0
            while (result == null) {
                ensureActive()
                try {
                    val have = if (part.exists()) part.length() else 0L
                    val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                    c.connectTimeout = 20_000
                    c.readTimeout = 30_000
                    c.instanceFollowRedirects = true
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code == 416) { result = part; continue }   // уже докачано
                    if (code != 200 && code != 206) throw IOException("HTTP $code")
                    val resume = code == 206
                    val start = if (resume) have else 0L
                    val total = start + c.contentLengthLong
                    c.inputStream.use { input ->
                        FileOutputStream(part, resume).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = start
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                                ensureActive()
                            }
                            if (total > 0 && done < total) throw IOException("обрыв соединения")
                        }
                    }
                    result = part
                } catch (e: IOException) {
                    if (++attempts > 30) throw e
                    delay(3000)   // и пробуем докачать
                }
            }
            result
        }

    fun sha256Ok(f: File): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1024 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == SHA256
    }
}
