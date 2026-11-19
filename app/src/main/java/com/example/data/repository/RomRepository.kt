package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.example.data.local.PocketBoyDatabase
import com.example.data.local.RomEntity
import com.example.data.local.SaveSlotEntity
import com.example.data.model.RomSystem
import com.example.data.prefs.EmulatorSettings
import com.example.data.prefs.UserPrefsRepository
import com.example.data.rom.RomScanner
import com.example.data.rom.SaveStorageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class RomRepository(private val context: Context) {
    private val dao = PocketBoyDatabase.getInstance(context).dao()
    val prefs = UserPrefsRepository(context)
    val scanner = RomScanner(context)
    val saveStorage = SaveStorageManager(context)

    val allRoms: Flow<List<RomEntity>> = dao.observeAllRoms()
    val allSaveSlots: Flow<List<SaveSlotEntity>> = dao.observeAllSaveSlots()
    val settings: Flow<EmulatorSettings> = prefs.settings

    fun observeSaveSlotsForRom(romUriString: String): Flow<List<SaveSlotEntity>> =
        dao.observeSaveSlotsForRom(romUriString)

    suspend fun linkAndScanFolder(treeUri: Uri): Int {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
            .onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        val label = RomScanner.formatTreeFolderLabel(treeUri)
        prefs.setRomFolder(treeUri.toString(), label)
        return refreshLinkedFolder()
    }

    suspend fun refreshLinkedFolder(): Int {
        val currentSettings = prefs.settings.first()
        val uriStr = currentSettings.romFolderUri ?: return 0
        val treeUri = Uri.parse(uriStr)

        val scanned = scanner.scanFolder(treeUri)
        val merged = scanned.map { rom ->
            val existing = dao.getRomByUri(rom.uriString)
            if (existing != null) {
                rom.copy(
                    lastPlayedAt = existing.lastPlayedAt,
                    playTimeSeconds = existing.playTimeSeconds,
                    isFavorite = existing.isFavorite
                )
            } else {
                rom
            }
        }

        if (merged.isEmpty()) {
            dao.deleteAllFolderRoms()
        } else {
            dao.upsertRoms(merged)
            dao.deleteStaleFolderRoms(merged.map { it.uriString })
        }
        prefs.setLastScanTimestamp(System.currentTimeMillis())
        return merged.size
    }

    suspend fun importSingleRom(uri: Uri): RomEntity? {
        val imported = scanner.importSingleRom(uri) ?: return null
        dao.upsertRom(imported)
        return imported
    }

    suspend fun getRomByUri(uriString: String): RomEntity? = dao.getRomByUri(uriString)

    suspend fun toggleFavorite(rom: RomEntity) {
        dao.setFavorite(rom.uriString, !rom.isFavorite)
    }

    suspend fun recordPlaySession(uriString: String, deltaSeconds: Long) {
        dao.recordPlaySession(uriString, System.currentTimeMillis(), maxOf(0L, deltaSeconds))
    }

    suspend fun loadRomBytes(uriString: String): ByteArray? = scanner.loadRomBytes(uriString)

    suspend fun loadBatteryRam(fileName: String, system: RomSystem): ByteArray? =
        saveStorage.loadBatteryRam(fileName, system)

    suspend fun saveBatteryRam(fileName: String, system: RomSystem, data: ByteArray) =
        saveStorage.saveBatteryRam(fileName, system, data)

    suspend fun saveStateToSlot(
        rom: RomEntity,
        slotIndex: Int,
        stateData: ByteArray,
        screenshot: Bitmap?,
        sessionSeconds: Long
    ) {
        val (statePath, thumbPath) = saveStorage.saveStateToSlot(
            fileName = rom.fileName,
            system = rom.system,
            slotIndex = slotIndex,
            stateData = stateData,
            screenshot = screenshot
        )
        val key = saveStorage.computeRomKey(rom.fileName, rom.system)
        val entity = SaveSlotEntity(
            id = "${key}_slot_$slotIndex",
            romUriString = rom.uriString,
            romTitle = rom.displayTitle,
            system = rom.system,
            slotIndex = slotIndex,
            stateFilePath = statePath,
            screenshotPath = thumbPath,
            updatedAt = System.currentTimeMillis(),
            playTimeSeconds = rom.playTimeSeconds + sessionSeconds
        )
        dao.upsertSaveSlot(entity)
    }

    suspend fun loadStateFromSlot(slot: SaveSlotEntity): ByteArray? =
        saveStorage.loadStateFromPath(slot.stateFilePath)

    suspend fun getSaveSlot(romUriString: String, slotIndex: Int): SaveSlotEntity? =
        dao.getSaveSlot(romUriString, slotIndex)

    suspend fun deleteSaveSlot(slot: SaveSlotEntity) {
        saveStorage.deleteSlotFiles(slot.stateFilePath, slot.screenshotPath)
        dao.deleteSaveSlot(slot.id)
    }
}
