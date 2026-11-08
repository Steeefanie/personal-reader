package top.steeefanie.leafreader.reader.fonts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

data class FontFace(
    val file: File,
    val index: Int,
    val family: String,
    val displayName: String,
    val postScriptName: String,
    val weight: Int,
    val italic: Boolean,
    val weightRange: IntRange?,
    val chinese: Boolean
)

/** Reads bounded OpenType metadata. Font paths are discovered locally, never supplied by EPUB. */
object OpenTypeFont {
    private data class Table(val tag: String, val checksum: Long, val offset: Long, val length: Int)
    private fun RandomAccessFile.u32() = readInt().toLong() and 0xffffffffL
    private fun RandomAccessFile.tables(offset: Long): List<Table> {
        require(offset >= 0 && offset + 12 <= length())
        seek(offset)
        require(readInt() in listOf(0x00010000, 0x4f54544f, 0x74727565))
        val count = readUnsignedShort()
        require(count in 1..256 && offset + 12 + count * 16 <= length())
        skipBytes(6)
        return List(count) {
            val tag = ByteArray(4).also { readFully(it) }.toString(Charsets.US_ASCII)
            val checksum = u32()
            val start = u32()
            val size = u32()
            require(size <= 64 * 1024 * 1024 && start <= length() && size <= length() - start)
            Table(tag, checksum, start, size.toInt())
        }
    }
    private fun RandomAccessFile.offsets(): List<Long> {
        require(length() in 12..128L * 1024 * 1024)
        seek(0)
        if (readInt() != 0x74746366) return listOf(0L)
        skipBytes(4)
        val count = u32().toInt()
        require(count in 1..128 && 12L + count * 4 <= length())
        return List(count) { u32() }
    }
    private fun RandomAccessFile.tableBytes(table: Table, limit: Int = 1024 * 1024): ByteArray {
        require(table.length <= limit)
        seek(table.offset)
        return ByteArray(table.length).also { readFully(it) }
    }
    private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xffff
    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    fun read(file: File): List<FontFace> = RandomAccessFile(file, "r").use { input ->
        input.offsets().mapIndexedNotNull { index, offset ->
            runCatching { readFace(input, file, index, offset) }.getOrNull()
        }
    }
    private fun readFace(input: RandomAccessFile, file: File, index: Int, offset: Long): FontFace {
        val tables = input.tables(offset).associateBy { it.tag }
        val names = readNames(input.tableBytes(checkNotNull(tables["name"])))
        val family = names[16]?.first ?: names[1]?.first ?: file.nameWithoutExtension
        val display = listOfNotNull(names[16]?.second,names[1]?.second).firstOrNull(FontIndex::hasChinese)
            ?: names[16]?.second ?: names[1]?.second ?: family
        val os2 = buffer(input.tableBytes(checkNotNull(tables["OS/2"])))
        require(os2.limit() >= 8)
        val weight = os2.u16(4).coerceIn(1, 1000)
        val italic = os2.limit() >= 64 && (os2.u16(62) and 1) != 0
        val range = tables["fvar"]?.let { readWeightRange(input.tableBytes(it)) }
        val chinese = tables["cmap"]?.let { hasChinese(input.tableBytes(it, 4 * 1024 * 1024)) } ?: false
        return FontFace(file, index, family, display, names[6]?.first ?: family, weight, italic, range, chinese)
    }
    private fun readNames(bytes: ByteArray): Map<Int, Pair<String, String>> {
        val data = buffer(bytes)
        require(bytes.size >= 6)
        val count = data.u16(2)
        val start = data.u16(4)
        require(count <= 4096 && 6 + count * 12 <= bytes.size)
        data class Name(val value: String, val rank: Int, val chinese: Boolean)
        val names = mutableMapOf<Int, MutableList<Name>>()
        repeat(count) { index ->
            val at = 6 + index * 12
            val platform = data.u16(at)
            val language = data.u16(at + 4)
            val id = data.u16(at + 6)
            if (id !in listOf(1, 6, 16)) return@repeat
            val size = data.u16(at + 8)
            val position = start + data.u16(at + 10)
            if (position < 0 || position + size > bytes.size || size > 4096) return@repeat
            val charset = when (platform) {
                0, 3 -> Charsets.UTF_16BE
                1 -> runCatching { Charset.forName("MacRoman") }.getOrNull() ?: return@repeat
                else -> return@repeat
            }
            val value = String(bytes, position, size, charset).trim().replace('\u0000', ' ')
            if (value.isBlank()) return@repeat
            val chinese = platform == 3 && (language and 0x3ff) == 4
            val rank = when { platform == 3 && language == 0x409 -> 0; platform == 0 -> 1; chinese -> 2; else -> 3 }
            names.getOrPut(id) { mutableListOf() }.add(Name(value, rank, chinese))
        }
        return names.mapValues { (_, values) ->
            values.minBy { it.rank }.value to (values.firstOrNull { it.chinese } ?: values.minBy { it.rank }).value
        }
    }
    private fun readWeightRange(bytes: ByteArray): IntRange? {
        val data = buffer(bytes)
        require(bytes.size >= 16)
        val start = data.u16(4)
        val count = data.u16(8)
        val size = data.u16(10)
        require(count <= 64 && size >= 20 && start + count * size <= bytes.size)
        repeat(count) { index ->
            val at = start + index * size
            if (data.getInt(at) == 0x77676874) {
                val minimum = (data.getInt(at + 4) / 65536.0).toInt().coerceIn(1, 1000)
                val maximum = (data.getInt(at + 12) / 65536.0).toInt().coerceIn(1, 1000)
                if (minimum <= maximum) return minimum..maximum
            }
        }
        return null
    }
    private fun hasChinese(bytes: ByteArray): Boolean {
        val data = buffer(bytes)
        if (bytes.size < 4) return false
        val count = data.u16(2)
        if (4 + count * 8 > bytes.size) return false
        val character = 0x4e2d // 中; coverage hint for ordering the picker, not a complete language claim.
        repeat(count) { index ->
            val at = data.getInt(8 + index * 8)
            if (at < 0 || at + 16 > bytes.size) return@repeat
            when (data.u16(at)) {
                4 -> {
                    val segments = data.u16(at + 6) / 2
                    val ends = at + 14
                    val starts = ends + segments * 2 + 2
                    if (starts + segments * 6 > bytes.size) return@repeat
                    for (segment in 0 until segments) {
                        if (character !in data.u16(starts + segment * 2)..data.u16(ends + segment * 2)) continue
                        val delta = data.getShort(starts + segments * 2 + segment * 2).toInt()
                        val location = starts + segments * 4 + segment * 2
                        val distance = data.u16(location)
                        if (distance == 0) return ((character + delta) and 0xffff) != 0
                        val glyphAt = location + distance + (character - data.u16(starts + segment * 2)) * 2
                        if (glyphAt + 2 <= bytes.size && data.u16(glyphAt) != 0) return true
                    }
                }
                12 -> {
                    val groups = data.getInt(at + 12)
                    if (groups < 0 || at + 16L + groups * 12L > bytes.size) return@repeat
                    for (group in 0 until groups) {
                        val position = at + 16 + group * 12
                        if (character in data.getInt(position)..data.getInt(position + 4)) return true
                    }
                }
            }
        }
        return false
    }

