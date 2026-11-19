package com.example.emulator.gba

class GbaPpu {
    companion object {
        const val WIDTH = 240
        const val HEIGHT = 160

        private val OBJ_SIZES = arrayOf(
            intArrayOf(8, 8, 16, 16, 32, 32, 64, 64),   // Shape 0: Square
            intArrayOf(16, 8, 32, 8, 32, 16, 64, 32),   // Shape 1: Horizontal
            intArrayOf(8, 16, 8, 32, 16, 32, 32, 64)    // Shape 2: Vertical
        )
    }

    // Line layer buffers for priority & color blending
    private val lineColor = IntArray(WIDTH)
    private val linePriority = IntArray(WIDTH)
    private val lineLayer = IntArray(WIDTH) // 0..3=BG0..3, 4=OBJ, 5=BD
    private val secondColor = IntArray(WIDTH)
    private val secondLayer = IntArray(WIDTH)
    private val objSemiTrans = BooleanArray(WIDTH)

    // Internal latched affine reference registers
    var bg2RefX = 0
    var bg2RefY = 0
    var bg3RefX = 0
    var bg3RefY = 0

    fun reset() {
        bg2RefX = 0; bg2RefY = 0
        bg3RefX = 0; bg3RefY = 0
    }

    fun latchAffineRefs(mem: GbaMemory) {
        bg2RefX = signExtend28(mem.bg2X)
        bg2RefY = signExtend28(mem.bg2Y)
        bg3RefX = signExtend28(mem.bg3X)
        bg3RefY = signExtend28(mem.bg3Y)
    }

    private fun signExtend28(v: Int): Int = (v shl 4) shr 4

    fun renderScanline(y: Int, mem: GbaMemory, framebuffer: IntArray) {
        if (y !in 0 until HEIGHT) return
        val rowOff = y * WIDTH
        val dispcnt = mem.dispcnt

        if ((dispcnt and 0x0080) != 0) { // Forced blank
            for (x in 0 until WIDTH) framebuffer[rowOff + x] = 0xFFFFFFFF.toInt()
            return
        }

        val backdrop = readPalette15(mem.palette, 0)
        for (x in 0 until WIDTH) {
            lineColor[x] = backdrop
            linePriority[x] = 4
            lineLayer[x] = 5
            secondColor[x] = backdrop
            secondLayer[x] = 5
            objSemiTrans[x] = false
        }

        val mode = dispcnt and 0x07
        when (mode) {
            0 -> {
                for (bg in 3 downTo 0) {
                    if ((dispcnt and (1 shl (8 + bg))) != 0) {
                        renderRegularBg(y, bg, mem)
                    }
                }
            }
            1 -> {
                if ((dispcnt and 0x0400) != 0) renderAffineBg(y, 2, mem)
                if ((dispcnt and 0x0200) != 0) renderRegularBg(y, 1, mem)
                if ((dispcnt and 0x0100) != 0) renderRegularBg(y, 0, mem)
            }
            2 -> {
                if ((dispcnt and 0x0800) != 0) renderAffineBg(y, 3, mem)
                if ((dispcnt and 0x0400) != 0) renderAffineBg(y, 2, mem)
            }
            3 -> if ((dispcnt and 0x0400) != 0) renderMode3(y, mem)
            4 -> if ((dispcnt and 0x0400) != 0) renderMode4(y, mem)
            5 -> if ((dispcnt and 0x0400) != 0) renderMode5(y, mem)
        }

        if ((dispcnt and 0x1000) != 0) {
            renderSprites(y, mem)
        }

        // Update affine internal reference points at end of visible line
        if (mode == 1 || mode == 2) {
            bg2RefX += mem.bg2Pb.toShort().toInt()
            bg2RefY += mem.bg2Pd.toShort().toInt()
            if (mode == 2) {
                bg3RefX += mem.bg3Pb.toShort().toInt()
                bg3RefY += mem.bg3Pd.toShort().toInt()
            }
        }

        // Final color composition with blending/brightness
        val bldcnt = mem.bldcnt
        val bldMode = (bldcnt shr 6) and 0x03
        val eva = minOf(16, mem.bldalpha and 0x1F)
        val evb = minOf(16, (mem.bldalpha shr 8) and 0x1F)
        val evy = minOf(16, mem.bldy and 0x1F)

        for (x in 0 until WIDTH) {
            val topLayer = lineLayer[x]
            val topCol = lineColor[x]
            val isFirstTarget = (bldcnt and (1 shl topLayer)) != 0
            val isSecondTarget = (bldcnt and (1 shl (8 + secondLayer[x]))) != 0

            val rgb555 = if (objSemiTrans[x] && topLayer == 4 && isSecondTarget) {
                alphaBlend(topCol, secondColor[x], eva, evb)
            } else if (isFirstTarget && bldMode != 0) {
                when (bldMode) {
                    1 -> if (isSecondTarget) alphaBlend(topCol, secondColor[x], eva, evb) else topCol
                    2 -> brighten(topCol, evy)
                    3 -> darken(topCol, evy)
                    else -> topCol
                }
            } else topCol

            framebuffer[rowOff + x] = bgr555ToArgb(rgb555)
        }
    }

