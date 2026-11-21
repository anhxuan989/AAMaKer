package com.aamaker.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?
)

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String, val latestRelease: GitHubReleaseInfo) : UpdateCheckResult
    data class UpdateAvailable(val currentVersion: String, val latestRelease: GitHubReleaseInfo) : UpdateCheckResult
    data object NoReleases : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

object GitHubUpdateChecker {
    const val DEVELOPER_NAME = "anhxuan989"
    const val REPO_NAME = "AAMaKer"
    const val DEVELOPER_URL = "https://github.com/$DEVELOPER_NAME"
    const val REPO_URL = "https://github.com/$DEVELOPER_NAME/$REPO_NAME"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$DEVELOPER_NAME/$REPO_NAME/releases/latest"

    suspend fun checkLatestRelease(currentVersionRaw: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = currentVersionRaw.trim()
        var connection: HttpURLConnection? = null
        try {
            val url = URL(LATEST_RELEASE_API)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AAMaKer/$currentVersion")
            }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return@withContext UpdateCheckResult.NoReleases
            }
            if (code !in 200..299) {
                return@withContext UpdateCheckResult.Error("HTTP $code")
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name").trim()
            if (tagName.isEmpty()) {
                return@withContext UpdateCheckResult.NoReleases
            }
            val title = json.optString("name").trim().ifEmpty { tagName }
            val releaseNotes = json.optString("body").trim()
            val htmlUrl = json.optString("html_url").trim().ifEmpty { "$REPO_URL/releases/latest" }

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val downloadUrl = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                        apkUrl = downloadUrl
                        break
                    }
                }
            }

            val releaseInfo = GitHubReleaseInfo(
                tagName = tagName,
                title = title,
                releaseNotes = releaseNotes,
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkUrl
            )

            if (isNewerVersion(latestTag = tagName, currentVersion = currentVersion)) {
                UpdateCheckResult.UpdateAvailable(currentVersion, releaseInfo)
            } else {
                UpdateCheckResult.UpToDate(currentVersion, releaseInfo)
            }
        } catch (t: Throwable) {
            UpdateCheckResult.Error(t.localizedMessage ?: t.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    internal fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
        val latestParts = parseVersionParts(latestTag)
        val currentParts = parseVersionParts(currentVersion)
        if (latestParts.isEmpty() || currentParts.isEmpty()) {
            return latestTag.trim().removePrefix("v").removePrefix("V") !=
                currentVersion.trim().removePrefix("v").removePrefix("V")
        }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun parseVersionParts(version: String): List<Int> {
        val cleaned = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.split('.').mapNotNull { part ->
            part.trim().toIntOrNull()
        }
    }
}
