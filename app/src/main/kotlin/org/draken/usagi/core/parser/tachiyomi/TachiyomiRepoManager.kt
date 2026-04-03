package org.draken.usagi.core.parser.tachiyomi

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.usagi.core.parser.tachiyomi.model.TachiyomiExtensionInfo
import org.draken.usagi.core.parser.tachiyomi.model.TachiyomiExtensionJson
import org.draken.usagi.core.parser.tachiyomi.model.TachiyomiSourceInfo
import java.io.File

/**
 * Manages Tachiyomi extension repositories.
 * Handles storing repo URLs, fetching the extension index, downloading APKs, and tracking installs.
 */
object TachiyomiRepoManager {

	private const val PREFS_NAME = "tachiyomi_repos"
	private const val KEY_REPO_URLS = "repo_urls"

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	private fun prefs(context: Context): SharedPreferences =
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	// ========== Repo URL management ==========

	fun getRepoUrls(context: Context): Set<String> =
		prefs(context).getStringSet(KEY_REPO_URLS, emptySet()) ?: emptySet()

	fun addRepoUrl(context: Context, url: String) {
		val normalized = normalizeRepoUrl(url)
		val current = getRepoUrls(context).toMutableSet()
		current.add(normalized)
		prefs(context).edit().putStringSet(KEY_REPO_URLS, current).apply()
	}

	fun removeRepoUrl(context: Context, url: String) {
		val normalized = normalizeRepoUrl(url)
		val current = getRepoUrls(context).toMutableSet()
		current.remove(normalized)
		prefs(context).edit().putStringSet(KEY_REPO_URLS, current).apply()
	}

	private fun normalizeRepoUrl(url: String): String {
		var u = url.trim()
		// If user entered a full index.min.json URL, strip it to base
		if (u.endsWith("index.min.json")) {
			u = u.removeSuffix("index.min.json")
		}
		if (!u.endsWith("/")) {
			u += "/"
		}
		return u
	}

	// ========== Fetch extension list ==========

	suspend fun fetchExtensionList(
		httpClient: OkHttpClient,
		repoBaseUrl: String,
	): List<TachiyomiExtensionInfo> = withContext(Dispatchers.IO) {
		val indexUrl = "${repoBaseUrl}index.min.json"
		val request = Request.Builder().url(indexUrl).build()
		val response = httpClient.newCall(request).execute()
		if (!response.isSuccessful) {
			throw Exception("Failed to fetch extension index from $indexUrl: ${response.code}")
		}
		val body = response.body?.string() ?: throw Exception("Empty response from $indexUrl")
		val extensions = json.decodeFromString<List<TachiyomiExtensionJson>>(body)
		extensions.map { ext ->
			TachiyomiExtensionInfo(
				repoBaseUrl = repoBaseUrl,
				name = ext.name,
				pkgName = ext.pkg,
				apkName = ext.apk,
				lang = ext.lang,
				versionCode = ext.code,
				versionName = ext.version,
				isNsfw = ext.nsfw == 1,
				sources = ext.sources?.map { src ->
					TachiyomiSourceInfo(
						name = src.name,
						lang = src.lang,
						id = src.id,
						baseUrl = src.baseUrl,
					)
				} ?: emptyList(),
				iconUrl = "${repoBaseUrl}icon/${ext.pkg}.png",
			)
		}
	}

	// ========== Download APK ==========

	suspend fun downloadExtensionApk(
		context: Context,
		httpClient: OkHttpClient,
		info: TachiyomiExtensionInfo,
	): File = withContext(Dispatchers.IO) {
		val apkUrl = "${info.repoBaseUrl}apk/${info.apkName}"
		val request = Request.Builder().url(apkUrl).build()
		val response = httpClient.newCall(request).execute()
		if (!response.isSuccessful) {
			throw Exception("Failed to download extension APK from $apkUrl: ${response.code}")
		}
		val apkDir = TachiyomiExtensionLoader.extensionsDir(context)
		val outFile = File(apkDir, info.apkName)
		response.body?.byteStream()?.use { input ->
			outFile.outputStream().use { output ->
				input.copyTo(output)
			}
		} ?: throw Exception("Empty response body for $apkUrl")
		outFile
	}

	// ========== Install state management ==========

	fun getInstalledExtensionApks(context: Context): List<String> {
		val dir = TachiyomiExtensionLoader.extensionsDir(context)
		return dir.listFiles { f -> f.extension == "apk" }?.map { it.name } ?: emptyList()
	}

	fun isExtensionInstalled(context: Context, apkName: String): Boolean {
		val file = File(TachiyomiExtensionLoader.extensionsDir(context), apkName)
		return file.exists()
	}

	fun deleteExtension(context: Context, apkName: String): Boolean {
		val file = File(TachiyomiExtensionLoader.extensionsDir(context), apkName)
		return file.delete()
	}
}
