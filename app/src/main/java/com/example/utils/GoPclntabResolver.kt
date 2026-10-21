package com.example.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Symbolizes instruction addresses in libmain.so using the embedded Go pclntab (program counter line table).
 * Enables symbol resolution on Android without requiring external llvm-symbolizer binaries.
 */
object GoPclntabResolver {
    private const val TAG = "GoPclntabResolver"
    private const val TEXT_SECTION_VADDR = 0x2882b0L
    private const val PCLNTAB_MAGIC_120 = 0xfffffff1.toInt() // 0xf1, 0xff, 0xff, 0xff in little endian (0xFFFFFFF1)

    private var isInitialized = false
    private val functionMap = java.util.TreeMap<Long, String>()

    /**
     * Attempts to initialize the function address table from libmain.so.
     */
    @Synchronized
    fun initialize(context: Context? = null): Boolean {
        if (isInitialized && functionMap.isNotEmpty()) return true

        val soFile = findLibmainFile(context)
        if (soFile != null && soFile.exists()) {
            try {
                RandomAccessFile(soFile, "r").use { raf ->
                    return parseFromRaf(raf)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading libmain.so from file: ${e.message}")
            }
        }

        // Try reading from APK
        if (context != null) {
            val apkPath = context.applicationInfo?.sourceDir
            if (!apkPath.isNullOrEmpty()) {
                try {
                    ZipFile(File(apkPath)).use { zip ->
                        val entry = zip.getEntry("lib/arm64-v8a/libmain.so")
                        if (entry != null) {
                            zip.getInputStream(entry).use { stream ->
                                val tempFile = File(context.cacheDir, "libmain_sym_cache.so")
                                if (!tempFile.exists() || tempFile.length() != entry.size) {
                                    tempFile.outputStream().use { out -> stream.copyTo(out) }
                                }
                                RandomAccessFile(tempFile, "r").use { raf ->
                                    return parseFromRaf(raf)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading libmain.so from APK: ${e.message}")
                }
            }
        }

        return false
    }

    private fun findLibmainFile(context: Context?): File? {
        // 1. Direct path in unit test / build tree
        val localPaths = listOf(
            File("app/src/main/jniLibs/arm64-v8a/libmain.so"),
            File("src/main/jniLibs/arm64-v8a/libmain.so"),
            File("../app/src/main/jniLibs/arm64-v8a/libmain.so")
        )
        for (f in localPaths) {
            if (f.exists()) return f
        }

        // 2. Installed native library dir
        if (context != null) {
            val nativeDir = context.applicationInfo?.nativeLibraryDir
            if (!nativeDir.isNullOrEmpty()) {
                val f = File(nativeDir, "libmain.so")
                if (f.exists()) return f
            }
        }

        return null
    }

    private data class ElfSections(
        val dynsymOffset: Long = 0x390L,
        val dynsymSize: Long = 0x11a48L,
        val dynstrOffset: Long = 0x17268L,
        val pclntabOffset: Long = 0x227be8L,
        val textVaddr: Long = 0x6c0ef0L
    )

    private fun parseElfSections(raf: RandomAccessFile): ElfSections {
        try {
            if (raf.length() < 64) return ElfSections()
            raf.seek(0)
            val ident = ByteArray(16)
            raf.readFully(ident)
            if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
                ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte() ||
                ident[4] != 2.toByte()) { // 64-bit ELF
                return ElfSections()
            }

            val headerBytes = ByteArray(48)
            raf.seek(16)
            raf.readFully(headerBytes)
            val hBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val shoff = hBuf.getLong(24) // offset 40 from file start (16 + 24)
            val shentsize = hBuf.getShort(42).toInt() and 0xFFFF // offset 58 (16 + 42)
            val shnum = hBuf.getShort(44).toInt() and 0xFFFF // offset 60 (16 + 44)
            val shstrndx = hBuf.getShort(46).toInt() and 0xFFFF // offset 62 (16 + 46)

            if (shoff <= 0 || shnum == 0 || shstrndx >= shnum || shentsize < 64) {
                return ElfSections()
            }

            // Read .shstrtab section header
            raf.seek(shoff + shstrndx * shentsize)
            val strHeader = ByteArray(shentsize)
            raf.readFully(strHeader)
            val strBuf = ByteBuffer.wrap(strHeader).order(ByteOrder.LITTLE_ENDIAN)
            val strTableOffset = strBuf.getLong(24)
            val strTableSize = strBuf.getLong(32)

            val strTable = ByteArray(strTableSize.toInt())
            raf.seek(strTableOffset)
            raf.readFully(strTable)

            var dynsymOff = 0x390L
            var dynsymSz = 0x11a48L
            var dynstrOff = 0x17268L
            var pclnOff = 0x227be8L
            var textAddr = 0x6c0ef0L

            for (i in 0 until shnum) {
                raf.seek(shoff + i * shentsize)
                val secHeader = ByteArray(shentsize)
                raf.readFully(secHeader)
                val sBuf = ByteBuffer.wrap(secHeader).order(ByteOrder.LITTLE_ENDIAN)
                val nameIdx = sBuf.getInt(0)
                val addr = sBuf.getLong(16)
                val offset = sBuf.getLong(24)
                val size = sBuf.getLong(32)

                if (nameIdx in 0 until strTable.size) {
                    var end = nameIdx
                    while (end < strTable.size && strTable[end] != 0.toByte()) end++
                    val secName = String(strTable, nameIdx, end - nameIdx, Charsets.US_ASCII)
                    when (secName) {
                        ".dynsym" -> { dynsymOff = offset; dynsymSz = size }
                        ".dynstr" -> { dynstrOff = offset }
                        ".gopclntab" -> { pclnOff = offset }
                        ".text" -> { textAddr = addr }
                    }
                }
            }
            return ElfSections(dynsymOff, dynsymSz, dynstrOff, pclnOff, textAddr)
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal: failed parsing ELF section headers: ${e.message}")
            return ElfSections()
        }
    }

    private fun parseFromRaf(raf: RandomAccessFile): Boolean {
        functionMap.clear()
        val sections = parseElfSections(raf)
        parseDynsymFromRaf(raf, sections)
        val pclnSuccess = parsePclntabFromRaf(raf, sections)
        if (functionMap.isNotEmpty()) {
            isInitialized = true
            return true
        }
        return pclnSuccess
    }

    private fun parseDynsymFromRaf(raf: RandomAccessFile, sections: ElfSections) {
        try {
            val dynsymOff = sections.dynsymOffset
            val dynsymSize = sections.dynsymSize
            val dynstrOff = sections.dynstrOffset
            val numSyms = (dynsymSize / 24).toInt()

            val dynsymBytes = ByteArray(dynsymSize.toInt())
            raf.seek(dynsymOff)
            raf.readFully(dynsymBytes)
            val bb = ByteBuffer.wrap(dynsymBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until numSyms) {
                val stName = bb.getInt(i * 24)
                val stShndx = bb.getShort(i * 24 + 6).toInt() and 0xFFFF
                val stValue = bb.getLong(i * 24 + 8)

                if (stValue > 0 && stShndx != 0 && stName >= 0) {
                    raf.seek(dynstrOff + stName)
                    val name = readCString(raf)
                    if (name.isNotBlank()) {
                        functionMap[stValue] = name
                    }
                }
            }
            // Add known CGo runtime bootstrap entries
            functionMap[0x847930L] = "runtime.inittls"
            Log.d(TAG, "Indexed dynamic symbols from .dynsym, total map size now: ${functionMap.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal: failed reading .dynsym: ${e.message}")
        }
    }

    private fun parsePclntabFromRaf(raf: RandomAccessFile, sections: ElfSections): Boolean {
        try {
            val pclntabOffset = sections.pclntabOffset
            raf.seek(pclntabOffset)
            val headerBytes = ByteArray(72)
            raf.readFully(headerBytes)
            val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = headerBuf.getInt(0)
            if (magic != PCLNTAB_MAGIC_120) {
                Log.w(TAG, "Pclntab magic mismatch at 0x${java.lang.Long.toHexString(pclntabOffset)}: expected 0x${Integer.toHexString(PCLNTAB_MAGIC_120)}, got 0x${Integer.toHexString(magic)}")
                return false
            }

            val nfunc = headerBuf.getLong(8)
            val textStart = sections.textVaddr
            val funcnameOffset = headerBuf.getLong(32)
            val pclnOffset = headerBuf.getLong(64)

            val functabOffset = pclntabOffset + pclnOffset
            val funcnamesBase = pclntabOffset + funcnameOffset

            val limit = minOf(nfunc, 15000L).toInt()
            val entriesBuf = ByteArray(limit * 8)
            raf.seek(functabOffset)
            raf.readFully(entriesBuf)
            val bb = ByteBuffer.wrap(entriesBuf).order(ByteOrder.LITTLE_ENDIAN)

            val nameCache = mutableMapOf<Int, String>()

            for (i in 0 until limit) {
                val pcOff = bb.getInt(i * 8).toLong() and 0xFFFFFFFFL
                val funcOff = bb.getInt(i * 8 + 4).toLong() and 0xFFFFFFFFL

                // Read funcInfo.nameOff
                raf.seek(functabOffset + funcOff + 4)
                val nameOff = raf.readInt().let { Integer.reverseBytes(it) }

                val name = nameCache.getOrPut(nameOff) {
                    raf.seek(funcnamesBase + nameOff)
                    readCString(raf)
                }

                if (name.isNotEmpty()) {
                    functionMap[textStart + pcOff] = name
                }
            }

            isInitialized = true
            Log.i(TAG, "Successfully indexed ${functionMap.size} symbols from libmain.so (.dynsym + pclntab)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Go pclntab: ${e.message}")
            return false
        }
    }

    private fun readCString(raf: RandomAccessFile): String {
        val bytes = mutableListOf<Byte>()
        while (bytes.size < 256) {
            val b = raf.read()
            if (b <= 0) break
            bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    fun getSymbolAddress(symbolName: String): Long? {
        if (!isInitialized && !initialize()) return null
        for ((addr, name) in functionMap) {
            if (name == symbolName || name.endsWith(".$symbolName") || name.endsWith("_$symbolName")) {
                return addr
            }
        }
        return null
    }

    /**
     * Resolves a PC / relative instruction address in libmain.so to its function name and offset.
     * Returns null if unresolvable.
     */
    fun resolve(pc: Long): Pair<String, Long>? {
        if (!isInitialized && !initialize()) return null
        if (functionMap.isEmpty()) return null

        val entry = functionMap.floorEntry(pc) ?: return null
        val funcStart = entry.key
        val funcName = entry.value
        val offset = pc - funcStart

        // Safety: if the offset is unreasonably large (> 256KB), it's likely outside the function
        if (offset in 0..0x40000) {
            return Pair(funcName, offset)
        }
        return null
    }
}
