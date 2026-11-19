package com.example.emulator.gba

import java.io.DataInputStream
import java.io.DataOutputStream

enum class GbaSaveType {
    NONE,
    SRAM_32K,
    FLASH_64K,
    FLASH_128K,
    EEPROM_512B,
    EEPROM_8K
}

class GbaSaveMemory(rom: ByteArray) {
    val saveType: GbaSaveType = detectSaveType(rom)
    val data: ByteArray = when (saveType) {
        GbaSaveType.NONE -> ByteArray(0)
        GbaSaveType.SRAM_32K -> ByteArray(32 * 1024) { 0xFF.toByte() }
        GbaSaveType.FLASH_64K -> ByteArray(64 * 1024) { 0xFF.toByte() }
        GbaSaveType.FLASH_128K -> ByteArray(128 * 1024) { 0xFF.toByte() }
        GbaSaveType.EEPROM_512B -> ByteArray(512) { 0xFF.toByte() }
        GbaSaveType.EEPROM_8K -> ByteArray(8 * 1024) { 0xFF.toByte() }
    }

    // Flash state machine
    private var flashState = 0 // 0=idle, 1=0xAA, 2=0x55, 3=erase_wait, 4=erase_AA, 5=erase_55, 6=write_byte, 7=bank_select
    private var flashIdMode = false
    private var flashBank = 0

    // EEPROM serial state
    private var eepromBits = IntArray(128)
    private var eepromBitPos = 0
    private var eepromReadBits = IntArray(68)
    private var eepromReadPos = 0
    private var eepromReadLen = 0

    var dirty = false
        private set

    fun reset() {
        flashState = 0
        flashIdMode = false
        flashBank = 0
        eepromBitPos = 0
        eepromReadPos = 0
        eepromReadLen = 0
    }

    fun read8(offset: Int): Int {
        if (saveType == GbaSaveType.NONE) return 0xFF
        val off = offset and 0xFFFF
        if (saveType == GbaSaveType.FLASH_64K || saveType == GbaSaveType.FLASH_128K) {
            if (flashIdMode) {
                return when (off) {
                    0 -> if (saveType == GbaSaveType.FLASH_128K) 0x62 else 0x32 // Sanyo / Panasonic
                    1 -> if (saveType == GbaSaveType.FLASH_128K) 0x13 else 0x1B
                    else -> 0xFF
                }
            }
            val addr = (flashBank shl 16) or off
            return if (addr in data.indices) data[addr].toInt() and 0xFF else 0xFF
        }
        val addr = off % maxOf(1, data.size)
        return if (addr in data.indices) data[addr].toInt() and 0xFF else 0xFF
    }

    fun write8(offset: Int, value: Int) {
        val v = value and 0xFF
        val off = offset and 0xFFFF
        when (saveType) {
            GbaSaveType.SRAM_32K -> {
                val addr = off % data.size
                data[addr] = v.toByte()
                dirty = true
            }
            GbaSaveType.FLASH_64K, GbaSaveType.FLASH_128K -> writeFlash(off, v)
            else -> {}
        }
    }

    private fun writeFlash(off: Int, v: Int) {
        when (flashState) {
            6 -> { // Write single byte
                val addr = (flashBank shl 16) or off
                if (addr in data.indices) {
                    data[addr] = v.toByte()
                    dirty = true
                }
                flashState = 0
            }
            7 -> { // Switch bank (128K)
                if (off == 0) flashBank = v and 0x01
                flashState = 0
            }
            0 -> if (off == 0x5555 && v == 0xAA) flashState = 1
            1 -> flashState = if (off == 0x2AAA && v == 0x55) 2 else 0
            2 -> {
                if (off == 0x5555) {
                    when (v) {
                        0x90 -> { flashIdMode = true; flashState = 0 }
                        0xF0 -> { flashIdMode = false; flashState = 0 }
                        0x80 -> flashState = 3
                        0xA0 -> flashState = 6
                        0xB0 -> flashState = if (saveType == GbaSaveType.FLASH_128K) 7 else 0
                        else -> flashState = 0
                    }
                } else flashState = 0
            }
            3 -> flashState = if (off == 0x5555 && v == 0xAA) 4 else 0
            4 -> flashState = if (off == 0x2AAA && v == 0x55) 5 else 0
            5 -> {
                if (off == 0x5555 && v == 0x10) {
                    data.fill(0xFF.toByte())
                    dirty = true
                } else if (v == 0x30) {
                    val sectorStart = ((flashBank shl 16) or (off and 0xF000))
                    val sectorEnd = minOf(data.size, sectorStart + 0x1000)
                    for (i in sectorStart until sectorEnd) data[i] = 0xFF.toByte()
                    dirty = true
                }
                flashState = 0
            }
            else -> flashState = 0
        }
    }

