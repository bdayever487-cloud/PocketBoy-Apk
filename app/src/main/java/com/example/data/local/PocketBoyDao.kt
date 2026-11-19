package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PocketBoyDao {
    @Query("SELECT * FROM roms ORDER BY isFavorite DESC, lastPlayedAt DESC, displayTitle ASC")
    fun observeAllRoms(): Flow<List<RomEntity>>

    @Query("SELECT * FROM roms WHERE uriString = :uriString LIMIT 1")
    suspend fun getRomByUri(uriString: String): RomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoms(roms: List<RomEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRom(rom: RomEntity)

    @Query("DELETE FROM roms WHERE isImported = 0 AND uriString NOT IN (:activeUris)")
    suspend fun deleteStaleFolderRoms(activeUris: List<String>)

    @Query("DELETE FROM roms WHERE isImported = 0")
    suspend fun deleteAllFolderRoms()

    @Query("UPDATE roms SET isFavorite = :favorite WHERE uriString = :uriString")
    suspend fun setFavorite(uriString: String, favorite: Boolean)

    @Query("UPDATE roms SET lastPlayedAt = :timestamp, playTimeSeconds = playTimeSeconds + :deltaSeconds WHERE uriString = :uriString")
    suspend fun recordPlaySession(uriString: String, timestamp: Long, deltaSeconds: Long)

    @Query("SELECT * FROM save_slots ORDER BY updatedAt DESC")
    fun observeAllSaveSlots(): Flow<List<SaveSlotEntity>>

    @Query("SELECT * FROM save_slots WHERE romUriString = :romUriString ORDER BY slotIndex ASC")
    fun observeSaveSlotsForRom(romUriString: String): Flow<List<SaveSlotEntity>>

    @Query("SELECT * FROM save_slots WHERE romUriString = :romUriString AND slotIndex = :slotIndex LIMIT 1")
    suspend fun getSaveSlot(romUriString: String, slotIndex: Int): SaveSlotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSaveSlot(slot: SaveSlotEntity)

    @Query("DELETE FROM save_slots WHERE id = :id")
    suspend fun deleteSaveSlot(id: String)
}
