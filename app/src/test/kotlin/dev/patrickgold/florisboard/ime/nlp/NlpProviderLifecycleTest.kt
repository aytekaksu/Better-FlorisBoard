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

package dev.patrickgold.florisboard.ime.nlp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class NlpProviderLifecycleTest :
    FunSpec({
        test("create and destroy are idempotent and may form a new lifecycle") {
            val lifecycle = NlpProviderLifecycle()
            var creates = 0
            var destroys = 0

            lifecycle.createIfNecessary { creates++ }
            lifecycle.createIfNecessary { creates++ }
            lifecycle.destroyIfNecessary { destroys++ }
            lifecycle.destroyIfNecessary { destroys++ }
            lifecycle.createIfNecessary { creates++ }

            creates shouldBe 2
            destroys shouldBe 1
        }

        test("failed create remains retryable") {
            val lifecycle = NlpProviderLifecycle()
            var attempts = 0

            shouldThrow<IllegalStateException> {
                lifecycle.createIfNecessary {
                    attempts++
                    error("create failed")
                }
            }
            lifecycle.createIfNecessary { attempts++ }

            attempts shouldBe 2
        }

        test("failed destroy remains retryable") {
            val lifecycle = NlpProviderLifecycle()
            var attempts = 0
            lifecycle.createIfNecessary { }

            shouldThrow<IllegalStateException> {
                lifecycle.destroyIfNecessary {
                    attempts++
                    error("destroy failed")
                }
            }
            lifecycle.destroyIfNecessary { attempts++ }

            attempts shouldBe 2
        }

        test("concurrent lifecycle requests execute each transition once") {
            val lifecycle = NlpProviderLifecycle()
            var creates = 0
            var destroys = 0

            coroutineScope {
                List(20) {
                    async { lifecycle.createIfNecessary { creates++ } }
                }.awaitAll()
            }
            coroutineScope {
                List(20) {
                    async { lifecycle.destroyIfNecessary { destroys++ } }
                }.awaitAll()
            }

            creates shouldBe 1
            destroys shouldBe 1
        }
    })
