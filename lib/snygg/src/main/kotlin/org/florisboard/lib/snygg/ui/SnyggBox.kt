/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.value.SnyggAssetResolver

/**
 * A [Box] styled by the current Snygg theme.
 *
 * [clickAndSemanticsModifier] is applied after the background and before padding.
 * [supportsBackgroundImage] allows the style's image to fill the box, clipped to its shape;
 * [backgroundImageDescription] describes that image to accessibility services.
 * [allowClip] permits the style's clip setting to clip the box itself.
 */
@Composable
fun SnyggBox(
    modifier: Modifier = Modifier,
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
    clickAndSemanticsModifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    supportsBackgroundImage: Boolean = false,
    backgroundImageDescription: String? = null,
    allowClip: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    ProvideSnyggStyle(elementName, attributes, selector) { style ->
        val assetResolver = LocalSnyggAssetResolver.current
        val context = LocalContext.current
        val imageUri = if (supportsBackgroundImage) style.backgroundImage.uriOrNull() else null
        val imagePath = rememberBackgroundImagePath(imageUri, assetResolver)
        Box(
            modifier = modifier
                .snyggMargin(style)
                .snyggShadow(style)
                .snyggBorder(style)
                .snyggBackground(style, allowClip = allowClip)
                .then(clickAndSemanticsModifier)
                .snyggPadding(style),
            contentAlignment = contentAlignment,
            propagateMinConstraints = propagateMinConstraints,
        ) {
            if (imagePath != null) {
                AsyncImage(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(style.shape()),
                    // https://github.com/coil-kt/coil/issues/159
                    model = ImageRequest.Builder(context)
                        .data(imagePath)
                        .allowHardware(false) // slower, but hey at least it doesn't crash out of the blue
                        .build(),
                    contentScale = style.contentScale(),
                    contentDescription = backgroundImageDescription,
                )
            }
            content()
        }
    }
}

@Composable
internal fun rememberBackgroundImagePath(uri: String?, resolver: SnyggAssetResolver): String? {
    if (uri == null) return null
    return key(resolver, uri) {
        produceState<String?>(initialValue = null) {
            value = runInterruptible(Dispatchers.IO) {
                resolver.resolveAbsolutePath(uri).getOrNull()
            }
        }.value
    }
}
