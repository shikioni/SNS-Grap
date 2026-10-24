package com.instagrab.app

import com.instagrab.app.data.downloader.Mp4Patcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class Mp4PatcherTest {

    @Test
    fun testPatchTimestamps() {
        val tempFile = File.createTempFile("test_video", ".mp4")
        tempFile.deleteOnExit()

        // Create a mock MP4 with mvhd and tkhd boxes
        RandomAccessFile(tempFile, "rw").use { raf ->
            // ftyp box (16 bytes)
            raf.writeInt(16)
            raf.write("ftyp".toByteArray())
            raf.write("isom".toByteArray())
            raf.writeInt(512)

            // mvhd box (v0: 108 bytes, version at +4, creation_time at +8)
            raf.writeInt(108)
            raf.write("mvhd".toByteArray())
            raf.writeByte(0) // version 0
            raf.write(byteArrayOf(0, 0, 0)) // flags
            raf.writeInt(1000) // old creation_time
            raf.writeInt(1000) // old modification_time
            raf.write(ByteArray(88)) // rest of mvhd

            // tkhd box (v0: 92 bytes)
            raf.writeInt(92)
            raf.write("tkhd".toByteArray())
            raf.writeByte(0) // version 0
            raf.write(byteArrayOf(0, 0, 0)) // flags
            raf.writeInt(2000) // old creation_time
            raf.writeInt(2000) // old modification_time
            raf.write(ByteArray(72)) // rest of tkhd
        }

        val testEpochMs = 1700000000000L // 2023-11-14
        val expectedMp4Sec = (testEpochMs / 1000 + 2082844800L).toInt()

        val patched = Mp4Patcher.patchTimestamps(tempFile, testEpochMs)
        assertEquals(2, patched)

        // Verify values written in binary
        RandomAccessFile(tempFile, "r").use { raf ->
            // Check mvhd
            raf.seek(28) // mvhd creation_time offset
            assertEquals(expectedMp4Sec, raf.readInt())
            assertEquals(expectedMp4Sec, raf.readInt())

            // Check tkhd
            raf.seek(136) // tkhd creation_time offset
            assertEquals(expectedMp4Sec, raf.readInt())
            assertEquals(expectedMp4Sec, raf.readInt())
        }

        tempFile.delete()
    }
}
