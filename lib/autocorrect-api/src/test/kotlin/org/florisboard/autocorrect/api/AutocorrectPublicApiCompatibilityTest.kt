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

import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.util.jar.JarFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A compact JVM ABI snapshot for provider-facing declarations.
 *
 * Kotlin's built-in ABI validator is not exposed by AGP 9's built-in Kotlin integration, so this
 * test compares stable JVM descriptors instead. Compiler-generated coroutine/lambda/accessor
 * classes and source-level `internal` helpers are intentionally excluded.
 */
class AutocorrectPublicApiCompatibilityTest {
    @Test
    fun providerFacingJvmApiMatchesTheCheckedInSignature() {
        val actual = discoverApiClasses()
            .joinToString("\n\n", transform = ::classSignature)
            .trimEnd()
        writeReport("public-api-v4.txt", actual)
        if (updateSnapshot("public-api-v4.txt", actual)) return
        val expected = requireNotNull(javaClass.getResource("/api/public-api-v4.txt")) {
            "Missing src/test/resources/api/public-api-v4.txt"
        }.readText().trimEnd()

        assertEquals(
            "The provider-facing JVM API changed. Preserve existing descriptors or deliberately " +
                "review and update the checked signature. The generated candidate is in the " +
                "module build reports.",
            expected,
            actual,
        )
    }

    private fun discoverApiClasses(): List<Class<*>> {
        val packagePath = API_PACKAGE.replace('.', '/')
        val classNames = linkedSetOf<String>()
        val protectionDomain = requireNotNull(
            AutocorrectPluginContract::class.java.protectionDomain,
        )
        val location = requireNotNull(protectionDomain.codeSource).location
        when (location.protocol) {
            "file" -> {
                val locationFile = File(location.toURI())
                if (locationFile.isDirectory) {
                    val packageDir = File(locationFile, packagePath)
                    packageDir.listFiles()
                        .orEmpty()
                        .filter(File::isFile)
                        .map(File::getName)
                        .filter(::isApiClassFile)
                        .mapTo(classNames) { "$API_PACKAGE.${it.removeSuffix(".class")}" }
                } else if (locationFile.extension == "jar") {
                    JarFile(locationFile).use { jar ->
                        collectApiClassNames(jar, packagePath, classNames)
                    }
                }
            }
            "jar" -> {
                val connection = location.openConnection() as JarURLConnection
                connection.jarFile.use { jar ->
                    collectApiClassNames(jar, packagePath, classNames)
                }
            }
            else -> error("Unsupported API code-source URL: $location")
        }
        check(classNames.isNotEmpty()) {
            "Could not discover autocorrect API classes from $location"
        }
        return classNames
            .asSequence()
            .filterNot(EXCLUDED_FILE_FACADES::contains)
            .map { Class.forName(it, false, javaClass.classLoader) }
            .filter { Modifier.isPublic(it.modifiers) }
            .sortedBy(Class<*>::getName)
            .toList()
    }

    private fun collectApiClassNames(
        jar: JarFile,
        packagePath: String,
        destination: MutableSet<String>,
    ) {
        jar.entries().asSequence()
            .filterNot { it.isDirectory }
            .map { it.name }
            .filter { it.substringBeforeLast('/', "") == packagePath }
            .map { it.substringAfterLast('/') }
            .filter(::isApiClassFile)
            .mapTo(destination) { "$API_PACKAGE.${it.removeSuffix(".class")}" }
    }

    private fun isApiClassFile(fileName: String): Boolean {
        if (!fileName.startsWith("Autocorrect") || !fileName.endsWith(".class")) return false
        val nestedName = fileName.substringAfter('$', missingDelimiterValue = "")
            .removeSuffix(".class")
        return '$' !in fileName || nestedName == "Companion"
    }

