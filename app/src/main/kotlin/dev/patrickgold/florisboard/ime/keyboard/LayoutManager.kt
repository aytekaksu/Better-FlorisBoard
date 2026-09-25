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

package dev.patrickgold.florisboard.ime.keyboard

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.keyboardExtensionRepository
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.popup.PopupMapping
import dev.patrickgold.florisboard.ime.popup.PopupMappingComponent
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.io.DefaultJsonConfig
import dev.patrickgold.florisboard.lib.io.ZipUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import org.florisboard.lib.kotlin.DeferredResult
import org.florisboard.lib.kotlin.runCatchingAsync

private data class LTN(
    val type: LayoutType,
    val name: ExtensionComponentName,
)

data class CachedLayout(
    val type: LayoutType,
    val name: ExtensionComponentName,
    val meta: LayoutArrangementComponent,
    val arrangement: LayoutArrangement,
)

private data class CachedPopupMapping(
    val name: ExtensionComponentName,
    val meta: PopupMappingComponent,
    val mapping: PopupMapping,
)

private class GenerationCache<K, V> {
    private val entries = hashMapOf<K, DeferredResult<V>>()
    private val guard = Mutex()
    private var generation = -1L

    suspend fun load(
        scope: CoroutineScope,
        requestedGeneration: Long,
        key: K,
        description: String,
        create: CoroutineScope.() -> DeferredResult<V>,
    ): V = guard.withLock {
        if (requestedGeneration > generation) {
            entries.clear()
            generation = requestedGeneration
        }
        if (requestedGeneration < generation) {
            // An older computation must not put a stale entry back after a refresh.
            return@withLock scope.create()
        }
        entries[key]?.also {
            flogDebug(LogTopic.LAYOUT_MANAGER) { "Using cached $description" }
        } ?: run {
            flogDebug(LogTopic.LAYOUT_MANAGER) { "Loading $description" }
            scope.create().also { entries[key] = it }
        }
    }.await().getOrThrow()
}

data class DebugLayoutComputationResult(
    val main: Result<CachedLayout?>,
    val mod: Result<CachedLayout?>,
    val ext: Result<CachedLayout?>,
) {
    fun allLayoutsSuccess(): Boolean {
        return main.isSuccess && mod.isSuccess && ext.isSuccess
    }
}

/** Loads and caches keyboard layouts and popup mappings. */
class LayoutManager(context: Context) {
    private val prefs by FlorisPreferenceStore
    private val appContext by context.appContext()
    private val extensionManager by context.extensionManager()
    private val keyboardExtensionRepository by context.keyboardExtensionRepository()

    private val layoutCache = GenerationCache<LTN, CachedLayout>()
    private val popupMappingCache = GenerationCache<ExtensionComponentName, CachedPopupMapping>()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val debugLayoutComputationResultFlow = MutableStateFlow<DebugLayoutComputationResult?>(null)

    /**
     * Loads the layout for the specified type and name.
     *
     * @return A deferred result for a layout.
     */
    private fun loadLayoutAsync(
        ltn: LTN?,
        allowNullLTN: Boolean,
        snapshot: KeyboardExtensionSnapshot,
    ) = ioScope.runCatchingAsync {
        if (!allowNullLTN) {
            requireNotNull(ltn) { "Invalid argument value for 'ltn': null" }
        }
        if (ltn == null) {
            return@runCatchingAsync null
        }
        layoutCache.load(this, snapshot.generation, ltn, "layout: type=${ltn.type}") {
            val meta = snapshot.layouts[ltn.type]?.get(ltn.name)
                ?: error("No indexed entry found for ${ltn.type} - ${ltn.name}")
            val ext = extensionManager.getExtensionById(ltn.name.extensionId)
                ?: error("Extension ${ltn.name.extensionId} not found")
            val path = meta.arrangementFile(ltn.type)
            async {
                runCatching {
                    val jsonStr = ZipUtils.readFileFromArchive(appContext, ext.sourceRef!!, path).getOrThrow()
                    val arrangement = DefaultJsonConfig.decodeFromString<LayoutArrangement>(jsonStr)
                    CachedLayout(ltn.type, ltn.name, meta, arrangement)
                }
            }
        }
    }

    private fun loadPopupMappingAsync(
        snapshot: KeyboardExtensionSnapshot,
        subtype: Subtype? = null,
    ) = ioScope.runCatchingAsync {
        val name = subtype?.popupMapping ?: extCorePopupMapping("default")
        popupMappingCache.load(this, snapshot.generation, name, "popup mapping") {
            val meta = snapshot.popupMappings[name]
                ?: error("No indexed entry found for $name")
            val ext = extensionManager.getExtensionById(name.extensionId)
                ?: error("Extension ${name.extensionId} not found")
            val path = meta.mappingFile()
            async {
                runCatching {
                    val jsonStr = ZipUtils.readFileFromArchive(appContext, ext.sourceRef!!, path).getOrThrow()
                    val mapping = DefaultJsonConfig.decodeFromString<PopupMapping>(jsonStr)
                    CachedPopupMapping(name, meta, mapping)
                }
            }
        }
    }

