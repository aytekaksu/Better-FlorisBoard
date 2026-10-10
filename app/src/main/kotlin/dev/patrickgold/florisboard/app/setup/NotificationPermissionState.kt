/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.setup

/**
 * The [NotificationPermissionState] is used to determine the status of the notification permission.
 * The default value is [NOT_SET].
 * This value is only updated to [GRANTED] or [DENIED] on android 13+, depending on what the user selects.
 */
enum class NotificationPermissionState {
    NOT_SET,
    GRANTED,
    DENIED;
}
