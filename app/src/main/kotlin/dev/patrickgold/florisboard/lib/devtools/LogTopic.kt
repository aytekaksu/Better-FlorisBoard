/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.devtools

/**
 * This object holds all custom log topics for the [Flog] utility.
 *
 * _Contributors:_ if you add a new feature which is relatively large, you can
 * add a new topic here, just make sure it is a 2^n value and does not
 * exceed the maximum value of [FlogTopic].
 */
object LogTopic {
    const val ALL: FlogTopic =                  Flog.TOPIC_ALL

    const val IMS_EVENTS: FlogTopic =           1u
    const val KEY_EVENTS: FlogTopic =           2u

    const val LAYOUT_MANAGER: FlogTopic =       8u

    const val CRASH_UTILITY: FlogTopic =        2048u

}
