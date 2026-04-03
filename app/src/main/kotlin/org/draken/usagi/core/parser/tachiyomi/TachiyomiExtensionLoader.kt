package org.draken.usagi.core.parser.tachiyomi

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File

/**
 * Loads Tachiyomi extensions directly from APK files using [DexClassLoader].
 * On Android we do NOT need dex2jar; the Dalvik/ART runtime can load DEX bytecode natively.
 *
 * Each extension APK contains metadata in its AndroidManifest.xml:
 * - `tachiyomi.extension.class` → The fully-qualified name of the main class
 * - `tachiyomi.extension.nsfw` → NSFW flag (1 = true)
 *
 * The main class can be either:
 * - A [Source] / [CatalogueSource] → single source
 * - A [SourceFactory] → creates multiple sources via [SourceFactory.createSources]
 */
object TachiyomiExtensionLoader {

	private const val METADATA_CLASS = "tachiyomi.extension.class"
	private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
	private const val METADATA_HAS_DEEPLINKS = "tachiyomi.extension.hasDeepLinks"
	private const val LIB_VERSION_MIN = 1.4
	private const val LIB_VERSION_MAX = 1.5

	fun extensionsDir(context: Context): File {
		val dir = File(context.filesDir, "tachiyomi-extensions")
		if (!dir.exists()) dir.mkdirs()
		return dir
	}

	data class LoadResult(
		val sources: List<CatalogueSource>,
		val pkgName: String,
		val isNsfw: Boolean,
	)

	/**
	 * Load extension sources from an APK file.
	 *
	 * @param context Application context
	 * @param apkFile The downloaded APK file in internal storage
	 * @return A [LoadResult] containing all [CatalogueSource]s from the extension, or null if loading failed
	 */
	fun loadExtension(context: Context, apkFile: File): LoadResult? {
		if (!apkFile.exists()) return null
		val pkgInfo = getPackageInfo(context, apkFile) ?: return null
		val pkgName = pkgInfo.packageName ?: return null

		val appInfo = pkgInfo.applicationInfo ?: return null
		val metadata = appInfo.metaData ?: return null

		val classNames = metadata.getString(METADATA_CLASS)
			?.split(';')
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?: return null

		val isNsfw = metadata.getInt(METADATA_NSFW, 0) == 1

		appInfo.publicSourceDir = apkFile.absolutePath

		val classLoader = DexClassLoader(
			apkFile.absolutePath,
			context.codeCacheDir.absolutePath,
			null,
			context.classLoader,
		)

		val sources = mutableListOf<CatalogueSource>()

		for (className in classNames) {
			try {
				val fqClassName = if (className.startsWith('.')) {
					"$pkgName$className"
				} else {
					className
				}
				val clazz = classLoader.loadClass(fqClassName)
				val instance = clazz.getDeclaredConstructor().newInstance()

				when (instance) {
					is SourceFactory -> {
						instance.createSources().forEach { source ->
							if (source is CatalogueSource) {
								sources.add(source)
							}
						}
					}

					is CatalogueSource -> {
						sources.add(instance)
					}

					is Source -> {
						// Basic Source, not CatalogueSource — skip for now
						// Could wrap in a minimal adapter later
					}
				}
			} catch (e: Exception) {
				android.util.Log.e("TachiyomiLoader", "Failed to load class $className from $pkgName", e)
			}
		}

		return if (sources.isNotEmpty()) {
			LoadResult(sources = sources, pkgName = pkgName, isNsfw = isNsfw)
		} else {
			null
		}
	}

	@Suppress("DEPRECATION")
	private fun getPackageInfo(context: Context, apkFile: File): PackageInfo? {
		return try {
			context.packageManager.getPackageArchiveInfo(
				apkFile.absolutePath,
				PackageManager.GET_META_DATA,
			)
		} catch (_: Exception) {
			null
		}
	}
}
