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

package org.florisboard.autocorrect.api

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutocorrectProtocolRobustnessTest {
    @Test
    fun suggestionReaderRejectsMalformedEntriesWithoutLosingValidEntries() {
        val malformed = Bundle().apply {
            putString("text", "   ")
            putInt("replacementStart", 3)
            putInt("replacementEnd", 1)
        }
        val valid = Bundle().apply {
            putString("id", "valid")
            putString("text", "word")
            putDouble("confidence", Double.POSITIVE_INFINITY)
            putString("kind", "UNKNOWN_FUTURE_KIND")
            putString("separatorBehavior", "UNKNOWN_FUTURE_SEPARATOR")
        }
        val (_, result) = suggestionResultFromBundle(Bundle().apply {
            putParcelableArrayList("candidates", arrayListOf(malformed, valid))
            putIntArray("boostedCodePoints", intArrayOf(-1, 'a'.code, 0x10FFFF, 0x110000))
        })

        assertEquals(1, result.candidates.size)
        assertEquals(0.0, result.candidates.single().confidence, 0.0)
        assertEquals(AutocorrectCandidateKind.COMPLETION, result.candidates.single().kind)
        assertEquals(
            AutocorrectSeparatorBehavior.DEFAULT,
            result.candidates.single().separatorBehavior,
        )
        assertEquals(setOf('a'.code, 0x10FFFF), result.boostedCodePoints)
    }

    @Test
    fun traceReaderBoundsHostileCollectionsBeforeDecoding() {
        val keyBundles = ArrayList<Bundle>().apply {
            repeat(AutocorrectPluginContract.MAX_TRACE_KEY_COUNT) {
                add(Bundle().apply { putString("text", "") })
            }
            add(Bundle().apply {
                putString("text", "late")
                putFloat("left", 0.5f)
            })
        }
        val pointBundles = ArrayList<Bundle>().apply {
            repeat(AutocorrectPluginContract.MAX_TRACE_POINT_COUNT + 10) { index ->
                add(Bundle().apply {
                    putString("text", "k")
                    putFloat("x", if (index == 0) Float.NaN else 2f)
                    putFloat("y", -1f)
                })
            }
        }
        val gestureBundles = ArrayList<Bundle>().apply {
            repeat(AutocorrectPluginContract.MAX_GESTURE_POINT_COUNT + 10) {
                add(Bundle().apply {
                    putFloat("x", Float.POSITIVE_INFINITY)
                    putFloat("y", 0.25f)
                    putInt("elapsedTimeMillis", Int.MAX_VALUE)
                })
            }
        }
        val trace = Bundle().apply {
            putParcelableArrayList("keys", keyBundles)
            putParcelableArrayList("points", pointBundles)
            putParcelableArrayList("gesturePoints", gestureBundles)
            putString("mode", "FUTURE_MODE")
        }.toAutocorrectInputTrace()

        assertTrue(trace.keys.isEmpty())
        assertEquals(AutocorrectPluginContract.MAX_TRACE_POINT_COUNT, trace.points.size)
        assertEquals(0f, trace.points.first().x)
        assertEquals(1f, trace.points.last().x)
        assertEquals(0f, trace.points.last().y)
        assertEquals(AutocorrectPluginContract.MAX_GESTURE_POINT_COUNT, trace.gesturePoints.size)
        assertEquals(0f, trace.gesturePoints.first().x)
        assertEquals(60_000, trace.gesturePoints.last().elapsedTimeMillis)
        assertEquals(AutocorrectInputMode.TYPING, trace.mode)
    }

    @Test
    fun pluginUiReaderEnforcesGlobalPageItemAndOptionBudgets() {
        val options = ArrayList<Bundle>().apply {
            repeat(80) { index ->
                add(Bundle().apply {
                    putString("value", "value-$index")
                    putString("title", "Option $index")
                })
            }
        }
        val pages = ArrayList<Bundle>().apply {
            repeat(20) { pageIndex ->
                add(Bundle().apply {
                    putString("id", "page-$pageIndex")
                    putString("title", "Page $pageIndex")
                    putParcelableArrayList(
                        "items",
                        ArrayList<Bundle>().apply {
                            repeat(40) { itemIndex ->
                                add(Bundle().apply {
                                    putString("id", "item-$pageIndex-$itemIndex")
                                    putString("title", "Item $itemIndex")
                                    putString("kind", "CHOICE")
                                    putParcelableArrayList("options", options)
                                })
                            }
                        },
                    )
                })
            }
        }
        val result = pluginUiResultFromBundle(Bundle().apply {
            putLong("requestId", 1L)
            putBoolean("successful", true)
            putBundle("ui", Bundle().apply {
                putString("appRoot", "page-0")
                putParcelableArrayList("pages", pages)
            })
        })

        val ui = requireNotNull(result.ui)
        assertEquals(16, ui.pages.size)
        assertEquals(96, ui.pages.sumOf { it.items.size })
        assertEquals(256, ui.pages.sumOf { page -> page.items.sumOf { it.options.size } })
    }

    @Test
    fun pluginUiReaderNormalizesNonFiniteAndReversedSliderRanges() {
        val result = pluginUiResultFromBundle(Bundle().apply {
            putBundle("ui", Bundle().apply {
                putParcelableArrayList(
                    "pages",
                    arrayListOf(
                        Bundle().apply {
                            putString("id", "root")
                            putString("title", "Root")
                            putParcelableArrayList(
                                "items",
                                arrayListOf(
                                    Bundle().apply {
                                        putString("id", "slider")
                                        putString("title", "Slider")
                                        putString("kind", "SLIDER")
                                        putDouble("minimum", Double.NEGATIVE_INFINITY)
                                        putDouble("maximum", -1.0)
                                        putDouble("step", Double.NaN)
                                    },
                                ),
                            )
                        },
                    ),
                )
            })
        })

        val slider = requireNotNull(result.ui).pages.single().items.single()
        assertEquals(0.0, slider.minimum, 0.0)
        assertEquals(1.0, slider.maximum, 0.0)
        assertEquals(0.0, slider.step, 0.0)
    }

    @Test
    fun malformedDictionaryResultsNeverBecomeSuccessful() {
        val malformedResults = listOf(
            Bundle(),
            Bundle().apply {
                putLong("udRequestId", 1L)
                putString("udStatus", "OK")
                putStringArrayList("udEntries", arrayListOf("not-a-bundle"))
                putLong("udNextAfterId", 0L)
            },
            Bundle().apply {
                putLong("udRequestId", 1L)
                putString("udStatus", "OK")
                putParcelableArrayList(
                    "udEntries",
                    arrayListOf(
                        Bundle().apply {
                            putLong("udId", -1L)
                            putString("udWord", "word")
                            putInt("udFrequency", 100)
                        },
                    ),
                )
                putLong("udNextAfterId", 0L)
            },
        )

        malformedResults.forEach { bundle ->
            val (_, page) = userDictionaryResultFromBundle(bundle)
            assertFalse(page.successful)
            assertEquals(AutocorrectUserDictionaryStatus.INVALID, page.status)
        }
    }
}
