package org.draken.usagi.core.parser.tachiyomi.model

import kotlinx.serialization.Serializable

/**
 * Maps the JSON structure from Tachiyomi extension repo's `index.min.json`.
 */
@Serializable
data class TachiyomiExtensionJson(
	val name: String,
	val pkg: String,
	val apk: String,
	val lang: String,
	val code: Int,
	val version: String,
	val nsfw: Int,
	val hasReadme: Int = 0,
	val hasChangelog: Int = 0,
	val sources: List<TachiyomiSourceJson>? = null,
)

@Serializable
data class TachiyomiSourceJson(
	val name: String,
	val lang: String,
	val id: Long,
	val baseUrl: String,
)
