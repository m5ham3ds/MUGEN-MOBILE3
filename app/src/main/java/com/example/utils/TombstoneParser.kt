package com.example.utils

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Standard data structures for parsed Android Tombstone data.
 */
data class BacktraceFrameInfo(
    val frameIndex: Int,
    val relPc: Long,
    val pc: Long = 0L,
    val sp: Long = 0L,
    var functionName: String = "",
    var functionOffset: Long = 0L,
    var fileName: String = "",
    val fileMapOffset: Long = 0L,
    val buildId: String = ""
) : java.io.Serializable {
    fun toFormattedLine(): String {
        val pcHex = "%08x".format(relPc)
        val lib = if (fileName.isNotBlank()) fileName else "unknown"
        val fn = when {
            functionName.isNotBlank() && functionOffset > 0 -> " ($functionName+0x${"%x".format(functionOffset)})"
            functionName.isNotBlank() -> " ($functionName)"
            else -> ""
        }
        val bId = if (buildId.isNotBlank()) " (BuildId: $buildId)" else ""
        return "      #%02d pc %s  %s%s%s".format(frameIndex, pcHex, lib, fn, bId)
    }
}

data class ThreadInfo(
    val id: Int,
    val name: String,
    val registers: Map<String, Long> = emptyMap(),
    val backtrace: List<BacktraceFrameInfo> = emptyList()
) : java.io.Serializable

