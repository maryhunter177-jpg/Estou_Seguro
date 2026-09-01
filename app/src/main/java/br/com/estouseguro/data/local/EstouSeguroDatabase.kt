package br.com.estouseguro.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EstouSeguroDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trusted_contact (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL CHECK(length(name) BETWEEN 2 AND 80),
                phone TEXT NOT NULL UNIQUE CHECK(length(phone) BETWEEN 8 AND 16),
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE safety_alert (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                latitude REAL,
                longitude REAL,
                location_captured_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_alert_created_at ON safety_alert(created_at DESC)")
        createSmsDeliveryTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSmsDeliveryTable(db)
        check(newVersion == DATABASE_VERSION) { "Migration $oldVersion -> $newVersion not implemented" }
    }

    private fun createSmsDeliveryTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sms_delivery_attempt (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                alert_id INTEGER NOT NULL,
                recipient TEXT NOT NULL,
                part_index INTEGER NOT NULL,
                part_count INTEGER NOT NULL,
                subscription_id INTEGER,
                status TEXT NOT NULL,
                platform_result_code INTEGER,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(alert_id) REFERENCES safety_alert(id) ON DELETE CASCADE,
                UNIQUE(alert_id, recipient, part_index)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_sms_delivery_alert ON sms_delivery_attempt(alert_id, recipient)",
        )
    }

    companion object {
        private const val DATABASE_NAME = "estou_seguro.db"
        private const val DATABASE_VERSION = 2
    }
}