    /**
     * Merges the specified layouts (LTNs) and returns the computed layout.
     * The computed layout may look like this:
     *   e e e e e e e e e e      e = extension
     *   c c c c c c c c c c      c = main
     *    c c c c c c c c c       m = mod
     *   m c c c c c c c c m
     *   m m m m m m m m m m
     *
     * @param keyboardMode The keyboard mode for the returning [TextKeyboard].
     * @param subtype The subtype used for populating the extended popups.
     * @param main The main layout type and name.
     * @param modifier The modifier (mod) layout type and name.
     * @param extension The extension layout type and name.
     * @return a [TextKeyboard] object, regardless of the specified LTNs or errors.
     */
    private suspend fun mergeLayouts(
        keyboardMode: KeyboardMode,
        subtype: Subtype,
        main: LTN? = null,
        modifier: LTN? = null,
        extension: LTN? = null,
        snapshot: KeyboardExtensionSnapshot,
    ): TextKeyboard {
        val extendedPopupsDefault = loadPopupMappingAsync(snapshot)
        val extendedPopups = loadPopupMappingAsync(snapshot, subtype)

        val mainLayoutResult = loadLayoutAsync(main, allowNullLTN = false, snapshot = snapshot).await()
        val mainLayout = mainLayoutResult.onFailure {
            flogWarning { "Layout load failed: mode=$keyboardMode, role=main, error=${it.javaClass.simpleName}" }
        }.getOrNull()
        val modifierToLoad = if (mainLayout?.meta?.modifier != null) {
            val layoutType = when (mainLayout.type) {
                LayoutType.SYMBOLS -> {
                    LayoutType.SYMBOLS_MOD
                }
                LayoutType.SYMBOLS2 -> {
                    LayoutType.SYMBOLS2_MOD
                }
                else -> {
                    LayoutType.CHARACTERS_MOD
                }
            }
            LTN(layoutType, mainLayout.meta.modifier)
        } else {
            modifier
        }
        val modifierLayoutResult = loadLayoutAsync(modifierToLoad, allowNullLTN = true, snapshot = snapshot).await()
        val modifierLayout = modifierLayoutResult.onFailure {
            flogWarning { "Layout load failed: mode=$keyboardMode, role=modifier, error=${it.javaClass.simpleName}" }
        }.getOrNull()
        val extensionLayoutResult = loadLayoutAsync(extension, allowNullLTN = true, snapshot = snapshot).await()
        val extensionLayout = extensionLayoutResult.onFailure {
            flogWarning { "Layout load failed: mode=$keyboardMode, role=extension, error=${it.javaClass.simpleName}" }
        }.getOrNull()

        debugLayoutComputationResultFlow.value = DebugLayoutComputationResult(
            main = mainLayoutResult,
            mod = modifierLayoutResult,
            ext = extensionLayoutResult,
        )

        val computedArrangement: ArrayList<Array<TextKey>> = arrayListOf()
        fun addRow(row: List<AbstractKeyData>) {
            computedArrangement.add(Array(row.size) { TextKey(row[it]) })
        }

        for (row in extensionLayout?.arrangement.orEmpty()) {
            addRow(row)
        }

        val mainRows = mainLayout?.arrangement.orEmpty()
        for ((index, row) in mainRows.withIndex()) {
            if (modifierLayout != null && index == mainRows.lastIndex) {
                // The first modifier row places the last main row at its spacer key.
                val merged = arrayListOf<TextKey>()
                for (modKey in modifierLayout.arrangement.firstOrNull().orEmpty()) {
                    if (modKey is TextKeyData && modKey.code == 0) {
                        merged.addAll(row.map { TextKey(it) })
                    } else {
                        merged.add(TextKey(modKey))
                    }
                }
                computedArrangement.add(merged.toTypedArray())
            } else {
                addRow(row)
            }
        }
        for ((index, row) in modifierLayout?.arrangement.orEmpty().withIndex()) {
            if (mainLayout == null || index > 0) {
                addRow(row)
            }
        }

        // Add hints to keys
        if (keyboardMode == KeyboardMode.CHARACTERS && computedArrangement.isNotEmpty()) {
            val symbolsComputedArrangement = computeKeyboardAsync(KeyboardMode.SYMBOLS, subtype, snapshot).await().arrangement
            // number row hint always happens on first row
            if (prefs.keyboard.hintedNumberRowEnabled.get() && symbolsComputedArrangement.isNotEmpty()) {
                val row = computedArrangement[0]
                val symbolRow = symbolsComputedArrangement[0]
                addRowHints(row, symbolRow, KeyType.NUMERIC)
            }
            // all other symbols are added bottom-aligned
            val rOffset = computedArrangement.size - symbolsComputedArrangement.size
            for ((r, row) in computedArrangement.withIndex()) {
                if (r < rOffset) {
                    continue
                }
                val symbolRow = symbolsComputedArrangement.getOrNull(r - rOffset)
                if (symbolRow != null) {
                    addRowHints(row, symbolRow, KeyType.CHARACTER)
                }
            }
        }

        return TextKeyboard(
            arrangement = computedArrangement.toTypedArray(),
            mode = keyboardMode,
            extendedPopupMapping = extendedPopups.await().onFailure {
                flogWarning(LogTopic.LAYOUT_MANAGER) { "Popup mapping failed: subtype (${it.javaClass.simpleName})" }
            }.getOrNull()?.mapping,
            extendedPopupMappingDefault = extendedPopupsDefault.await().onFailure {
                flogWarning(LogTopic.LAYOUT_MANAGER) { "Popup mapping failed: default (${it.javaClass.simpleName})" }
            }.getOrNull()?.mapping
        )
    }

