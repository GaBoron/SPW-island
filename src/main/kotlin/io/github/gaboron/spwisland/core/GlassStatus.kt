// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.core

/** Capture health, failure reason and telemetry remain separate presentation fields. */
internal data class GlassStatus(val summary: String, val detail: String = "", val warning: Boolean = false,
                                val framesPerSecond: Int? = null, val nativeTimeMs: Long? = null)
