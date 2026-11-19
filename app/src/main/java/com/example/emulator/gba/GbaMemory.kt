package com.example.emulator.gba

import com.example.emulator.EmulatorButtons
import java.io.DataInputStream
import java.io.DataOutputStream

class GbaMemory(
    val rom: ByteArray,
    val bios: GbaBiosHle,
    val ppu: GbaPpu,
    val apu: GbaApu,
    val saveMem: GbaSaveMemory
) {
    val ewram = ByteArray(256 * 1024)
    val iwram = ByteArray(32 * 1024)
    val palette = ByteArray(1024)
    val vram = ByteArray(96 * 1024)
    val oam = ByteArray(1024)

    // Display registers
    var dispcnt = 0x0080
    var dispstat = 0
    var vcount = 0
    val bgCnt = IntArray(4)
    val bgHofs = IntArray(4)
    val bgVofs = IntArray(4)
    var bg2Pa = 0x0100; var bg2Pb = 0; var bg2Pc = 0; var bg2Pd = 0x0100
    var bg2X = 0; var bg2Y = 0
    var bg3Pa = 0x0100; var bg3Pb = 0; var bg3Pc = 0; var bg3Pd = 0x0100
    var bg3X = 0; var bg3Y = 0
    var win0H = 0; var win1H = 0; var win0V = 0; var win1V = 0
    var winIn = 0; var winOut = 0
    var mosaic = 0
    var bldcnt = 0; var bldalpha = 0; var bldy = 0

    // Interrupts & System
    var ie = 0
    var ifReg = 0
    var ime = 0
    var keyInput = 0x03FF
    var keyCnt = 0
    var postFlg = 0
    var waitcnt = 0
    var halted = false
    var intrWaitMask = 0

    // DMA 0..3
    private val dmaSad = IntArray(4)
    private val dmaDad = IntArray(4)
    private val dmaCntL = IntArray(4)
    private val dmaCntH = IntArray(4)
    private val dmaInternalSrc = IntArray(4)
    private val dmaInternalDst = IntArray(4)

    // Timers 0..3
    private val tmReload = IntArray(4)
    private val tmCounter = IntArray(4)
    private val tmControl = IntArray(4)
    private val tmCycles = IntArray(4)

    fun reset() {
        ewram.fill(0)
        iwram.fill(0)
        palette.fill(0)
        vram.fill(0)
        oam.fill(0)
        dispcnt = 0x0080
        dispstat = 0
        vcount = 0
        bgCnt.fill(0); bgHofs.fill(0); bgVofs.fill(0)
        bg2Pa = 0x0100; bg2Pb = 0; bg2Pc = 0; bg2Pd = 0x0100; bg2X = 0; bg2Y = 0
        bg3Pa = 0x0100; bg3Pb = 0; bg3Pc = 0; bg3Pd = 0x0100; bg3X = 0; bg3Y = 0
        win0H = 0; win1H = 0; win0V = 0; win1V = 0; winIn = 0; winOut = 0
        mosaic = 0; bldcnt = 0; bldalpha = 0; bldy = 0
        ie = 0; ifReg = 0; ime = 0
        keyInput = 0x03FF; keyCnt = 0
        postFlg = 0; waitcnt = 0
        halted = false; intrWaitMask = 0
        dmaSad.fill(0); dmaDad.fill(0); dmaCntL.fill(0); dmaCntH.fill(0)
        dmaInternalSrc.fill(0); dmaInternalDst.fill(0)
        tmReload.fill(0); tmCounter.fill(0); tmControl.fill(0); tmCycles.fill(0)
    }

    fun updateKeypad(buttonMask: Int) {
        var keys = 0x03FF
        if ((buttonMask and EmulatorButtons.A) != 0) keys = keys and (1 shl 0).inv()
        if ((buttonMask and EmulatorButtons.B) != 0) keys = keys and (1 shl 1).inv()
        if ((buttonMask and EmulatorButtons.SELECT) != 0) keys = keys and (1 shl 2).inv()
        if ((buttonMask and EmulatorButtons.START) != 0) keys = keys and (1 shl 3).inv()
        if ((buttonMask and EmulatorButtons.RIGHT) != 0) keys = keys and (1 shl 4).inv()
        if ((buttonMask and EmulatorButtons.LEFT) != 0) keys = keys and (1 shl 5).inv()
        if ((buttonMask and EmulatorButtons.UP) != 0) keys = keys and (1 shl 6).inv()
        if ((buttonMask and EmulatorButtons.DOWN) != 0) keys = keys and (1 shl 7).inv()
        if ((buttonMask and EmulatorButtons.R) != 0) keys = keys and (1 shl 8).inv()
        if ((buttonMask and EmulatorButtons.L) != 0) keys = keys and (1 shl 9).inv()
        keyInput = keys
    }

    fun requestIrq(irqBit: Int) {
        ifReg = ifReg or irqBit
        // Record in BIOS IRQ flags area for IntrWait / VBlankIntrWait compatibility
        val biosIf = read16(0x03007FF8)
        write16(0x03007FF8, biosIf or irqBit)
        if ((ie and irqBit) != 0) {
            if (intrWaitMask == 0 || (intrWaitMask and irqBit) != 0) {
                halted = false
                intrWaitMask = 0
            }
        }
    }

    fun stepTimers(cycles: Int) {
        var prevOverflow = false
        for (i in 0 until 4) {
            val ctrl = tmControl[i]
            if ((ctrl and 0x80) == 0) {
                prevOverflow = false
                continue
            }
            var inc = 0
            if (i > 0 && (ctrl and 0x04) != 0) {
                if (prevOverflow) inc = 1
            } else {
                val shift = when (ctrl and 0x03) {
                    0 -> 0; 1 -> 6; 2 -> 8; else -> 10
                }
                tmCycles[i] += cycles
                inc = tmCycles[i] shr shift
                tmCycles[i] = tmCycles[i] and ((1 shl shift) - 1)
            }

            prevOverflow = false
            if (inc > 0) {
                val sum = tmCounter[i] + inc
                if (sum > 0xFFFF) {
                    tmCounter[i] = tmReload[i] + ((sum - 0x10000) and 0xFFFF)
                    prevOverflow = true
                    if ((ctrl and 0x40) != 0) requestIrq(1 shl (3 + i))
                    if (i <= 1) apu.onTimerOverflow(i, this)
                } else {
                    tmCounter[i] = sum
                }
            }
        }
    }

    fun read8(addr: Int): Int {
        val region = (addr ushr 24) and 0xFF
        return when (region) {
            0x00 -> bios.biosRom.getOrElse(addr and 0x3FFF) { 0 }.toInt() and 0xFF
            0x02 -> ewram[addr and 0x3FFFF].toInt() and 0xFF
            0x03 -> iwram[addr and 0x7FFF].toInt() and 0xFF
            0x04 -> readIo8(addr and 0x3FF)
            0x05 -> palette[addr and 0x3FF].toInt() and 0xFF
            0x06 -> {
                var off = addr and 0x1FFFF
                if (off >= 0x18000) off -= 0x8000
                vram[off].toInt() and 0xFF
            }
            0x07 -> oam[addr and 0x3FF].toInt() and 0xFF
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> {
                if (region == 0x0D && (saveMem.saveType == GbaSaveType.EEPROM_512B || saveMem.saveType == GbaSaveType.EEPROM_8K) && rom.size > 0x1000000) {
                    return saveMem.readEepromBit()
                }
                val off = addr and 0x01FFFFFF
                if (off < rom.size) rom[off].toInt() and 0xFF else ((off shr 1) and 0xFF)
            }
            0x0E, 0x0F -> saveMem.read8(addr and 0xFFFF)
            else -> 0
        }
    }

    fun read16(addr: Int): Int {
        val a = addr and -2
        val region = (a ushr 24) and 0xFF
        if (region == 0x0D && (saveMem.saveType == GbaSaveType.EEPROM_512B || saveMem.saveType == GbaSaveType.EEPROM_8K)) {
            val off = a and 0x01FFFFFF
            if (rom.size <= 0x1000000 || off >= 0x01000000) {
                return saveMem.readEepromBit()
            }
        }
        return read8(a) or (read8(a + 1) shl 8)
    }

    fun read32(addr: Int): Int {
        val a = addr and -4
        return read8(a) or (read8(a + 1) shl 8) or (read8(a + 2) shl 16) or (read8(a + 3) shl 24)
    }

    fun write8(addr: Int, value: Int) {
        val v = value and 0xFF
        val region = (addr ushr 24) and 0xFF
        when (region) {
            0x02 -> ewram[addr and 0x3FFFF] = v.toByte()
            0x03 -> iwram[addr and 0x7FFF] = v.toByte()
            0x04 -> writeIo8(addr and 0x3FF, v)
            0x05 -> {
                val off = addr and 0x3FE
                palette[off] = v.toByte()
                palette[off + 1] = v.toByte()
            }
            0x06 -> {
                var off = addr and 0x1FFFE
                if (off >= 0x18000) off -= 0x8000
                if (off < 0x10000 || (dispcnt and 0x07) < 3) {
                    vram[off] = v.toByte()
                    vram[off + 1] = v.toByte()
                }
            }
            0x0E, 0x0F -> saveMem.write8(addr and 0xFFFF, v)
        }
    }

    fun write16(addr: Int, value: Int) {
        val a = addr and -2
        val v = value and 0xFFFF
        val region = (a ushr 24) and 0xFF
        when (region) {
            0x02 -> { val o = a and 0x3FFFF; ewram[o] = v.toByte(); ewram[o + 1] = (v shr 8).toByte() }
            0x03 -> { val o = a and 0x7FFF; iwram[o] = v.toByte(); iwram[o + 1] = (v shr 8).toByte() }
            0x04 -> writeIo16(a and 0x3FF, v)
            0x05 -> { val o = a and 0x3FF; palette[o] = v.toByte(); palette[o + 1] = (v shr 8).toByte() }
            0x06 -> {
                var o = a and 0x1FFFF
                if (o >= 0x18000) o -= 0x8000
                vram[o] = v.toByte(); vram[o + 1] = (v shr 8).toByte()
            }
            0x07 -> { val o = a and 0x3FF; oam[o] = v.toByte(); oam[o + 1] = (v shr 8).toByte() }
            0x0E, 0x0F -> saveMem.write8(a and 0xFFFF, v and 0xFF)
        }
    }

    fun write32(addr: Int, value: Int) {
        val a = addr and -4
        val region = (a ushr 24) and 0xFF
        if (region == 0x04) {
            val reg = a and 0x3FF
            if (reg == 0x0A0) { apu.pushFifoA(value); return }
            if (reg == 0x0A4) { apu.pushFifoB(value); return }
        }
        write16(a, value and 0xFFFF)
        write16(a + 2, (value ushr 16) and 0xFFFF)
    }

    private fun readIo8(reg: Int): Int {
        val v16 = readIo16(reg and -2)
        return if ((reg and 1) == 0) v16 and 0xFF else (v16 shr 8) and 0xFF
    }

    private fun readIo16(reg: Int): Int = when (reg) {
        0x000 -> dispcnt
        0x004 -> dispstat
        0x006 -> vcount
        0x008 -> bgCnt[0]; 0x00A -> bgCnt[1]; 0x00C -> bgCnt[2]; 0x00E -> bgCnt[3]
        0x048 -> winIn; 0x04A -> winOut
        0x050 -> bldcnt; 0x052 -> bldalpha
        0x082 -> apu.soundCntH
        0x084 -> apu.soundCntX
        0x0BA -> dmaCntH[0]; 0x0C6 -> dmaCntH[1]; 0x0D2 -> dmaCntH[2]; 0x0DE -> dmaCntH[3]
        0x100 -> tmCounter[0]; 0x102 -> tmControl[0]
        0x104 -> tmCounter[1]; 0x106 -> tmControl[1]
        0x108 -> tmCounter[2]; 0x10A -> tmControl[2]
        0x10C -> tmCounter[3]; 0x10E -> tmControl[3]
        0x130 -> keyInput
        0x132 -> keyCnt
        0x200 -> ie
        0x202 -> ifReg
        0x204 -> waitcnt
        0x208 -> ime
        0x300 -> postFlg
        else -> 0
    }

    private fun writeIo8(reg: Int, v: Int) {
        if (reg == 0x301) {
            if ((v and 0x80) == 0) halted = true
            return
        }
        val aligned = reg and -2
        val old = readIo16(aligned)
        val merged = if ((reg and 1) == 0) (old and 0xFF00) or v else (old and 0x00FF) or (v shl 8)
        writeIo16(aligned, merged)
    }

    private fun writeIo16(reg: Int, v: Int) {
        when (reg) {
            0x000 -> dispcnt = v
            0x004 -> dispstat = (dispstat and 0x0007) or (v and 0xFFF8)
            0x008 -> bgCnt[0] = v; 0x00A -> bgCnt[1] = v; 0x00C -> bgCnt[2] = v; 0x00E -> bgCnt[3] = v
            0x010 -> bgHofs[0] = v; 0x012 -> bgVofs[0] = v
            0x014 -> bgHofs[1] = v; 0x016 -> bgVofs[1] = v
            0x018 -> bgHofs[2] = v; 0x01A -> bgVofs[2] = v
            0x01C -> bgHofs[3] = v; 0x01E -> bgVofs[3] = v
            0x020 -> bg2Pa = v; 0x022 -> bg2Pb = v; 0x024 -> bg2Pc = v; 0x026 -> bg2Pd = v
            0x028 -> { bg2X = (bg2X and 0xFFFF0000.toInt()) or v; ppu.bg2RefX = (bg2X shl 4) shr 4 }
            0x02A -> { bg2X = (bg2X and 0x0000FFFF) or (v shl 16); ppu.bg2RefX = (bg2X shl 4) shr 4 }
            0x02C -> { bg2Y = (bg2Y and 0xFFFF0000.toInt()) or v; ppu.bg2RefY = (bg2Y shl 4) shr 4 }
            0x02E -> { bg2Y = (bg2Y and 0x0000FFFF) or (v shl 16); ppu.bg2RefY = (bg2Y shl 4) shr 4 }
            0x030 -> bg3Pa = v; 0x032 -> bg3Pb = v; 0x034 -> bg3Pc = v; 0x036 -> bg3Pd = v
            0x038 -> { bg3X = (bg3X and 0xFFFF0000.toInt()) or v; ppu.bg3RefX = (bg3X shl 4) shr 4 }
            0x03A -> { bg3X = (bg3X and 0x0000FFFF) or (v shl 16); ppu.bg3RefX = (bg3X shl 4) shr 4 }
            0x03C -> { bg3Y = (bg3Y and 0xFFFF0000.toInt()) or v; ppu.bg3RefY = (bg3Y shl 4) shr 4 }
            0x03E -> { bg3Y = (bg3Y and 0x0000FFFF) or (v shl 16); ppu.bg3RefY = (bg3Y shl 4) shr 4 }
            0x040 -> win0H = v; 0x042 -> win1H = v; 0x044 -> win0V = v; 0x046 -> win1V = v
            0x048 -> winIn = v; 0x04A -> winOut = v; 0x04C -> mosaic = v
            0x050 -> bldcnt = v; 0x052 -> bldalpha = v; 0x054 -> bldy = v
            0x082 -> apu.writeSoundCntH(v)
            0x084 -> apu.soundCntX = v
            0x0B0 -> dmaSad[0] = (dmaSad[0] and 0xFFFF0000.toInt()) or v
            0x0B2 -> dmaSad[0] = (dmaSad[0] and 0x0000FFFF) or (v shl 16)
            0x0B4 -> dmaDad[0] = (dmaDad[0] and 0xFFFF0000.toInt()) or v
            0x0B6 -> dmaDad[0] = (dmaDad[0] and 0x0000FFFF) or (v shl 16)
            0x0B8 -> dmaCntL[0] = v
            0x0BA -> writeDmaCntH(0, v)
            0x0BC -> dmaSad[1] = (dmaSad[1] and 0xFFFF0000.toInt()) or v
            0x0BE -> dmaSad[1] = (dmaSad[1] and 0x0000FFFF) or (v shl 16)
            0x0C0 -> dmaDad[1] = (dmaDad[1] and 0xFFFF0000.toInt()) or v
            0x0C2 -> dmaDad[1] = (dmaDad[1] and 0x0000FFFF) or (v shl 16)
            0x0C4 -> dmaCntL[1] = v
            0x0C6 -> writeDmaCntH(1, v)
            0x0C8 -> dmaSad[2] = (dmaSad[2] and 0xFFFF0000.toInt()) or v
            0x0CA -> dmaSad[2] = (dmaSad[2] and 0x0000FFFF) or (v shl 16)
            0x0CC -> dmaDad[2] = (dmaDad[2] and 0xFFFF0000.toInt()) or v
            0x0CE -> dmaDad[2] = (dmaDad[2] and 0x0000FFFF) or (v shl 16)
            0x0D0 -> dmaCntL[2] = v
            0x0D2 -> writeDmaCntH(2, v)
            0x0D4 -> dmaSad[3] = (dmaSad[3] and 0xFFFF0000.toInt()) or v
            0x0D6 -> dmaSad[3] = (dmaSad[3] and 0x0000FFFF) or (v shl 16)
            0x0D8 -> dmaDad[3] = (dmaDad[3] and 0xFFFF0000.toInt()) or v
            0x0DA -> dmaDad[3] = (dmaDad[3] and 0x0000FFFF) or (v shl 16)
            0x0DC -> dmaCntL[3] = v
            0x0DE -> writeDmaCntH(3, v)
            0x100 -> tmReload[0] = v
            0x102 -> writeTmCnt(0, v)
            0x104 -> tmReload[1] = v
            0x106 -> writeTmCnt(1, v)
            0x108 -> tmReload[2] = v
            0x10A -> writeTmCnt(2, v)
            0x10C -> tmReload[3] = v
            0x10E -> writeTmCnt(3, v)
            0x132 -> keyCnt = v
            0x200 -> ie = v and 0x3FFF
            0x202 -> ifReg = ifReg and v.inv() // Write 1 to acknowledge
            0x204 -> waitcnt = v
            0x208 -> ime = v and 1
            0x300 -> postFlg = v
        }
    }

    private fun writeTmCnt(ch: Int, v: Int) {
        val wasEnabled = (tmControl[ch] and 0x80) != 0
        tmControl[ch] = v
        if (!wasEnabled && (v and 0x80) != 0) {
            tmCounter[ch] = tmReload[ch]
            tmCycles[ch] = 0
        }
    }

    private fun writeDmaCntH(ch: Int, v: Int) {
        val wasEnabled = (dmaCntH[ch] and 0x8000) != 0
        dmaCntH[ch] = v
        if (!wasEnabled && (v and 0x8000) != 0) {
            dmaInternalSrc[ch] = dmaSad[ch]
            dmaInternalDst[ch] = dmaDad[ch]
            if (((v shr 12) and 0x03) == 0) { // Immediate
                runDma(ch)
            }
        }
    }

    fun triggerDmaTiming(timing: Int) {
        for (ch in 0 until 4) {
            val cnt = dmaCntH[ch]
            if ((cnt and 0x8000) != 0 && ((cnt shr 12) and 0x03) == timing) {
                runDma(ch)
            }
        }
    }

    fun triggerSoundDma(fifoNum: Int) {
        val targetAddr = if (fifoNum == 1) 0x040000A0 else 0x040000A4
        for (ch in 1..2) {
            val cnt = dmaCntH[ch]
            if ((cnt and 0x8000) != 0 && ((cnt shr 12) and 0x03) == 3 && dmaDad[ch] == targetAddr) {
                var src = dmaInternalSrc[ch] and -4
                for (i in 0 until 4) {
                    val word = read32(src)
                    if (fifoNum == 1) apu.pushFifoA(word) else apu.pushFifoB(word)
                    src += 4
                }
                dmaInternalSrc[ch] = src
            }
        }
    }

    private fun runDma(ch: Int) {
        val cnt = dmaCntH[ch]
        val is32 = (cnt and 0x0400) != 0
        val rawCount = dmaCntL[ch]
        val count = if (rawCount == 0) (if (ch == 3) 0x10000 else 0x4000) else rawCount
        val srcCtrl = (cnt shr 7) and 0x03
        val dstCtrl = (cnt shr 5) and 0x03
        val stepSize = if (is32) 4 else 2

        val srcInc = when (srcCtrl) { 0 -> stepSize; 1 -> -stepSize; else -> 0 }
        val dstInc = when (dstCtrl) { 0, 3 -> stepSize; 1 -> -stepSize; else -> 0 }

        var src = dmaInternalSrc[ch] and (if (is32) -4 else -2)
        var dst = dmaInternalDst[ch] and (if (is32) -4 else -2)

        // EEPROM transfer detection on DMA3
        val isEepromDst = ch == 3 && ((dst ushr 24) == 0x0D)
        for (i in 0 until count) {
            if (is32) {
                write32(dst, read32(src))
            } else {
                val val16 = read16(src)
                if (isEepromDst) {
                    saveMem.writeEepromBit(val16 and 1, count)
                } else {
                    write16(dst, val16)
                }
            }
            src += srcInc
            dst += dstInc
        }

        dmaInternalSrc[ch] = src
        if (dstCtrl != 3) dmaInternalDst[ch] = dst else dmaInternalDst[ch] = dmaDad[ch]

        if ((cnt and 0x0200) == 0) {
            dmaCntH[ch] = cnt and 0x7FFF
        }
        if ((cnt and 0x4000) != 0) {
            requestIrq(1 shl (8 + ch))
        }
    }

    fun saveState(out: DataOutputStream) {
        out.write(ewram); out.write(iwram); out.write(palette); out.write(vram); out.write(oam)
        out.writeInt(dispcnt); out.writeInt(dispstat); out.writeInt(vcount)
        for (i in 0 until 4) {
            out.writeInt(bgCnt[i]); out.writeInt(bgHofs[i]); out.writeInt(bgVofs[i])
            out.writeInt(dmaSad[i]); out.writeInt(dmaDad[i]); out.writeInt(dmaCntL[i]); out.writeInt(dmaCntH[i])
            out.writeInt(dmaInternalSrc[i]); out.writeInt(dmaInternalDst[i])
            out.writeInt(tmReload[i]); out.writeInt(tmCounter[i]); out.writeInt(tmControl[i]); out.writeInt(tmCycles[i])
        }
        out.writeInt(bg2Pa); out.writeInt(bg2Pb); out.writeInt(bg2Pc); out.writeInt(bg2Pd); out.writeInt(bg2X); out.writeInt(bg2Y)
        out.writeInt(bg3Pa); out.writeInt(bg3Pb); out.writeInt(bg3Pc); out.writeInt(bg3Pd); out.writeInt(bg3X); out.writeInt(bg3Y)
        out.writeInt(ppu.bg2RefX); out.writeInt(ppu.bg2RefY); out.writeInt(ppu.bg3RefX); out.writeInt(ppu.bg3RefY)
        out.writeInt(win0H); out.writeInt(win1H); out.writeInt(win0V); out.writeInt(win1V)
        out.writeInt(winIn); out.writeInt(winOut); out.writeInt(mosaic)
        out.writeInt(bldcnt); out.writeInt(bldalpha); out.writeInt(bldy)
        out.writeInt(ie); out.writeInt(ifReg); out.writeInt(ime)
        out.writeBoolean(halted); out.writeInt(intrWaitMask)
    }

    fun loadState(inp: DataInputStream) {
        inp.readFully(ewram); inp.readFully(iwram); inp.readFully(palette); inp.readFully(vram); inp.readFully(oam)
        dispcnt = inp.readInt(); dispstat = inp.readInt(); vcount = inp.readInt()
        for (i in 0 until 4) {
            bgCnt[i] = inp.readInt(); bgHofs[i] = inp.readInt(); bgVofs[i] = inp.readInt()
            dmaSad[i] = inp.readInt(); dmaDad[i] = inp.readInt(); dmaCntL[i] = inp.readInt(); dmaCntH[i] = inp.readInt()
            dmaInternalSrc[i] = inp.readInt(); dmaInternalDst[i] = inp.readInt()
            tmReload[i] = inp.readInt(); tmCounter[i] = inp.readInt(); tmControl[i] = inp.readInt(); tmCycles[i] = inp.readInt()
        }
        bg2Pa = inp.readInt(); bg2Pb = inp.readInt(); bg2Pc = inp.readInt(); bg2Pd = inp.readInt(); bg2X = inp.readInt(); bg2Y = inp.readInt()
        bg3Pa = inp.readInt(); bg3Pb = inp.readInt(); bg3Pc = inp.readInt(); bg3Pd = inp.readInt(); bg3X = inp.readInt(); bg3Y = inp.readInt()
        ppu.bg2RefX = inp.readInt(); ppu.bg2RefY = inp.readInt(); ppu.bg3RefX = inp.readInt(); ppu.bg3RefY = inp.readInt()
        win0H = inp.readInt(); win1H = inp.readInt(); win0V = inp.readInt(); win1V = inp.readInt()
        winIn = inp.readInt(); winOut = inp.readInt(); mosaic = inp.readInt()
        bldcnt = inp.readInt(); bldalpha = inp.readInt(); bldy = inp.readInt()
        ie = inp.readInt(); ifReg = inp.readInt(); ime = inp.readInt()
        halted = inp.readBoolean(); intrWaitMask = inp.readInt()
    }
}
