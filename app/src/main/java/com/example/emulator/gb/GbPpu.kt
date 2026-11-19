package com.example.emulator.gb

import java.io.DataInputStream
import java.io.DataOutputStream

class GbPpu(private val isCgb: Boolean) {
    companion object {
        const val WIDTH = 160
        const val HEIGHT = 144

        // Palettes: 0 = Classic Olive DMG, 1 = Pocket Silver, 2 = Crisp LCD
        private val DMG_PALETTES = arrayOf(
            intArrayOf(0xFF9BBC0F.toInt(), 0xFF8BAC0F.toInt(), 0xFF306230.toInt(), 0xFF0F380F.toInt()),
            intArrayOf(0xFFE0E8D8.toInt(), 0xFFA2AA99.toInt(), 0xFF5A6154.toInt(), 0xFF1D221B.toInt()),
            intArrayOf(0xFFF4F7F0.toInt(), 0xFFA5B2C4.toInt(), 0xFF4B5B73.toInt(), 0xFF141B29.toInt())
        )
    }

    // 2 VRAM banks of 8KB each (CGB uses both, DMG uses bank 0)
    val vram = Array(2) { ByteArray(0x2000) }
    val oam = ByteArray(0xA0)
    var vramBank = 0

    // Registers
    var lcdc = 0x91
    var stat = 0x85
    var scy = 0
    var scx = 0
    var ly = 0
    var lyc = 0
    var bgp = 0xFC
    var obp0 = 0xFF
    var obp1 = 0xFF
    var wy = 0
    var wx = 0

    // CGB Palettes (64 bytes each: 8 palettes * 4 colors * 2 bytes)
    var bcps = 0
    val bcpd = ByteArray(64) { 0xFF.toByte() }
    var ocps = 0
    val ocpd = ByteArray(64) { 0xFF.toByte() }

    var dmgPalettePreset = 0

    private var mode = 2
    private var dotCounter = 0
    private var winLineCounter = 0
    private val bgColorIndices = IntArray(WIDTH)
    private val bgPriority = BooleanArray(WIDTH)

    var requestVBlankIrq = false
    var requestStatIrq = false
    var enteredHBlank = false

    fun reset() {
        vram[0].fill(0)
        vram[1].fill(0)
        oam.fill(0)
        vramBank = 0
        lcdc = 0x91
        stat = 0x85
        scy = 0
        scx = 0
        ly = 0
        lyc = 0
        bgp = 0xFC
        obp0 = 0xFF
        obp1 = 0xFF
        wy = 0
        wx = 0
        bcps = 0
        ocps = 0
        bcpd.fill(0xFF.toByte())
        ocpd.fill(0xFF.toByte())
        mode = 2
        dotCounter = 0
        winLineCounter = 0
        requestVBlankIrq = false
        requestStatIrq = false
        enteredHBlank = false
    }

    fun step(tCycles: Int, framebuffer: IntArray): Boolean {
        enteredHBlank = false
        if ((lcdc and 0x80) == 0) {
            dotCounter = 0
            ly = 0
            winLineCounter = 0
            setMode(0)
            return false
        }

        var frameCompleted = false
        dotCounter += tCycles

        when (mode) {
            2 -> { // OAM Search (80 dots)
                if (dotCounter >= 80) {
                    dotCounter -= 80
                    setMode(3)
                }
            }
            3 -> { // Pixel Transfer (172 dots)
                if (dotCounter >= 172) {
                    dotCounter -= 172
                    renderScanline(framebuffer)
                    setMode(0)
                    enteredHBlank = true
                    if ((stat and 0x08) != 0) requestStatIrq = true
                }
            }
            0 -> { // HBlank (204 dots)
                if (dotCounter >= 204) {
                    dotCounter -= 204
                    ly++
                    checkLyc()
                    if (ly >= 144) {
                        setMode(1)
                        requestVBlankIrq = true
                        if ((stat and 0x10) != 0) requestStatIrq = true
                        frameCompleted = true
                    } else {
                        setMode(2)
                        if ((stat and 0x20) != 0) requestStatIrq = true
                    }
                }
            }
            1 -> { // VBlank (lines 144..153, 456 dots each)
                if (dotCounter >= 456) {
                    dotCounter -= 456
                    ly++
                    if (ly > 153) {
                        ly = 0
                        winLineCounter = 0
                        setMode(2)
                        if ((stat and 0x20) != 0) requestStatIrq = true
                    }
                    checkLyc()
                }
            }
        }
        return frameCompleted
    }

