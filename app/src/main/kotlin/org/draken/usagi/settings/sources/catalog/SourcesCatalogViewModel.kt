package org.draken.usagi.settings.sources.catalog

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.draken.usagi.R
import org.draken.usagi.core.network.BaseHttpClient
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.TABLE_SOURCES
import org.draken.usagi.core.parser.DynamicParserManager
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.parser.tachiyomi.TachiyomiRepoManager
import org.draken.usagi.core.parser.tachiyomi.model.TachiyomiExtensionInfo
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.core.util.ext.mapSortedByCount
import org.draken.usagi.explore.data.MangaSourcesRepository
import org.draken.usagi.explore.data.SourcesSortOrder
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SourcesCatalogViewModel @Inject constructor(
	private val repository: MangaSourcesRepository,
	db: MangaDatabase,
	settings: AppSettings,
	@ApplicationContext private val appContext: Context,
	@BaseHttpClient private val httpClient: OkHttpClient,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val locales: Set<String?> = repository.allMangaSources.mapTo(HashSet<String?>()) { it.locale }.also {
		it.add(null)
	}

	private val searchQuery = MutableStateFlow<String?>(null)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			types = emptySet(),
			locale = Locale.getDefault().language.takeIf { it in locales },
			isNewOnly = false,
		),
	)

	val hasNewSources = repository.observeHasNewSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val contentTypes = MutableStateFlow<List<ContentType>>(emptyList())

	// Remote extensions fetched from repos
	private val remoteExtensions = MutableStateFlow<List<TachiyomiExtensionInfo>>(emptyList())
	private val installingPackages = MutableStateFlow<Set<String>>(emptySet())

	val content: StateFlow<List<ListModel>> = combine(
		searchQuery,
		appliedFilter,
		db.invalidationTracker.createFlow(TABLE_SOURCES),
		remoteExtensions,
		installingPackages,
	) { q, f, _, remote, installing ->
		buildSourcesList(f, q, remote, installing)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		repository.clearNewSourcesBadge()
		launchJob(Dispatchers.Default) {
			contentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
		}
		// Fetch remote extensions from configured repos
		launchJob(Dispatchers.Default) {
			fetchRemoteExtensions()
		}
	}

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun setLocale(value: String?) {
		appliedFilter.value = appliedFilter.value.copy(locale = value)
	}

	fun addSource(source: MangaSource) {
		launchJob(Dispatchers.Default) {
			val rollback = repository.setSourcesEnabled(setOf(source), true)
			onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
		}
	}

	fun setContentType(value: ContentType, isAdd: Boolean) {
		val filter = appliedFilter.value
		val types = EnumSet.noneOf(ContentType::class.java)
		types.addAll(filter.types)
		if (isAdd) {
			types.add(value)
		} else {
			types.remove(value)
		}
		appliedFilter.value = filter.copy(types = types)
	}

	fun setNewOnly(value: Boolean) {
		appliedFilter.value = appliedFilter.value.copy(isNewOnly = value)
	}

	fun downloadExtension(info: TachiyomiExtensionInfo) {
		launchJob(Dispatchers.Default) {
			installingPackages.update { it + info.pkgName }
			try {
				withContext(Dispatchers.IO) {
					TachiyomiRepoManager.downloadExtensionApk(appContext, httpClient, info)
					val pluginsDir = PluginFileLoader.pluginsDir(appContext)
					DynamicParserManager.loadParsersFromDirectory(appContext, pluginsDir)
				}
				// Remove from remote list (it's now installed)
				remoteExtensions.update { list ->
					list.filterNot { it.pkgName == info.pkgName }
				}
				onActionDone.call(ReversibleAction(R.string.extension_installed, null))
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				android.util.Log.e("SourcesCatalogVM", "Failed to install extension: ${info.name}", e)
				onActionDone.call(ReversibleAction(R.string.extension_install_failed, null))
			} finally {
				installingPackages.update { it - info.pkgName }
			}
		}
	}

	private suspend fun fetchRemoteExtensions() {
		val repos = TachiyomiRepoManager.getRepoUrls(appContext)
		if (repos.isEmpty()) return
		val allExtensions = mutableListOf<TachiyomiExtensionInfo>()
		val installed = TachiyomiRepoManager.getInstalledExtensionApks(appContext).toSet()
		withContext(Dispatchers.IO) {
			for (repo in repos) {
				try {
					val extensions = TachiyomiRepoManager.fetchExtensionList(httpClient, repo)
					// Only include extensions that are NOT already installed
					allExtensions.addAll(extensions.filter { it.apkName !in installed })
				} catch (e: Exception) {
					if (e is CancellationException) throw e
					android.util.Log.e("SourcesCatalogVM", "Failed to fetch from $repo", e)
				}
			}
		}
		remoteExtensions.value = allExtensions
	}

	private suspend fun buildSourcesList(
		filter: SourcesCatalogFilter,
		query: String?,
		remote: List<TachiyomiExtensionInfo>,
		installing: Set<String>,
	): List<SourceCatalogItem> {
		// 1. Disabled kotatsu/plugin sources (existing behavior)
		val sources = repository.queryParserSources(
			isDisabledOnly = true,
			isNewOnly = filter.isNewOnly,
			excludeBroken = false,
			types = filter.types,
			query = query,
			locale = filter.locale,
			sortOrder = SourcesSortOrder.ALPHABETIC,
		)
		val result = ArrayList<SourceCatalogItem>(sources.size + remote.size)
		sources.mapTo(result) { SourceCatalogItem.Source(source = it) }

		// 2. Append remote tachiyomi extensions (filtered)
		val filteredRemote = remote.filter { ext ->
			// Apply locale filter
			if (filter.locale != null && ext.lang != filter.locale && ext.lang != "all") {
				return@filter false
			}
			// Apply content type filter
			if (filter.types.isNotEmpty()) {
				val extType = if (ext.isNsfw) ContentType.HENTAI else ContentType.MANGA
				if (extType !in filter.types) return@filter false
			}
			// Apply search query
			if (!query.isNullOrEmpty()) {
				if (!ext.name.contains(query, ignoreCase = true) &&
					!ext.pkgName.contains(query, ignoreCase = true)
				) {
					return@filter false
				}
			}
			// Skip "new only" filter for remote extensions (they're always "new")
			true
		}.sortedBy { it.name }

		filteredRemote.mapTo(result) { ext ->
			SourceCatalogItem.TachiyomiExtension(
				info = ext,
				isInstalling = ext.pkgName in installing,
			)
		}

		return result.ifEmpty {
			listOf(
				if (query == null) {
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.no_manga_sources,
						text = R.string.no_manga_sources_catalog_text,
					)
				} else {
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.nothing_found,
						text = R.string.no_manga_sources_found,
					)
				},
			)
		}
	}

	@WorkerThread
	private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
		val result = repository.allMangaSources.mapSortedByCount { it.contentType }
		return if (isNsfwDisabled) {
			result.filterNot { it == ContentType.HENTAI }
		} else {
			result
		}
	}
}
