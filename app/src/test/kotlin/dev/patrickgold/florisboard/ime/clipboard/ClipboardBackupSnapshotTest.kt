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

package dev.patrickgold.florisboard.ime.clipboard

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ClipboardBackupSnapshotTest :
    FunSpec({
        test("release is idempotent after the lease is released") {
            runTest {
                var releases = 0
                val snapshot = ClipboardBackupSnapshot(emptyList()) {
                    releases++
                }

                snapshot.release()
                snapshot.release()

                releases shouldBe 1
            }
        }

        test("a failed release remains retryable") {
            runTest {
                var attempts = 0
                val snapshot = ClipboardBackupSnapshot(emptyList()) {
                    attempts++
                    if (attempts == 1) {
                        throw IOException("synthetic release failure")
                    }
                }

                shouldThrow<IOException> {
                    snapshot.release()
                }
                snapshot.release()

                attempts shouldBe 2
            }
        }

        test("a cancelled release remains retryable") {
            runTest {
                var attempts = 0
                val snapshot = ClipboardBackupSnapshot(emptyList()) {
                    attempts++
                    if (attempts == 1) {
                        throw CancellationException("synthetic cancellation")
                    }
                }

                shouldThrow<CancellationException> {
                    snapshot.release()
                }
                snapshot.release()

                attempts shouldBe 2
            }
        }
    })
