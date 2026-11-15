package eu.kanade.tachiyomi.ui.crash

import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.extension.util.ExtensionCrashGuard
import eu.kanade.tachiyomi.util.system.e
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

class GlobalExceptionHandler private constructor(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler,
    private val activityToBeLaunched: Class<*>,
) : Thread.UncaughtExceptionHandler {

    object ThrowableSerializer : KSerializer<Throwable> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Throwable", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Throwable =
            Throwable(message = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Throwable) =
            encoder.encodeString(value.stackTraceToString())
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            try {
                writeCrashSummary(applicationContext, exception)
            } catch (e: Exception) {
                Logger.e(e) { "Unable to write crash summary" }
            }
            try {
                ExtensionCrashGuard.quarantineFromCrash(applicationContext, exception)
                if (ExtensionCrashGuard.shouldClearImageCache(exception)) {
                    ExtensionCrashGuard.requestImageCacheClear(applicationContext)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Unable to record crash recovery state" }
            }
            Logger.e(exception)
            launchActivity(applicationContext, activityToBeLaunched, exception)
            exitProcess(0)
        } catch (_: Throwable) {
            defaultHandler.uncaughtException(thread, exception)
        }
    }

    private fun launchActivity(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, exception))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    private fun writeCrashSummary(context: Context, exception: Throwable) {
        var rootCause = exception
        val seen = mutableSetOf<Throwable>()
        while (rootCause.cause != null && seen.add(rootCause)) {
            rootCause = rootCause.cause!!
        }
        val message = rootCause.message?.replace('\n', ' ')?.take(500)
        val frame = exception.stackTrace.firstOrNull()
        val summary = buildString {
            append(rootCause.javaClass.simpleName)
            if (!message.isNullOrBlank()) append(": ").append(message)
            if (frame != null) {
                append("\nAt ")
                append(frame.className).append('.').append(frame.methodName)
                append(':').append(frame.lineNumber)
            }
        }.take(MAX_CRASH_SUMMARY_LENGTH)
        File(context.filesDir, CRASH_SUMMARY_FILE).writeText(summary)
    }

    companion object {
        private const val INTENT_EXTRA = "Throwable"
        private const val CRASH_SUMMARY_FILE = "last_crash_summary.txt"
        private const val MAX_CRASH_SUMMARY_LENGTH = 2_048

        fun initialize(
            applicationContext: Context,
            activityToBeLaunched: Class<*>,
        ) {
            val handler = GlobalExceptionHandler(
                applicationContext,
                Thread.getDefaultUncaughtExceptionHandler() as Thread.UncaughtExceptionHandler,
                activityToBeLaunched,
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        fun getThrowableFromIntent(intent: Intent): Throwable? {
            return try {
                Json.decodeFromString(ThrowableSerializer, intent.getStringExtra(INTENT_EXTRA)!!)
            } catch (e: Exception) {
                Logger.e(e) { "Wasn't able to retrieve throwable from intent" }
                null
            }
        }
    }
}
