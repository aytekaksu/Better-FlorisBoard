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

package dev.patrickgold.florisboard.app.settings.dictionary

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavBackStackEntry
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.settings.theme.DialogProperty
import dev.patrickgold.florisboard.dictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.FREQUENCY_MAX
import dev.patrickgold.florisboard.ime.dictionary.FREQUENCY_MIN
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryValidation
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.compose.Validation
import dev.patrickgold.florisboard.lib.rememberValidationResult
import dev.patrickgold.florisboard.lib.util.launchActivity
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import dev.patrickgold.jetpref.material.ui.JetPrefTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.rippleClickable
import org.florisboard.lib.compose.stringRes

private val UserDictionaryEntryToAdd = UserDictionaryEntry(id = 0, "", 255, null, null)
private const val SystemUserDictionaryUiIntentAction = "android.settings.USER_DICTIONARY_SETTINGS"

enum class UserDictionaryType {
    FLORIS,
    SYSTEM,
}

@Composable
fun UserDictionaryScreen(type: UserDictionaryType, routeEntry: NavBackStackEntry) = FlorisScreen {
    title = stringRes(when (type) {
        UserDictionaryType.FLORIS -> R.string.settings__udm__title_floris
        UserDictionaryType.SYSTEM -> R.string.settings__udm__title_system
    })
    previewFieldVisible = false
    scrollable = false

    val navController = LocalNavController.current
    val context = LocalContext.current
    val dictionaryManager by context.dictionaryManager()
    val model = remember(routeEntry) {
        ViewModelProvider(routeEntry, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UserDictionaryScreenModel(
                database = {
                    when (type) {
                        UserDictionaryType.FLORIS -> dictionaryManager.florisUserDictionary
                        UserDictionaryType.SYSTEM -> dictionaryManager.systemUserDictionary
                    }
                },
                context = context.applicationContext,
            ) as T
        })[UserDictionaryScreenModel::class.java]
    }
    val state = model.state
    val currentLocale = state.currentLocale
    val scope = rememberCoroutineScope()

    var userDictionaryEntryForDialog by remember { mutableStateOf<UserDictionaryEntry?>(null) }

    fun getDisplayNameForLocale(locale: FlorisLocale): String {
        return if (locale == AllLanguagesLocale) {
            context.stringRes(R.string.settings__udm__all_languages)
        } else {
            locale.displayName()
        }
    }

    val importDictionary = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null) model.importFrom(uri)
        },
    )

    val exportDictionary = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(),
        onResult = { uri ->
            if (uri != null) model.exportTo(uri)
        },
    )

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        when (notice) {
            UserDictionaryNotice.ImportSuccess ->
                context.showLongToast(R.string.settings__udm__dictionary_import_success)
            UserDictionaryNotice.ExportSuccess ->
                context.showLongToast(R.string.settings__udm__dictionary_export_success)
            is UserDictionaryNotice.Failure ->
                context.showLongToast(notice.detail?.let { "Error: $it" }
                    ?: context.stringRes(R.string.error__snackbar_message))
        }
        model.clearNotice(notice)
    }

    navigationIcon {
        FlorisIconButton(
            onClick = {
                if (currentLocale != null) {
                    model.selectLocale(null)
                } else {
                    navController.popBackStack()
                }
            },
            icon = if (currentLocale != null) {
                Icons.Default.Close
            } else {
                Icons.AutoMirrored.Filled.ArrowBack
            },
        )
    }

    actions {
        var expanded by remember { mutableStateOf(false) }
        FlorisIconButton(
            onClick = { expanded = !expanded },
            icon = Icons.Default.MoreVert,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                onClick = {
                    importDictionary.launch("*/*")
                    expanded = false
                },
                enabled = !state.busy,
                text = { Text(text = stringRes(R.string.action__import)) },
            )
            DropdownMenuItem(
                onClick = {
                    exportDictionary.launch("my-personal-dictionary.clb")
                    expanded = false
                },
                enabled = !state.busy,
                text = { Text(text = stringRes(R.string.action__export)) },
            )
            if (type == UserDictionaryType.SYSTEM) {
                DropdownMenuItem(
                    onClick = {
                        context.launchActivity { it.action = SystemUserDictionaryUiIntentAction }
                        expanded = false
                    },
                    text = { Text(text = stringRes(R.string.settings__udm__open_system_manager_ui)) },
                )
            }
        }
    }

    floatingActionButton {
        ExtendedFloatingActionButton(
            onClick = {
                if (!state.busy) userDictionaryEntryForDialog = UserDictionaryEntryToAdd
            },
            icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) },
            text = { Text(text = stringRes(R.string.settings__udm__dialog__title_add)) },
        )
    }

    content {
        BackHandler(currentLocale != null) {
            model.selectLocale(null)
        }

        LazyColumn {
            if (state.loading) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            } else if (currentLocale == null && state.languages.isEmpty()) {
                item {
                    Text(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                        text = stringRes(R.string.settings__udm__no_words_in_dictionary),
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            if (!state.loading && currentLocale == null) {
                items(state.languages) { language ->
                    JetPrefListItem(
                        modifier = Modifier.rippleClickable {
                            scope.launch {
                                // Delay makes UI ripple visible and experience better
                                delay(150)
                                model.selectLocale(language)
                            }
                        },
                        text = getDisplayNameForLocale(language),
                    )
                }
            } else if (!state.loading) {
                items(state.words) { wordEntry ->
                    JetPrefListItem(
                        modifier = Modifier.rippleClickable {
                            if (!state.busy) userDictionaryEntryForDialog = wordEntry
                        },
                        text = wordEntry.word,
                        secondaryText = stringRes(
                            if (wordEntry.shortcut != null) {
                                R.string.settings__udm__word_summary_freq_shortcut
                            } else {
                                R.string.settings__udm__word_summary_freq
                            },
                            "freq" to wordEntry.freq,
                            "shortcut" to wordEntry.shortcut,
                        ),
                    )
                }
            }
        }

        val wordEntry = userDictionaryEntryForDialog
        if (wordEntry != null) {
            var showValidationErrors by rememberSaveable { mutableStateOf(false) }
            val isAddWord = wordEntry === UserDictionaryEntryToAdd
            var word by rememberSaveable { mutableStateOf(wordEntry.word) }
            val wordValidation = rememberValidationResult(UserDictionaryValidation.Word, word)
            var freq by rememberSaveable { mutableStateOf(wordEntry.freq.toString()) }
            val freqValidation = rememberValidationResult(UserDictionaryValidation.Freq, freq)
            var shortcut by rememberSaveable { mutableStateOf(wordEntry.shortcut ?: "") }
            val shortcutValidation = rememberValidationResult(UserDictionaryValidation.Shortcut, shortcut)
            var locale by rememberSaveable { mutableStateOf(wordEntry.locale ?: "") }
            val localeValidation = rememberValidationResult(UserDictionaryValidation.Locale, locale)

            JetPrefAlertDialog(
                title = stringRes(if (isAddWord) {
                    R.string.settings__udm__dialog__title_add
                } else {
                    R.string.settings__udm__dialog__title_edit
                }),
                confirmLabel = stringRes(if (isAddWord) {
                    R.string.action__add
                } else {
                    R.string.action__apply
                }),
                onConfirm = {
                    val isInvalid = wordValidation.isInvalid() ||
                        freqValidation.isInvalid() ||
                        shortcutValidation.isInvalid() ||
                        localeValidation.isInvalid()
                    if (isInvalid) {
                        showValidationErrors = true
                    } else {
                        val entry = UserDictionaryEntry(
                            id = wordEntry.id,
                            word = word.trim(),
                            freq = freq.toInt(10),
                            shortcut = shortcut.trim().takeIf { it.isNotBlank() },
                            locale = locale.trim().takeIf { it.isNotBlank() }?.let {
                                // Normalize tag
                                FlorisLocale.fromTag(it).localeTag()
                            },
                        )
                        if (model.save(entry, isAddWord)) {
                            userDictionaryEntryForDialog = null
                        }
                    }
                },
                dismissLabel = stringRes(R.string.action__cancel),
                onDismiss = {
                    userDictionaryEntryForDialog = null
                },
                neutralLabel = if (isAddWord) {
                    null
                } else {
                    stringRes(R.string.action__delete)
                },
                onNeutral = {
                    if (model.delete(wordEntry)) {
                        userDictionaryEntryForDialog = null
                    }
                },
            ) {
                Column {
                    DialogProperty(text = stringRes(R.string.settings__udm__dialog__word_label)) {
                        JetPrefTextField(
                            value = word,
                            onValueChange = { word = it },
                        )
                        Validation(showValidationErrors, wordValidation)
                    }
                    DialogProperty(text = stringRes(
                        R.string.settings__udm__dialog__freq_label,
                        "f_min" to FREQUENCY_MIN, "f_max" to FREQUENCY_MAX,
                    )) {
                        JetPrefTextField(
                            value = freq,
                            onValueChange = { freq = it },
                        )
                        Validation(showValidationErrors, freqValidation)
                    }
                    DialogProperty(text = stringRes(R.string.settings__udm__dialog__shortcut_label)) {
                        JetPrefTextField(
                            value = shortcut,
                            onValueChange = { shortcut = it },
                        )
                        Validation(showValidationErrors, shortcutValidation)
                    }
                    DialogProperty(text = stringRes(R.string.settings__udm__dialog__locale_label)) {
                        JetPrefTextField(
                            value = locale,
                            onValueChange = { locale = it },
                        )
                        Validation(showValidationErrors, localeValidation)
                    }
                }
            }
        }
    }
}
