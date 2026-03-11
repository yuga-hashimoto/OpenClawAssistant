package com.openclaw.assistant.utils

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val downloadUrl: String
)

data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("html_url") val htmlUrl: String
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_API_URL = "https://api.github.com/repos/yuga-hashimoto/openclaw-assistant/releases/latest"
    
    // Use a short timeout for update checks so it doesn't block startup long
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
        
    private val gson = Gson()

    suspend fun checkUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to check update: HTTP ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string() ?: return@withContext null
                val release = gson.fromJson(responseBody, GithubRelease::class.java)

                val latestVersion = release.tagName.removePrefix("v")
                val current = currentVersion.removePrefix("v")

                // Simple string comparison for versions (assuming semantic versioning format like 1.2.3)
                // In a more complex scenario, a proper semver parser should be used.
                // For now, if string is different and latest doesn't contain debug/test, we flag an update.
                val hasUpdate = isNewerVersion(current, latestVersion)

                return@withContext UpdateInfo(
                    hasUpdate = hasUpdate,
                    latestVersion = latestVersion,
                    downloadUrl = release.htmlUrl
                )
            }
        } catch (e: java.io.IOException) {
            // Expected network errors (timeout, DNS failure, etc.) — not worth reporting to Crashlytics
            Log.w(TAG, "Update check failed: ${e.message}")
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            return@withContext null
        }
    }
    
    private fun isNewerVersion(current: String, latest: String): Boolean {
        // Handle cases like "1.1.1-debug" or "test" natively returned from Gradle
        if (current.contains("-debug") || current.contains("test")) return false
        
        try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            
            val maxLength = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until maxLength) {
                val c = currentParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (e: Exception) {
            // Fallback to simple string inequality if parsing fails
            return current != latest
        }
    }
}
