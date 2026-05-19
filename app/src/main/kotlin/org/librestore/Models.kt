package org.librestore

data class FDroidApp(
    val packageName: String,
    val name: String,
    val summary: String,
    val description: String,
    val categories: List<String>,
    val iconUrl: String?,
    val latestVersion: String?,
    val latestApkUrl: String?,
    val apkSize: Long,
)

data class FDroidCategory(
    val id: String,
    val name: String,
)

// Resposta da API de índice do F-Droid
data class FDroidIndex(
    val apps: List<FDroidApp>,
    val categories: List<FDroidCategory>,
)
