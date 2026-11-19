package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.model.RomSystem

class PocketBoyConverters {
    @TypeConverter
    fun fromRomSystem(system: RomSystem): String = system.name

    @TypeConverter
    fun toRomSystem(value: String): RomSystem =
        runCatching { RomSystem.valueOf(value) }.getOrDefault(RomSystem.GB)
}

@Database(
    entities = [RomEntity::class, SaveSlotEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(PocketBoyConverters::class)
abstract class PocketBoyDatabase : RoomDatabase() {
    abstract fun dao(): PocketBoyDao

    companion object {
        @Volatile
        private var INSTANCE: PocketBoyDatabase? = null

        fun getInstance(context: Context): PocketBoyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketBoyDatabase::class.java,
                    "pocketboy_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
