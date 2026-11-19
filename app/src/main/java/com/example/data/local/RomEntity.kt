package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.RomSystem

@Entity(tableName = "roms")
data class RomEntity(
    @PrimaryKey val uriString: String,
    val fileName: String,
    val displayTitle: String,
    val system: RomSystem,
    val gameCode: String,
    val mapperInfo: String,
    val fileSizeBytes: Long,
    val lastModified: Long,
    val lastPlayedAt: Long = 0L,
    val playTimeSeconds: Long = 0L,
    val isFavorite: Boolean = false,
    val isImported: Boolean = false
)
