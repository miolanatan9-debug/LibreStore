package org.librestore

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadInstallManager(
    private val context: Context,
    private val tts: TtsManager,
    private val onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit,
    private val onFinished: (success: Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var downloadThread: Thread? = null
    private var cancelado = false

    fun baixar(app: FDroidApp) {
        val apkUrl = app.latestApkUrl ?: run {
            tts.falar("Erro: URL do APK nao encontrada")
            onFinished(false)
            return
        }

        cancelado = false
        tts.falar("Iniciando download de ${app.name}")

        // Salva no cache interno — sempre acessível pelo FileProvider
        val destFile = File(context.cacheDir, "${app.packageName}.apk")
        if (destFile.exists()) destFile.delete()

        downloadThread = Thread {
            try {
                baixarComProgresso(apkUrl, destFile)
                if (cancelado) return@Thread
                // Copia para Downloads público para o usuário ter o APK
                try {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    downloadsDir.mkdirs()
                    val publicFile = File(downloadsDir, "${app.packageName}.apk")
                    destFile.copyTo(publicFile, overwrite = true)
                } catch (_: Exception) { /* silencioso — Downloads é opcional */ }

                handler.post {
                    tts.falar("Download de ${app.name} concluido. Instalando...")
                    onFinished(true)
                    instalar(destFile)
                }
            } catch (e: Exception) {
                handler.post {
                    tts.falar("Erro ao baixar ${app.name}: ${e.message}")
                    onFinished(false)
                }
            }
        }
        downloadThread!!.start()
    }

    private fun baixarComProgresso(urlStr: String, dest: File) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "LibreStore/1.0")
            conn.connect()

            val total = conn.contentLengthLong
            var downloaded = 0L

            FileOutputStream(dest).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        if (cancelado) return
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            handler.post { onProgress(percent, downloaded, total) }
                        }
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun instalar(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            tts.falar("Erro ao abrir instalador")
        }
    }

    fun cancelar() {
        cancelado = true
        downloadThread?.interrupt()
        downloadThread = null
    }
}
