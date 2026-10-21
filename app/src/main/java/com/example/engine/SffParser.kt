package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object SffParser {

    /**
     * Extracts the first valid PCX image from an SFFv1 file.
     * MUGEN SFFv1 stores images as PCX. We parse the subheaders to find the portrait (Group 9000)
     * or fallback to the first available image (Group 0).
     */
    fun extractPortrait(sffFile: File, cacheDir: File, cacheFileName: String): Uri? {
        if (!sffFile.exists()) return null
        if (!cacheDir.exists()) cacheDir.mkdirs()
        
        val outFile = File(cacheDir, "$cacheFileName.png")
        if (outFile.exists()) return Uri.fromFile(outFile) // Return cached image

        try {
            RandomAccessFile(sffFile, "r").use { raf ->
                val signature = ByteArray(12)
                raf.read(signature)
                val sigStr = String(signature)
                if (!sigStr.startsWith("ElecbyteSpr")) return null

                val ver1 = raf.read()
                val ver2 = raf.read()
                val ver3 = raf.read()
                val ver4 = raf.read()
                
                // MUGEN 1.0+ uses SFFv2 (ver4 == 2). SFFv2 parsing is complex (uses LZ5/PNG).
                // If it's SFFv2, we abort extraction for now.
                if (ver4 >= 2) return null

                raf.seek(16)
                val numGroups = readIntLE(raf)
                val numImages = readIntLE(raf)
                val nextSubOffset = readIntLE(raf)
                
                var offset = nextSubOffset
                var fallbackOffset = -1
                var portraitOffset = -1
                var portraitLength = 0
                
                var iterations = 0
                while (offset > 0 && iterations < numImages) {
                    raf.seek(offset.toLong())
                    val next = readIntLE(raf)
                    val length = readIntLE(raf)
                    val xAxis = readShortLE(raf)
                    val yAxis = readShortLE(raf)
                    val group = readShortLE(raf)
                    val image = readShortLE(raf)
                    
                    // Group 9000 is usually the large portrait, 9000,1 is the small one.
                    if (group == 9000 && (image == 1 || image == 0)) {
                        portraitOffset = offset + 32
                        portraitLength = length
                        if (image == 1) break // Prefer large portrait if found
                    }
                    if (fallbackOffset == -1 && length > 0) {
                        fallbackOffset = offset + 32
                        portraitLength = length
                    }
                    
                    if (next <= offset || next <= 0) break
                    offset = next
                    iterations++
                }
                
                val targetOffset = if (portraitOffset != -1) portraitOffset else fallbackOffset
                if (targetOffset != -1 && portraitLength > 0) {
                    raf.seek(targetOffset.toLong())
                    val pcxData = ByteArray(portraitLength)
                    raf.readFully(pcxData)
                    
                    val bitmap = decodePcx(pcxData)
                    if (bitmap != null) {
                        FileOutputStream(outFile).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        }
                        return Uri.fromFile(outFile)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun readIntLE(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        val b4 = raf.read()
        return (b1 and 0xFF) or ((b2 and 0xFF) shl 8) or ((b3 and 0xFF) shl 16) or ((b4 and 0xFF) shl 24)
    }
    
    private fun readShortLE(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        return (b1 and 0xFF) or ((b2 and 0xFF) shl 8)
    }

    private fun decodePcx(data: ByteArray): Bitmap? {
        if (data.size < 128 || data[0] != 10.toByte()) return null
        
        val xmin = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        val ymin = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
        val xmax = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
        val ymax = (data[10].toInt() and 0xFF) or ((data[11].toInt() and 0xFF) shl 8)
        
        val width = xmax - xmin + 1
        val height = ymax - ymin + 1
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return null
        
        val bpl = (data[66].toInt() and 0xFF) or ((data[67].toInt() and 0xFF) shl 8)
        val pixels = IntArray(width * height)
        var pos = 128
        val rawData = ByteArray(bpl * height)
        var rawPos = 0
        
        while (rawPos < rawData.size && pos < data.size) {
            var byte = data[pos++].toInt() and 0xFF
            if ((byte and 0xC0) == 0xC0) {
                val count = byte and 0x3F
                if (pos >= data.size) break
                byte = data[pos++].toInt() and 0xFF
                for (i in 0 until count) {
                    if (rawPos < rawData.size) rawData[rawPos++] = byte.toByte()
                }
            } else {
                if (rawPos < rawData.size) rawData[rawPos++] = byte.toByte()
            }
        }
        
        var palOffset = data.size - 768
        if (palOffset > 0 && data[palOffset - 1] == 0x0C.toByte()) {
            // standard PCX palette
        } else {
            palOffset = data.size - 768
        }
        
        if (palOffset >= 0 && palOffset + 768 <= data.size) {
            val palette = IntArray(256)
            for (i in 0..255) {
                val r = data[palOffset + i * 3].toInt() and 0xFF
                val g = data[palOffset + i * 3 + 1].toInt() and 0xFF
                val b = data[palOffset + i * 3 + 2].toInt() and 0xFF
                // MUGEN SFFv1 uses color 0 as transparent
                palette[i] = if (i == 0) Color.TRANSPARENT else Color.rgb(r, g, b)
            }
            
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (x < bpl && rawPos >= y * bpl + x) {
                        val idx = rawData[y * bpl + x].toInt() and 0xFF
                        pixels[y * width + x] = palette[idx]
                    }
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
        return null
    }
}
