/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
