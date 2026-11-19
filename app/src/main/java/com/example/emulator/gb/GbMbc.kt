package com.example.emulator.gb

import java.io.DataInputStream
import java.io.DataOutputStream

enum class GbMbcType {
    ROM_ONLY,
    MBC1,
    MBC2,
    MBC3,
    MBC5
}

class GbMbc(val rom: ByteArray) {
    val mbcType: GbMbcType
    val hasBattery: Boolean
    val isCgbSupported: Boolean
    val romBanks: Int
    val ram: ByteArray

    private var ramEnabled = false
    private var romBankLow = 1
    private var romBankHigh = 0
    private var ramBank = 0
    private var bankingMode = 0 // 0 = ROM mode, 1 = RAM/upper ROM mode

    // MBC3 RTC registers
    private var rtcLatchState = 0
    private var rtcSeconds = 0
    private var rtcMinutes = 0
    private var rtcHours = 0
    private var rtcDaysLow = 0
    private var rtcDaysHigh = 0
    private var rtcSelectedReg = -1

    var ramDirty = false
        private set

    init {
        val cgbFlag = if (rom.size > 0x0143) rom[0x0143].toInt() and 0xFF else 0
        isCgbSupported = (cgbFlag and 0x80) != 0

        val cartType = if (rom.size > 0x0147) rom[0x0147].toInt() and 0xFF else 0
        mbcType = when (cartType) {
            0x00, 0x08, 0x09 -> GbMbcType.ROM_ONLY
            0x01, 0x02, 0x03 -> GbMbcType.MBC1
            0x05, 0x06 -> GbMbcType.MBC2
            0x0F, 0x10, 0x11, 0x12, 0x13 -> GbMbcType.MBC3
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> GbMbcType.MBC5
            else -> if (rom.size > 32768) GbMbcType.MBC1 else GbMbcType.ROM_ONLY
        }
        hasBattery = cartType in setOf(0x03, 0x06, 0x09, 0x0F, 0x10, 0x13, 0x1B, 0x1E)

        romBanks = maxOf(2, (rom.size + 16383) / 16384)
        val ramSizeCode = if (rom.size > 0x0149) rom[0x0149].toInt() and 0xFF else 0
        val ramBytes = when {
            mbcType == GbMbcType.MBC2 -> 512
            ramSizeCode == 0x01 -> 2048
            ramSizeCode == 0x02 -> 8192
            ramSizeCode == 0x03 -> 32768
            ramSizeCode == 0x04 -> 131072
            ramSizeCode == 0x05 -> 65536
            else -> if (hasBattery) 8192 else 0
        }
        ram = ByteArray(ramBytes)
    }

    fun reset() {
        ramEnabled = false
        romBankLow = if (mbcType == GbMbcType.MBC5) 1 else 1
        romBankHigh = 0
        ramBank = 0
        bankingMode = 0
        rtcLatchState = 0
        rtcSelectedReg = -1
    }

    fun readRom(addr: Int): Int {
        val bank = if (addr < 0x4000) {
            when (mbcType) {
                GbMbcType.MBC1 -> if (bankingMode == 1) ((romBankHigh shl 5) % romBanks) else 0
                else -> 0
            }
        } else {
            val rawBank = when (mbcType) {
                GbMbcType.ROM_ONLY -> 1
                GbMbcType.MBC1 -> {
                    val b = (romBankHigh shl 5) or (romBankLow and 0x1F)
                    if ((b and 0x1F) == 0) b + 1 else b
                }
                GbMbcType.MBC2 -> maxOf(1, romBankLow and 0x0F)
                GbMbcType.MBC3 -> maxOf(1, romBankLow and 0x7F)
                GbMbcType.MBC5 -> ((romBankHigh and 0x01) shl 8) or (romBankLow and 0xFF)
            }
            rawBank % romBanks
        }
        val offset = (bank * 0x4000) + (addr and 0x3FFF)
        return if (offset in rom.indices) rom[offset].toInt() and 0xFF else 0xFF
    }

    fun writeRom(addr: Int, value: Int) {
        val v = value and 0xFF
        when (mbcType) {
            GbMbcType.ROM_ONLY -> {}
            GbMbcType.MBC1 -> when (addr) {
                in 0x0000..0x1FFF -> ramEnabled = (v and 0x0F) == 0x0A
                in 0x2000..0x3FFF -> romBankLow = v and 0x1F
                in 0x4000..0x5FFF -> romBankHigh = v and 0x03
                in 0x6000..0x7FFF -> bankingMode = v and 0x01
            }
            GbMbcType.MBC2 -> {
                if (addr in 0x0000..0x3FFF) {
                    if ((addr and 0x0100) == 0) {
                        ramEnabled = (v and 0x0F) == 0x0A
                    } else {
                        romBankLow = v and 0x0F
                    }
                }
            }
            GbMbcType.MBC3 -> when (addr) {
                in 0x0000..0x1FFF -> ramEnabled = (v and 0x0F) == 0x0A
                in 0x2000..0x3FFF -> romBankLow = v and 0x7F
                in 0x4000..0x5FFF -> {
                    if (v in 0x00..0x03) {
                        ramBank = v
                        rtcSelectedReg = -1
                    } else if (v in 0x08..0x0C) {
                        rtcSelectedReg = v
                    }
                }
                in 0x6000..0x7FFF -> {
                    if (rtcLatchState == 0 && v == 1) {
                        val nowSec = (System.currentTimeMillis() / 1000L)
                        rtcSeconds = (nowSec % 60L).toInt()
                        rtcMinutes = ((nowSec / 60L) % 60L).toInt()
                        rtcHours = ((nowSec / 3600L) % 24L).toInt()
                    }
                    rtcLatchState = v
                }
            }
            GbMbcType.MBC5 -> when (addr) {
                in 0x0000..0x1FFF -> ramEnabled = (v and 0x0F) == 0x0A
                in 0x2000..0x2FFF -> romBankLow = v
                in 0x3000..0x3FFF -> romBankHigh = v and 0x01
                in 0x4000..0x5FFF -> ramBank = v and 0x0F
            }
        }
    }

