package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.RomSystem

@Entity(tableName = "save_slots")
data class SaveSlotEntity(
    @PrimaryKey val id: String, // "${romKey}_slot_${slotIndex}"
    val romUriString: String,
    val romTitle: String,
    val system: RomSystem,
    val slotIndex: Int, // 0 = Auto-Save, 1..4 = Manual Slots
    val stateFilePath: String,
    val screenshotPath: String?,
    val updatedAt: Long,
    val playTimeSeconds: Long
)