    /** Extracts one TTC face into standalone SFNT bytes for font loading and resource access. */
    fun standalone(face: FontFace): ByteArray = RandomAccessFile(face.file, "r").use { input ->
        val offsets = input.offsets()
        val offset = offsets.getOrNull(face.index) ?: error("字体已变化，请重新选择")
        val tables = input.tables(offset).filter { it.tag != "DSIG" }.sortedBy { it.tag }
        val headerSize = 12 + tables.size * 16
        val total = tables.fold(headerSize.toLong()) { sum, table -> sum + ((table.length.toLong() + 3) / 4) * 4 }
        require(total <= 64 * 1024 * 1024)
        val result = ByteArray(total.toInt())
        val output = buffer(result)
        input.seek(offset)
        output.putInt(input.readInt())
        val power = Integer.highestOneBit(tables.size)
        output.putShort(tables.size.toShort()).putShort((power * 16).toShort())
            .putShort(Integer.numberOfTrailingZeros(power).toShort()).putShort((tables.size * 16 - power * 16).toShort())
        var position = headerSize
        var head = -1
        tables.forEach { table ->
            output.put(table.tag.toByteArray(Charsets.US_ASCII)).putInt(table.checksum.toInt()).putInt(position).putInt(table.length)
            input.seek(table.offset)
            input.readFully(result, position, table.length)
            if (table.tag == "head") { require(table.length >= 12);head = position;output.putInt(head + 8, 0) }
            position += (table.length + 3) / 4 * 4
        }
        require(head >= 0)
        var checksum = 0L
        for (at in result.indices step 4) checksum = (checksum + (output.getInt(at).toLong() and 0xffffffffL)) and 0xffffffffL
        output.putInt(head + 8, (0xb1b0afbaL - checksum).toInt())
        result
    }
}