    private fun pushPixel(x: Int, rgb555: Int, prio: Int, layer: Int, semiTrans: Boolean = false) {
        val curPrio = linePriority[x]
        if (prio < curPrio || (prio == curPrio && layer < lineLayer[x])) {
            secondColor[x] = lineColor[x]
            secondLayer[x] = lineLayer[x]
            lineColor[x] = rgb555
            linePriority[x] = prio
            lineLayer[x] = layer
            objSemiTrans[x] = semiTrans
        } else if (layer != lineLayer[x]) {
            secondColor[x] = rgb555
            secondLayer[x] = layer
        }
    }

    private fun renderRegularBg(y: Int, bg: Int, mem: GbaMemory) {
        val cnt = mem.bgCnt[bg]
        val prio = cnt and 0x03
        val charBase = ((cnt shr 2) and 0x03) * 0x4000
        val color256 = (cnt and 0x0080) != 0
        val screenBase = ((cnt shr 8) and 0x1F) * 0x0800
        val sizeMode = (cnt shr 14) and 0x03

        val mapW = if (sizeMode == 1 || sizeMode == 3) 512 else 256
        val mapH = if (sizeMode >= 2) 512 else 256
        val hofs = mem.bgHofs[bg] and 0x1FF
        val vofs = mem.bgVofs[bg] and 0x1FF

        val ty = (y + vofs) and (mapH - 1)
        val tileY = ty shr 3
        val fineY = ty and 7

        for (x in 0 until WIDTH) {
            val tx = (x + hofs) and (mapW - 1)
            val tileX = tx shr 3
            val fineX = tx and 7

            var screenBlock = 0
            if (tileX >= 32) screenBlock++
            if (tileY >= 32) screenBlock += if (sizeMode == 3) 2 else 1

            val entryAddr = screenBase + (screenBlock * 0x800) + (((tileY and 31) * 32 + (tileX and 31)) * 2)
            val entry = readU16(mem.vram, entryAddr)
            val tileId = entry and 0x03FF
            val hFlip = (entry and 0x0400) != 0
            val vFlip = (entry and 0x0800) != 0
            val palBank = (entry shr 12) and 0x0F

            val py = if (vFlip) 7 - fineY else fineY
            val px = if (hFlip) 7 - fineX else fineX

            if (color256) {
                val addr = charBase + tileId * 64 + py * 8 + px
                if (addr in mem.vram.indices) {
                    val colIdx = mem.vram[addr].toInt() and 0xFF
                    if (colIdx != 0) {
                        pushPixel(x, readPalette15(mem.palette, colIdx * 2), prio, bg)
                    }
                }
            } else {
                val addr = charBase + tileId * 32 + py * 4 + (px shr 1)
                if (addr in mem.vram.indices) {
                    val b = mem.vram[addr].toInt() and 0xFF
                    val colIdx = if ((px and 1) == 0) (b and 0x0F) else (b shr 4)
                    if (colIdx != 0) {
                        pushPixel(x, readPalette15(mem.palette, (palBank * 16 + colIdx) * 2), prio, bg)
                    }
                }
            }
        }
    }

