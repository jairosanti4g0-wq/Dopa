package com.dopa.cleaner.scan

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dopa.cleaner.core.MediaRef
import com.dopa.cleaner.core.NsfwScores

data class StoredItem(
    val ref: MediaRef,
    val sourceName: String,
    val canDelete: Boolean,
    val scores: NsfwScores,
    val flagged: Boolean,
    val dismissed: Boolean,
)

class ScanStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "scan.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE items (
                key TEXT PRIMARY KEY NOT NULL,
                source_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                size INTEGER,
                location TEXT NOT NULL,
                version TEXT,
                source_name TEXT NOT NULL,
                can_delete INTEGER NOT NULL,
                drawings REAL NOT NULL,
                hentai REAL NOT NULL,
                neutral REAL NOT NULL,
                porn REAL NOT NULL,
                sexy REAL NOT NULL,
                flagged INTEGER NOT NULL,
                dismissed INTEGER NOT NULL DEFAULT 0,
                run_id INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_items_source_run ON items(source_id, run_id)")
        db.execSQL("CREATE INDEX idx_items_flagged ON items(flagged, dismissed)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS items")
        onCreate(db)
    }

    @Synchronized
    fun get(key: String): StoredItem? =
        readableDatabase.query("items", null, "key = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.toItem() else null
        }

    @Synchronized
    fun upsert(ref: MediaRef, sourceName: String, canDelete: Boolean, scores: NsfwScores, flagged: Boolean, runId: Long) {
        val previous = get(ref.key)
        val keepDismissed = previous != null && previous.dismissed && previous.ref.version != null && previous.ref.version == ref.version
        val v = ContentValues().apply {
            put("key", ref.key)
            put("source_id", ref.sourceId)
            put("item_id", ref.itemId)
            put("name", ref.name)
            put("mime", ref.mimeType)
            if (ref.sizeBytes != null) put("size", ref.sizeBytes) else putNull("size")
            put("location", ref.location)
            if (ref.version != null) put("version", ref.version) else putNull("version")
            put("source_name", sourceName)
            put("can_delete", if (canDelete) 1 else 0)
            put("drawings", scores.drawings.toDouble())
            put("hentai", scores.hentai.toDouble())
            put("neutral", scores.neutral.toDouble())
            put("porn", scores.porn.toDouble())
            put("sexy", scores.sexy.toDouble())
            put("flagged", if (flagged) 1 else 0)
            put("dismissed", if (keepDismissed) 1 else 0)
            put("run_id", runId)
        }
        writableDatabase.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun touch(key: String, runId: Long, flagged: Boolean, sourceName: String, canDelete: Boolean) {
        val v = ContentValues().apply {
            put("run_id", runId)
            put("flagged", if (flagged) 1 else 0)
            put("source_name", sourceName)
            put("can_delete", if (canDelete) 1 else 0)
        }
        writableDatabase.update("items", v, "key = ?", arrayOf(key))
    }

    @Synchronized
    fun findings(): List<StoredItem> =
        readableDatabase.query("items", null, "flagged = 1 AND dismissed = 0", null, null, null, "porn + hentai + sexy DESC")
            .use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }

    @Synchronized
    fun dismiss(keys: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply { put("dismissed", 1) }
            keys.forEach { db.update("items", v, "key = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun remove(keys: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            keys.forEach { db.delete("items", "key = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun prune(sourceId: String, runId: Long): List<String> {
        val db = writableDatabase
        val stale = db.query("items", arrayOf("key"), "source_id = ? AND run_id <> ?", arrayOf(sourceId, runId.toString()), null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        if (stale.isNotEmpty()) db.delete("items", "source_id = ? AND run_id <> ?", arrayOf(sourceId, runId.toString()))
        return stale
    }

    @Synchronized
    fun count(): Long = readableDatabase.compileStatement("SELECT COUNT(*) FROM items").use { it.simpleQueryForLong() }

    @Synchronized
    fun clear() {
        writableDatabase.delete("items", null, null)
    }

    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col))
    private fun Cursor.strOrNull(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.flt(col: String): Float = getFloat(getColumnIndexOrThrow(col))
    private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))

    private fun Cursor.toItem(): StoredItem {
        val sizeIdx = getColumnIndexOrThrow("size")
        return StoredItem(
            ref = MediaRef(
                sourceId = str("source_id"),
                itemId = str("item_id"),
                name = str("name"),
                mimeType = str("mime"),
                sizeBytes = if (isNull(sizeIdx)) null else getLong(sizeIdx),
                location = str("location"),
                version = strOrNull("version"),
            ),
            sourceName = str("source_name"),
            canDelete = int("can_delete") == 1,
            scores = NsfwScores(flt("drawings"), flt("hentai"), flt("neutral"), flt("porn"), flt("sexy")),
            flagged = int("flagged") == 1,
            dismissed = int("dismissed") == 1,
        )
    }
}
