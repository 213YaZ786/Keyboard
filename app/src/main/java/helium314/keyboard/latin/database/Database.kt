// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getStringOrNull
import androidx.core.database.sqlite.transaction
import helium314.keyboard.latin.utils.GestureDataDao
import helium314.keyboard.latin.utils.Log
import java.io.File

class Database private constructor(context: Context, name: String = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {
    // Keyboard: deleted clips (a password kept 20 s) are overwritten with zeros, not left in free pages:
    // SQLite's secure_delete (https://www.sqlite.org/pragma.html#pragma_secure_delete), set in onConfigure
    // (https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper#onConfigure(android.database.sqlite.SQLiteDatabase)).
    override fun onConfigure(db: SQLiteDatabase) {
        // The pragma answers with a row, so it is a query (execSQL refuses it): rawQuery, as
        // https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#rawQuery(java.lang.String,%20java.lang.String[])
        db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ClipboardDao.CREATE_TABLE)
        onUpgrade(db, 0, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion <= 1) {
            db.execSQL(GestureDataDao.CREATE_TABLE)
        }
        if (oldVersion <= 2) {
            db.execSQL(ClipboardDao.ADD_FILE_COLUMN)
            db.execSQL(ClipboardDao.ADD_MIME_TYPE_COLUMN)
        }
        if (oldVersion <= 3) { // Keyboard: sensitive clips
            db.execSQL(ClipboardDao.ADD_SENSITIVE_COLUMN)
        }
    }

    companion object {
        private val TAG = Database::class.java.simpleName
        private const val VERSION = 4 // Keyboard: 4 adds the clipboard's SENSITIVE column
        const val NAME = "heliboard.db"
        private var instance: Database? = null
        fun getInstance(context: Context): Database {
            if (instance == null)
                instance = Database(context)
            return instance!!
        }

        // needs to be in sync with db version
        fun copyFromDb(file: File, context: Context) {
            if (!file.exists())
                return
            val otherDb = Database(context, file.name) // this upgrades the DB if necessary
            val clipDao = ClipboardDao.getInstance(context) // insert to dao because of cache
            val db = getInstance(context)

            try {
                db.writableDatabase.transaction {
                    if (clipDao == null) {
                        Log.e(TAG, "can't transfer clipboard data because ClipboardDao is null")
                    } else {
                        otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TEXT, FILE, MIME_TYPE, SENSITIVE FROM CLIPBOARD", null)
                            .use {
                                clipDao.clear()
                                while (it.moveToNext()) {
                                    clipDao.insertNewEntry(
                                        it.getLong(0),
                                        it.getInt(1) != 0,
                                        it.getStringOrNull(2),
                                        it.getStringOrNull(3),
                                        it.getStringOrNull(4)?.split("§"),
                                        null,
                                        it.getInt(5) != 0
                                    )
                                }
                            }
                    }
                    db.writableDatabase.execSQL("DELETE FROM GESTURE_DATA")
                    otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, WORD, EXPORTED, SOURCE_ACTIVE, DATA FROM GESTURE_DATA", null)
                        .use { c ->
                            while (c.moveToNext()) {
                                execSQL("INSERT INTO GESTURE_DATA (TIMESTAMP, WORD, EXPORTED, SOURCE_ACTIVE, DATA) " +
                                    "VALUES (${c.getLong(0)},?,${c.getInt(2)},${c.getInt(3)},?)", arrayOf(c.getString(1), c.getString(4)))
                            }
                        }
                }
            } finally {
                otherDb.close()
                file.delete()
            }
        }
    }
}
