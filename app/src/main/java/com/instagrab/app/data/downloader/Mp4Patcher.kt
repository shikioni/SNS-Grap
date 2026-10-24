package com.instagrab.app.data.downloader

import java.io.File
import java.io.RandomAccessFile

object Mp4Patcher {

    /**
     * Patches the creation_time and modification_time in all movie header (mvhd),
     * track header (tkhd), and media header (mdhd) boxes in the given MP4 file.
     * Sets them to the specified epoch timestamp (defaults to current time).
     *
     * In the MP4 specification (ISO/IEC 14496-12), timestamps are measured in seconds
     * since midnight, Jan 1, 1904 UTC.
     * Unix epoch is Jan 1, 1970 UTC. The offset between 1904 and 1970 is 2,082,844,800 seconds.
     */
    fun patchTimestamps(file: File, epochMs: Long = System.currentTimeMillis()): Int {
        if (!file.exists() || file.length() < 32) return 0

        var patchedCount = 0
        try {
            val unixSec = epochMs / 1000
            val mp4Sec = unixSec + 2082844800L

            RandomAccessFile(file, "rw").use { raf ->
                val fileLen = raf.length()
                val tags = listOf(
                    byteArrayOf('m'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte()),
                    byteArrayOf('t'.code.toByte(), 'k'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte()),
                    byteArrayOf('m'.code.toByte(), 'd'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())
                )

                val bufferSize = 65536
                val buffer = ByteArray(bufferSize)
                var fileOffset = 0L

                while (fileOffset < fileLen) {
                    raf.seek(fileOffset)
                    val bytesRead = raf.read(buffer)
                    if (bytesRead < 8) break

                    for (tag in tags) {
                        var searchIdx = 0
                        while (searchIdx <= bytesRead - 4) {
                            if (buffer[searchIdx] == tag[0] &&
                                buffer[searchIdx + 1] == tag[1] &&
                                buffer[searchIdx + 2] == tag[2] &&
                                buffer[searchIdx + 3] == tag[3]
                            ) {
                                val tagFilePos = fileOffset + searchIdx
                                val verPos = tagFilePos + 4
                                if (verPos < fileLen) {
                                    raf.seek(verPos)
                                    val version = raf.read()
                                    val timePos = verPos + 4
                                    if (version == 0 && timePos + 8 <= fileLen) {
                                        raf.seek(timePos)
                                        raf.writeInt(mp4Sec.toInt())
                                        raf.writeInt(mp4Sec.toInt())
                                        patchedCount++
                                    } else if (version == 1 && timePos + 16 <= fileLen) {
                                        raf.seek(timePos)
                                        raf.writeLong(mp4Sec)
                                        raf.writeLong(mp4Sec)
                                        patchedCount++
                                    }
                                }
                            }
                            searchIdx++
                        }
                    }

                    if (bytesRead < bufferSize) break
                    fileOffset += (bytesRead - 32).coerceAtLeast(1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return patchedCount
    }
}
