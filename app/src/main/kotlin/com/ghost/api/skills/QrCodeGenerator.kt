package com.ghost.api.skills

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Self-contained, zero-dependency ISO/IEC 18004 QR Code generator (Byte mode, ECC Level L).
 * Produces a scannable Bitmap or Base64 PNG string completely offline with zero external CDNs or libraries.
 */
object QrCodeGenerator {

    // Total codewords and ECC codewords (Level L) for QR Versions 1..10
    private data class VersionSpec(
        val version: Int,
        val totalCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocksGroup1: Int,
        val dataCodewordsGroup1: Int,
        val numBlocksGroup2: Int = 0,
        val dataCodewordsGroup2: Int = 0,
        val alignmentCenters: IntArray = intArrayOf()
    ) {
        val totalDataCodewords: Int
            get() = numBlocksGroup1 * dataCodewordsGroup1 + numBlocksGroup2 * dataCodewordsGroup2
    }

    private val VERSIONS = listOf(
        VersionSpec(1, 26, 7, 1, 19),
        VersionSpec(2, 44, 10, 1, 34, alignmentCenters = intArrayOf(6, 18)),
        VersionSpec(3, 70, 15, 1, 55, alignmentCenters = intArrayOf(6, 22)),
        VersionSpec(4, 100, 20, 1, 80, alignmentCenters = intArrayOf(6, 26)),
        VersionSpec(5, 134, 26, 1, 108, alignmentCenters = intArrayOf(6, 30)),
        VersionSpec(6, 172, 18, 2, 68, alignmentCenters = intArrayOf(6, 34)),
        VersionSpec(7, 196, 20, 4, 39, alignmentCenters = intArrayOf(6, 22, 38)),
        VersionSpec(8, 242, 24, 2, 97, alignmentCenters = intArrayOf(6, 24, 42)),
        VersionSpec(9, 292, 30, 3, 76, 2, 77, alignmentCenters = intArrayOf(6, 26, 46)),
        VersionSpec(10, 346, 18, 4, 68, 1, 69, alignmentCenters = intArrayOf(6, 28, 50))
    )

    fun generatePngBase64(text: String, pixelSize: Int = 512): String {
        val matrix = encodeMatrix(text.toByteArray(Charsets.UTF_8))
        val bmp = renderBitmap(matrix, pixelSize)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun encodeMatrix(payload: ByteArray): Array<BooleanArray> {
        // Pick smallest version that fits payload in Byte mode (4-bit mode + 8/16-bit length + bytes)
        val spec = VERSIONS.firstOrNull { v ->
            val lenBits = if (v.version <= 9) 8 else 16
            val neededBits = 4 + lenBits + payload.size * 8
            neededBits <= v.totalDataCodewords * 8
        } ?: VERSIONS.last()

        val safePayload = if (payload.size > spec.totalDataCodewords - 3) {
            payload.copyOf(spec.totalDataCodewords - 3)
        } else payload

        // 1. Build bit stream
        val bits = ArrayList<Int>(spec.totalDataCodewords * 8)
        fun appendBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }

        appendBits(0b0100, 4) // Byte mode indicator
        val countBits = if (spec.version <= 9) 8 else 16
        appendBits(safePayload.size, countBits)
        for (b in safePayload) {
            appendBits(b.toInt() and 0xFF, 8)
        }

        val capacityBits = spec.totalDataCodewords * 8
        val terminator = minOf(4, capacityBits - bits.size)
        appendBits(0, terminator)
        while (bits.size % 8 != 0) bits.add(0)

        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < capacityBits) {
            appendBits(padBytes[padIdx % 2], 8)
            padIdx++
        }

