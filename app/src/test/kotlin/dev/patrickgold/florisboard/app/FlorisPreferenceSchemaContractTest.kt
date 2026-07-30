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

package dev.patrickgold.florisboard.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FlorisPreferenceSchemaContractTest :
    FunSpec({
        val reviewedSchema = loadReviewedSchema()

        test("reviewed schema is sorted and has unique keys") {
            val keys = reviewedSchema.map(SchemaRow::key)

            keys shouldBe keys.sorted()
            keys.distinct().size shouldBe keys.size
        }

        test("reviewed active schema matches the generated preference model") {
            val reviewedActive = reviewedSchema
                .filter { it.status == SchemaStatus.ACTIVE }
                .map(SchemaRow::typedKey)
                .sorted()
            val generatedActive = FlorisPreferenceModelImpl()
                .declaredPreferenceEntries
                .keys
                .map { typedKey -> "${typedKey.type.id};${typedKey.key}" }
                .sorted()

            generatedActive shouldBe reviewedActive
        }

        test("historical keys stay reserved") {
            val generatedKeys = FlorisPreferenceModelImpl()
                .declaredPreferenceEntries
                .keys
                .mapTo(mutableSetOf()) { it.key }
            val reusedHistoricalKeys = reviewedSchema
                .filter { it.status != SchemaStatus.ACTIVE }
                .map(SchemaRow::key)
                .filter(generatedKeys::contains)

            reusedHistoricalKeys shouldBe emptyList()
        }
    })

private enum class SchemaStatus {
    ACTIVE,
    LEGACY,
    RETIRED,
}

private data class SchemaRow(val status: SchemaStatus, val type: String, val key: String) {
    val typedKey = "$type;$key"
}

private fun loadReviewedSchema(): List<SchemaRow> {
    val resource = FlorisPreferenceSchemaContractTest::class.java
        .getResourceAsStream("/preferences/floris-preference-schema.txt")
        ?: error("Preference schema resource is missing")
    return resource.bufferedReader().useLines { lines ->
        lines
            .filterNot { it.isBlank() || it.startsWith('#') }
            .map { line ->
                val (status, type, key) = line.split(';', limit = 3)
                SchemaRow(
                    status = SchemaStatus.valueOf(status.uppercase()),
                    type = type,
                    key = key,
                )
            }
            .toList()
    }
}
