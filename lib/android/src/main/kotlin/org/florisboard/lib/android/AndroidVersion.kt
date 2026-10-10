/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.android

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

object AndroidVersion {
    /** Android 8.1 **/
    inline val ATLEAST_API27_O_MR1
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O_MR1)
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1

    /** Android 9 **/
    inline val ATLEAST_API28_P
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    inline val ATMOST_API28_P
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    /** Android 10 **/
    inline val ATLEAST_API29_Q
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Android 11 **/
    inline val ATLEAST_API30_R
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Android 12 **/
    inline val ATLEAST_API31_S
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Android 13 **/
    inline val ATLEAST_API33_T
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Android 14 **/
    inline val ATLEAST_API34_U
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
