package com.example.data.rom

import android.content.Context
import android.graphics.Bitmap
import com.example.data.model.RomSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class SaveStorageManager(private val context: Context) {
    private val savesRoot = File(context.filesDir, "saves").apply { mkdirs() }

    fun computeRomKey(fileName: String, system: RomSystem): String {
        val base = "${system.name}_${fileName.substringBeforeLast('.').lowercase()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun romDir(fileName: String, system: RomSystem): File {
        return File(savesRoot, computeRomKey(fileName, system)).apply { mkdirs() }
    }

    suspend fun loadBatteryRam(fileName: String, system: RomSystem): ByteArray? = withContext(Dispatchers.IO) {
        val savFile = File(romDir(fileName, system), "battery.sav")
        if (savFile.exists()) savFile.readBytes() else null
    }

    suspend fun saveBatteryRam(fileName: String, system: RomSystem, data: ByteArray) = withContext(Dispatchers.IO) {
        if (data.isEmpty()) return@withContext
        val dir = romDir(fileName, system)
        val tmp = File(dir, "battery.sav.tmp")
        val target = File(dir, "battery.sav")
        tmp.writeBytes(data)
        tmp.renameTo(target)
    }

    suspend fun saveStateToSlot(
        fileName: String,
        system: RomSystem,
        slotIndex: Int,
        stateData: ByteArray,
        screenshot: Bitmap?
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        val dir = romDir(fileName, system)
        val stateFile = File(dir, "slot_$slotIndex.state")
        val tmpFile = File(dir, "slot_$slotIndex.state.tmp")
        tmpFile.writeBytes(stateData)
        tmpFile.renameTo(stateFile)

        var thumbPath: String? = null
        if (screenshot != null) {
            val thumbFile = File(dir, "slot_$slotIndex.png")
            FileOutputStream(thumbFile).use { out ->
                screenshot.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            thumbPath = thumbFile.absolutePath
        }
        stateFile.absolutePath to thumbPath
    }

    suspend fun loadStateFromPath(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.exists()) f.readBytes() else null
    }

    suspend fun deleteSlotFiles(statePath: String, screenshotPath: String?) = withContext(Dispatchers.IO) {
        File(statePath).delete()
        if (screenshotPath != null) File(screenshotPath).delete()
    }
}