data class ParsedTombstone(
    val arch: String = "ARM64",
    val buildFingerprint: String = "",
    val revision: String = "",
    val timestamp: String = "",
    val pid: Int = 0,
    val tid: Int = 0,
    val uid: Int = 0,
    val selinuxLabel: String = "",
    val commandLine: List<String> = emptyList(),
    val processUptime: Int = 0,
    val signalNumber: Int = 0,
    val signalName: String = "",
    val signalCode: Int = 0,
    val signalCodeName: String = "",
    val senderUid: Int? = null,
    val senderPid: Int? = null,
    val faultAddress: Long? = null,
    val abortMessage: String? = null,
    val causes: List<String> = emptyList(),
    val crashingThreadName: String = "",
    val crashingThreadRegisters: Map<String, Long> = emptyMap(),
    val crashingThreadBacktrace: List<BacktraceFrameInfo> = emptyList(),
    val otherThreads: List<ThreadInfo> = emptyList(),
    val faultingLibrary: String? = null,
    val faultingPc: Long? = null,
    val pageSize: Int = 0,
    val hasBeen16kbMode: Boolean = false,
    val rawBytesFile: String? = null,
    val rawBytesSha256: String = "",
    val evidenceStatus: String = "PARTIAL_EVIDENCE", // "COMPLETE_EVIDENCE", "PARTIAL_EVIDENCE", "MINIMAL_EVIDENCE"
    val missingEvidenceReasons: List<String> = emptyList(),
    val extractedLogs: List<String> = emptyList(),
    val isGzipDecompressed: Boolean = false,
    val isProtoParsed: Boolean = false
) : java.io.Serializable {

    fun toFormattedBacktrace(): String {
        if (crashingThreadBacktrace.isEmpty()) {
            return if (tid > 0) {
                "(No stack frames recorded in trace buffer for thread #$tid '$crashingThreadName')"
            } else {
                "(No stack frames recorded in OS trace buffer)"
            }
        }
        val sb = StringBuilder()
        sb.append("backtrace (Crashing Thread #$tid '$crashingThreadName'):\n")
        crashingThreadBacktrace.forEach { frame ->
            sb.append(frame.toFormattedLine()).append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun toFormattedFullReport(): String {
        val sb = StringBuilder()
        sb.append("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n")
        if (buildFingerprint.isNotBlank()) sb.append("Build fingerprint: '$buildFingerprint'\n")
        if (revision.isNotBlank()) sb.append("Revision: '$revision'\n")
        sb.append("ABI: '$arch'\n")
        if (timestamp.isNotBlank()) sb.append("Timestamp: $timestamp\n")
        if (processUptime > 0) sb.append("Process uptime: ${processUptime}s\n")
        if (commandLine.isNotEmpty()) sb.append("Cmdline: ${commandLine.joinToString(" ")}\n")

        val pidStr = if (pid > 0) "pid: $pid" else "pid: (Not in tombstone buffer)"
        val tidStr = if (tid > 0) "tid: $tid" else "tid: (Not in tombstone buffer)"
        val nameStr = if (crashingThreadName.isNotBlank()) ", name: $crashingThreadName" else ""
        sb.append("$pidStr, $tidStr$nameStr\n")
        if (uid > 0) sb.append("uid: $uid\n")

        val sigDesc = when {
            signalName.isNotBlank() && signalNumber > 0 -> "signal $signalNumber ($signalName)"
            signalName.isNotBlank() -> "signal ($signalName)"
            signalNumber > 0 -> "signal $signalNumber (${TombstoneParser.getSignalName(signalNumber)})"
            else -> "Signal not specified in trace buffer"
        }
        val codeDesc = when {
            signalCodeName.isNotBlank() && signalCode != 0 -> "$signalCodeName ($signalCode)"
            signalCodeName.isNotBlank() -> signalCodeName
            signalCode != 0 -> "Code $signalCode"
            else -> "N/A"
        }
        val faultDesc = faultAddress?.let { "fault addr 0x%x".format(it) } ?: "fault addr --------"
        sb.append("$sigDesc, code $codeDesc, $faultDesc\n")

        if (senderPid != null && senderPid > 0) {
            sb.append("sender pid: $senderPid, sender uid: ${senderUid ?: 0}\n")
        }

        if (!abortMessage.isNullOrBlank()) {
            sb.append("Abort message: '$abortMessage'\n")
        }

        if (causes.isNotEmpty()) {
            sb.append("Causes:\n")
            causes.forEach { c -> sb.append("  $c\n") }
        }

        if (crashingThreadRegisters.isNotEmpty()) {
            sb.append("\nRegisters (Crashing Thread #$tid):\n")
            val regList = crashingThreadRegisters.entries.toList()
            for (i in regList.indices step 4) {
                val chunk = regList.subList(i, minOf(i + 4, regList.size))
                val line = chunk.joinToString("  ") { (k, v) ->
                    "%-4s %016x".format(k, v)
                }
                sb.append("  ").append(line).append("\n")
            }
        }

        sb.append("\n").append(toFormattedBacktrace()).append("\n")

        if (otherThreads.isNotEmpty()) {
            sb.append("\n--- Other Threads (${otherThreads.size} threads) ---\n")
            otherThreads.take(8).forEach { t ->
                sb.append("Thread #${t.id} '${t.name}':\n")
                t.backtrace.take(6).forEach { frame ->
                    sb.append(frame.toFormattedLine()).append("\n")
                }
                if (t.backtrace.size > 6) {
                    sb.append("      ... (${t.backtrace.size - 6} more frames)\n")
                }
            }
            if (otherThreads.size > 8) {
                sb.append("... (${otherThreads.size - 8} additional threads omitted for brevity)\n")
            }
        }

        if (extractedLogs.isNotEmpty()) {
            sb.append("\n--- Tombstone Log Buffer (${extractedLogs.size} entries) ---\n")
            extractedLogs.takeLast(25).forEach { logLine ->
                sb.append("  ").append(logLine).append("\n")
            }
        }

        if (!rawBytesFile.isNullOrBlank()) {
            sb.append("\nRaw Tombstone archive: $rawBytesFile\n")
            if (rawBytesSha256.isNotBlank()) {
                sb.append("SHA-256: $rawBytesSha256\n")
            }
        }

        return sb.toString()
    }
}

/**
 * Protocol Buffers binary parser for Android Tombstone data.
 * Adheres strictly to tombstone.proto schema, handles GZIP/ZLIB streams, and preserves raw binary bytes.
 */
object TombstoneParser {
    private const val TAG = "TombstoneParser"

    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_32BIT = 5

    fun getSignalName(sigNum: Int): String {
        return when (sigNum) {
            1 -> "SIGHUP"
            2 -> "SIGINT"
            3 -> "SIGQUIT"
            4 -> "SIGILL"
            5 -> "SIGTRAP"
            6 -> "SIGABRT"
            7 -> "SIGBUS"
            8 -> "SIGFPE"
            9 -> "SIGKILL"
            10 -> "SIGUSR1"
            11 -> "SIGSEGV"
            12 -> "SIGUSR2"
            13 -> "SIGPIPE"
            14 -> "SIGALRM"
            15 -> "SIGTERM"
            19 -> "SIGSTOP"
            else -> if (sigNum > 0) "SIGNAL_$sigNum" else ""
        }
    }

    /**
     * Parses an input stream from ApplicationExitInfo.getTraceInputStream() for REASON_CRASH_NATIVE.
     * Extracts structured tombstone data, decompresses GZIP if required, and dumps raw bytes to disk.
     */
    fun parse(stream: InputStream, rawDumpFile: File? = null): ParsedTombstone {
        val rawBytes = try {
            stream.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading tombstone stream: ${e.message}")
            return ParsedTombstone(
                abortMessage = "Trace stream unreadable: ${e.message}",
                evidenceStatus = "MINIMAL_EVIDENCE",
                missingEvidenceReasons = listOf("Stream read exception: ${e.message}")
            )
        }

        var sha256 = ""
        if (rawBytes.isNotEmpty()) {
            sha256 = computeSha256(rawBytes)
        }

        // Save raw binary archive immediately for forensics
        if (rawDumpFile != null && rawBytes.isNotEmpty()) {
            try {
                rawDumpFile.parentFile?.mkdirs()
                rawDumpFile.writeBytes(rawBytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed archiving raw tombstone pb bytes: ${e.message}")
            }
        }

        if (rawBytes.isEmpty()) {
            return ParsedTombstone(
                abortMessage = "Empty trace data received from OS",
                evidenceStatus = "MINIMAL_EVIDENCE",
                missingEvidenceReasons = listOf("OS provided an empty trace stream (0 bytes)")
            )
        }

        // Automatic GZIP / ZLIB decompression
        val (decompressedBytes, wasGzip) = decompressIfGzip(rawBytes)

        // If data starts with plain text characters, handle via text fallback
        if (isPlainText(decompressedBytes)) {
            val textParsed = parseTextTombstone(decompressedBytes, rawDumpFile?.absolutePath)
            return textParsed.copy(
                rawBytesSha256 = sha256,
                isGzipDecompressed = wasGzip
            )
        }

        // Parse binary Protobuf Tombstone
        return try {
            val reader = ProtoStreamReader(decompressedBytes)
            val parsed = parseTombstoneMessage(reader, rawDumpFile?.absolutePath)
            parsed.copy(
                rawBytesSha256 = sha256,
                isGzipDecompressed = wasGzip,
                isProtoParsed = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Protobuf parsing error, falling back to text inspection: ${e.message}")
            val fallback = parseTextTombstone(decompressedBytes, rawDumpFile?.absolutePath)
            fallback.copy(
                rawBytesSha256 = sha256,
                isGzipDecompressed = wasGzip,
                evidenceStatus = "PARTIAL_EVIDENCE",
                missingEvidenceReasons = listOf("Protobuf binary parse failure: ${e.message}")
            )
        }
    }

    private fun decompressIfGzip(bytes: ByteArray): Pair<ByteArray, Boolean> {
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            try {
                java.util.zip.GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                    return Pair(gis.readBytes(), true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "GZIP decompression failed: ${e.message}")
            }
        }
        if (bytes.size >= 2 && bytes[0] == 0x78.toByte()) {
            try {
                java.util.zip.InflaterInputStream(ByteArrayInputStream(bytes)).use { iis ->
                    return Pair(iis.readBytes(), true)
                }
            } catch (_: Exception) {}
        }
        return Pair(bytes, false)
    }

    private fun computeSha256(bytes: ByteArray): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun isPlainText(bytes: ByteArray): Boolean {
        val checkLen = minOf(bytes.size, 64)
        var printable = 0
        for (i in 0 until checkLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x0A || b == 0x0D || b == 0x09 || (b in 0x20..0x7E)) {
                printable++
            }
        }
        return (printable.toFloat() / checkLen) > 0.85f
    }

    private fun parseTombstoneMessage(reader: ProtoStreamReader, rawPath: String?): ParsedTombstone {
        var arch = "ARM64"
        var buildFingerprint = ""
        var revision = ""
        var timestamp = ""
        var pid = 0
        var tid = 0
        var uid = 0
        var selinuxLabel = ""
        val commandLine = mutableListOf<String>()
        var processUptime = 0
        var signalNumber = 0
        var signalName = ""
        var signalCode = 0
        var signalCodeName = ""
        var senderUid: Int? = null
        var senderPid: Int? = null
        var faultAddress: Long? = null
        var abortMessage: String? = null
        val causes = mutableListOf<String>()
        val threadsMap = mutableMapOf<Int, ThreadInfo>()
        val memoryMappings = mutableListOf<Pair<LongRange, String>>()
        val extractedLogs = mutableListOf<String>()
        var pageSize = 0
        var hasBeen16kbMode = false

        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            when (fieldNum) {
                1 -> { // arch (enum)
                    val archEnum = reader.readInt32()
                    arch = when (archEnum) {
                        0 -> "ARM32"
                        1 -> "ARM64"
                        2 -> "X86"
                        3 -> "X86_64"
                        4 -> "RISCV64"
                        else -> "UNKNOWN ($archEnum)"
                    }
                }
                2 -> buildFingerprint = reader.readString()
                3 -> revision = reader.readString()
                4 -> timestamp = reader.readString()
                5 -> pid = reader.readUInt32()
                6 -> tid = reader.readUInt32()
                7 -> uid = reader.readUInt32()
                8 -> selinuxLabel = reader.readString()
                9 -> commandLine.add(reader.readString())
                20 -> processUptime = reader.readUInt32()
                10 -> { // signal_info (Signal message)
                    val sigLen = reader.readVarint().toInt()
                    val sigEnd = reader.position + sigLen
                    while (reader.position < sigEnd) {
                        val sTag = reader.readVarint()
                        val sField = (sTag ushr 3).toInt()
                        val sWire = (sTag and 0x7).toInt()
                        when (sField) {
                            1 -> signalNumber = reader.readInt32()
                            2 -> signalName = reader.readString()
                            3 -> signalCode = reader.readInt32()
                            4 -> signalCodeName = reader.readString()
                            6 -> senderUid = reader.readInt32()
                            7 -> senderPid = reader.readInt32()
                            9 -> faultAddress = reader.readUInt64()
                            else -> reader.skip(sWire)
                        }
                    }
                }
                14 -> abortMessage = reader.readString()
                15 -> { // causes (repeated Cause)
                    val causeLen = reader.readVarint().toInt()
                    val causeEnd = reader.position + causeLen
                    var causeText = ""
                    while (reader.position < causeEnd) {
                        val cTag = reader.readVarint()
                        val cField = (cTag ushr 3).toInt()
                        val cWire = (cTag and 0x7).toInt()
                        if (cField == 1) {
                            causeText = reader.readString()
                        } else {
                            reader.skip(cWire)
                        }
                    }
                    if (causeText.isNotBlank()) causes.add(causeText)
                }
                16 -> { // threads (map<uint32, Thread>)
                    val entryLen = reader.readVarint().toInt()
                    val entryEnd = reader.position + entryLen
                    var threadId = 0
                    var threadInfo: ThreadInfo? = null

                    while (reader.position < entryEnd) {
                        val eTag = reader.readVarint()
                        val eField = (eTag ushr 3).toInt()
                        val eWire = (eTag and 0x7).toInt()
                        when (eField) {
                            1 -> threadId = reader.readUInt32()
                            2 -> {
                                val tLen = reader.readVarint().toInt()
                                threadInfo = parseThreadMessage(reader, tLen)
                            }
                            else -> reader.skip(eWire)
                        }
                    }
                    if (threadInfo != null) {
                        threadsMap[threadId] = threadInfo
                    }
                }
                17 -> { // memory_mappings (repeated MemoryMapping)
                    val mapLen = reader.readVarint().toInt()
                    val mapEnd = reader.position + mapLen
                    var begin = 0L
                    var end = 0L
                    var mapName = ""
                    while (reader.position < mapEnd) {
                        val mTag = reader.readVarint()
                        val mField = (mTag ushr 3).toInt()
                        val mWire = (mTag and 0x7).toInt()
                        when (mField) {
                            1 -> begin = reader.readUInt64()
                            2 -> end = reader.readUInt64()
                            7 -> mapName = reader.readString()
                            else -> reader.skip(mWire)
                        }
                    }
                    if (begin < end && mapName.isNotBlank()) {
                        memoryMappings.add(Pair(begin..end, mapName))
                    }
                }
                18 -> { // log_buffers (repeated LogBuffer)
                    val logBufLen = reader.readVarint().toInt()
                    val logBufEnd = reader.position + logBufLen
                    var bufName = ""
                    while (reader.position < logBufEnd) {
                        val lTag = reader.readVarint()
                        val lField = (lTag ushr 3).toInt()
                        val lWire = (lTag and 0x7).toInt()
                        when (lField) {
                            1 -> bufName = reader.readString()
                            2 -> { // repeated LogMessage
                                val msgLen = reader.readVarint().toInt()
                                val msgEnd = reader.position + msgLen
                                var logTime = ""
                                var logTag = ""
                                var logMsg = ""
                                var logPrio = 0
                                while (reader.position < msgEnd) {
                                    val mTag = reader.readVarint()
                                    val mField = (mTag ushr 3).toInt()
                                    val mWire = (mTag and 0x7).toInt()
                                    when (mField) {
                                        1 -> logTime = reader.readString()
                                        4 -> logPrio = reader.readInt32()
                                        5 -> logTag = reader.readString()
                                        6 -> logMsg = reader.readString()
                                        else -> reader.skip(mWire)
                                    }
                                }
                                val prioChar = when (logPrio) {
                                    2 -> "V"
                                    3 -> "D"
                                    4 -> "I"
                                    5 -> "W"
                                    6 -> "E"
                                    7 -> "F"
                                    else -> "D"
                                }
                                val formattedLog = if (logTime.isNotBlank()) {
                                    "$logTime $prioChar/$logTag: $logMsg"
                                } else {
                                    "$prioChar/$logTag: $logMsg"
                                }
                                extractedLogs.add(formattedLog)
                            }
                            else -> reader.skip(lWire)
                        }
                    }
                }
                22 -> pageSize = reader.readUInt32()
                23 -> hasBeen16kbMode = reader.readVarint() != 0L
                else -> reader.skip(wireType)
            }
        }

        // Identify the crashing thread
        val crashingThread = threadsMap[tid] ?: threadsMap.values.firstOrNull { it.id == tid } ?: threadsMap.values.firstOrNull()
        val crashingThreadName = crashingThread?.name ?: "UnknownThread"
        val crashingBacktrace = crashingThread?.backtrace ?: emptyList()
        val crashingRegisters = crashingThread?.registers ?: emptyMap()

        // Distinguish other threads
        val otherThreads = threadsMap.values.filter { it.id != tid }

        // Find faulting library and PC from the crashing backtrace
        var faultingLibrary: String? = null
        var faultingPc: Long? = null

        for (frame in crashingBacktrace) {
            // Enrich frame filename from memory mappings if missing
            if (frame.fileName.isBlank() && frame.relPc > 0) {
                for ((range, name) in memoryMappings) {
                    if (frame.relPc in range || frame.pc in range) {
                        frame.fileName = name
                        break
                    }
                }
            }

            // Enrich function name from Go pclntab if frame is in libmain.so and function is missing
            if (frame.fileName.contains("libmain.so") && frame.functionName.isBlank()) {
                val resolved = GoPclntabResolver.resolve(frame.relPc)
                if (resolved != null) {
                    frame.functionName = resolved.first
                    frame.functionOffset = resolved.second
                }
            }

            if (faultingLibrary == null && frame.fileName.contains("libmain.so")) {
                faultingLibrary = "libmain.so"
                faultingPc = frame.relPc
            }
        }

        if (faultingLibrary == null && crashingBacktrace.isNotEmpty()) {
            faultingLibrary = crashingBacktrace.first().fileName.substringAfterLast('/')
            faultingPc = crashingBacktrace.first().relPc
        }

        val missingReasons = mutableListOf<String>()
        val evidenceStatus = if (crashingBacktrace.isNotEmpty() && (signalNumber > 0 || signalName.isNotBlank())) {
            "COMPLETE_EVIDENCE"
        } else {
            if (crashingBacktrace.isEmpty()) {
                missingReasons.add("No backtrace frames found in trace buffer for crashing thread #$tid")
            }
            if (signalNumber == 0 && signalName.isBlank()) {
                missingReasons.add("Signal details were not populated in signal_info proto message")
            }
            "PARTIAL_EVIDENCE"
        }

        return ParsedTombstone(
            arch = arch,
            buildFingerprint = buildFingerprint,
            revision = revision,
            timestamp = timestamp,
            pid = pid,
            tid = tid,
            uid = uid,
            selinuxLabel = selinuxLabel,
            commandLine = commandLine,
            processUptime = processUptime,
            signalNumber = signalNumber,
            signalName = signalName,
            signalCode = signalCode,
            signalCodeName = signalCodeName,
            senderUid = senderUid,
            senderPid = senderPid,
            faultAddress = faultAddress,
            abortMessage = abortMessage,
            causes = causes,
            crashingThreadName = crashingThreadName,
            crashingThreadRegisters = crashingRegisters,
            crashingThreadBacktrace = crashingBacktrace,
            otherThreads = otherThreads,
            faultingLibrary = faultingLibrary,
            faultingPc = faultingPc,
            pageSize = pageSize,
            hasBeen16kbMode = hasBeen16kbMode,
            rawBytesFile = rawPath,
            evidenceStatus = evidenceStatus,
            missingEvidenceReasons = missingReasons,
            extractedLogs = extractedLogs
        )
    }

    private fun parseThreadMessage(reader: ProtoStreamReader, len: Int): ThreadInfo {
        val endPos = reader.position + len
        var id = 0
        var name = ""
        val frames = mutableListOf<BacktraceFrameInfo>()
        val registers = mutableMapOf<String, Long>()
        var frameIndex = 0

        while (reader.position < endPos) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()

            when (field) {
                1 -> id = reader.readInt32()
                2 -> name = reader.readString()
                3 -> { // registers (repeated Register)
                    val regLen = reader.readVarint().toInt()
                    val regEnd = reader.position + regLen
                    var regName = ""
                    var regVal = 0L
                    while (reader.position < regEnd) {
                        val rTag = reader.readVarint()
                        val rField = (rTag ushr 3).toInt()
                        val rWire = (rTag and 0x7).toInt()
                        when (rField) {
                            1 -> regName = reader.readString()
                            2 -> regVal = reader.readUInt64()
                            else -> reader.skip(rWire)
                        }
                    }
                    if (regName.isNotBlank()) {
                        registers[regName] = regVal
                    }
                }
                4 -> { // current_backtrace (repeated BacktraceFrame)
                    val frameLen = reader.readVarint().toInt()
                    val frameEnd = reader.position + frameLen
                    var relPc = 0L
                    var pc = 0L
                    var sp = 0L
                    var funcName = ""
                    var funcOffset = 0L
                    var fileName = ""
                    var fileMapOffset = 0L
                    var buildId = ""

                    while (reader.position < frameEnd) {
                        val fTag = reader.readVarint()
                        val fField = (fTag ushr 3).toInt()
                        val fWire = (fTag and 0x7).toInt()
                        when (fField) {
                            1 -> relPc = reader.readUInt64()
                            2 -> pc = reader.readUInt64()
                            3 -> sp = reader.readUInt64()
                            4 -> funcName = reader.readString()
                            5 -> funcOffset = reader.readUInt64()
                            6 -> fileName = reader.readString()
                            7 -> fileMapOffset = reader.readUInt64()
                            8 -> buildId = reader.readString()
                            else -> reader.skip(fWire)
                        }
                    }

                    frames.add(
                        BacktraceFrameInfo(
                            frameIndex = frameIndex++,
                            relPc = relPc,
                            pc = pc,
                            sp = sp,
                            functionName = funcName,
                            functionOffset = funcOffset,
                            fileName = fileName,
                            fileMapOffset = fileMapOffset,
                            buildId = buildId
                        )
                    )
                }
                else -> reader.skip(wire)
            }
        }

        return ThreadInfo(id = id, name = name, registers = registers, backtrace = frames)
    }

    private fun parseTextTombstone(bytes: ByteArray, rawPath: String?): ParsedTombstone {
        val text = String(bytes, Charsets.UTF_8)
        var pid = 0
        var tid = 0
        var threadName = "unknown"
        var signalNumber = 0
        var signalName = ""
        var signalCode = 0
        var signalCodeName = ""
        var faultAddr: Long? = null
        var abortMsg: String? = null
        val frames = mutableListOf<BacktraceFrameInfo>()

        val pidRegex = Regex("""pid:\s*(\d+),\s*tid:\s*(\d+),\s*name:\s*([^,\n\r]+)""")
        val pidMatch = pidRegex.find(text)
        if (pidMatch != null) {
            pid = pidMatch.groupValues[1].toIntOrNull() ?: 0
            tid = pidMatch.groupValues[2].toIntOrNull() ?: 0
            threadName = pidMatch.groupValues[3].trim()
        }

        val sigRegex = Regex("""signal\s+(\d+)\s*\(([^)]+)\),\s*code\s+(-?\d+)\s*\(([^)]+)\)""")
        val sigMatch = sigRegex.find(text)
        if (sigMatch != null) {
            signalNumber = sigMatch.groupValues[1].toIntOrNull() ?: 0
            signalName = sigMatch.groupValues[2]
            signalCode = sigMatch.groupValues[3].toIntOrNull() ?: 0
            signalCodeName = sigMatch.groupValues[4]
        }

        val faultRegex = Regex("""fault addr (?:0x)?([0-9a-fA-F]+)""")
        val faultMatch = faultRegex.find(text)
        if (faultMatch != null) {
            faultAddr = faultMatch.groupValues[1].toLongOrNull(16)
        }

        val abortRegex = Regex("""Abort message:\s*'([^']+)'""")
        val abortMatch = abortRegex.find(text)
        if (abortMatch != null) {
            abortMsg = abortMatch.groupValues[1]
        }

        val frameRegex = Regex("""#(\d+)\s+pc\s+([0-9a-fA-F]+)\s+([^\s(]+)(?:\s+\(([^)]+)\))?""")
        var idx = 0
        for (m in frameRegex.findAll(text)) {
            val pc = m.groupValues[2].toLongOrNull(16) ?: 0L
            val file = m.groupValues[3]
            val fnRaw = m.groupValues.getOrNull(4) ?: ""
            var fn = fnRaw
            var fnOff = 0L
            if (fn.contains('+')) {
                val parts = fn.split('+')
                fn = parts[0]
                fnOff = parts[1].removePrefix("0x").toLongOrNull(16) ?: 0L
            }
            frames.add(
                BacktraceFrameInfo(
                    frameIndex = idx++,
                    relPc = pc,
                    functionName = fn,
                    functionOffset = fnOff,
                    fileName = file
                )
            )
        }

        val missing = mutableListOf<String>()
        val evidenceStatus = if (frames.isNotEmpty() && (signalNumber > 0 || signalName.isNotBlank())) {
            "COMPLETE_EVIDENCE"
        } else {
            if (frames.isEmpty()) missing.add("Text tombstone had no readable stack frames")
            "PARTIAL_EVIDENCE"
        }

        return ParsedTombstone(
            pid = pid,
            tid = tid,
            signalNumber = signalNumber,
            signalName = signalName,
            signalCode = signalCode,
            signalCodeName = signalCodeName,
            faultAddress = faultAddr,
            abortMessage = abortMsg,
            crashingThreadName = threadName,
            crashingThreadBacktrace = frames,
            faultingLibrary = frames.firstOrNull { it.fileName.contains("libmain.so") }?.fileName ?: "libmain.so",
            rawBytesFile = rawPath,
            evidenceStatus = evidenceStatus,
            missingEvidenceReasons = missing
        )
    }

    /**
     * Binary Protocol Buffers stream cursor.
     */
    class ProtoStreamReader(private val buffer: ByteArray) {
        var position: Int = 0

        fun hasMore(): Boolean = position < buffer.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (position < buffer.size) {
                val b = buffer[position++].toLong()
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80L) == 0L) {
                    return result
                }
                shift += 7
                if (shift >= 64) break
            }
            return result
        }

        fun readInt32(): Int = readVarint().toInt()

        fun readUInt32(): Int = readVarint().toInt() and 0xFFFFFFFF.toInt()

        fun readUInt64(): Long = readVarint()

        fun readString(): String {
            val len = readVarint().toInt()
            if (len <= 0) return ""
            val safeLen = minOf(len, buffer.size - position)
            val str = String(buffer, position, safeLen, Charsets.UTF_8)
            position += safeLen
            return str
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_64BIT -> position = minOf(position + 8, buffer.size)
                WIRE_LENGTH_DELIMITED -> {
                    val len = readVarint().toInt()
                    position = minOf(position + maxOf(0, len), buffer.size)
                }
                WIRE_32BIT -> position = minOf(position + 4, buffer.size)
                else -> position = buffer.size // Corrupted or unknown wire type, abort
            }
        }
    }
}
