package org.draken.usagi.settings.sources.catalog

import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.draken.usagi.R
import org.draken.usagi.core.model.getSummary
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.ui.image.FaviconDrawable
import org.draken.usagi.core.util.ext.drawableStart
import org.draken.usagi.core.util.ext.getThemeDimensionPixelOffset
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.databinding.ItemEmptyHintBinding
import org.draken.usagi.databinding.ItemSourceCatalogBinding
import org.draken.usagi.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

interface SourceCatalogListener {
	fun onAddSource(item: SourceCatalogItem.Source)
	fun onDownloadExtension(item: SourceCatalogItem.TachiyomiExtension)
	fun onSourceClick(item: SourceCatalogItem.Source)
}

fun sourceCatalogItemSourceAD(
	listener: SourceCatalogListener
) = adapterDelegateViewBinding<SourceCatalogItem.Source, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener {
		listener.onAddSource(item)
	}
	binding.root.setOnClickListener {
		listener.onSourceClick(item)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	binding.root.updatePaddingRelative(
		end = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0),
	)

	bind {
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewDescription.text = item.source.getSummary(context)
		binding.textViewDescription.drawableStart = if (item.source.isBroken) {
			ContextCompat.getDrawable(context, R.drawable.ic_off_small)
		} else {
			null
		}
		FaviconDrawable(context, R.style.FaviconDrawable_Small, item.source.name)
		binding.imageViewIcon.setImageAsync(item.source)
		// Source items: show add button, hide download/progress
		binding.imageViewAdd.isVisible = true
		binding.imageViewDownload.isGone = true
		binding.progressBar.isGone = true
	}
}

fun sourceCatalogItemTachiyomiAD(
	listener: SourceCatalogListener
) = adapterDelegateViewBinding<SourceCatalogItem.TachiyomiExtension, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewDownload.setOnClickListener {
		listener.onDownloadExtension(item)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	binding.root.updatePaddingRelative(
		end = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0),
	)

	bind {
		binding.textViewTitle.text = item.info.name
		binding.textViewDescription.text = item.info.lang
		binding.textViewDescription.drawableStart = null
		binding.imageViewIcon.setImageDrawable(null) // No icon for remote extensions
		// TachiyomiExtension items: show download button or progress, hide add
		binding.imageViewAdd.isGone = true
		if (item.isInstalling) {
			binding.progressBar.isVisible = true
			binding.imageViewDownload.isGone = true
		} else {
			binding.progressBar.isGone = true
			binding.imageViewDownload.isVisible = true
		}
	}
}

fun sourceCatalogItemHintAD() = adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyHintBinding>(
	{ inflater, parent -> ItemEmptyHintBinding.inflate(inflater, parent, false) },
) {

	binding.buttonRetry.isVisible = false

	bind {
		binding.icon.setImageAsync(item.icon)
		binding.textPrimary.setText(item.title)
		binding.textSecondary.setTextAndVisible(item.text)
	}
}

