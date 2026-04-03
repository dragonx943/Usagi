package org.draken.usagi.core.parser.tachiyomi.model

/**
 * Runtime representation of a Tachiyomi extension (APK from a repo).
 */
data class TachiyomiExtensionInfo(
	val repoBaseUrl: String,
	val name: String,
	val pkgName: String,
	val apkName: String,
	val lang: String,
	val versionCode: Int,
	val versionName: String,
	val isNsfw: Boolean,
	val sources: List<TachiyomiSourceInfo>,
	val iconUrl: String,
) {
	val isInstalled: Boolean
		get() = false // resolved at runtime by manager
}

data class TachiyomiSourceInfo(
	val name: String,
	val lang: String,
	val id: Long,
	val baseUrl: String,
)
