/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import dev.patrickgold.florisboard.lib.ext.ExtensionComponent
import kotlinx.serialization.Serializable

/**
 * Symbols from a keyboard extension used to decide when automatic and phantom spaces are allowed.
 * The spacing symbol fields list individual characters; other editor checks can still prevent a space.
 *
 * @property id Rule ID, usually "default" or a locale tag.
 * @property label Display name; defaults to [id].
 * @property authors Component authors; defaults to "unspecified".
 * @property symbolsPrecedingAutoSpace Characters allowed immediately before an automatic space.
 * @property symbolsFollowingAutoSpace Characters allowed immediately after an automatic space.
 * @property symbolsPrecedingPhantomSpace Characters allowed immediately before a phantom space.
 * @property symbolsFollowingPhantomSpace Characters allowed immediately after a phantom space.
 * @property symbolsTerminatingSentence Reserved sentence-ending characters; the editor does not currently use them.
 */
@Serializable
data class PunctuationRule(
    override val id: String,
    override val label: String = id,
    override val authors: List<String> = listOf("unspecified"),
    val symbolsPrecedingAutoSpace: String,
    val symbolsFollowingAutoSpace: String,
    val symbolsPrecedingPhantomSpace: String,
    val symbolsFollowingPhantomSpace: String,
    val symbolsTerminatingSentence: String,
) : ExtensionComponent {

    companion object {
        /** Basic spacing rule used when the selected rule is missing. */
        val Fallback = PunctuationRule(
            id = "fallback",
            label = "Fallback",
            symbolsPrecedingAutoSpace = ".,?!",
            symbolsFollowingAutoSpace = "",
            symbolsPrecedingPhantomSpace = ".,?!",
            symbolsFollowingPhantomSpace = "",
            symbolsTerminatingSentence = ".?!",
        )
    }
}
