package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import eu.kanade.tachiyomi.util.system.ImageUtil
import okio.BufferedSource
import tachiyomi.decoder.ImageDecoder

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    override suspend fun decode(): DecodeResult {
        val decoder = resources.sourceOrNull()?.use {
            ImageDecoder.newInstance(it.inputStream(), options.cropBorders, displayProfile)
        }

        check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder." }

        val srcWidth = decoder.width
        val srcHeight = decoder.height

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        ).let { calculated ->
            if (isLowMemory() && srcWidth > 0 && srcHeight / srcWidth > 3) maxOf(calculated, 2) else calculated
        }

        val decodedBitmap = try {
            decoder.decode(sampleSize = sampleSize)
        } finally {
            decoder.recycle()
        }
        var bitmap = decodedBitmap ?: error("Failed to decode image.")

        if (
            options.bitmapConfig == Bitmap.Config.RGB_565 &&
            bitmap.config != Bitmap.Config.RGB_565 &&
            hasMemoryForBitmapConversion(bitmap)
        ) {
            bitmap.copy(Bitmap.Config.RGB_565, false)?.let { converted ->
                bitmap.recycle()
                bitmap = converted
            }
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            options.bitmapConfig == Bitmap.Config.HARDWARE &&
            !ImageUtil.isHardwareThresholdExceeded(bitmap)
        ) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    private fun isLowMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - runtime.totalMemory() + runtime.freeMemory()
        return maxMemory > 0 && availableMemory < maxMemory * LOW_MEMORY_THRESHOLD
    }

    private fun hasMemoryForBitmapConversion(bitmap: Bitmap): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - runtime.totalMemory() + runtime.freeMemory()
        val requiredMemory = bitmap.byteCount / 2L + BITMAP_CONVERSION_HEADROOM_BYTES
        return maxMemory > 0 && availableMemory >= requiredMemory
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (isApplicable(result.source.source()) || options.customDecoder) return TachiyomiImageDecoder(result.source, options)
            return null
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null
    }
}

private const val LOW_MEMORY_THRESHOLD = 0.15
private const val BITMAP_CONVERSION_HEADROOM_BYTES = 8L * 1024 * 1024
