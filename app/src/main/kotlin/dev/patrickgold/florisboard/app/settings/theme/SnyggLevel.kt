/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.theme

/**
 * SnyggLevel indicates if a rule property is intended to be edited by all users (BASIC) or only by advanced users
 * (ADVANCED). This level is intended for theme editor UIs to hide certain properties in a "basic" mode, for the Snygg
 * theme engine internally this level will be ignored completely.
 */
enum class SnyggLevel : Comparable<SnyggLevel> {
    /** A property is intended to be edited by all users **/
    BASIC,
    /** A property is intended to be edited by advanced users **/
    ADVANCED,
    /** A property is intended to be edited by developers **/
    DEVELOPER;
}
