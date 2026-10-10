/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.text.key

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class KeyVariation(val value: Int) {
    @SerialName("all")
    ALL(0),
    @SerialName("email")
    EMAIL_ADDRESS(1),
    @SerialName("normal")
    NORMAL(2),
    @SerialName("password")
    PASSWORD(3),
    @SerialName("uri")
    URI(4);

    companion object {
        fun fromInt(int: Int) = entries.firstOrNull { it.value == int } ?: ALL
    }

    fun toInt() = value
}
