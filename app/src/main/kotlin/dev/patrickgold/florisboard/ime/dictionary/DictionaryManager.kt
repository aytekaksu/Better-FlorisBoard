/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.dictionary

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.room.Room

/**
 * Owns the user-dictionary stores for this app process and creates each on first use.
 */
class DictionaryManager(context: Context) {
    private val applicationContext = context.applicationContext ?: context

    val florisUserDictionary: UserDictionaryDatabase by lazy {
        check(UserManagerCompat.isUserUnlocked(applicationContext)) {
            "The Floris user dictionary is unavailable before user unlock."
        }
        Room.databaseBuilder(
            applicationContext,
            FlorisUserDictionaryDatabase::class.java,
            FlorisUserDictionaryDatabase.DB_FILE_NAME,
        ).allowMainThreadQueries().build()
    }

    val systemUserDictionary: SystemUserDictionaryDatabase by lazy {
        SystemUserDictionaryDatabase(applicationContext)
    }
}
