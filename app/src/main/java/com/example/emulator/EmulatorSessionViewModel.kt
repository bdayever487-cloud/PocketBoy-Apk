package com.example.emulator

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.RomEntity
import com.example.data.local.SaveSlotEntity
import com.example.data.model.RomSystem
import com.example.data.prefs.EmulatorSettings
import com.example.data.repository.RomRepository
import com.example.emulator.gb.GbCore
import com.example.emulator.gba.GbaCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Error(val message: String) : PlayerUiState
    data class Ready(
        val rom: RomEntity,
        val width: Int,
        val height: Int,
        val isPaused: Boolean = false,
        val isFastForward: Boolean = false,
        val isMuted: Boolean = false,
        val fps: Int = 60
    ) : PlayerUiState
}

class EmulatorSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RomRepository(application)

    val settings: StateFlow<EmulatorSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EmulatorSettings())

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _saveSlots = MutableStateFlow<List<SaveSlotEntity>>(emptyList())
    val saveSlots: StateFlow<List<SaveSlotEntity>> = _saveSlots.asStateFlow()

    private val _frameTick = MutableStateFlow(0L)
    val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var core: EmulatorCore? = null
    private var currentRom: RomEntity? = null
    private var audioPlayer: EmulatorAudioPlayer? = null
    private var emulationJob: Job? = null
    private var slotsObserveJob: Job? = null

    private var backFramebuffer = IntArray(0)
    private var frontFramebuffer = IntArray(0)
    private val frameLock = Any()
    private val audioBuffer = ShortArray(4096)

    @Volatile
    private var inputMask = 0
    @Volatile
    private var paused = false
    @Volatile
    private var fastForward = false
    @Volatile
    private var fastForwardMult = 2
    @Volatile
    private var sessionStartTimeMs = 0L

    fun startSession(romUriString: String, initialSlotIndex: Int = -1) {
        if (currentRom?.uriString == romUriString && core != null) return

        stopSession(saveAuto = false)
        _uiState.value = PlayerUiState.Loading

        viewModelScope.launch {
            val rom = repository.getRomByUri(romUriString)
            if (rom == null) {
                _uiState.value = PlayerUiState.Error("ROM não encontrada na biblioteca.")
                return@launch
            }
            val bytes = repository.loadRomBytes(romUriString)
            if (bytes == null || bytes.isEmpty()) {
                _uiState.value = PlayerUiState.Error("Não foi possível ler o arquivo da ROM.")
                return@launch
            }

            val currentSettings = repository.settings.first()
            val emuCore: EmulatorCore = withContext(Dispatchers.Default) {
                when (rom.system) {
                    RomSystem.GBA -> GbaCore(bytes)
                    RomSystem.GB, RomSystem.GBC -> GbCore(bytes, rom.system).apply {
                        setDmgPalettePreset(currentSettings.gbPalettePreset)
                    }
                }
            }

            // Load battery SRAM if present
            val batteryData = repository.loadBatteryRam(rom.fileName, rom.system)
            if (batteryData != null) {
                emuCore.importBatteryRam(batteryData)
            }

            // Optional launch directly from a saved state slot
            if (initialSlotIndex >= 0) {
                val slot = repository.getSaveSlot(romUriString, initialSlotIndex)
                if (slot != null) {
                    val stateBytes = repository.loadStateFromSlot(slot)
                    if (stateBytes != null) emuCore.loadState(stateBytes)
                }
            }

            currentRom = rom
            core = emuCore
            backFramebuffer = IntArray(emuCore.screenWidth * emuCore.screenHeight)
            frontFramebuffer = IntArray(emuCore.screenWidth * emuCore.screenHeight)
            audioPlayer = EmulatorAudioPlayer(44100).apply {
                isMuted = !currentSettings.soundEnabled
            }
            fastForwardMult = currentSettings.fastForwardMultiplier
            paused = false
            fastForward = false
            sessionStartTimeMs = System.currentTimeMillis()

            _uiState.value = PlayerUiState.Ready(
                rom = rom,
                width = emuCore.screenWidth,
                height = emuCore.screenHeight,
                isPaused = false,
                isFastForward = false,
                isMuted = !currentSettings.soundEnabled
            )

            slotsObserveJob?.cancel()
            slotsObserveJob = viewModelScope.launch {
                repository.observeSaveSlotsForRom(romUriString).collect { list ->
                    _saveSlots.value = list
                }
            }

            startLoop()
        }
    }

    private fun startLoop() {
        emulationJob?.cancel()
        emulationJob = viewModelScope.launch(Dispatchers.Default) {
            val emu = core ?: return@launch
            val rom = currentRom ?: return@launch
            val frameDurationNs = 16_742_706L // ~59.7275 Hz
            var lastFpsTimeNs = System.nanoTime()
            var framesCounted = 0
            var lastSramCheckMs = System.currentTimeMillis()

            while (isActive) {
                if (paused) {
                    delay(40)
                    lastFpsTimeNs = System.nanoTime()
                    framesCounted = 0
                    continue
                }

                val startNs = System.nanoTime()
                val speed = if (fastForward) fastForwardMult else 1

                emu.setInput(inputMask)
                var audioWritten = 0
                for (step in 0 until speed) {
                    val samples = emu.stepFrame(
                        backFramebuffer,
                        if (step == 0 && audioPlayer?.isMuted == false) audioBuffer else null
                    )
                    if (step == 0) audioWritten = samples
                }

                if (audioWritten > 0 && !fastForward) {
                    audioPlayer?.write(audioBuffer, audioWritten)
                }

                synchronized(frameLock) {
                    System.arraycopy(backFramebuffer, 0, frontFramebuffer, 0, frontFramebuffer.size)
                }
                _frameTick.value = _frameTick.value + 1L
                framesCounted += speed

                val nowNs = System.nanoTime()
                if (nowNs - lastFpsTimeNs >= 1_000_000_000L) {
                    val fps = framesCounted
                    framesCounted = 0
                    lastFpsTimeNs = nowNs
                    val current = _uiState.value
                    if (current is PlayerUiState.Ready) {
                        _uiState.value = current.copy(fps = fps)
                    }
                }

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSramCheckMs >= 5000L) {
                    lastSramCheckMs = nowMs
                    if (emu.isBatteryDirty()) {
                        emu.exportBatteryRam()?.let { ram ->
                            repository.saveBatteryRam(rom.fileName, rom.system, ram)
                            emu.clearBatteryDirty()
                        }
                    }
                }

                val elapsedNs = System.nanoTime() - startNs
                val remainingMs = (frameDurationNs - elapsedNs) / 1_000_000L
                if (remainingMs > 1L) {
                    delay(remainingMs)
                }
            }
        }
    }

    fun copyLatestFrame(destBitmap: Bitmap) {
        synchronized(frameLock) {
            if (frontFramebuffer.size == destBitmap.width * destBitmap.height) {
                destBitmap.setPixels(
                    frontFramebuffer,
                    0,
                    destBitmap.width,
                    0,
                    0,
                    destBitmap.width,
                    destBitmap.height
                )
            }
        }
    }

    fun setInputMask(mask: Int) {
        inputMask = mask
    }

    fun setPaused(pause: Boolean) {
        paused = pause
        if (pause) audioPlayer?.pause() else audioPlayer?.resume()
        val current = _uiState.value
        if (current is PlayerUiState.Ready) {
            _uiState.value = current.copy(isPaused = pause)
        }
        if (pause) persistBatteryIfDirty()
    }

    fun toggleFastForward() {
        fastForward = !fastForward
        val current = _uiState.value
        if (current is PlayerUiState.Ready) {
            _uiState.value = current.copy(isFastForward = fastForward)
        }
    }

    fun toggleMute() {
        val player = audioPlayer ?: return
        val nextMuted = !player.isMuted
        player.isMuted = nextMuted
        val current = _uiState.value
        if (current is PlayerUiState.Ready) {
            _uiState.value = current.copy(isMuted = nextMuted)
        }
        viewModelScope.launch {
            repository.prefs.setSoundEnabled(!nextMuted)
        }
    }

    fun resetConsole() {
        core?.reset()
        paused = false
        audioPlayer?.resume()
        val current = _uiState.value
        if (current is PlayerUiState.Ready) {
            _uiState.value = current.copy(isPaused = false)
        }
        _events.tryEmit("Console reiniciado")
    }

    fun saveToSlot(slotIndex: Int) {
        val emu = core ?: return
        val rom = currentRom ?: return
        val wasPaused = paused
        paused = true

        viewModelScope.launch {
            val stateBytes = withContext(Dispatchers.Default) { emu.saveState() }
            val thumb = captureThumbnailBitmap(emu.screenWidth, emu.screenHeight)
            val elapsedSec = maxOf(0L, (System.currentTimeMillis() - sessionStartTimeMs) / 1000L)
            repository.saveStateToSlot(rom, slotIndex, stateBytes, thumb, elapsedSec)
            persistBatteryIfDirty()

            val label = if (slotIndex == 0) "Auto-Save" else "Slot $slotIndex"
            _events.tryEmit("Estado salvo no $label")
            paused = wasPaused
        }
    }

    fun loadFromSlot(slotIndex: Int) {
        val emu = core ?: return
        val rom = currentRom ?: return
        paused = true

        viewModelScope.launch {
            val slot = repository.getSaveSlot(rom.uriString, slotIndex)
            if (slot == null) {
                _events.tryEmit("Slot vazio")
                paused = false
                return@launch
            }
            val bytes = repository.loadStateFromSlot(slot)
            if (bytes != null) {
                val ok = withContext(Dispatchers.Default) { emu.loadState(bytes) }
                val label = if (slotIndex == 0) "Auto-Save" else "Slot $slotIndex"
                _events.tryEmit(if (ok) "Estado carregado do $label" else "Falha ao carregar estado")
            }
            paused = false
            val current = _uiState.value
            if (current is PlayerUiState.Ready) {
                _uiState.value = current.copy(isPaused = false)
            }
            audioPlayer?.resume()
        }
    }

    private fun captureThumbnailBitmap(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        copyLatestFrame(bmp)
        return bmp
    }

    private fun persistBatteryIfDirty() {
        val emu = core ?: return
        val rom = currentRom ?: return
        if (emu.isBatteryDirty()) {
            val ram = emu.exportBatteryRam() ?: return
            emu.clearBatteryDirty()
            viewModelScope.launch {
                repository.saveBatteryRam(rom.fileName, rom.system, ram)
            }
        }
    }

    fun stopSession(saveAuto: Boolean = true) {
        emulationJob?.cancel()
        emulationJob = null
        slotsObserveJob?.cancel()
        slotsObserveJob = null

        val emu = core
        val rom = currentRom
        if (saveAuto && emu != null && rom != null) {
            val elapsedSec = maxOf(0L, (System.currentTimeMillis() - sessionStartTimeMs) / 1000L)
            val batteryRam = if (emu.isBatteryDirty()) emu.exportBatteryRam() else null
            val autoState = runCatching { emu.saveState() }.getOrNull()
            val thumb = captureThumbnailBitmap(emu.screenWidth, emu.screenHeight)

            // Persist cleanly in application scope / IO without getting cancelled
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                repository.recordPlaySession(rom.uriString, elapsedSec)
                if (batteryRam != null) {
                    repository.saveBatteryRam(rom.fileName, rom.system, batteryRam)
                }
                if (autoState != null) {
                    repository.saveStateToSlot(rom, 0, autoState, thumb, elapsedSec)
                }
            }
        }

        audioPlayer?.release()
        audioPlayer = null
        core = null
        currentRom = null
    }

    override fun onCleared() {
        stopSession(saveAuto = true)
        super.onCleared()
    }
}
