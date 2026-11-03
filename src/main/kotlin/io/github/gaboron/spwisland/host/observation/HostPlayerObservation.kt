// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class)
package io.github.gaboron.spwisland.host.observation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import com.xuncorp.spw.workshop.api.hook.HookRegistrar
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.Consumer

/** Host-loaded UI boundary. Constructor arguments and callbacks cross loaders using JDK types only. */
class HostPlayerObservation(
    registrar: Any,
    owner: String,
    method: String,
    caller: String,
    private val navigator: Class<*>,
    private val playerState: Field,
    private val expanded: Method,
    private val locals: List<Field>,
    private val changed: Consumer<Boolean>,
    private val failed: Consumer<Throwable>
) : AutoCloseable {
    private var handle: AutoCloseable? = (registrar as HookRegistrar).afterComposable(owner, method, caller,
        parameterTypes = emptyList()) { Observe() }

    @Composable
    private fun Observe() {
        val composer = currentComposer
        val focused = LocalWindowInfo.current.isWindowFocused
        val state = runCatching {
            val host = locals.firstNotNullOfOrNull { local ->
                @Suppress("UNCHECKED_CAST")
                val value = composer.consume(local.get(null) as CompositionLocal<Any?>)
                value?.takeIf(navigator::isInstance)
            } ?: error("SPW 导航上下文不可用")
            expanded.invoke(playerState.get(host)) as Boolean
        }
        // Effects publish only committed state changes and never change the host composition.
        LaunchedEffect(state.getOrNull(), state.exceptionOrNull()?.javaClass, focused) {
            state.onSuccess { changed.accept(it && focused) }.onFailure { failed.accept(it) }
        }
        DisposableEffect(Unit) {
            onDispose { changed.accept(false) }
        }
    }

    override fun close() {
        val previous = handle
        handle = null
        previous?.close()
    }
}
