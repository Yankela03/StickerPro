package com.stickermaker.whatsapp.sticker

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds an animated WebP file from a list of same-size bitmaps, entirely in Kotlin.
 * Optimized for WhatsApp stickers (exactly 512x512, under 500KB).
 */
object AnimatedWebPEncoder {

    private const val LOOP_FOREVER = 0

    fun encode(
        frames: List<Bitmap>,
        frameDurationMs: Int,
        quality: Int
    ): ByteArray {
        require(frames.isNotEmpty()) { "Need at least one frame" }
        val width = frames[0].width
        val height = frames[0].height

        val frameDataChunks = frames.map { bitmap ->
            val singleFrameWebp = compressSingleFrame(bitmap, quality)
            extractFrameData(singleFrameWebp)
        }

        val anmfChunks = ByteArrayOutputStream()
        for (data in frameDataChunks) {
            anmfChunks.write(buildAnmfChunk(data, width, height, frameDurationMs))
        }

        val vp8x = buildVp8xChunk(width, height)
        val anim = buildAnimChunk()

        val body = ByteArrayOutputStream()
        body.write(vp8x)
        body.write(anim)
        body.write(anmfChunks.toByteArray())
        val bodyBytes = body.toByteArray()

        val riffSize = 4 /* "WEBP" */ + bodyBytes.size
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(riffSize))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun compressSingleFrame(bitmap: Bitmap, quality: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        // For stickers, lossless is much better for animation compatibility and alpha handling.
        // We use quality as a hint: if it's very low, we use lossy to save space, 
        // but prefer lossless for the bitstream stability.
        val format = when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R -> {
                if (quality >= 70) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            }
            else -> {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
        // If lossless, quality is ignored. If lossy, it's used.
        bitmap.compress(format, quality, baos)
        return baos.toByteArray()
    }

    /**
     * Extracts ALPH and VP8/VP8L chunks from a single-frame WebP file.
     */
    private fun extractFrameData(webpFile: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(webpFile).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 12) return webpFile // Should not happen
        
        val header = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
        if (header != "RIFF") return webpFile
        buffer.int // skip size
        val webp = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
        if (webp != "WEBP") return webpFile

        val out = ByteArrayOutputStream()
        while (buffer.remaining() >= 8) {
            val chunkStart = buffer.position()
            val fourcc = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
            val size = buffer.int
            val paddedSize = if (size % 2 == 1) size + 1 else size
            
            if (fourcc == "VP8 " || fourcc == "VP8L" || fourcc == "ALPH") {
                out.write(webpFile.copyOfRange(chunkStart, chunkStart + 8 + paddedSize))
            }
            
            try {
                buffer.position(buffer.position() + paddedSize)
            } catch (e: Exception) {
                break
            }
        }
        return out.toByteArray()
    }

    private fun buildVp8xChunk(width: Int, height: Int): ByteArray {
        val payload = ByteArray(10)
        // Flag byte: bit 1 (0x02) = has animation, bit 4 (0x10) = has alpha
        payload[0] = 0x12 
        writeUInt24LE(payload, 4, width - 1)
        writeUInt24LE(payload, 7, height - 1)
        return chunk("VP8X", payload)
    }

    private fun buildAnimChunk(): ByteArray {
        val payload = ByteArray(6)
        payload[0] = 0x00 // B
        payload[1] = 0x00 // G
        payload[2] = 0x00 // R
        payload[3] = 0x00 // A
        val loop = le16(LOOP_FOREVER)
        payload[4] = loop[0]
        payload[5] = loop[1]
        return chunk("ANIM", payload)
    }

    private fun buildAnmfChunk(frameData: ByteArray, width: Int, height: Int, durationMs: Int): ByteArray {
        val header = ByteArray(15)
        writeUInt24LE(header, 0, 0)
        writeUInt24LE(header, 3, 0)
        writeUInt24LE(header, 6, width - 1)
        writeUInt24LE(header, 9, height - 1)
        writeUInt24LE(header, 12, durationMs.coerceIn(10, 16777215))
        
        val payload = ByteArrayOutputStream()
        payload.write(header)
        payload.write(0x02) // Flags: disposal=1 (background), blending=0 (alpha blend)
        payload.write(frameData)

        return chunk("ANMF", payload.toByteArray())
    }

    private fun chunk(fourcc: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(le32(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0x00)
        return out.toByteArray()
    }

    private fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun writeUInt24LE(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }
}
