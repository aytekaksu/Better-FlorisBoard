/*
 * Copyright (C) 2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp.plugin

import android.os.Trace

/**
 * Fixed, content-free Perfetto sections for separating host overhead from provider inference.
 *
 * Keep labels static: request IDs, provider identity, text, candidates, and touch data do not
 * belong in a system trace.
 */
internal enum class AutocorrectPerformanceSection(val traceLabel: String) {
    DISCOVER("AutocorrectHost.discover"),
    BIND("AutocorrectHost.bind"),
    SEND("AutocorrectHost.send"),
    DECODE_REPLY("AutocorrectHost.decodeReply"),
}

internal inline fun <T> traceAutocorrectPerformance(section: AutocorrectPerformanceSection, block: () -> T): T {
    Trace.beginSection(section.traceLabel)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
