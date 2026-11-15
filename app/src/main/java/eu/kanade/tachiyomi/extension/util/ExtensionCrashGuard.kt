package eu.kanade.tachiyomi.extension.util

import android.content.Context
import java.util.Collections
import java.util.IdentityHashMap

internal object ExtensionCrashGuard {

    const val PREFERENCES_NAME = "crash_guard"
    const val IMAGE_CACHE_DIRECTORY = "image_cache"

    private const val KEY_QUARANTINED_PACKAGES = "quarantined_packages"
    private const val KEY_PENDING_NOTICE = "pending_notice"
    private const val KEY_CLEAR_IMAGE_CACHE = "clear_image_cache"

    fun quarantineFromCrash(context: Context, exception: Throwable): String? {
        val classNames = causeChain(exception).flatMap { throwable ->
            throwable.stackTrace.map { it.className }
        }
        val packageName = ExtensionLoader.getExtensionPackageNames(context)
            .asSequence()
            .filter { packageName -> classNames.any { it.startsWith("$packageName.") } }
            .maxByOrNull(String::length)
            ?: return null

        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val quarantined = preferences.getStringSet(KEY_QUARANTINED_PACKAGES, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(packageName) }
        val saved = preferences.edit()
            .putStringSet(KEY_QUARANTINED_PACKAGES, quarantined)
            .putString(KEY_PENDING_NOTICE, packageName)
            .commit()
        return packageName.takeIf { saved }
    }

    fun isQuarantined(context: Context, packageName: String): Boolean =
        packageName in quarantinedPackages(context)

    fun clearQuarantine(context: Context, packageName: String) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val quarantined = preferences.getStringSet(KEY_QUARANTINED_PACKAGES, emptySet())
            .orEmpty()
            .toMutableSet()
        if (quarantined.remove(packageName)) {
            preferences.edit().putStringSet(KEY_QUARANTINED_PACKAGES, quarantined).apply()
        }
    }

    fun consumePendingNotice(context: Context): String? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val packageName = preferences.getString(KEY_PENDING_NOTICE, null) ?: return null
        preferences.edit().remove(KEY_PENDING_NOTICE).apply()
        return packageName
    }

    fun shouldClearImageCache(exception: Throwable): Boolean {
        val chain = causeChain(exception)
        if (chain.any { it is OutOfMemoryError }) return true

        val cacheFailure = chain.any { throwable ->
            throwable.message?.contains("corrupt", ignoreCase = true) == true ||
                throwable.message?.contains("journal", ignoreCase = true) == true
        }
        val diskLruCacheFrame = chain.any { throwable ->
            throwable.stackTrace.any { it.className.contains("DiskLruCache") }
        }
        return cacheFailure && diskLruCacheFrame
    }

    fun requestImageCacheClear(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CLEAR_IMAGE_CACHE, true)
            .commit()
    }

    fun imageCacheClearRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLEAR_IMAGE_CACHE, false)

    fun clearImageCacheRequest(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CLEAR_IMAGE_CACHE)
            .apply()
    }

    private fun quarantinedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_QUARANTINED_PACKAGES, emptySet())
            .orEmpty()

    private fun causeChain(exception: Throwable): List<Throwable> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Throwable>()
        var current: Throwable? = exception
        while (current != null && visited.add(current)) {
            result += current
            current = current.cause
        }
        return result
    }
}
