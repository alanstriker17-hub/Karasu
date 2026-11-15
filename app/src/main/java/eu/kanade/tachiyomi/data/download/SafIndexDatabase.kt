package eu.kanade.tachiyomi.data.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import karasu.domain.storage.StoragePreferences

internal data class SafIndexedChapter(
    val mangaId: Long,
    val sourceId: Long,
    val parentUri: String,
    val uri: String,
)

internal data class SafIndexedDocument(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val uri: String,
)

internal class SafIndexDatabase(
    context: Context,
    private val storagePreferences: StoragePreferences,
) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    private val appContext = context.applicationContext
    private val chapterCache = ConcurrentHashMap<Long, SafIndexedChapter>()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $CHAPTERS_TABLE (
                chapter_id INTEGER PRIMARY KEY,
                manga_id INTEGER NOT NULL,
                source_id INTEGER NOT NULL,
                parent_uri TEXT NOT NULL,
                uri TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX saf_chapter_manga_idx ON $CHAPTERS_TABLE(manga_id)")
        db.execSQL(
            """
            CREATE TABLE $DIRECTORY_SCANS_TABLE (
                parent_uri TEXT PRIMARY KEY,
                scanned_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $DIRECTORY_ENTRIES_TABLE (
                parent_uri TEXT NOT NULL,
                document_id TEXT NOT NULL,
                name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size INTEGER NOT NULL,
                last_modified INTEGER NOT NULL,
                uri TEXT NOT NULL,
                PRIMARY KEY(parent_uri, document_id)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun findChapter(chapterId: Long, mangaId: Long): SafIndexedChapter? {
        return chapterCache[chapterId]?.takeIf { it.mangaId == mangaId }
    }

    fun evictChapter(chapterId: Long) {
        chapterCache.remove(chapterId)
    }

    suspend fun warmChapterCache() = withContext(Dispatchers.IO) {
        readableDatabase.query(
            CHAPTERS_TABLE,
            arrayOf("chapter_id", "manga_id", "source_id", "parent_uri", "uri"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                chapterCache[cursor.getLong(0)] = SafIndexedChapter(
                    mangaId = cursor.getLong(1),
                    sourceId = cursor.getLong(2),
                    parentUri = cursor.getString(3),
                    uri = cursor.getString(4),
                )
            }
        }
    }

    suspend fun upsertChapter(chapterId: Long, entry: SafIndexedChapter) = withContext(Dispatchers.IO) {
        val values = ContentValues(6).apply {
            put("chapter_id", chapterId)
            put("manga_id", entry.mangaId)
            put("source_id", entry.sourceId)
            put("parent_uri", entry.parentUri)
            put("uri", entry.uri)
            put("updated_at", System.currentTimeMillis())
        }
        val rowId = writableDatabase.insertWithOnConflict(
            CHAPTERS_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(rowId != -1L) { "Failed to write chapter $chapterId to the SAF index" }
        chapterCache[chapterId] = entry
        invalidateChildrenOnIo(entry.parentUri)
    }

    suspend fun removeChapters(chapterIds: Collection<Long>, mangaId: Long) = withContext(Dispatchers.IO) {
        if (chapterIds.isEmpty()) return@withContext

        val db = writableDatabase
        val parentUris = mutableSetOf<String>()
        db.beginTransaction()
        try {
            chapterIds.chunked(SQLITE_BIND_LIMIT).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                db.query(
                    CHAPTERS_TABLE,
                    arrayOf("parent_uri"),
                    "manga_id = ? AND chapter_id IN ($placeholders)",
                    arrayOf(mangaId.toString()) + batch.map { it.toString() },
                    null,
                    null,
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) parentUris += cursor.getString(0)
                }
                db.delete(
                    CHAPTERS_TABLE,
                    "manga_id = ? AND chapter_id IN ($placeholders)",
                    arrayOf(mangaId.toString()) + batch.map { it.toString() },
                )
                batch.forEach(chapterCache::remove)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        parentUris.forEach(::invalidateChildrenOnIo)
    }

    suspend fun removeManga(mangaId: Long) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val parentUris = mutableSetOf<String>()
        db.query(
            CHAPTERS_TABLE,
            arrayOf("parent_uri"),
            "manga_id = ?",
            arrayOf(mangaId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) parentUris += cursor.getString(0)
        }
        db.delete(CHAPTERS_TABLE, "manga_id = ?", arrayOf(mangaId.toString()))
        chapterCache.entries.forEach { (chapterId, entry) ->
            if (entry.mangaId == mangaId) chapterCache.remove(chapterId, entry)
        }
        parentUris.forEach(::invalidateChildrenOnIo)
    }

    suspend fun removeSource(sourceId: Long) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val parentUris = mutableSetOf<String>()
        db.query(
            CHAPTERS_TABLE,
            arrayOf("parent_uri"),
            "source_id = ?",
            arrayOf(sourceId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) parentUris += cursor.getString(0)
        }
        db.delete(CHAPTERS_TABLE, "source_id = ?", arrayOf(sourceId.toString()))
        chapterCache.entries.forEach { (chapterId, entry) ->
            if (entry.sourceId == sourceId) chapterCache.remove(chapterId, entry)
        }
        parentUris.forEach(::invalidateChildrenOnIo)
    }

    fun listChildren(parent: UniFile, refresh: Boolean = false): List<SafIndexedDocument>? {
        val treeUri = Uri.parse(storagePreferences.baseStorageDirectory().get())
        if (!DocumentsContract.isTreeUri(treeUri) || parent.uri.scheme != "content") return null

        val parentDocumentId = if (DocumentsContract.isDocumentUri(appContext, parent.uri)) {
            DocumentsContract.getDocumentId(parent.uri)
        } else {
            DocumentsContract.getTreeDocumentId(parent.uri)
        }
        val parentUri = parent.uri.toString()

        if (!refresh) {
            readDirectory(parentUri)?.takeIf { isFresh(it.scannedAt) }?.let {
                return it.documents
            }
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val documents = appContext.contentResolver.query(childrenUri, projection, null, null, null)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                buildList {
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(idColumn) ?: continue
                        val name = cursor.getString(nameColumn) ?: continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                        add(
                            SafIndexedDocument(
                                documentId = documentId,
                                name = name,
                                mimeType = cursor.getString(mimeColumn).orEmpty(),
                                size = sizeColumn.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong) ?: 0L,
                                lastModified = modifiedColumn.takeIf { it >= 0 && !cursor.isNull(it) }
                                    ?.let(cursor::getLong) ?: 0L,
                                uri = uri.toString(),
                            ),
                        )
                    }
                }
            } ?: throw IllegalStateException("SAF provider returned no cursor for $childrenUri")

        val snapshot = DirectorySnapshot(System.currentTimeMillis(), documents)
        writeDirectory(parentUri, snapshot)
        return documents
    }

    suspend fun invalidateChildren(parentUri: Uri?) = withContext(Dispatchers.IO) {
        if (parentUri == null) return@withContext
        invalidateChildrenOnIo(parentUri.toString())
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(CHAPTERS_TABLE, null, null)
            db.delete(DIRECTORY_SCANS_TABLE, null, null)
            db.delete(DIRECTORY_ENTRIES_TABLE, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        chapterCache.clear()
    }

    private fun invalidateChildrenOnIo(parentUri: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(DIRECTORY_SCANS_TABLE, "parent_uri = ?", arrayOf(parentUri))
            db.delete(DIRECTORY_ENTRIES_TABLE, "parent_uri = ?", arrayOf(parentUri))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readDirectory(parentUri: String): DirectorySnapshot? {
        val db = readableDatabase
        val scannedAt = db.query(
            DIRECTORY_SCANS_TABLE,
            arrayOf("scanned_at"),
            "parent_uri = ?",
            arrayOf(parentUri),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null } ?: return null

        val documents = db.query(
            DIRECTORY_ENTRIES_TABLE,
            arrayOf("document_id", "name", "mime_type", "size", "last_modified", "uri"),
            "parent_uri = ?",
            arrayOf(parentUri),
            null,
            null,
            "name COLLATE NOCASE",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SafIndexedDocument(
                            documentId = cursor.getString(0),
                            name = cursor.getString(1),
                            mimeType = cursor.getString(2),
                            size = cursor.getLong(3),
                            lastModified = cursor.getLong(4),
                            uri = cursor.getString(5),
                        ),
                    )
                }
            }
        }
        return DirectorySnapshot(scannedAt, documents)
    }

    private fun writeDirectory(parentUri: String, snapshot: DirectorySnapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(DIRECTORY_ENTRIES_TABLE, "parent_uri = ?", arrayOf(parentUri))
            db.delete(DIRECTORY_SCANS_TABLE, "parent_uri = ?", arrayOf(parentUri))
            db.insertOrThrow(
                DIRECTORY_SCANS_TABLE,
                null,
                ContentValues(2).apply {
                    put("parent_uri", parentUri)
                    put("scanned_at", snapshot.scannedAt)
                },
            )
            snapshot.documents.forEach { document ->
                db.insertOrThrow(
                    DIRECTORY_ENTRIES_TABLE,
                    null,
                    ContentValues(7).apply {
                        put("parent_uri", parentUri)
                        put("document_id", document.documentId)
                        put("name", document.name)
                        put("mime_type", document.mimeType)
                        put("size", document.size)
                        put("last_modified", document.lastModified)
                        put("uri", document.uri)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun isFresh(scannedAt: Long) = System.currentTimeMillis() - scannedAt < DIRECTORY_CACHE_TTL

    private data class DirectorySnapshot(
        val scannedAt: Long,
        val documents: List<SafIndexedDocument>,
    )

    private companion object {
        const val DATABASE_NAME = "saf_fast_index.db"
        const val DATABASE_VERSION = 1
        const val CHAPTERS_TABLE = "chapter_downloads"
        const val DIRECTORY_SCANS_TABLE = "directory_scans"
        const val DIRECTORY_ENTRIES_TABLE = "directory_entries"
        const val DIRECTORY_CACHE_TTL = 60L * 60 * 1000
        const val SQLITE_BIND_LIMIT = 900
    }
}
