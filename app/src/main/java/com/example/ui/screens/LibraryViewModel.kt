package com.example.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.RomEntity
import com.example.data.local.SaveSlotEntity
import com.example.data.model.RomSystem
import com.example.data.prefs.EmulatorSettings
import com.example.data.repository.RomRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SystemFilter {
    ALL, GB, GBC, GBA
}

data class LibraryUiState(
    val roms: List<RomEntity> = emptyList(),
    val totalRomCount: Int = 0,
    val gbCount: Int = 0,
    val gbcCount: Int = 0,
    val gbaCount: Int = 0,
    val searchQuery: String = "",
    val selectedFilter: SystemFilter = SystemFilter.ALL,
    val isScanning: Boolean = false,
    val settings: EmulatorSettings = EmulatorSettings()
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RomRepository(application)

    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilter = MutableStateFlow(SystemFilter.ALL)
    private val _isScanning = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.allRoms,
        _searchQuery,
        _selectedFilter,
        _isScanning,
        repository.settings
    ) { allRoms, query, filter, scanning, settings ->
        val filtered = allRoms.filter { rom ->
            val matchesSystem = when (filter) {
                SystemFilter.ALL -> true
                SystemFilter.GB -> rom.system == RomSystem.GB
                SystemFilter.GBC -> rom.system == RomSystem.GBC
                SystemFilter.GBA -> rom.system == RomSystem.GBA
            }
            val matchesQuery = query.isBlank() ||
                rom.displayTitle.contains(query, ignoreCase = true) ||
                rom.fileName.contains(query, ignoreCase = true) ||
                rom.gameCode.contains(query, ignoreCase = true)
            matchesSystem && matchesQuery
        }

        LibraryUiState(
            roms = filtered,
            totalRomCount = allRoms.size,
            gbCount = allRoms.count { it.system == RomSystem.GB },
            gbcCount = allRoms.count { it.system == RomSystem.GBC },
            gbaCount = allRoms.count { it.system == RomSystem.GBA },
            searchQuery = query,
            selectedFilter = filter,
            isScanning = scanning,
            settings = settings
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    val allSaveSlots: StateFlow<List<SaveSlotEntity>> = repository.allSaveSlots
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: SystemFilter) {
        _selectedFilter.value = filter
    }

    fun linkFolderAndScan(treeUri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            runCatching {
                val count = repository.linkAndScanFolder(treeUri)
                _messages.tryEmit(
                    if (count == 0) "Pasta vinculada. Nenhum arquivo .gb/.gba encontrado ainda."
                    else "$count jogos carregados da pasta!"
                )
            }.onFailure {
                _messages.tryEmit("Não foi possível acessar a pasta selecionada.")
            }
            _isScanning.value = false
        }
    }

    fun refreshFolder() {
        viewModelScope.launch {
            if (uiState.value.settings.romFolderUri == null) {
                _messages.tryEmit("Selecione primeiro a pasta onde estão suas ROMs.")
                return@launch
            }
            _isScanning.value = true
            runCatching {
                val count = repository.refreshLinkedFolder()
                _messages.tryEmit(
                    if (count == 0) "Pasta atualizada: nenhuma ROM .gb/.gbc/.gba encontrada."
                    else "Biblioteca atualizada: $count jogos encontrados!"
                )
            }.onFailure {
                _messages.tryEmit("Erro ao ler a pasta de ROMs. Tente selecioná-la novamente.")
            }
            _isScanning.value = false
        }
    }

    fun importSingleRom(uri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            val rom = repository.importSingleRom(uri)
            _isScanning.value = false
            if (rom != null) {
                _messages.tryEmit("${rom.displayTitle} importado com sucesso!")
            } else {
                _messages.tryEmit("Formato inválido. Selecione um arquivo .gb, .gbc ou .gba.")
            }
        }
    }

    fun toggleFavorite(rom: RomEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(rom)
        }
    }

    fun deleteSaveSlot(slot: SaveSlotEntity) {
        viewModelScope.launch {
            repository.deleteSaveSlot(slot)
            _messages.tryEmit("Save removido")
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.prefs.setSoundEnabled(enabled) }
    }

    fun setHapticEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.prefs.setHapticEnabled(enabled) }
    }

    fun setFastForwardMultiplier(mult: Int) {
        viewModelScope.launch { repository.prefs.setFastForwardMultiplier(mult) }
    }

    fun setGbPalettePreset(preset: Int) {
        viewModelScope.launch { repository.prefs.setGbPalettePreset(preset) }
    }

    fun setControllerOpacity(opacity: Float) {
        viewModelScope.launch { repository.prefs.setControllerOpacity(opacity) }
    }

    fun setShowFps(show: Boolean) {
        viewModelScope.launch { repository.prefs.setShowFps(show) }
    }

    fun setPixelFilter(crisp: Boolean) {
        viewModelScope.launch { repository.prefs.setPixelFilter(crisp) }
    }
}
