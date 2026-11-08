package top.steeefanie.leafreader.reader.fonts

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class OpenTypeFontTest {
    private fun tables(name:String,weight:Int,variable:Boolean=false):Map<String,ByteArray> {
        val text=name.toByteArray(Charsets.UTF_16BE)
        val names=ByteBuffer.allocate(18+text.size).apply {
            putShort(0);putShort(1);putShort(18)
            putShort(3);putShort(1);putShort(0x409);putShort(1);putShort(text.size.toShort());putShort(0);put(text)
        }.array()
        return linkedMapOf("head" to ByteArray(54),"name" to names,
            "OS/2" to ByteBuffer.allocate(64).apply { putShort(4,weight.toShort()) }.array()).apply {
            if(variable)put("fvar",ByteBuffer.allocate(36).apply {
                putInt(0x10000);putShort(16);putShort(2);putShort(1);putShort(20);putShort(0);putShort(0)
                putInt(0x77676874);putInt(100 shl 16);putInt(400 shl 16);putInt(900 shl 16);putInt(0)
            }.array())
        }
    }
    private fun font(faces:List<Map<String,ByteArray>>,collection:Boolean=false):ByteArray {
        val start=if(collection)12+4*faces.size else 0
        val offsets=faces.runningFold(start){offset,tables -> offset+12+tables.size*16+tables.values.sumOf{(it.size+3)/4*4}}
        val data=ByteBuffer.allocate(offsets.last())
        if(collection){data.putInt(0x74746366);data.putInt(0x10000);data.putInt(faces.size);offsets.dropLast(1).forEach{data.putInt(it)}}
        faces.forEachIndexed { index,tables ->
            data.position(offsets[index]);data.putInt(0x10000);data.putShort(tables.size.toShort());data.put(ByteArray(6))
            var cursor=offsets[index]+12+tables.size*16
            tables.forEach { (tag,bytes) ->
                var checksum=0
                val padded=bytes.copyOf((bytes.size+3)/4*4)
                val words=ByteBuffer.wrap(padded);while(words.remaining()>0)checksum+=words.int
                data.put(tag.toByteArray());data.putInt(checksum);data.putInt(cursor);data.putInt(bytes.size)
                padded.forEachIndexed{i,byte -> data.put(cursor+i,byte)};cursor+=padded.size
            }
        }
        return data.array()
    }
    private fun fixture(bytes:ByteArray,block:(File)->Unit) {
        val file=File.createTempFile("reader-font-",".ttc")
        try{file.writeBytes(bytes);block(file)}finally{file.delete()}
    }
    @Test fun collectionKeepsFacesAndExtractionHasValidChecksum() {
        fixture(font(listOf(tables("Sample Sans",400),tables("Sample Serif",700)),true)){file ->
            val faces=OpenTypeFont.read(file)
            assertEquals(listOf("Sample Sans","Sample Serif"),faces.map{it.family})
            assertEquals(listOf(400,700),faces.map{it.weight})
            faces.forEach { face ->
                val standalone=OpenTypeFont.standalone(face)
                var sum=0;val words=ByteBuffer.wrap(standalone);while(words.remaining()>0)sum+=words.int
                assertEquals(0xb1b0afba.toInt(),sum)
                fixture(standalone){assertEquals(face.family,OpenTypeFont.read(it).single().family)}
            }
        }
    }
    @Test fun variableAndStaticFamiliesExposeOnlyAvailableWeights() {
        fixture(font(listOf(tables("Variable",400,true)))){file ->
            val face=OpenTypeFont.read(file).single()
            assertEquals(100..900,face.weightRange)
            assertEquals((100..900 step 100).toList(),DeviceFontFamily("v","Variable",listOf(face)).weights)
            val static=DeviceFontFamily("s","Static",listOf(face.copy(weightRange=null,weight=500),face.copy(weightRange=null,weight=700)))
            assertEquals(listOf(500,700),static.weights);assertEquals(500,static.nearest(530.0))
            assertEquals(listOf(400),DeviceFontFamily("system-default","System",emptyList()).weights)
        }
    }
    @Test fun chineseLegacyFamilyNameIsNotHiddenByEnglishTypographicName() {
        val records=listOf(Triple(1,0x804,"设备中文字体"),Triple(16,0x409,"OEM Typographic Family"))
        val strings=records.map { it.third.toByteArray(Charsets.UTF_16BE) }
        val start=6+records.size*12
        val names=ByteBuffer.allocate(start+strings.sumOf { it.size })
        names.putShort(0);names.putShort(records.size.toShort());names.putShort(start.toShort())
        var offset=0
        records.forEachIndexed { i,record ->
            names.putShort(3);names.putShort(1);names.putShort(record.second.toShort());names.putShort(record.first.toShort())
            names.putShort(strings[i].size.toShort());names.putShort(offset.toShort());offset+=strings[i].size
        }
        strings.forEach { names.put(it) }
        fixture(font(listOf(tables("Original",400)+("name" to names.array())))) {
            val face=OpenTypeFont.read(it).single()
            assertEquals("OEM Typographic Family",face.family)
            assertEquals("设备中文字体",face.displayName)
        }
    }
    @Test fun truncatedTablesAreRejectedWithoutReadingOutsideFile() {
        fixture(font(listOf(tables("Broken",400))).copyOf(40)){assertTrue(OpenTypeFont.read(it).isEmpty())}
    }
}
