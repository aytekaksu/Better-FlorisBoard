/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.dictionary

import android.content.Context
import android.os.UserManager
import androidx.room.Room

/**
 * Owns the user-dictionary stores for this app process and creates each on first use.
 */
class DictionaryManager(context: Context) {
    private val applicationContext = context.applicationContext ?: context

    val florisUserDictionary: UserDictionaryDatabase by lazy {
        check(applicationContext.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
            "The Floris user dictionary is unavailable before user unlock."
        }
        Room.databaseBuilder(
            applicationContext,
            FlorisUserDictionaryDatabase::class.java,
            FlorisUserDictionaryDatabase.DB_FILE_NAME,
        ).build()
    }

    val systemUserDictionary: SystemUserDictionaryDatabase by lazy {
        SystemUserDictionaryDatabase(applicationContext)
    }
}
