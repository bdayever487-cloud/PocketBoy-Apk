package com.example

import com.example.data.model.RomSystem
import com.example.data.rom.RomHeaderParser
import com.example.emulator.EmulatorButtons
import com.example.emulator.gb.GbCore
import com.example.emulator.gba.GbaCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorCoreTest {

    @Test
    fun romHeaderParser_identifiesGbGbcAndGba() {
        val gbRom = ByteArray(32768)
        "TETRIS".toByteArray().copyInto(gbRom, 0x0134)
        gbRom[0x0147] = 0x03 // MBC1 + RAM + Battery
        gbRom[0x0149] = 0x02 // 8KB RAM

        val gbInfo = RomHeaderParser.parse("tetris.gb", gbRom)
        assertEquals(RomSystem.GB, gbInfo.system)
        assertTrue(gbInfo.hasBattery)
        assertTrue(gbInfo.mapperInfo.contains("MBC1"))

        val gbcRom = ByteArray(32768)
        gbcRom[0x0143] = 0xC0.toByte()
        gbcRom[0x0147] = 0x1B // MBC5 + RAM + Battery
        val gbcInfo = RomHeaderParser.parse("zelda_dx.gbc", gbcRom)
        assertEquals(RomSystem.GBC, gbcInfo.system)

        val gbaRom = ByteArray(4096)
        "POKEMON EMER".toByteArray().copyInto(gbaRom, 0xA0)
        "BPEE".toByteArray().copyInto(gbaRom, 0xAC)
        "FLASH1M_V103".toByteArray().copyInto(gbaRom, 0x200)
        val gbaInfo = RomHeaderParser.parse("emerald.gba", gbaRom)
        assertEquals(RomSystem.GBA, gbaInfo.system)
        assertEquals("BPEE", gbaInfo.gameCode)
        assertTrue(gbaInfo.mapperInfo.contains("FLASH 128K"))
    }

    @Test
    fun gbCore_executesInstructionsRendersAndSavesState() {
        // Build a minimal 32KB GB ROM that enables LCD + BG, writes a non-zero tile + map, and writes SRAM
        val rom = ByteArray(32768)
        rom[0x0147] = 0x03 // MBC1 + RAM + Battery
        rom[0x0149] = 0x02 // 8KB RAM

        // Entry at 0x0100: NOP; JP 0x0150
        rom[0x0100] = 0x00
        rom[0x0101] = 0xC3.toByte()
        rom[0x0102] = 0x50
        rom[0x0103] = 0x01

        // Program at 0x0150:
        // Enable SRAM: LD A, 0x0A; LD (0x0000), A
        // Write 0x42 to SRAM 0xA000
        // Write solid tile #1 into VRAM 0x8010..0x801F
        // Set BG tile (0,0) = 1 at 0x9800
        // Set BGP = 0xE4
        // Loop forever: JR -2 (0x18 0xFE)
        var p = 0x0150
        fun emit(vararg bytes: Int) { for (b in bytes) rom[p++] = b.toByte() }
        emit(0x3E, 0x0A, 0xEA, 0x00, 0x00) // LD A,0A; LD (0000),A
        emit(0x3E, 0x42, 0xEA, 0x00, 0xA0) // LD A,42; LD (A000),A
        emit(0x3E, 0xFF, 0xEA, 0x10, 0x80, 0xEA, 0x11, 0x80) // Tile 1 row 0 color 3
        emit(0x3E, 0x01, 0xEA, 0x00, 0x98) // Tile map (0,0) = tile 1
        emit(0x3E, 0xE4, 0xE0, 0x47)       // LD (FF47), A (BGP)
        emit(0x18, 0xFE)                   // JR -2

        val core = GbCore(rom)
        val fb = IntArray(160 * 144)
        core.stepFrame(fb, null)

        // Pixel (0,0) should be darkest shade (color 3 = 0xFF0F380F), distinct from background shade 0
        assertEquals(0xFF0F380F.toInt(), fb[0])
        assertNotEquals(fb[0], fb[8])
        assertTrue(core.isBatteryDirty())
        val sram = core.exportBatteryRam()!!
        assertEquals(0x42.toByte(), sram[0])

        val state = core.saveState()
        assertTrue(state.isNotEmpty())
        core.reset()
        assertTrue(core.loadState(state))
        val fbAfter = IntArray(160 * 144)
        core.stepFrame(fbAfter, null)
        assertEquals(0xFF0F380F.toInt(), fbAfter[0])
    }

    @Test
    fun gbaCore_executesArmMode3RenderAndSavesState() {
        // Minimal GBA ROM:
        // 0x08000000:
        //   mov r0, #0x04000000      (0xE3A00301)
        //   mov r1, #0x0400          (0xE3A01C04) -> Mode 3 + BG2 enable (0x0403)
        //   orr r1, r1, #3           (0xE3811003)
        //   strh r1, [r0]            (0xE1C010B0)
        //   mov r2, #0x06000000      (0xE3A02406)
        //   mov r3, #0x001F          (0xE3A0301F) -> Pure Red in BGR555
        //   strh r3, [r2]            (0xE1C230B0)
        //   b .                      (0xEAFFFFFE)
        val rom = ByteArray(1024)
        fun write32(off: Int, w: Int) {
            rom[off] = (w and 0xFF).toByte()
            rom[off + 1] = ((w ushr 8) and 0xFF).toByte()
            rom[off + 2] = ((w ushr 16) and 0xFF).toByte()
            rom[off + 3] = ((w ushr 24) and 0xFF).toByte()
        }
        write32(0x00, 0xE3A00301.toInt())
        write32(0x04, 0xE3A01C04.toInt())
        write32(0x08, 0xE3811003.toInt())
        write32(0x0C, 0xE1C010B0.toInt())
        write32(0x10, 0xE3A02406.toInt())
        write32(0x14, 0xE3A0301F.toInt())
        write32(0x18, 0xE1C230B0.toInt())
        write32(0x1C, 0xEAFFFFFE.toInt())

        val core = GbaCore(rom)
        core.setInput(EmulatorButtons.A)
        val fb = IntArray(240 * 160)
        core.stepFrame(fb, null)

        // Pixel (0,0) should be pure red ARGB (0xFFFF0000), while (1,0) is black (0xFF000000)
        assertEquals(0xFFFF0000.toInt(), fb[0])
        assertEquals(0xFF000000.toInt(), fb[1])
        // Verify KEYINPUT bit 0 (A button) is cleared (0 = pressed)
        assertEquals(0, core.mem.keyInput and 1)

        val state = core.saveState()
        core.reset()
        assertTrue(core.loadState(state))
        val fbAfter = IntArray(240 * 160)
        core.stepFrame(fbAfter, null)
        assertEquals(0xFFFF0000.toInt(), fbAfter[0])
    }
}
