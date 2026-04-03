package org.draken.usagi.settings.sources.catalog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.draken.usagi.core.parser.tachiyomi.model.TachiyomiExtensionInfo
import org.draken.usagi.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaSource

sealed interface SourceCatalogItem : ListModel {

	data class Source(
		val source: MangaSource,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Source && other.source == source
		}
	}

	data class TachiyomiExtension(
		val info: TachiyomiExtensionInfo,
		val isInstalling: Boolean = false,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is TachiyomiExtension && other.info.pkgName == info.pkgName
		}
	}

	data class Hint(
		@DrawableRes val icon: Int,
		@StringRes val title: Int,
		@StringRes val text: Int,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Hint && other.title == title
		}
	}
}

