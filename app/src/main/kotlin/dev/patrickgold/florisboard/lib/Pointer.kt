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

package dev.patrickgold.florisboard.lib

import androidx.annotation.RestrictTo

/**
 * Reuses a fixed pool of touch pointers. Each result from `init` is reset before use.
 * The pool has one slot when [capacity] is zero or negative.
 */
class PointerMap<P : Pointer>(val capacity: Int = 4, init: (Int) -> P) : Iterable<P> {
    private val pointers: List<P> = List(capacity.coerceAtLeast(1)) { i ->
        init(i).also { pointer -> pointer.reset() }
    }

    /** Returns a reset slot for [id], or null if [id] is invalid, already active, or the pool is full. */
    fun add(id: Int, index: Int): P? {
        if (id < 0) return null
        if (findById(id) != null) return null
        for (pointer in pointers) {
            if (pointer.isNotUsed) {
                pointer.reset()
                pointer.id = id
                pointer.index = index
                return pointer
            }
        }
        return null
    }

    fun clear() {
        for (pointer in pointers) {
            pointer.reset()
        }
    }

    /** Returns the active pointer with [id], if present. */
    fun findById(id: Int): P? {
        if (id < 0) return null
        for (pointer in pointers) {
            if (pointer.id == id) {
                return pointer
            }
        }
        return null
    }

    /** Returns an active pool slot at [index], or null. Used by [PointerIterator]. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun get(index: Int): P? {
        val pointer = pointers.getOrNull(index)
        if (pointer != null && pointer.isUsed) {
            return pointer
        }
        return null
    }

    override fun iterator(): Iterator<P> {
        return PointerIterator(this)
    }

    /** Resets the pointer with [id]. Returns false if it was not active. */
    fun removeById(id: Int): Boolean {
        if (id < 0) return false
        for (pointer in pointers) {
            if (pointer.id == id) {
                pointer.reset()
                return true
            }
        }
        return false
    }

    /** Number of active pointers. */
    val size: Int
        get() = pointers.count { it.isUsed }
}

class PointerIterator<P : Pointer>(private val pointerMap: PointerMap<P>) : Iterator<P> {
    private var index: Int = 0

    override fun hasNext(): Boolean {
        do {
            if (pointerMap.get(index) != null) {
                return true
            }
        } while (++index < pointerMap.capacity)
        return false
    }

    override fun next(): P {
        return pointerMap.get(index++)!!
    }
}

/** Mutable slot for one touch pointer. [reset] makes it available for reuse. */
abstract class Pointer {
    companion object {
        const val UNUSED_P: Int = -1
    }

    /** MotionEvent pointer ID, or [UNUSED_P] when free. */
    var id: Int = UNUSED_P

    /** Current MotionEvent pointer index, or [UNUSED_P] when free. */
    var index: Int = UNUSED_P

    val isUsed: Boolean
        get() = id >= 0

    val isNotUsed: Boolean
        get() = !isUsed

    open fun reset() {
        id = UNUSED_P
        index = UNUSED_P
    }
}