    private fun setMode(newMode: Int) {
        mode = newMode
        stat = (stat and 0xFC) or (newMode and 0x03)
    }

    fun checkLyc() {
        if (ly == lyc) {
            stat = stat or 0x04
            if ((stat and 0x40) != 0) requestStatIrq = true
        } else {
            stat = stat and 0xFB
        }
    }

    private fun renderScanline(framebuffer: IntArray) {
        if (ly !in 0 until HEIGHT) return
        val rowOffset = ly * WIDTH
        val palette = DMG_PALETTES[dmgPalettePreset.coerceIn(0, DMG_PALETTES.lastIndex)]

        val bgEnabled = isCgb || (lcdc and 0x01) != 0
        val winEnabled = bgEnabled && (lcdc and 0x20) != 0 && ly >= wy && wx <= 166
        val tileDataUnsigned = (lcdc and 0x10) != 0
        val bgMapBase = if ((lcdc and 0x08) != 0) 0x1C00 else 0x1800
        val winMapBase = if ((lcdc and 0x40) != 0) 0x1C00 else 0x1800

        var windowDrawnOnLine = false

        for (x in 0 until WIDTH) {
            val useWindow = winEnabled && x >= (wx - 7)
            if (!bgEnabled) {
                bgColorIndices[x] = 0
                bgPriority[x] = false
                framebuffer[rowOffset + x] = if (isCgb) 0xFFFFFFFF.toInt() else palette[0]
                continue
            }

            val mapBase: Int
            val mapX: Int
            val mapY: Int
            if (useWindow) {
                windowDrawnOnLine = true
                mapBase = winMapBase
                mapX = x - (wx - 7)
                mapY = winLineCounter
            } else {
                mapBase = bgMapBase
                mapX = (x + scx) and 0xFF
                mapY = (ly + scy) and 0xFF
            }

            val tileCol = (mapX shr 3) and 31
            val tileRow = (mapY shr 3) and 31
            val mapAddr = mapBase + (tileRow * 32) + tileCol

            val tileIdxRaw = vram[0][mapAddr].toInt() and 0xFF
            val attr = if (isCgb) vram[1][mapAddr].toInt() and 0xFF else 0
            val tileBank = if (isCgb) (attr shr 3) and 1 else 0
            val cgbPal = attr and 0x07
            val xFlip = isCgb && (attr and 0x20) != 0
            val yFlip = isCgb && (attr and 0x40) != 0
            bgPriority[x] = isCgb && (attr and 0x80) != 0

            val tileOffset = if (tileDataUnsigned) {
                tileIdxRaw * 16
            } else {
                0x1000 + (tileIdxRaw.toByte().toInt() * 16)
            }

            val fineY = if (yFlip) 7 - (mapY and 7) else (mapY and 7)
            val fineX = if (xFlip) (mapX and 7) else 7 - (mapX and 7)
            val lineAddr = tileOffset + (fineY * 2)
            val b1 = vram[tileBank][lineAddr].toInt() and 0xFF
            val b2 = vram[tileBank][lineAddr + 1].toInt() and 0xFF
            val colorId = (((b2 shr fineX) and 1) shl 1) or ((b1 shr fineX) and 1)

            bgColorIndices[x] = colorId
            framebuffer[rowOffset + x] = if (isCgb) {
                readCgbColor(bcpd, cgbPal, colorId)
            } else {
                val shade = (bgp shr (colorId * 2)) and 0x03
                palette[shade]
            }
        }

        if (windowDrawnOnLine) winLineCounter++

        // Render Sprites (OBJ)
        if ((lcdc and 0x02) != 0) {
            val spriteHeight = if ((lcdc and 0x04) != 0) 16 else 8
            val visibleIndices = IntArray(10)
            var count = 0
            for (i in 0 until 40) {
                val base = i * 4
                val sy = (oam[base].toInt() and 0xFF) - 16
                if (ly in sy until (sy + spriteHeight)) {
                    visibleIndices[count++] = i
                    if (count == 10) break
                }
            }

            // Draw back-to-front so earlier OAM indices take priority on DMG (and preserve CGB OAM order)
            for (k in (count - 1) downTo 0) {
                val idx = visibleIndices[k]
                val base = idx * 4
                val sy = (oam[base].toInt() and 0xFF) - 16
                val sx = (oam[base + 1].toInt() and 0xFF) - 8
                var tileNum = oam[base + 2].toInt() and 0xFF
                val flags = oam[base + 3].toInt() and 0xFF

                if (spriteHeight == 16) tileNum = tileNum and 0xFE
                val xFlip = (flags and 0x20) != 0
                val yFlip = (flags and 0x40) != 0
                val objBehindBg = (flags and 0x80) != 0
                val tileBank = if (isCgb) (flags shr 3) and 1 else 0
                val dmgPal = if ((flags and 0x10) != 0) obp1 else obp0
                val cgbPal = flags and 0x07

                val rowInSprite = ly - sy
                val fineY = if (yFlip) (spriteHeight - 1 - rowInSprite) else rowInSprite
                val tileAddr = (tileNum * 16) + (fineY * 2)
                val b1 = vram[tileBank][tileAddr].toInt() and 0xFF
                val b2 = vram[tileBank][tileAddr + 1].toInt() and 0xFF

                for (px in 0 until 8) {
                    val screenX = sx + px
                    if (screenX !in 0 until WIDTH) continue
                    val bit = if (xFlip) px else 7 - px
                    val colorId = (((b2 shr bit) and 1) shl 1) or ((b1 shr bit) and 1)
                    if (colorId == 0) continue // transparent

                    val bgCol = bgColorIndices[screenX]
                    val masterBgPriority = !isCgb || (lcdc and 0x01) != 0
                    if (masterBgPriority && bgCol != 0 && (objBehindBg || bgPriority[screenX])) {
                        continue
                    }

                    framebuffer[rowOffset + screenX] = if (isCgb) {
                        readCgbColor(ocpd, cgbPal, colorId)
                    } else {
                        val shade = (dmgPal shr (colorId * 2)) and 0x03
                        palette[shade]
                    }
                }
            }
        }
    }

