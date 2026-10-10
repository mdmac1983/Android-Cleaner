package io.github.mdmac1983.leancleaner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Re-reads [read] every [intervalMillis] while the screen is visible, and stops completely when the
 * app goes to the background, so live stats never cost battery when nobody is looking.
 */
@Composable
fun <T> rememberPolled(intervalMillis: Long, read: () -> T): State<T?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<T?>(initialValue = null, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = withContext(Dispatchers.IO) { read() }
                delay(intervalMillis)
            }
        }
    }
}