    private fun renderAffineBg(y: Int, bg: Int, mem: GbaMemory) {
        val cnt = mem.bgCnt[bg]
        val prio = cnt and 0x03
        val charBase = ((cnt shr 2) and 0x03) * 0x4000
        val wrap = (cnt and 0x2000) != 0
        val screenBase = ((cnt shr 8) and 0x1F) * 0x0800
        val sizeShift = (cnt shr 14) and 0x03
        val dim = 128 shl sizeShift
        val tileDim = dim shr 3

        val pa = (if (bg == 2) mem.bg2Pa else mem.bg3Pa).toShort().toInt()
        val pc = (if (bg == 2) mem.bg2Pc else mem.bg3Pc).toShort().toInt()
        var refX = if (bg == 2) bg2RefX else bg3RefX
        var refY = if (bg == 2) bg2RefY else bg3RefY

        for (x in 0 until WIDTH) {
            var texX = refX shr 8
            var texY = refY shr 8
            refX += pa
            refY += pc

            if (wrap) {
                texX = texX and (dim - 1)
                texY = texY and (dim - 1)
            } else if (texX !in 0 until dim || texY !in 0 until dim) {
                continue
            }

            val tileId = mem.vram[screenBase + (texY shr 3) * tileDim + (texX shr 3)].toInt() and 0xFF
            val addr = charBase + tileId * 64 + (texY and 7) * 8 + (texX and 7)
            if (addr in mem.vram.indices) {
                val colIdx = mem.vram[addr].toInt() and 0xFF
                if (colIdx != 0) {
                    pushPixel(x, readPalette15(mem.palette, colIdx * 2), prio, bg)
                }
            }
        }
    }

    private fun renderMode3(y: Int, mem: GbaMemory) {
        val prio = mem.bgCnt[2] and 0x03
        for (x in 0 until WIDTH) {
            val addr = (y * WIDTH + x) * 2
            pushPixel(x, readU16(mem.vram, addr), prio, 2)
        }
    }

    private fun renderMode4(y: Int, mem: GbaMemory) {
        val prio = mem.bgCnt[2] and 0x03
        val pageOffset = if ((mem.dispcnt and 0x0010) != 0) 0xA000 else 0
        for (x in 0 until WIDTH) {
            val colIdx = mem.vram[pageOffset + y * WIDTH + x].toInt() and 0xFF
            if (colIdx != 0) {
                pushPixel(x, readPalette15(mem.palette, colIdx * 2), prio, 2)
            }
        }
    }

    private fun renderMode5(y: Int, mem: GbaMemory) {
        if (y >= 128) return
        val prio = mem.bgCnt[2] and 0x03
        val pageOffset = if ((mem.dispcnt and 0x0010) != 0) 0xA000 else 0
        for (x in 0 until 160) {
            val addr = pageOffset + (y * 160 + x) * 2
            pushPixel(x, readU16(mem.vram, addr), prio, 2)
        }
    }