        val dataBytes = IntArray(spec.totalDataCodewords)
        for (i in dataBytes.indices) {
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or bits[i * 8 + b]
            }
            dataBytes[i] = v
        }

        // 2. Split into blocks and compute Reed-Solomon ECC
        val dataBlocks = ArrayList<IntArray>()
        val eccBlocks = ArrayList<IntArray>()
        var offset = 0
        for (i in 0 until spec.numBlocksGroup1) {
            val block = dataBytes.copyOfRange(offset, offset + spec.dataCodewordsGroup1)
            offset += spec.dataCodewordsGroup1
            dataBlocks.add(block)
            eccBlocks.add(reedSolomonRemainder(block, spec.ecCodewordsPerBlock))
        }
        for (i in 0 until spec.numBlocksGroup2) {
            val block = dataBytes.copyOfRange(offset, offset + spec.dataCodewordsGroup2)
            offset += spec.dataCodewordsGroup2
            dataBlocks.add(block)
            eccBlocks.add(reedSolomonRemainder(block, spec.ecCodewordsPerBlock))
        }

        // Interleave data and ECC codewords
        val finalCodewords = ArrayList<Int>(spec.totalCodewords)
        val maxDataLen = maxOf(spec.dataCodewordsGroup1, spec.dataCodewordsGroup2)
        for (i in 0 until maxDataLen) {
            for (block in dataBlocks) {
                if (i < block.size) finalCodewords.add(block[i])
            }
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (block in eccBlocks) {
                if (i < block.size) finalCodewords.add(block[i])
            }
        }

        // 3. Construct module grid
        val size = 17 + 4 * spec.version
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunctionModule(x: Int, y: Int, isDark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                modules[y][x] = isDark
                isFunction[y][x] = true
            }
        }

        // Finder patterns + separators
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val xx = cx + dx
                    val yy = cy + dy
                    if (xx in 0 until size && yy in 0 until size) {
                        setFunctionModule(xx, yy, dist != 2 && dist != 4)
                    }
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Timing patterns
        for (i in 0 until size) {
            if (!isFunction[6][i]) setFunctionModule(i, 6, i % 2 == 0)
            if (!isFunction[i][6]) setFunctionModule(6, i, i % 2 == 0)
        }

        // Alignment patterns
        val centers = spec.alignmentCenters
        for (cy in centers) {
            for (cx in centers) {
                if ((cx == 6 && cy == 6) || (cx == 6 && cy == size - 7) || (cx == size - 7 && cy == 6)) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunctionModule(cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve format information areas + dark module
        for (i in 0..8) {
            if (i != 6) {
                if (!isFunction[8][i]) setFunctionModule(i, 8, false)
                if (!isFunction[i][8]) setFunctionModule(8, i, false)
            }
        }
        for (i in 0..7) {
            if (!isFunction[8][size - 1 - i]) setFunctionModule(size - 1 - i, 8, false)
            if (!isFunction[size - 1 - i][8]) setFunctionModule(8, size - 1 - i, false)
        }
        setFunctionModule(8, size - 8, true) // Dark module

        // Reserve version info for version >= 7
        if (spec.version >= 7) {
            var rem = spec.version
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val verBits = (spec.version shl 12) or rem
            for (i in 0 until 18) {
                val bit = ((verBits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val b = i / 3
                setFunctionModule(a, b, bit)
                setFunctionModule(b, a, bit)
            }
        }

        // 4. Zigzag data placement
        var bitIdx = 0
        val totalBits = finalCodewords.size * 8
        var right = size - 1
        var vert = 0
        while (right >= 1) {
            if (right == 6) right = 5
            for (cnt in 0 until size) {
                val y = if ((vert and 1) == 0) size - 1 - cnt else cnt
                for (j in 0..1) {
                    val x = right - j
                    if (!isFunction[y][x]) {
                        val dark = if (bitIdx < totalBits) {
                            ((finalCodewords[bitIdx ushr 3] ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else false
                        modules[y][x] = dark
                        bitIdx++
                    }
                }
            }
            right -= 2
            vert++
        }

        // 5. Apply mask 0 ((x + y) % 2 == 0) and write format bits (ECC Level L = 01, Mask = 000)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!isFunction[y][x] && (x + y) % 2 == 0) {
                    modules[y][x] = !modules[y][x]
                }
            }
        }

        // Format info for ECC Level L (01) and Mask 0 (000): data = 0b01_000 = 8
        val formatData = 0b01000
        var fRem = formatData
        for (i in 0 until 10) fRem = (fRem shl 1) xor ((fRem ushr 9) * 0x537)
        val formatBits = ((formatData shl 10) or fRem) xor 0x5412

        fun getFormatBit(i: Int): Boolean = ((formatBits ushr i) and 1) != 0
        for (i in 0..5) setFunctionModule(8, i, getFormatBit(i))
        setFunctionModule(8, 7, getFormatBit(6))
        setFunctionModule(8, 8, getFormatBit(7))
        setFunctionModule(7, 8, getFormatBit(8))
        for (i in 9..14) setFunctionModule(14 - i, 8, getFormatBit(i))

        for (i in 0..7) setFunctionModule(size - 1 - i, 8, getFormatBit(i))
        for (i in 8..14) setFunctionModule(8, size - 15 + i, getFormatBit(i))

        return modules
    }

    private fun renderBitmap(matrix: Array<BooleanArray>, targetPx: Int): Bitmap {
        val quietZone = 4
        val totalModules = matrix.size + quietZone * 2
        val scale = maxOf(1, targetPx / totalModules)
        val imgSize = totalModules * scale
        val pixels = IntArray(imgSize * imgSize) { Color.WHITE }

        for (y in matrix.indices) {
            for (x in matrix[y].indices) {
                if (matrix[y][x]) {
                    val startY = (y + quietZone) * scale
                    val startX = (x + quietZone) * scale
                    for (py in 0 until scale) {
                        val rowOffset = (startY + py) * imgSize + startX
                        for (px in 0 until scale) {
                            pixels[rowOffset + px] = Color.BLACK
                        }
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, imgSize, imgSize, Bitmap.Config.ARGB_8888)
    }

    // GF(256) Reed-Solomon generator with primitive polynomial 0x11D
    private fun gfMul(x: Int, y: Int): Int {
        var r = 0
        var a = x
        var b = y
        for (i in 7 downTo 0) {
            r = (r shl 1) xor ((r ushr 7) * 0x11D)
            r = r xor (((b ushr i) and 1) * a)
        }
        return r
    }

    private fun reedSolomonRemainder(data: IntArray, degree: Int): IntArray {
        val divisor = IntArray(degree)
        divisor[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                divisor[j] = gfMul(divisor[j], root)
                if (j + 1 < degree) divisor[j] = divisor[j] xor divisor[j + 1]
            }
            root = gfMul(root, 0x02)
        }

        val result = IntArray(degree)
        for (b in data) {
            val factor = b xor result[0]
            System.arraycopy(result, 1, result, 0, degree - 1)
            result[degree - 1] = 0
            for (i in 0 until degree) {
                result[i] = result[i] xor gfMul(divisor[i], factor)
            }
        }
        return result
    }
}