    fun readRam(addr: Int): Int {
        if (!ramEnabled) return 0xFF
        if (mbcType == GbMbcType.MBC3 && rtcSelectedReg in 0x08..0x0C) {
            return when (rtcSelectedReg) {
                0x08 -> rtcSeconds
                0x09 -> rtcMinutes
                0x0A -> rtcHours
                0x0B -> rtcDaysLow
                0x0C -> rtcDaysHigh
                else -> 0xFF
            }
        }
        if (ram.isEmpty()) return 0xFF
        val effectiveBank = when (mbcType) {
            GbMbcType.MBC1 -> if (bankingMode == 1) romBankHigh else 0
            GbMbcType.MBC2 -> 0
            GbMbcType.MBC3, GbMbcType.MBC5 -> ramBank
            GbMbcType.ROM_ONLY -> 0
        }
        val localAddr = if (mbcType == GbMbcType.MBC2) {
            addr and 0x01FF
        } else {
            ((effectiveBank * 0x2000) + (addr and 0x1FFF)) % ram.size
        }
        val byteVal = ram[localAddr].toInt() and 0xFF
        return if (mbcType == GbMbcType.MBC2) byteVal or 0xF0 else byteVal
    }

    fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled) return
        val v = value and 0xFF
        if (mbcType == GbMbcType.MBC3 && rtcSelectedReg in 0x08..0x0C) {
            when (rtcSelectedReg) {
                0x08 -> rtcSeconds = v
                0x09 -> rtcMinutes = v
                0x0A -> rtcHours = v
                0x0B -> rtcDaysLow = v
                0x0C -> rtcDaysHigh = v
            }
            return
        }
        if (ram.isEmpty()) return
        val effectiveBank = when (mbcType) {
            GbMbcType.MBC1 -> if (bankingMode == 1) romBankHigh else 0
            GbMbcType.MBC2 -> 0
            GbMbcType.MBC3, GbMbcType.MBC5 -> ramBank
            GbMbcType.ROM_ONLY -> 0
        }
        val localAddr = if (mbcType == GbMbcType.MBC2) {
            addr and 0x01FF
        } else {
            ((effectiveBank * 0x2000) + (addr and 0x1FFF)) % ram.size
        }
        ram[localAddr] = (if (mbcType == GbMbcType.MBC2) (v and 0x0F) else v).toByte()
        ramDirty = true
    }

    fun importRam(data: ByteArray) {
        if (ram.isEmpty()) return
        val copyLen = minOf(ram.size, data.size)
        System.arraycopy(data, 0, ram, 0, copyLen)
        ramDirty = false
    }

    fun exportRam(): ByteArray? = if (ram.isNotEmpty()) ram.copyOf() else null

    fun clearRamDirty() {
        ramDirty = false
    }

    fun saveState(out: DataOutputStream) {
        out.writeBoolean(ramEnabled)
        out.writeInt(romBankLow)
        out.writeInt(romBankHigh)
        out.writeInt(ramBank)
        out.writeInt(bankingMode)
        out.writeInt(rtcLatchState)
        out.writeInt(rtcSeconds)
        out.writeInt(rtcMinutes)
        out.writeInt(rtcHours)
        out.writeInt(rtcDaysLow)
        out.writeInt(rtcDaysHigh)
        out.writeInt(rtcSelectedReg)
        out.writeInt(ram.size)
        if (ram.isNotEmpty()) out.write(ram)
    }

    fun loadState(inp: DataInputStream) {
        ramEnabled = inp.readBoolean()
        romBankLow = inp.readInt()
        romBankHigh = inp.readInt()
        ramBank = inp.readInt()
        bankingMode = inp.readInt()
        rtcLatchState = inp.readInt()
        rtcSeconds = inp.readInt()
        rtcMinutes = inp.readInt()
        rtcHours = inp.readInt()
        rtcDaysLow = inp.readInt()
        rtcDaysHigh = inp.readInt()
        rtcSelectedReg = inp.readInt()
        val savedRamSize = inp.readInt()
        if (savedRamSize > 0) {
            val buf = ByteArray(savedRamSize)
            inp.readFully(buf)
            val copyLen = minOf(ram.size, buf.size)
            if (copyLen > 0) System.arraycopy(buf, 0, ram, 0, copyLen)
        }
        ramDirty = true
    }
}