    private fun renderSprites(y: Int, mem: GbaMemory) {
        val is1D = (mem.dispcnt and 0x0040) != 0
        val baseObjVram = 0x10000

        // Iterate reverse OAM order so lower index overwrites higher index on equal priority
        for (idx in 127 downTo 0) {
            val base = idx * 8
            val attr0 = readU16(mem.oam, base)
            val objMode = (attr0 shr 8) and 0x03
            if (objMode == 2) continue // Disabled

            val isAffine = (attr0 and 0x0100) != 0
            val isDoubleSize = isAffine && (attr0 and 0x0200) != 0
            val gfxMode = (attr0 shr 10) and 0x03
            if (gfxMode == 2) continue // OBJ window mask
            val semiTrans = gfxMode == 1
            val color256 = (attr0 and 0x2000) != 0
            val shape = (attr0 shr 14) and 0x03
            if (shape == 3) continue

            val attr1 = readU16(mem.oam, base + 2)
            val size = (attr1 shr 14) and 0x03
            val w = OBJ_SIZES[shape][size * 2]
            val h = OBJ_SIZES[shape][size * 2 + 1]
            val boundW = if (isDoubleSize) w * 2 else w
            val boundH = if (isDoubleSize) h * 2 else h

            var sy = attr0 and 0xFF
            if (sy >= 160) sy -= 256
            if (y < sy || y >= sy + boundH) continue

            var sx = attr1 and 0x01FF
            if (sx >= 240) sx -= 512

            val attr2 = readU16(mem.oam, base + 4)
            val baseTile = attr2 and 0x03FF
            val prio = (attr2 shr 10) and 0x03
            val palBank = (attr2 shr 12) and 0x0F

            val hFlip = !isAffine && (attr1 and 0x1000) != 0
            val vFlip = !isAffine && (attr1 and 0x2000) != 0
            val matIdx = (attr1 shr 9) and 0x1F
            val pa = if (isAffine) readS16(mem.oam, matIdx * 32 + 6) else 256
            val pb = if (isAffine) readS16(mem.oam, matIdx * 32 + 14) else 0
            val pc = if (isAffine) readS16(mem.oam, matIdx * 32 + 22) else 0
            val pd = if (isAffine) readS16(mem.oam, matIdx * 32 + 30) else 256

            val halfW = boundW / 2
            val halfH = boundH / 2
            val dy = y - sy - halfH

            for (px in 0 until boundW) {
                val screenX = sx + px
                if (screenX !in 0 until WIDTH) continue

                val tx: Int
                val ty: Int
                if (isAffine) {
                    val dx = px - halfW
                    tx = ((pa * dx + pb * dy) shr 8) + (w / 2)
                    ty = ((pc * dx + pd * dy) shr 8) + (h / 2)
                    if (tx !in 0 until w || ty !in 0 until h) continue
                } else {
                    tx = if (hFlip) (w - 1 - px) else px
                    ty = if (vFlip) (h - 1 - (y - sy)) else (y - sy)
                }

                val tileX = tx shr 3
                val tileY = ty shr 3
                val fineX = tx and 7
                val fineY = ty and 7

                val tileOffset = if (is1D) {
                    val tilesPerRow = w shr 3
                    baseTile + (tileY * tilesPerRow + tileX) * (if (color256) 2 else 1)
                } else {
                    baseTile + tileY * 32 + tileX * (if (color256) 2 else 1)
                }

                val colIdx = if (color256) {
                    val addr = baseObjVram + (tileOffset * 32) + fineY * 8 + fineX
                    if (addr < 0x18000) mem.vram[addr].toInt() and 0xFF else 0
                } else {
                    val addr = baseObjVram + (tileOffset * 32) + fineY * 4 + (fineX shr 1)
                    if (addr < 0x18000) {
                        val b = mem.vram[addr].toInt() and 0xFF
                        if ((fineX and 1) == 0) (b and 0x0F) else (b shr 4)
                    } else 0
                }

                if (colIdx != 0) {
                    val palOff = if (color256) 0x200 + colIdx * 2 else 0x200 + (palBank * 16 + colIdx) * 2
                    pushPixel(screenX, readPalette15(mem.palette, palOff), prio, 4, semiTrans)
                }
            }
        }
    }

    private fun readU16(buf: ByteArray, addr: Int): Int {
        if (addr < 0 || addr + 1 >= buf.size) return 0
        return (buf[addr].toInt() and 0xFF) or ((buf[addr + 1].toInt() and 0xFF) shl 8)
    }

    private fun readS16(buf: ByteArray, addr: Int): Int = readU16(buf, addr).toShort().toInt()

    private fun readPalette15(pal: ByteArray, addr: Int): Int = readU16(pal, addr and 0x3FE) and 0x7FFF

    private fun bgr555ToArgb(c: Int): Int {
        val r = ((c and 0x1F) * 255) / 31
        val g = (((c shr 5) and 0x1F) * 255) / 31
        val b = (((c shr 10) and 0x1F) * 255) / 31
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun alphaBlend(c1: Int, c2: Int, eva: Int, evb: Int): Int {
        val r = minOf(31, (((c1 and 0x1F) * eva + (c2 and 0x1F) * evb) shr 4))
        val g = minOf(31, ((((c1 shr 5) and 0x1F) * eva + ((c2 shr 5) and 0x1F) * evb) shr 4))
        val b = minOf(31, ((((c1 shr 10) and 0x1F) * eva + ((c2 shr 10) and 0x1F) * evb) shr 4))
        return r or (g shl 5) or (b shl 10)
    }

    private fun brighten(c: Int, evy: Int): Int {
        val r0 = c and 0x1F
        val g0 = (c shr 5) and 0x1F
        val b0 = (c shr 10) and 0x1F
        val r = r0 + (((31 - r0) * evy) shr 4)
        val g = g0 + (((31 - g0) * evy) shr 4)
        val b = b0 + (((31 - b0) * evy) shr 4)
        return r or (g shl 5) or (b shl 10)
    }

    private fun darken(c: Int, evy: Int): Int {
        val r0 = c and 0x1F
        val g0 = (c shr 5) and 0x1F
        val b0 = (c shr 10) and 0x1F
        val r = r0 - ((r0 * evy) shr 4)
        val g = g0 - ((g0 * evy) shr 4)
        val b = b0 - ((b0 * evy) shr 4)
        return r or (g shl 5) or (b shl 10)
    }
}
