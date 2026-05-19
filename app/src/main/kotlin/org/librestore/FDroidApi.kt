package org.librestore

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object FDroidApi {

    private const val TAG = "FDroidApi"
    private const val REPO = "https://f-droid.org/repo"
    private const val INDEX_URL = "$REPO/index-v1.jar"
    private val CACHE_TTL_MS = 60 * 60 * 1000L

    private var cachedApps: List<FDroidApp> = emptyList()
    private var cachedCategories: List<FDroidCategory> = emptyList()
    private var cacheTimestamp = 0L

    val CATEGORIES: List<FDroidCategory>
        get() = cachedCategories.ifEmpty { listOf(FDroidCategory("All", "Todos os apps")) }

    fun carregarIndice(context: Context, forcar: Boolean = false): Boolean {
        val agora = System.currentTimeMillis()
        if (!forcar && cachedApps.isNotEmpty() && (agora - cacheTimestamp) < CACHE_TTL_MS) return true
        return try {
            val jarFile = File(context.cacheDir, "fdroid-index.jar")
            if (forcar || !jarFile.exists() || jarFile.length() < 1000) {
                Log.i(TAG, "Baixando indice F-Droid...")
                if (!baixarArquivo(INDEX_URL, jarFile)) return false
            }
            Log.i(TAG, "Parseando indice (${jarFile.length() / 1024} KB)...")
            val (apps, cats) = parsearIndexJar(jarFile)
            cachedApps = apps
            cachedCategories = buildList {
                add(FDroidCategory("All", "Todos os apps"))
                addAll(cats.sortedBy { it.name })
            }
            cacheTimestamp = agora
            Log.i(TAG, "OK: ${apps.size} apps, ${cats.size} categorias")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar indice", e)
            false
        }
    }

    fun getAppsByCategory(categoryId: String): List<FDroidApp> =
        if (categoryId == "All") cachedApps
        else cachedApps.filter { it.categories.contains(categoryId) }

    fun searchApps(query: String): List<FDroidApp> {
        val q = query.lowercase().trim()
        return cachedApps.filter {
            it.name.lowercase().contains(q) ||
            it.summary.lowercase().contains(q) ||
            it.packageName.lowercase().contains(q)
        }.take(100)
    }

    private fun parsearIndexJar(jarFile: File): Pair<List<FDroidApp>, List<FDroidCategory>> {
        ZipInputStream(jarFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "index-v1.json") {
                    val bytes = zip.readBytes()
                    Log.i(TAG, "index-v1.json encontrado: ${bytes.size / 1024} KB")
                    return parsearJson(JSONObject(bytes.toString(Charsets.UTF_8)))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        Log.e(TAG, "index-v1.json nao encontrado no JAR")
        return Pair(emptyList(), emptyList())
    }

    private fun parsearJson(root: JSONObject): Pair<List<FDroidApp>, List<FDroidCategory>> {
        val apps = mutableListOf<FDroidApp>()
        val categorias = mutableSetOf<String>()

        // apps é JSONArray (lista), cada item tem packageName dentro
        val appsArr: JSONArray = root.optJSONArray("apps") ?: run {
            Log.e(TAG, "Campo 'apps' nao encontrado ou nao e array")
            return Pair(emptyList(), emptyList())
        }

        // packages é JSONObject: { "packageName": [ {...}, {...} ] }
        val packagesObj: JSONObject? = root.optJSONObject("packages")

        Log.i(TAG, "Total de apps no JSON: ${appsArr.length()}")

        for (i in 0 until appsArr.length()) {
            val obj = appsArr.optJSONObject(i) ?: continue
            val pkg = obj.optString("packageName")
            if (pkg.isBlank()) continue
            val name = obj.optString("name").ifBlank { pkg }
            val summary = obj.optString("summary").ifBlank { "" }
            val description = obj.optString("description").ifBlank { "" }
            val iconName = obj.optString("icon").ifBlank { null }

            val cats = mutableListOf<String>()
            val catsArr = obj.optJSONArray("categories")
            if (catsArr != null) {
                for (j in 0 until catsArr.length()) {
                    val c = catsArr.optString(j)
                    if (c.isNotBlank()) { cats.add(c); categorias.add(c) }
                }
            }

            // Pega o primeiro APK da lista desse packageName
            val pkgVersions = packagesObj?.optJSONArray(pkg)
            var latestVersion: String? = null
            var latestApkUrl: String? = null
            var apkSize = 0L
            if (pkgVersions != null && pkgVersions.length() > 0) {
                val latest = pkgVersions.optJSONObject(0)
                latestVersion = latest?.optString("versionName")?.ifBlank { null }
                val apkName = latest?.optString("apkName")?.ifBlank { null }
                if (apkName != null) latestApkUrl = "$REPO/$apkName"
                apkSize = latest?.optLong("size") ?: 0L
            }

            apps.add(FDroidApp(
                packageName = pkg,
                name = name,
                summary = summary,
                description = description,
                categories = cats,
                iconUrl = iconName?.let { "$REPO/icons/$it" },
                latestVersion = latestVersion,
                latestApkUrl = latestApkUrl,
                apkSize = apkSize,
            ))
        }

        Log.i(TAG, "Apps parseados: ${apps.size}, categorias: ${categorias.size}")
        val cats = categorias.map { FDroidCategory(it, traduzir(it)) }
        return Pair(apps, cats)
    }

    private fun traduzir(id: String) = when (id) {
        "Development" -> "Desenvolvimento"
        "Games" -> "Jogos"
        "Internet" -> "Internet"
        "Multimedia" -> "Multimídia"
        "Navigation" -> "Navegação"
        "Phone & SMS" -> "Telefone e SMS"
        "Reading" -> "Leitura"
        "Science & Education" -> "Ciência e Educação"
        "Security" -> "Segurança"
        "System" -> "Sistema"
        "Writing" -> "Escrita"
        "Sports & Health" -> "Esportes e Saúde"
        "Connectivity" -> "Conectividade"
        "Money" -> "Finanças"
        "Time" -> "Produtividade"
        "Graphics" -> "Gráficos"
        "Theming" -> "Temas"
        "Network" -> "Rede"
        "Office" -> "Escritório"
        else -> id
    }

    private fun baixarArquivo(url: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "LibreStore/1.0")
            if (conn.responseCode != 200) { Log.e(TAG, "HTTP ${conn.responseCode}"); return false }
            conn.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            Log.i(TAG, "Download OK: ${dest.length() / 1024} KB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro download", e); dest.delete(); false
        } finally { conn?.disconnect() }
    }

    fun limparCache(context: Context) {
        cachedApps = emptyList(); cachedCategories = emptyList(); cacheTimestamp = 0L
        File(context.cacheDir, "fdroid-index.jar").delete()
    }
}
