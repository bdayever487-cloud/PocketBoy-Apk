package com.example.emulator.gb

import com.example.data.model.RomSystem
import com.example.emulator.EmulatorButtons
import com.example.emulator.EmulatorCore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class GbCore(
    romBytes: ByteArray,
    private val forceSystem: RomSystem? = null
) : EmulatorCore {

    val mbc = GbMbc(romBytes)
    override val system: RomSystem = forceSystem
        ?: if (mbc.isCgbSupported) RomSystem.GBC else RomSystem.GB
    val isCgb: Boolean = (system == RomSystem.GBC)

    override val screenWidth: Int = GbPpu.WIDTH
    override val screenHeight: Int = GbPpu.HEIGHT

    val ppu = GbPpu(isCgb)
    val apu = GbApu()
    private val cpu = GbCpu(this)

    // Work RAM (8 banks of 4KB for CGB; DMG uses banks 0 and 1)
    private val wram = Array(8) { ByteArray(0x1000) }
    private var wramBank = 1
    private val hram = ByteArray(0x7F)

    // Joypad & Interrupts
    private var joypSelect = 0x30
    private var buttonMask = 0
    var ifReg = 0xE1
    var ieReg = 0x00

    // Timers
    private var divCounter = 0xABCC
    private var tima = 0
    private var tma = 0
    private var tac = 0
    private var timaAccum = 0

    // CGB Double speed & HDMA
    var doubleSpeed = false
    var key1Prepare = false
    private var hdmaSrc = 0
    private var hdmaDst = 0
    private var hdmaLen = 0
    private var hdmaActive = false

    // Serial stub
    private var sb = 0
    private var sc = 0x7E

    init {
        reset()
    }

    override fun reset() {
        mbc.reset()
        ppu.reset()
        apu.reset()
        wram.forEach { it.fill(0) }
        wramBank = 1
        hram.fill(0)
        joypSelect = 0x30
        ifReg = 0xE1
        ieReg = 0x00
        divCounter = 0xABCC
        tima = 0
        tma = 0
        tac = 0
        timaAccum = 0
        doubleSpeed = false
        key1Prepare = false
        hdmaSrc = 0
        hdmaDst = 0
        hdmaLen = 0
        hdmaActive = false
        cpu.reset(isCgb)
    }

    override fun setInput(buttonMask: Int) {
        val prev = this.buttonMask
        this.buttonMask = buttonMask
        if ((buttonMask and prev.inv()) != 0) {
            ifReg = ifReg or 0x10 // Joypad interrupt
        }
    }

    fun setDmgPalettePreset(preset: Int) {
        ppu.dmgPalettePreset = preset
    }

    override fun stepFrame(framebuffer: IntArray, audioBuffer: ShortArray?): Int {
        var audioPos = 0
        var cyclesThisFrame = 0
        val maxCyclesPerFrame = if (doubleSpeed) 140448 else 70224

        while (cyclesThisFrame < maxCyclesPerFrame) {
            val cpuCycles = cpu.step()
            cyclesThisFrame += cpuCycles

            // Timer & APU always advance relative to base/effective clock
            val ppuCycles = if (doubleSpeed) cpuCycles shr 1 else cpuCycles
            stepTimers(cpuCycles)
            audioPos = apu.step(ppuCycles, audioBuffer, audioPos)

            val frameDone = ppu.step(ppuCycles, framebuffer)
            if (ppu.requestVBlankIrq) {
                ppu.requestVBlankIrq = false
                ifReg = ifReg or 0x01
            }
            if (ppu.requestStatIrq) {
                ppu.requestStatIrq = false
                ifReg = ifReg or 0x02
            }
            if (ppu.enteredHBlank && hdmaActive) {
                stepHdmaBlock()
            }
            if (frameDone) break
        }
        return audioPos
    }

    private fun stepTimers(cycles: Int) {
        divCounter = (divCounter + cycles) and 0xFFFF
        if ((tac and 0x04) != 0) {
            val period = when (tac and 0x03) {
                0 -> 1024
                1 -> 16
                2 -> 64
                else -> 256
            }
            timaAccum += cycles
            while (timaAccum >= period) {
                timaAccum -= period
                tima++
                if (tima > 0xFF) {
                    tima = tma
                    ifReg = ifReg or 0x04
                }
            }
        }
    }

    private fun stepHdmaBlock() {
        for (i in 0 until 16) {
            val v = read8(hdmaSrc + i)
            val dstOffset = (hdmaDst + i) and 0x1FFF
            ppu.vram[ppu.vramBank][dstOffset] = v.toByte()
        }
        hdmaSrc = (hdmaSrc + 16) and 0xFFFF
        hdmaDst = (hdmaDst + 16) and 0x1FFF
        hdmaLen--
        if (hdmaLen <= 0) {
            hdmaActive = false
            hdmaLen = 0xFF
        }
    }

    fun read8(addr: Int): Int {
        val a = addr and 0xFFFF
        return when (a) {
            in 0x0000..0x7FFF -> mbc.readRom(a)
            in 0x8000..0x9FFF -> ppu.vram[ppu.vramBank][a - 0x8000].toInt() and 0xFF
            in 0xA000..0xBFFF -> mbc.readRam(a)
            in 0xC000..0xCFFF -> wram[0][a - 0xC000].toInt() and 0xFF
            in 0xD000..0xDFFF -> wram[wramBank][a - 0xD000].toInt() and 0xFF
            in 0xE000..0xFDFF -> read8(a - 0x2000)
            in 0xFE00..0xFE9F -> ppu.oam[a - 0xFE00].toInt() and 0xFF
            in 0xFEA0..0xFEFF -> 0xFF
            in 0xFF00..0xFF7F -> readIo(a)
            in 0xFF80..0xFFFE -> hram[a - 0xFF80].toInt() and 0xFF
            0xFFFF -> ieReg
            else -> 0xFF
        }
    }

    fun write8(addr: Int, value: Int) {
        val a = addr and 0xFFFF
        val v = value and 0xFF
        when (a) {
            in 0x0000..0x7FFF -> mbc.writeRom(a, v)
            in 0x8000..0x9FFF -> ppu.vram[ppu.vramBank][a - 0x8000] = v.toByte()
            in 0xA000..0xBFFF -> mbc.writeRam(a, v)
            in 0xC000..0xCFFF -> wram[0][a - 0xC000] = v.toByte()
            in 0xD000..0xDFFF -> wram[wramBank][a - 0xD000] = v.toByte()
            in 0xE000..0xFDFF -> write8(a - 0x2000, v)
            in 0xFE00..0xFE9F -> ppu.oam[a - 0xFE00] = v.toByte()
            in 0xFEA0..0xFEFF -> {}
            in 0xFF00..0xFF7F -> writeIo(a, v)
            in 0xFF80..0xFFFE -> hram[a - 0xFF80] = v.toByte()
            0xFFFF -> ieReg = v
        }
    }

    private fun readIo(addr: Int): Int = when (addr) {
        0xFF00 -> readJoypad()
        0xFF01 -> sb
        0xFF02 -> sc
        0xFF04 -> (divCounter shr 8) and 0xFF
        0xFF05 -> tima
        0xFF06 -> tma
        0xFF07 -> tac or 0xF8
        0xFF0F -> ifReg or 0xE0
        in 0xFF10..0xFF3F -> apu.readRegister(addr)
        0xFF40 -> ppu.lcdc
        0xFF41 -> ppu.stat or 0x80
        0xFF42 -> ppu.scy
        0xFF43 -> ppu.scx
        0xFF44 -> ppu.ly
        0xFF45 -> ppu.lyc
        0xFF47 -> ppu.bgp
        0xFF48 -> ppu.obp0
        0xFF49 -> ppu.obp1
        0xFF4A -> ppu.wy
        0xFF4B -> ppu.wx
        0xFF4D -> if (isCgb) ((if (doubleSpeed) 0x80 else 0) or (if (key1Prepare) 1 else 0) or 0x7E) else 0xFF
        0xFF4F -> if (isCgb) (ppu.vramBank or 0xFE) else 0xFF
        0xFF55 -> if (isCgb) (if (hdmaActive) (hdmaLen - 1) and 0x7F else 0xFF) else 0xFF
        0xFF68 -> if (isCgb) ppu.bcps else 0xFF
        0xFF69 -> if (isCgb) ppu.readBcpd() else 0xFF
        0xFF6A -> if (isCgb) ppu.ocps else 0xFF
        0xFF6B -> if (isCgb) ppu.readOcpd() else 0xFF
        0xFF70 -> if (isCgb) wramBank else 0xFF
        else -> 0xFF
    }

    private fun writeIo(addr: Int, v: Int) {
        when (addr) {
            0xFF00 -> joypSelect = v and 0x30
            0xFF01 -> sb = v
            0xFF02 -> sc = v
            0xFF04 -> divCounter = 0
            0xFF05 -> tima = v
            0xFF06 -> tma = v
            0xFF07 -> tac = v and 0x07
            0xFF0F -> ifReg = v and 0x1F
            in 0xFF10..0xFF3F -> apu.writeRegister(addr, v)
            0xFF40 -> ppu.lcdc = v
            0xFF41 -> ppu.stat = (ppu.stat and 0x07) or (v and 0x78)
            0xFF42 -> ppu.scy = v
            0xFF43 -> ppu.scx = v
            0xFF45 -> {
                ppu.lyc = v
                ppu.checkLyc()
            }
            0xFF46 -> { // OAM DMA
                val srcBase = v shl 8
                for (i in 0 until 0xA0) {
                    ppu.oam[i] = read8(srcBase + i).toByte()
                }
            }
            0xFF47 -> ppu.bgp = v
            0xFF48 -> ppu.obp0 = v
            0xFF49 -> ppu.obp1 = v
            0xFF4A -> ppu.wy = v
            0xFF4B -> ppu.wx = v
            0xFF4D -> if (isCgb) key1Prepare = (v and 0x01) != 0
            0xFF4F -> if (isCgb) ppu.vramBank = v and 0x01
            0xFF51 -> if (isCgb) hdmaSrc = (hdmaSrc and 0x00FF) or (v shl 8)
            0xFF52 -> if (isCgb) hdmaSrc = (hdmaSrc and 0xFF00) or (v and 0xF0)
            0xFF53 -> if (isCgb) hdmaDst = (hdmaDst and 0x00FF) or ((v and 0x1F) shl 8)
            0xFF54 -> if (isCgb) hdmaDst = (hdmaDst and 0xFF00) or (v and 0xF0)
            0xFF55 -> if (isCgb) {
                val blocks = (v and 0x7F) + 1
                if (hdmaActive && (v and 0x80) == 0) {
                    hdmaActive = false
                } else if ((v and 0x80) != 0) {
                    hdmaLen = blocks
                    hdmaActive = true
                } else {
                    // General DMA (immediate)
                    val total = blocks * 16
                    for (i in 0 until total) {
                        val b = read8(hdmaSrc + i)
                        ppu.vram[ppu.vramBank][(hdmaDst + i) and 0x1FFF] = b.toByte()
                    }
                    hdmaSrc = (hdmaSrc + total) and 0xFFFF
                    hdmaDst = (hdmaDst + total) and 0x1FFF
                    hdmaActive = false
                }
            }
            0xFF68 -> if (isCgb) ppu.writeBcps(v)
            0xFF69 -> if (isCgb) ppu.writeBcpd(v)
            0xFF6A -> if (isCgb) ppu.writeOcps(v)
            0xFF6B -> if (isCgb) ppu.writeOcpd(v)
            0xFF70 -> if (isCgb) wramBank = maxOf(1, v and 0x07)
        }
    }

    private fun readJoypad(): Int {
        var res = 0xCF or joypSelect
        if ((joypSelect and 0x10) == 0) { // D-Pad
            if ((buttonMask and EmulatorButtons.RIGHT) != 0) res = res and 0xFE
            if ((buttonMask and EmulatorButtons.LEFT) != 0) res = res and 0xFD
            if ((buttonMask and EmulatorButtons.UP) != 0) res = res and 0xFB
            if ((buttonMask and EmulatorButtons.DOWN) != 0) res = res and 0xF7
        }
        if ((joypSelect and 0x20) == 0) { // Action buttons
            if ((buttonMask and EmulatorButtons.A) != 0) res = res and 0xFE
            if ((buttonMask and EmulatorButtons.B) != 0) res = res and 0xFD
            if ((buttonMask and EmulatorButtons.SELECT) != 0) res = res and 0xFB
            if ((buttonMask and EmulatorButtons.START) != 0) res = res and 0xF7
        }
        return res
    }

    override fun exportBatteryRam(): ByteArray? = mbc.exportRam()

    override fun importBatteryRam(data: ByteArray) = mbc.importRam(data)

    override fun isBatteryDirty(): Boolean = mbc.ramDirty

    override fun clearBatteryDirty() = mbc.clearRamDirty()

    override fun saveState(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(0x47425354) // "GBST" magic
            out.writeInt(1) // version
            cpu.saveState(out)
            ppu.saveState(out)
            mbc.saveState(out)
            for (bank in wram) out.write(bank)
            out.writeInt(wramBank)
            out.write(hram)
            out.writeInt(joypSelect)
            out.writeInt(ifReg)
            out.writeInt(ieReg)
            out.writeInt(divCounter)
            out.writeInt(tima)
            out.writeInt(tma)
            out.writeInt(tac)
            out.writeInt(timaAccum)
            out.writeBoolean(doubleSpeed)
            out.writeBoolean(key1Prepare)
            out.writeInt(hdmaSrc)
            out.writeInt(hdmaDst)
            out.writeInt(hdmaLen)
            out.writeBoolean(hdmaActive)
        }
        return baos.toByteArray()
    }

    override fun loadState(data: ByteArray): Boolean {
        return try {
            DataInputStream(ByteArrayInputStream(data)).use { inp ->
                val magic = inp.readInt()
                val version = inp.readInt()
                if (magic != 0x47425354 || version != 1) return false
                cpu.loadState(inp)
                ppu.loadState(inp)
                mbc.loadState(inp)
                for (bank in wram) inp.readFully(bank)
                wramBank = inp.readInt()
                inp.readFully(hram)
                joypSelect = inp.readInt()
                ifReg = inp.readInt()
                ieReg = inp.readInt()
                divCounter = inp.readInt()
                tima = inp.readInt()
                tma = inp.readInt()
                tac = inp.readInt()
                timaAccum = inp.readInt()
                doubleSpeed = inp.readBoolean()
                key1Prepare = inp.readBoolean()
                hdmaSrc = inp.readInt()
                hdmaDst = inp.readInt()
                hdmaLen = inp.readInt()
                hdmaActive = inp.readBoolean()
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
