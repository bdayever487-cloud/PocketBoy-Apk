package com.example.emulator.gba

import com.example.data.model.RomSystem
import com.example.emulator.EmulatorCore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class GbaCore(romBytes: ByteArray) : EmulatorCore {
    override val system: RomSystem = RomSystem.GBA
    override val screenWidth: Int = GbaPpu.WIDTH
    override val screenHeight: Int = GbaPpu.HEIGHT

    val bios = GbaBiosHle()
    val ppu = GbaPpu()
    val apu = GbaApu()
    val saveMem = GbaSaveMemory(romBytes)
    val mem = GbaMemory(romBytes, bios, ppu, apu, saveMem)
    val cpu = GbaCpu(mem, bios)

    init {
        reset()
    }

    override fun reset() {
        mem.reset()
        ppu.reset()
        apu.reset()
        saveMem.reset()
        cpu.reset()
    }

    override fun setInput(buttonMask: Int) {
        mem.updateKeypad(buttonMask)
    }

    override fun stepFrame(framebuffer: IntArray, audioBuffer: ShortArray?): Int {
        var audioPos = 0

        for (line in 0 until 228) {
            mem.vcount = line
            val lyc = (mem.dispstat ushr 8) and 0xFF
            if (line == lyc) {
                mem.dispstat = mem.dispstat or 0x04
                if ((mem.dispstat and 0x20) != 0) mem.requestIrq(0x0004)
            } else {
                mem.dispstat = mem.dispstat and 0xFFFB
            }

            if (line == 0) {
                ppu.latchAffineRefs(mem)
                mem.dispstat = mem.dispstat and 0xFFFE // Clear VBlank flag
            } else if (line == 160) {
                mem.dispstat = mem.dispstat or 0x01 // Set VBlank flag
                if ((mem.dispstat and 0x08) != 0) mem.requestIrq(0x0001)
                mem.triggerDmaTiming(1) // VBlank DMA
            }

            // Visible/HDraw phase (960 cycles)
            mem.dispstat = mem.dispstat and 0xFFFD
            audioPos = runCycles(960, audioBuffer, audioPos)

            if (line < 160) {
                ppu.renderScanline(line, mem, framebuffer)
                mem.triggerDmaTiming(2) // HBlank DMA
            }

            // HBlank phase (272 cycles)
            mem.dispstat = mem.dispstat or 0x02
            if ((mem.dispstat and 0x10) != 0) mem.requestIrq(0x0002)
            audioPos = runCycles(272, audioBuffer, audioPos)
        }
        return audioPos
    }

    private fun runCycles(targetCycles: Int, audioBuffer: ShortArray?, startAudioPos: Int): Int {
        var elapsed = 0
        var audioPos = startAudioPos
        while (elapsed < targetCycles) {
            if (mem.halted && (mem.ime == 0 || (mem.ie and mem.ifReg) == 0)) {
                val skip = targetCycles - elapsed
                mem.stepTimers(skip)
                audioPos = apu.step(skip, audioBuffer, audioPos)
                break
            }
            val c = cpu.step()
            elapsed += c
            mem.stepTimers(c)
            audioPos = apu.step(c, audioBuffer, audioPos)
        }
        return audioPos
    }

    override fun exportBatteryRam(): ByteArray? = saveMem.exportRam()
    override fun importBatteryRam(data: ByteArray) = saveMem.importRam(data)
    override fun isBatteryDirty(): Boolean = saveMem.dirty
    override fun clearBatteryDirty() = saveMem.clearDirty()

    override fun saveState(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(0x47424153) // "GBAS"
            out.writeInt(1)
            cpu.saveState(out)
            mem.saveState(out)
            saveMem.saveState(out)
        }
        return baos.toByteArray()
    }

    override fun loadState(data: ByteArray): Boolean {
        return try {
            DataInputStream(ByteArrayInputStream(data)).use { inp ->
                val magic = inp.readInt()
                val version = inp.readInt()
                if (magic != 0x47424153 || version != 1) return false
                cpu.loadState(inp)
                mem.loadState(inp)
                saveMem.loadState(inp)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
