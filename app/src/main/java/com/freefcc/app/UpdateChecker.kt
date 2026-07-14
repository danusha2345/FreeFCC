package com.freefcc.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks for app updates by querying the GitHub Releases API.
 *
 * GitHub API endpoint:
 *   GET https://api.github.com/repos/doesthings/FreeFCC/releases/latest
 *
 * Returns JSON with tag_name, name, body (changelog), and assets[] (download URLs).
 *
 * The download flow:
 *   1. Download the APK to app cache dir
 *   2. Verify the SHA-256 digest against the GitHub asset digest
 *   3. Return the file path — the ViewModel opens an install Intent
 */
data class UpdateInfo(
    val version: String,       // e.g. "1.4"
    val title: String,         // e.g. "v1.4 — Altitude Unlock"
    val changelog: String,    // release body (markdown)
    val downloadUrl: String,  // direct APK URL
    val apkSize: Long,        // bytes
    val publishedAt: String,  // ISO date
    val sha256: String?       // expected hex digest from GitHub, or null if absent
) {
    fun isNewerThan(currentVersion: String): Boolean {
        val cur = parseVersion(currentVersion)
        val new = parseVersion(version)
        val maxLen = maxOf(cur.size, new.size)
        for (i in 0 until maxLen) {
            val c = cur.getOrElse(i) { 0 }
            val n = new.getOrElse(i) { 0 }
            if (n != c) return n > c
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> {
        return v.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
    }
}

object UpdateChecker {

    private const val REPO = "doesthings/FreeFCC"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /**
     * Fetches the latest release info from GitHub.
     * Returns null on any error (network, parse, etc).
     */
    fun fetchLatest(): UpdateInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "FreeFCC-App")
            }

            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").removePrefix("v")
            val name = json.optString("name", "v$tagName")
            val changelog = json.optString("body", "").trim()
            val publishedAt = json.optString("published_at", "")

            // Find the first APK asset
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var apkSize = 0L
            var sha256: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val nameField = asset.optString("name", "")
                if (nameField.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0)
                    // GitHub returns "sha256:<hex>" in the digest field.
                    sha256 = asset.optString("digest", "").removePrefix("sha256:").ifEmpty { null }
                    break
                }
            }

            if (apkUrl == null) return null

            UpdateInfo(
                version = tagName,
                title = name,
                changelog = changelog,
                downloadUrl = apkUrl,
                apkSize = apkSize,
                publishedAt = publishedAt,
                sha256 = sha256
            )
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Downloads the APK file to the app cache directory.
     * Calls onProgress with bytes downloaded / total bytes.
     * Verifies the SHA-256 digest if the GitHub release provided one.
     * Returns the downloaded file, or null on failure (including hash mismatch).
     */
    fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "FreeFCC-App")
            }

            if (conn.responseCode != 200) return null

            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
            val outputDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outputFile = File(outputDir, "freefcc_update.apk")
            val md = info.sha256?.let { MessageDigest.getInstance("SHA-256") }

            FileOutputStream(outputFile).use { fos ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        md?.update(buffer, 0, read)
                        downloaded += read
                        onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }

            // Verify the digest if GitHub provided one. A mismatch means the
            // file was tampered with, the connection was MITM'd, or the
            // release changed underneath us — refuse to install in all cases.
            if (md != null) {
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    outputFile.delete()
                    return null
                }
            }

            outputFile
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}