    fun writeEepromBit(bit: Int, dmaCount: Int) {
        if (saveType != GbaSaveType.EEPROM_512B && saveType != GbaSaveType.EEPROM_8K) return
        if (eepromBitPos < eepromBits.size) {
            eepromBits[eepromBitPos++] = bit and 1
        }
        val addrBits = if (saveType == GbaSaveType.EEPROM_8K || dmaCount == 17 || dmaCount == 81) 14 else 6
        if (eepromBitPos >= 2) {
            val cmd = (eepromBits[0] shl 1) or eepromBits[1]
            if (cmd == 3 && eepromBitPos == 2 + addrBits + 1) { // Read request
                var blockAddr = 0
                for (i in 0 until addrBits) blockAddr = (blockAddr shl 1) or eepromBits[2 + i]
                val byteStart = (blockAddr * 8) % data.size
                eepromReadLen = 68
                eepromReadPos = 0
                for (i in 0 until 4) eepromReadBits[i] = 0
                for (b in 0 until 8) {
                    val byteVal = data[(byteStart + b) % data.size].toInt() and 0xFF
                    for (bitIdx in 0 until 8) {
                        eepromReadBits[4 + b * 8 + bitIdx] = (byteVal shr (7 - bitIdx)) and 1
                    }
                }
                eepromBitPos = 0
            } else if (cmd == 2 && eepromBitPos == 2 + addrBits + 64 + 1) { // Write request
                var blockAddr = 0
                for (i in 0 until addrBits) blockAddr = (blockAddr shl 1) or eepromBits[2 + i]
                val byteStart = (blockAddr * 8) % data.size
                for (b in 0 until 8) {
                    var byteVal = 0
                    for (bitIdx in 0 until 8) {
                        byteVal = (byteVal shl 1) or eepromBits[2 + addrBits + b * 8 + bitIdx]
                    }
                    data[(byteStart + b) % data.size] = byteVal.toByte()
                }
                dirty = true
                eepromBitPos = 0
            }
        }
    }

    fun readEepromBit(): Int {
        if (eepromReadPos < eepromReadLen) {
            return eepromReadBits[eepromReadPos++]
        }
        return 1
    }

    fun importRam(bytes: ByteArray) {
        if (data.isEmpty()) return
        val len = minOf(data.size, bytes.size)
        System.arraycopy(bytes, 0, data, 0, len)
        dirty = false
    }

    fun exportRam(): ByteArray? = if (data.isNotEmpty()) data.copyOf() else null
    fun clearDirty() { dirty = false }

    fun saveState(out: DataOutputStream) {
        out.writeInt(flashState)
        out.writeBoolean(flashIdMode)
        out.writeInt(flashBank)
        out.writeInt(data.size)
        if (data.isNotEmpty()) out.write(data)
    }

    fun loadState(inp: DataInputStream) {
        flashState = inp.readInt()
        flashIdMode = inp.readBoolean()
        flashBank = inp.readInt()
        val sz = inp.readInt()
        if (sz > 0) {
            val buf = ByteArray(sz)
            inp.readFully(buf)
            val len = minOf(data.size, buf.size)
            if (len > 0) System.arraycopy(buf, 0, data, 0, len)
        }
        dirty = true
    }

    companion object {
        fun detectSaveType(rom: ByteArray): GbaSaveType {
            if (rom.size < 256) return GbaSaveType.SRAM_32K
            val text = String(rom, Charsets.ISO_8859_1)
            return when {
                text.contains("FLASH1M_V") -> GbaSaveType.FLASH_128K
                text.contains("FLASH512_V") || text.contains("FLASH_V") -> GbaSaveType.FLASH_64K
                text.contains("EEPROM_V") -> GbaSaveType.EEPROM_8K
                text.contains("SRAM_V") || text.contains("SRAM_F_V") -> GbaSaveType.SRAM_32K
                else -> GbaSaveType.SRAM_32K
            }
        }
    }
}
