package com.example.data.rom

import com.example.data.model.RomSystem
import com.example.emulator.gba.GbaSaveMemory

data class RomHeaderInfo(
    val system: RomSystem,
    val displayTitle: String,
    val gameCode: String,
    val mapperInfo: String,
    val hasBattery: Boolean
)

object RomHeaderParser {
    fun parse(fileName: String, headerOrFullBytes: ByteArray): RomHeaderInfo {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val fallbackTitle = fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .trim()
            .ifBlank { fileName }

        return when (ext) {
            "gba" -> parseGba(fallbackTitle, headerOrFullBytes)
            "gbc" -> parseGb(fallbackTitle, headerOrFullBytes, forceGbc = true)
            else -> parseGb(fallbackTitle, headerOrFullBytes, forceGbc = false)
        }
    }

    private fun parseGb(fallbackTitle: String, bytes: ByteArray, forceGbc: Boolean): RomHeaderInfo {
        val cgbFlag = if (bytes.size > 0x0143) bytes[0x0143].toInt() and 0xFF else 0
        val isCgb = forceGbc || (cgbFlag and 0x80) != 0
        val system = if (isCgb) RomSystem.GBC else RomSystem.GB

        val titleEnd = if (bytes.size > 0x0143 && (cgbFlag == 0x80 || cgbFlag == 0xC0)) 0x0143 else 0x0144
        val internalTitle = if (bytes.size >= titleEnd) {
            bytes.copyOfRange(0x0134, titleEnd)
                .map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else ' ' }
                .joinToString("")
                .trim()
        } else ""

        val cartType = if (bytes.size > 0x0147) bytes[0x0147].toInt() and 0xFF else 0
        val mapperName = when (cartType) {
            0x00, 0x08, 0x09 -> "ROM ONLY"
            0x01, 0x02, 0x03 -> "MBC1"
            0x05, 0x06 -> "MBC2"
            0x0F, 0x10, 0x11, 0x12, 0x13 -> "MBC3"
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> "MBC5"
            else -> "MBC"
        }
        val hasBattery = cartType in setOf(0x03, 0x06, 0x09, 0x0F, 0x10, 0x13, 0x1B, 0x1E)
        val displayTitle = if (internalTitle.length >= 2) {
            formatCartridgeTitle(internalTitle, fallbackTitle)
        } else {
            fallbackTitle
        }
        val gameCode = if (bytes.size >= 0x0143) {
            bytes.copyOfRange(0x013F, 0x0143)
                .filter { (it.toInt() and 0xFF) in 48..90 }
                .map { it.toInt().toChar() }
                .joinToString("")
                .ifBlank { mapperName }
        } else mapperName

        return RomHeaderInfo(
            system = system,
            displayTitle = displayTitle,
            gameCode = gameCode,
            mapperInfo = if (hasBattery) "$mapperName + Bateria" else mapperName,
            hasBattery = hasBattery
        )
    }

    private fun parseGba(fallbackTitle: String, bytes: ByteArray): RomHeaderInfo {
        val internalTitle = if (bytes.size >= 0xAC) {
            bytes.copyOfRange(0xA0, 0xAC)
                .map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else ' ' }
                .joinToString("")
                .trim()
        } else ""

        val gameCode = if (bytes.size >= 0xB0) {
            bytes.copyOfRange(0xAC, 0xB0)
                .map { b -> val c = b.toInt() and 0xFF; if (c in 48..90) c.toChar() else ' ' }
                .joinToString("")
                .trim()
                .ifBlank { "GBA" }
        } else "GBA"

        val saveType = if (bytes.size > 1024) {
            GbaSaveMemory.detectSaveType(bytes).name.replace('_', ' ')
        } else "SRAM 32K"

        return RomHeaderInfo(
            system = RomSystem.GBA,
            displayTitle = fallbackTitle.ifBlank { internalTitle.ifBlank { "GBA ROM" } },
            gameCode = gameCode,
            mapperInfo = saveType,
            hasBattery = true
        )
    }

    private fun formatCartridgeTitle(internal: String, fallback: String): String {
        // Prefer clean filename if it's more descriptive than short 8.3 uppercase cartridge title
        return if (fallback.length > internal.length || fallback.any { it.isLowerCase() }) {
            fallback
        } else {
            internal.lowercase().split(' ').joinToString(" ") { w ->
                w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }
}
