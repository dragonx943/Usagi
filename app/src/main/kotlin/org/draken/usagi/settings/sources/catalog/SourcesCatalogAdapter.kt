package org.draken.usagi.settings.sources.catalog

import android.content.Context
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.list.fastscroll.FastScroller
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.adapter.loadingStateAD
import org.draken.usagi.list.ui.model.ListModel

class SourcesCatalogAdapter(
	listener: SourceCatalogListener,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.CHAPTER_LIST, sourceCatalogItemSourceAD(listener))
		addDelegate(ListItemType.DOWNLOAD, sourceCatalogItemTachiyomiAD(listener))
		addDelegate(ListItemType.HINT_EMPTY, sourceCatalogItemHintAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return when (val item = items.getOrNull(position)) {
			is SourceCatalogItem.Source -> item.source.getTitle(context)?.take(1)
			is SourceCatalogItem.TachiyomiExtension -> item.info.name.take(1)
			else -> null
		}
	}
}