    private fun readCgbColor(table: ByteArray, pal: Int, color: Int): Int {
        val idx = (pal * 8) + (color * 2)
        val lo = table[idx].toInt() and 0xFF
        val hi = table[idx + 1].toInt() and 0xFF
        val rgb555 = lo or (hi shl 8)
        val r = ((rgb555 and 0x1F) * 255) / 31
        val g = (((rgb555 shr 5) and 0x1F) * 255) / 31
        val b = (((rgb555 shr 10) and 0x1F) * 255) / 31
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun writeBcps(v: Int) { bcps = v and 0xBF }
    fun readBcpd(): Int = bcpd[bcps and 0x3F].toInt() and 0xFF
    fun writeBcpd(v: Int) {
        bcpd[bcps and 0x3F] = v.toByte()
        if ((bcps and 0x80) != 0) bcps = (bcps and 0x80) or ((bcps + 1) and 0x3F)
    }

    fun writeOcps(v: Int) { ocps = v and 0xBF }
    fun readOcpd(): Int = ocpd[ocps and 0x3F].toInt() and 0xFF
    fun writeOcpd(v: Int) {
        ocpd[ocps and 0x3F] = v.toByte()
        if ((ocps and 0x80) != 0) ocps = (ocps and 0x80) or ((ocps + 1) and 0x3F)
    }

    fun saveState(out: DataOutputStream) {
        out.write(vram[0])
        out.write(vram[1])
        out.write(oam)
        out.writeInt(vramBank)
        out.writeInt(lcdc)
        out.writeInt(stat)
        out.writeInt(scy)
        out.writeInt(scx)
        out.writeInt(ly)
        out.writeInt(lyc)
        out.writeInt(bgp)
        out.writeInt(obp0)
        out.writeInt(obp1)
        out.writeInt(wy)
        out.writeInt(wx)
        out.writeInt(bcps)
        out.write(bcpd)
        out.writeInt(ocps)
        out.write(ocpd)
        out.writeInt(mode)
        out.writeInt(dotCounter)
        out.writeInt(winLineCounter)
    }

    fun loadState(inp: DataInputStream) {
        inp.readFully(vram[0])
        inp.readFully(vram[1])
        inp.readFully(oam)
        vramBank = inp.readInt()
        lcdc = inp.readInt()
        stat = inp.readInt()
        scy = inp.readInt()
        scx = inp.readInt()
        ly = inp.readInt()
        lyc = inp.readInt()
        bgp = inp.readInt()
        obp0 = inp.readInt()
        obp1 = inp.readInt()
        wy = inp.readInt()
        wx = inp.readInt()
        bcps = inp.readInt()
        inp.readFully(bcpd)
        ocps = inp.readInt()
        inp.readFully(ocpd)
        mode = inp.readInt()
        dotCounter = inp.readInt()
        winLineCounter = inp.readInt()
    }
}
