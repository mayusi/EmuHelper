package io.github.mayusi.emuhelper.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConsoleSelectViewModel @Inject constructor(
    private val settings: SettingsStore
) : ViewModel() {

    val lastSelectedConsoles: StateFlow<Set<String>> =
        settings.lastSelectedConsoles.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun saveSelectedConsoles(consoles: Set<String>) {
        viewModelScope.launch {
            settings.setLastSelectedConsoles(consoles)
        }
    }
}
