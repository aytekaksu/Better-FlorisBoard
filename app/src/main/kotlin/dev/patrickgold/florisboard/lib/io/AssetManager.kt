/*
 * Copyright (C) 2020-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.io

import android.content.Context
import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.keyboard.CaseSelector
import dev.patrickgold.florisboard.ime.keyboard.CharWidthSelector
import dev.patrickgold.florisboard.ime.keyboard.KanaSelector
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.LayoutDirectionSelector
import dev.patrickgold.florisboard.ime.keyboard.ShiftStateSelector
import dev.patrickgold.florisboard.ime.keyboard.VariationSelector
import dev.patrickgold.florisboard.ime.text.keyboard.AutoTextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.MultiTextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

val DefaultJsonConfig = Json {
    classDiscriminator = "$"
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
    serializersModule = SerializersModule {
        polymorphic(AbstractKeyData::class) {
            subclass(TextKeyData::class, TextKeyData.serializer())
            subclass(AutoTextKeyData::class, AutoTextKeyData.serializer())
            subclass(MultiTextKeyData::class, MultiTextKeyData.serializer())
            subclass(CaseSelector::class, CaseSelector.serializer())
            subclass(ShiftStateSelector::class, ShiftStateSelector.serializer())
            subclass(VariationSelector::class, VariationSelector.serializer())
            subclass(LayoutDirectionSelector::class, LayoutDirectionSelector.serializer())
            subclass(CharWidthSelector::class, CharWidthSelector.serializer())
            subclass(KanaSelector::class, KanaSelector.serializer())
            defaultDeserializer { TextKeyData.serializer() }
        }
        polymorphic(KeyData::class) {
            subclass(TextKeyData::class, TextKeyData.serializer())
            subclass(AutoTextKeyData::class, AutoTextKeyData.serializer())
            subclass(MultiTextKeyData::class, MultiTextKeyData.serializer())
            defaultDeserializer { TextKeyData.serializer() }
        }
    }
}

fun FlorisRef.loadTextAsset(context: Context): Result<String> {
    if (!isAssets) return Result.failure(Exception("Unsupported asset ref!"))
    return runCatching {
        context.assets.open(relativePath).reader(Charsets.UTF_8).use { it.readText() }
    }
}