    private fun classSignature(type: Class<*>): String = buildString {
        append(classModifiers(type.modifiers))
        append(' ')
        append(
            when {
                type.isAnnotation -> "@interface"
                type.isEnum -> "enum"
                type.isInterface -> "interface"
                else -> "class"
            },
        )
        append(' ')
        appendLine(type.name)
        val superclass = type.superclass
        if (superclass != null && superclass != Any::class.java && !type.isEnum) {
            appendLine("  extends ${superclass.descriptor()}")
        }
        type.interfaces
            .map { it.descriptor() }
            .sorted()
            .forEach { appendLine("  implements $it") }
        type.declaredFields
            .asSequence()
            .filter(::isApiMember)
            .filterNot { field ->
                field.name in INTERNAL_FIELDS[type.name].orEmpty()
            }
            .sortedWith(compareBy({ it.name }, { it.type.descriptor() }))
            .forEach { field ->
                appendLine(
                    "  ${memberModifiers(field.modifiers)} field " +
                        "${field.name}:${field.type.descriptor()}",
                )
            }
        type.declaredConstructors
            .asSequence()
            .filter(::isApiMember)
            .filterNot { constructor ->
                constructor.parameterTypes.any {
                    it.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                }
            }
            .sortedBy { it.parameterTypes.methodArgumentsDescriptor() }
            .forEach { constructor ->
                appendLine(
                    "  ${memberModifiers(constructor.modifiers)} constructor" +
                        constructor.parameterTypes.methodArgumentsDescriptor() +
                        "V",
                )
            }
        type.declaredMethods
            .asSequence()
            .filter(::isApiMember)
            .filterNot { '$' in it.name }
            .filterNot { method ->
                method.name == "copy" ||
                    method.name == "equals" ||
                    method.name == "hashCode" ||
                    method.name == "toString" ||
                    method.name.matches(COMPONENT_METHOD)
            }
            .filterNot { method ->
                type.isEnum && method.name in ENUM_SUPPORT_METHODS
            }
            .filterNot { method ->
                method.name in INTERNAL_TOP_LEVEL_METHODS[type.name].orEmpty()
            }
            .sortedWith(
                compareBy(
                    { it.name },
                    { it.parameterTypes.methodArgumentsDescriptor() },
                    { it.returnType.descriptor() },
                ),
            )
            .forEach { method ->
                appendLine(
                    "  ${memberModifiers(method.modifiers)} method ${method.name}" +
                        method.parameterTypes.methodArgumentsDescriptor() +
                        method.returnType.descriptor(),
                )
            }
    }.trimEnd()

    private fun isApiMember(member: Member): Boolean {
        val accessible = Modifier.isPublic(member.modifiers) || Modifier.isProtected(member.modifiers)
        return accessible && !member.isSynthetic
    }

    private fun classModifiers(modifiers: Int) = buildList {
        if (Modifier.isPublic(modifiers)) add("public")
        if (Modifier.isProtected(modifiers)) add("protected")
        if (Modifier.isAbstract(modifiers)) add("abstract")
        if (Modifier.isFinal(modifiers)) add("final")
    }.joinToString(" ")

    private fun memberModifiers(modifiers: Int) = buildList {
        if (Modifier.isPublic(modifiers)) add("public")
        if (Modifier.isProtected(modifiers)) add("protected")
        if (Modifier.isStatic(modifiers)) add("static")
        if (Modifier.isAbstract(modifiers)) add("abstract")
        if (Modifier.isFinal(modifiers)) add("final")
    }.joinToString(" ")

    private fun Array<Class<*>>.methodArgumentsDescriptor() =
        joinToString(prefix = "(", postfix = ")") { it.descriptor() }

    private fun Class<*>.descriptor(): String = when (this) {
        java.lang.Void.TYPE -> "V"
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Byte.TYPE -> "B"
        java.lang.Character.TYPE -> "C"
        java.lang.Short.TYPE -> "S"
        java.lang.Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        else -> if (isArray) name.replace('.', '/') else "L${name.replace('.', '/')};"
    }

    private fun writeReport(name: String, contents: String) {
        val reportDir = System.getProperty("autocorrect.api.reportDir") ?: return
        File(reportDir).mkdirs()
        File(reportDir, name).writeText("$contents\n")
    }

    private fun updateSnapshot(name: String, contents: String): Boolean {
        val snapshotDir = System.getProperty("autocorrect.api.snapshotDir") ?: return false
        File(snapshotDir).mkdirs()
        File(snapshotDir, name).writeText("$contents\n")
        return true
    }

    companion object {
        private const val API_PACKAGE = "org.florisboard.autocorrect.api"
        private val COMPONENT_METHOD = Regex("component\\d+")
        private val ENUM_SUPPORT_METHODS = setOf("getEntries", "valueOf", "values")

        private val EXCLUDED_FILE_FACADES = setOf(
            "$API_PACKAGE.AutocorrectInputTraceKt",
            "$API_PACKAGE.AutocorrectPluginServiceKt",
        )

        private val INTERNAL_TOP_LEVEL_METHODS = mapOf(
            "$API_PACKAGE.AutocorrectPluginContractKt" to setOf(
                "finalRequestFromFinishSessionBundle",
                "suggestionResultToBundle",
                "takeWireChars",
            ),
            "$API_PACKAGE.AutocorrectPluginUiKt" to setOf(
                "pluginUiDocument",
                "pluginUiItemId",
                "pluginUiLanguageTags",
                "pluginUiRequestId",
                "pluginUiResultBundle",
                "pluginUiValue",
            ),
        )

        private val INTERNAL_FIELDS = mapOf(
            "$API_PACKAGE.AutocorrectEditorFlags" to setOf("ALL"),
        )
    }
}
