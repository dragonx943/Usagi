package org.draken.usagi.core.parser.tachiyomi

import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * A MangaSource backed by a Tachiyomi CatalogueSource, loaded from an extension APK.
 * The [name] uses the "tachi:<pkgName>:<sourceId>" format to be uniquely identifiable and
 * distinguishable from Kotatsu plugin sources.
 */
data class TachiyomiMangaSource(
	val sourceId: Long,
	val sourceName: String,
	val sourceLang: String,
	val pkgName: String,
	val apkName: String,
	val isNsfw: Boolean,
) : MangaSource {

	override val name: String
		get() = "tachi:$pkgName:$sourceId"

	override val locale: String
		get() = if (sourceLang == "all") "" else sourceLang

	override val contentType: ContentType
		get() = if (isNsfw) ContentType.HENTAI else ContentType.MANGA

	override val title: String
		get() = sourceName

	override val isBroken: Boolean
		get() = false
}
