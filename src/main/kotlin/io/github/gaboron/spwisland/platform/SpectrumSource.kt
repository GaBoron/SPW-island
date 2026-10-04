// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

internal interface SpectrumSource : AutoCloseable {
    val status: String
    fun setEnabled(value: Boolean)
    fun levels(): FloatArray
    fun usesSyntheticFallback(): Boolean
}
