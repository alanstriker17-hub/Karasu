package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.chapterOrder
import eu.kanade.tachiyomi.data.database.models.sortDescending
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import karasu.domain.library.LibraryPreferences
import kotlin.math.roundToInt
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ChapterSort(
    val manga: Manga,
    val chapterFilter: ChapterFilter = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    fun <T : Chapter> getChaptersSorted(
        rawChapters: List<T>,
        andFiltered: Boolean = true,
        filterForReader: Boolean = false,
        currentChapter: T? = null,
    ): List<T> {
        val chapters = when {
            filterForReader -> chapterFilter.filterChaptersForReader(
                rawChapters,
                manga,
                currentChapter,
            )
            andFiltered -> chapterFilter.filterChapters(rawChapters, manga)
            else -> rawChapters
        }

        return prioritizePreferredScanlators(chapters.withoutBlockedScanlators().sortedWith(sortComparator()))
    }

    fun <T : Chapter> getNextChapter(rawChapters: List<T>, andFiltered: Boolean = true): T? {
        val chapters = when {
            andFiltered -> chapterFilter.filterChapters(rawChapters, manga)
            else -> rawChapters
        }
        return prioritizePreferredScanlators(chapters.withoutBlockedScanlators().sortedWith(sortComparator(true))).firstOrNull()
    }

    fun <T : Chapter> getNextUnreadChapter(rawChapters: List<T>, andFiltered: Boolean = true): T? {
        val chapters = when {
            andFiltered -> chapterFilter.filterChapters(rawChapters, manga)
            else -> rawChapters
        }

        return prioritizePreferredScanlators(chapters.withoutBlockedScanlators().sortedWith(sortComparator(true)))
            .find { !it.read }
    }

    fun <T : Chapter> sortComparator(ignoreAsc: Boolean = false): Comparator<T> {
        val sortDescending = !ignoreAsc && manga.sortDescending(preferences)
        val sortFunction: (T, T) -> Int =
            when (manga.chapterOrder(preferences)) {
                Manga.CHAPTER_SORTING_SOURCE -> when (sortDescending) {
                    true -> { c1, c2 -> c1.source_order.compareTo(c2.source_order) }
                    false -> { c1, c2 -> c2.source_order.compareTo(c1.source_order) }
                }
                Manga.CHAPTER_SORTING_NUMBER -> when (sortDescending) {
                    true -> { c1, c2 -> c2.chapter_number.toString().compareToCaseInsensitiveNaturalOrder(c1.chapter_number.toString()) }
                    false -> { c1, c2 -> c1.chapter_number.toString().compareToCaseInsensitiveNaturalOrder(c2.chapter_number.toString()) }
                }
                Manga.CHAPTER_SORTING_UPLOAD_DATE -> when (sortDescending) {
                    true -> { c1, c2 -> c2.date_upload_or_fetch.compareTo(c1.date_upload_or_fetch) }
                    false -> { c1, c2 -> c1.date_upload_or_fetch.compareTo(c2.date_upload_or_fetch) }
                }
                else -> { c1, c2 -> c1.source_order.compareTo(c2.source_order) }
            }
        return Comparator(sortFunction)
    }

    fun <T : Chapter> prioritizePreferredScanlators(sortedChapters: List<T>): List<T> {
        val preferred = libraryPreferences.preferredScanlators().get()
            .map { it.trim().lowercase() }
            .filter(String::isNotBlank)
            .toSet()
        if (preferred.isEmpty()) return sortedChapters

        val groups = sortedChapters.indices.groupBy { chapterNumberKey(sortedChapters[it].chapter_number) }
        val duplicatePositions = groups.values.filter { it.size > 1 }
        if (duplicatePositions.isEmpty()) return sortedChapters

        val result = sortedChapters.toMutableList()
        duplicatePositions.forEach { positions ->
            val ordered = positions
                .map(sortedChapters::get)
                .sortedBy { scanlatorPreference(it.scanlator, preferred) }
            positions.zip(ordered).forEach { (position, chapter) -> result[position] = chapter }
        }
        return result
    }

    private fun <T : Chapter> List<T>.withoutBlockedScanlators(): List<T> {
        val blocked = libraryPreferences.blockedScanlators().get()
            .map { it.trim().lowercase() }
            .filter(String::isNotBlank)
            .toSet()
        if (blocked.isEmpty()) return this
        return filterNot { chapter ->
            chapter.scanlator.orEmpty()
                .split('&', ',', '/', '+', '|')
                .any { it.trim().lowercase() in blocked }
        }
    }

    private fun scanlatorPreference(scanlator: String?, preferred: Set<String>): Int {
        if (preferred.isEmpty()) return 1
        return if (
            scanlator.orEmpty()
                .split('&', ',', '/', '+', '|')
                .any { it.trim().lowercase() in preferred }
        ) 0 else 1
    }

    private fun chapterNumberKey(number: Float) = (number * 1000f).roundToInt()
}
