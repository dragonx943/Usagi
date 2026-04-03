@file:Suppress("DEPRECATION")
@file:OptIn(InternalParsersApi::class)

package org.draken.usagi.core.parser.tachiyomi

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import rx.Observable

/**
 * Bridges a Tachiyomi [CatalogueSource] into Kotatsu/Usagi's [MangaParser] interface.
 *
 * This adapter translates between the two model systems:
 * - Tachiyomi's `SManga` / `SChapter` / `Page` ↔ Kotatsu's `Manga` / `MangaChapter` / `MangaPage`
 * - Tachiyomi's RxJava Observable API ↔ Kotatsu's suspend functions
 */
class TachiyomiSourceBridge(
	private val catalogueSource: CatalogueSource,
	private val mangaSource: TachiyomiMangaSource,
	private val sourceConfig: MangaSourceConfig,
) : MangaParser {

	override val source: MangaSource get() = mangaSource

	override val availableSortOrders: Set<SortOrder> = buildSet {
		add(SortOrder.POPULARITY)
		if (catalogueSource.supportsLatest) {
			add(SortOrder.UPDATED)
		}
	}

	@Deprecated("Use filterCapabilities instead")
	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = MangaSearchQueryCapabilities()

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override val config: MangaSourceConfig get() = sourceConfig

	override val authorizationProvider: MangaParserAuthProvider? get() = null

	override val configKeyDomain: ConfigKey.Domain
		get() {
			val baseUrl = (catalogueSource as? HttpSource)?.baseUrl ?: ""
			val domain = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
			return ConfigKey.Domain(domain)
		}

	override val domain: String
		get() {
			val baseUrl = (catalogueSource as? HttpSource)?.baseUrl ?: ""
			return baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
		}

	@Deprecated("Use getList with filter instead")
	override suspend fun getList(query: MangaSearchQuery): List<Manga> {
		return getList(0, SortOrder.POPULARITY, MangaListFilter.EMPTY)
	}

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Tachiyomi uses 1-based page numbers; we approximate from offset
		val page = (offset / 20) + 1
		val searchQuery = filter.query

		val observable: Observable<*> = when {
			!searchQuery.isNullOrEmpty() -> {
				catalogueSource.fetchSearchManga(page, searchQuery, FilterList())
			}

			order == SortOrder.UPDATED && catalogueSource.supportsLatest -> {
				catalogueSource.fetchLatestUpdates(page)
			}

			else -> {
				catalogueSource.fetchPopularManga(page)
			}
		}

		val mangaList = when (val mangasPage = observable.toBlocking().first()) {
			is eu.kanade.tachiyomi.source.model.MangasPage -> mangasPage.mangas
			else -> return emptyList()
		}

		return mangaList.map { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val sManga = manga.toSManga()

		// Fetch details via RxJava API
		val detailsManga = try {
			catalogueSource.fetchMangaDetails(sManga).toBlocking().first()
		} catch (_: Exception) {
			sManga
		}

		// Fetch chapters via RxJava API
		val chapters: List<SChapter> = try {
			catalogueSource.fetchChapterList(sManga).toBlocking().first()
		} catch (_: Exception) {
			emptyList()
		}

		return manga.copy(
			description = detailsManga.description,
			coverUrl = detailsManga.thumbnail_url ?: manga.coverUrl,
			largeCoverUrl = detailsManga.thumbnail_url,
			state = mapStatus(detailsManga.status),
			tags = detailsManga.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
				?.map { MangaTag(it, it, mangaSource) }?.toSet() ?: manga.tags,
			authors = buildSet {
				detailsManga.author?.let { add(it) }
				detailsManga.artist?.takeIf { it != detailsManga.author }?.let { add(it) }
			},
			chapters = chapters.mapIndexed { index, chapter ->
				chapter.toMangaChapter(index)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val sChapter = chapter.toSChapter()
		val pages: List<Page> = try {
			catalogueSource.fetchPageList(sChapter).toBlocking().first()
		} catch (_: Exception) {
			emptyList()
		}
		return pages.map { it.toMangaPage() }
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		// If the URL is already an absolute image URL, return it directly
		val url = page.url
		if (url.startsWith("http://") || url.startsWith("https://")) {
			return url
		}
		// Otherwise, try to resolve via HttpSource
		if (catalogueSource is HttpSource) {
			try {
				val tPage = Page(0, url)
				val resolved = catalogueSource.fetchImageUrl(tPage).toBlocking().first()
				if (!resolved.isNullOrEmpty()) return resolved
			} catch (_: Exception) {
			}
		}
		return url
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions()
	}

	override suspend fun getFavicons(): Favicons {
		return Favicons.EMPTY
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		keys.add(configKeyDomain)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override fun getRequestHeaders(): Headers {
		return if (catalogueSource is HttpSource) {
			catalogueSource.headers
		} else {
			Headers.Builder().build()
		}
	}

	@InternalParsersApi
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? = null

	override fun intercept(chain: Interceptor.Chain): Response {
		return chain.proceed(chain.request())
	}

	// ========== Conversion helpers ==========

	private fun SManga.toManga(): Manga {
		val fullUrl = resolveUrl(url)
		return Manga(
			id = generateUid(fullUrl),
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = fullUrl,
			rating = RATING_UNKNOWN,
			contentRating = if (mangaSource.isNsfw) ContentRating.ADULT else null,
			coverUrl = thumbnail_url,
			tags = genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
				?.map { MangaTag(it, it, mangaSource) }?.toSet() ?: emptySet(),
			state = mapStatus(status),
			authors = buildSet {
				author?.let { add(it) }
				artist?.takeIf { it != author }?.let { add(it) }
			},
			source = mangaSource,
		)
	}

	private fun SChapter.toMangaChapter(index: Int): MangaChapter {
		val fullUrl = resolveUrl(url)
		return MangaChapter(
			id = generateUid(fullUrl),
			title = name,
			number = if (chapter_number > 0) chapter_number else (index + 1).toFloat(),
			volume = 0,
			url = url,
			scanlator = scanlator,
			uploadDate = date_upload,
			branch = null,
			source = mangaSource,
		)
	}

	private fun Page.toMangaPage(): MangaPage {
		val resolvedUrl = imageUrl ?: url
		return MangaPage(
			id = generateUid("${mangaSource.name}_page_$index"),
			url = resolvedUrl,
			preview = null,
			source = mangaSource,
		)
	}

	private fun Manga.toSManga(): SManga {
		return object : SManga {
			override var url: String = this@toSManga.url
			override var title: String = this@toSManga.title
			override var artist: String? = this@toSManga.authors.drop(1).firstOrNull()
			override var author: String? = this@toSManga.authors.firstOrNull()
			override var description: String? = this@toSManga.description
			override var genre: String? = this@toSManga.tags.joinToString(", ") { it.title }
			override var status: Int = reverseMapStatus(this@toSManga.state)
			override var thumbnail_url: String? = this@toSManga.coverUrl
			override var update_strategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE
			override var initialized: Boolean = true
		}
	}

	private fun MangaChapter.toSChapter(): SChapter {
		return object : SChapter {
			override var url: String = this@toSChapter.url
			override var name: String = this@toSChapter.title ?: "Chapter ${this@toSChapter.number}"
			override var date_upload: Long = this@toSChapter.uploadDate
			override var chapter_number: Float = this@toSChapter.number
			override var scanlator: String? = this@toSChapter.scanlator
		}
	}

	private fun resolveUrl(url: String): String {
		if (url.startsWith("http://") || url.startsWith("https://")) return url
		val baseUrl = (catalogueSource as? HttpSource)?.baseUrl ?: ""
		return "$baseUrl$url"
	}

	private fun mapStatus(status: Int): MangaState? = when (status) {
		SManga.ONGOING -> MangaState.ONGOING
		SManga.COMPLETED -> MangaState.FINISHED
		SManga.LICENSED -> MangaState.ABANDONED
		SManga.PUBLISHING_FINISHED -> MangaState.FINISHED
		SManga.CANCELLED -> MangaState.ABANDONED
		SManga.ON_HIATUS -> MangaState.PAUSED
		else -> null
	}

	private fun reverseMapStatus(state: MangaState?): Int = when (state) {
		MangaState.ONGOING -> SManga.ONGOING
		MangaState.FINISHED -> SManga.COMPLETED
		MangaState.ABANDONED -> SManga.CANCELLED
		MangaState.PAUSED -> SManga.ON_HIATUS
		MangaState.UPCOMING -> SManga.UNKNOWN
		MangaState.RESTRICTED -> SManga.UNKNOWN
		null -> SManga.UNKNOWN
	}

}
