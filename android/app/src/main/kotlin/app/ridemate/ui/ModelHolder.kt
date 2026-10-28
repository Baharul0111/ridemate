package app.ridemate.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Keeps a plain state-holder ("model") alive across configuration changes and scoped to its navigation
 * destination, while the model itself stays a plain class that tests construct with their own scope.
 */
class ModelHolder<T : Any>(factory: (CoroutineScope) -> T) : ViewModel() {
    val model: T = factory(viewModelScope)
}

/** The destination-scoped model for [key], created once with [factory]. */
@Composable
fun <T : Any> rememberScopedModel(key: String, factory: (CoroutineScope) -> T): T {
    val holder: ModelHolder<*> = viewModel(key = key) { ModelHolder(factory) }
    @Suppress("UNCHECKED_CAST")
    return holder.model as T
}