    private fun addRowHints(main: Array<TextKey>, hint: Array<TextKey>, hintType: KeyType) {
        for ((k,key) in main.withIndex()) {
            val hintKey = hint.getOrNull(k)?.data?.compute(DefaultComputingEvaluator)
            if (hintKey?.type != hintType) {
                continue
            }

            when (hintType) {
                KeyType.CHARACTER -> {
                    key.computedSymbolHint = hintKey
                }
                KeyType.NUMERIC -> {
                    key.computedNumberHint = hintKey
                }
                else -> {
                    // do nothing
                }
            }
        }
    }

    /**
     * Computes a layout for [keyboardMode] based on the given [subtype] and returns it.
     *
     * @param keyboardMode The keyboard mode for which the layout should be computed.
     * @param subtype The subtype which localizes the computed layout.
     */
    fun computeKeyboardAsync(
        keyboardMode: KeyboardMode,
        subtype: Subtype,
    ): Deferred<TextKeyboard> = computeKeyboardAsync(keyboardMode, subtype, keyboardExtensionRepository.snapshot.value)

    private fun computeKeyboardAsync(
        keyboardMode: KeyboardMode,
        subtype: Subtype,
        snapshot: KeyboardExtensionSnapshot,
    ): Deferred<TextKeyboard> = ioScope.async {
        var main: LTN? = null
        var modifier: LTN? = null
        var extension: LTN? = null

        when (keyboardMode) {
            KeyboardMode.CHARACTERS -> {
                if (prefs.keyboard.numberRow.get()) {
                    extension = LTN(LayoutType.NUMERIC_ROW, subtype.layoutMap.numericRow)
                }
                main = LTN(LayoutType.CHARACTERS, subtype.layoutMap.characters)
                modifier = LTN(LayoutType.CHARACTERS_MOD, extCoreLayout("default"))
            }
            KeyboardMode.NUMERIC -> {
                main = LTN(LayoutType.NUMERIC, subtype.layoutMap.numeric)
            }
            KeyboardMode.NUMERIC_ADVANCED -> {
                main = LTN(LayoutType.NUMERIC_ADVANCED, subtype.layoutMap.numericAdvanced)
            }
            KeyboardMode.PHONE -> {
                main = LTN(LayoutType.PHONE, subtype.layoutMap.phone)
            }
            KeyboardMode.PHONE2 -> {
                main = LTN(LayoutType.PHONE2, subtype.layoutMap.phone2)
            }
            KeyboardMode.SYMBOLS -> {
                extension = LTN(LayoutType.NUMERIC_ROW, subtype.layoutMap.numericRow)
                main = LTN(LayoutType.SYMBOLS, subtype.layoutMap.symbols)
                modifier = LTN(LayoutType.SYMBOLS_MOD, extCoreLayout("default"))
            }
            KeyboardMode.SYMBOLS2 -> {
                main = LTN(LayoutType.SYMBOLS2, subtype.layoutMap.symbols2)
                modifier = LTN(LayoutType.SYMBOLS2_MOD, extCoreLayout("default"))
            }
            else -> {
                // Default values are already provided
            }
        }

        return@async mergeLayouts(keyboardMode, subtype, main, modifier, extension, snapshot)
    }

    /**
     * Called when the application is destroyed. Used to cancel any pending coroutines.
     */
    fun onDestroy() {
        ioScope.cancel()
    }
}
