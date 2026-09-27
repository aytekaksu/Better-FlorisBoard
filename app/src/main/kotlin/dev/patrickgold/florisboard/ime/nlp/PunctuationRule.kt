/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
