package com.pam.recap.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1 → v2 upgrade contract (CR-008 / SRS v0.7 §3).
 *
 * The budget module left the product, but the upgrade must not destroy stored rows. This test
 * builds a real v1 database from the committed v1 schema artifact, fills it with transactions, a
 * category, app settings and budget rows, then opens it through Room v2 so the production
 * [MIGRATION_1_2] runs. It proves the declared tables still open, the legacy budget tables keep
 * every row, and Room itself can serve queries after the migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PamDatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `v1 to v2 upgrade keeps legacy budget rows and the rest of the data`() {
        val v1 = readSchema(version = 1)
        createV1Database(v1)

        val db = Room.databaseBuilder(context, PamDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening writable runs the migration through Room's upgrade path.
            val open = db.openHelper.writableDatabase
            assertEquals(2, open.version)

            // Declared data is untouched: transaction row, its category, and app settings.
            open.query("SELECT COUNT(*) FROM transactions").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            open.query("SELECT category, amount FROM transactions").use { cursor ->
                cursor.moveToFirst()
                assertEquals("food", cursor.getString(0))
                assertEquals(16_000L, cursor.getLong(1))
            }
            open.query("SELECT value FROM app_metadata WHERE `key` = 'settings.locale'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("id", cursor.getString(0))
            }

            // The retired tables are orphaned but complete: no row was deleted by the upgrade.
            open.query("SELECT `id`, expectedIncome, valueOriginsJson FROM budget_plans").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("plan_legacy", cursor.getString(0))
                assertEquals(2_500_000L, cursor.getLong(1))
                assertTrue(cursor.getString(2).contains("OWNER_INPUT"))
            }
            open.query("SELECT planId, categoryCode, amount FROM budget_limits ORDER BY categoryCode").use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToFirst()
                assertEquals("plan_legacy", cursor.getString(0))
                assertEquals("food", cursor.getString(1))
                assertEquals(1_500_000L, cursor.getLong(2))
                cursor.moveToNext()
                assertEquals("transport", cursor.getString(1))
                assertEquals(1_000_000L, cursor.getLong(2))
            }

            // Room can serve fresh reads after the upgrade, not just open the file.
            val rows = runBlocking {
                db.transactionDao().listRange(
                    ownerId = OWNER_ID,
                    startMillis = 0L,
                    endMillis = Long.MAX_VALUE,
                )
            }
            assertEquals(1, rows.size)
            assertEquals("food", rows.single().categoryCode.code)
            assertEquals("id", runBlocking { db.appMetadataDao().value("settings.locale") })
        } finally {
            db.close()
        }
    }

    private fun createV1Database(schema: JSONObject) {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            raw.beginTransaction()
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (indexIndex in 0 until indices.length()) {
                    val sql = indices.getJSONObject(indexIndex).getString("createSql")
                    raw.execSQL(sql.replace("\${TABLE_NAME}", table))
                }
            }
            raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            raw.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${schema.getString("identityHash")}')",
            )

            raw.execSQL(
                "INSERT INTO transactions (id, ownerId, direction, source, amount, currency, eventAt, " +
                    "receivedAt, description, merchantLabel, acquirer, status, fingerprint, parserVersion, " +
                    "parserConfidence, category, categoryOrigin, categoryConfidence, categoryUpdatedAt, " +
                    "isInternalTransfer, linkedRefundOf, note, importJobId, revision, createdAt, updatedAt) " +
                    "VALUES ('tx_legacy', '$OWNER_ID', 'DEBIT', 'EMAIL', 16000, 'IDR', 1700000000000, " +
                    "1700000001000, 'Makan siang', NULL, NULL, 'POSTED', 'fp_legacy', 'v1', 0.98, 'food', " +
                    "'USER', 1.0, NULL, 0, NULL, 'catatan', NULL, 1, 1700000000000, 1700000000000)",
            )
            raw.execSQL(
                "INSERT INTO app_metadata (`key`, value, updatedAtEpochMillis) " +
                    "VALUES ('settings.locale', 'id', 1700000000000)",
            )
            raw.execSQL(
                "INSERT INTO budget_plans (id, ownerId, monthId, expectedIncome, plannedSavings, gap, " +
                    "revision, valueOriginsJson, createdAt, updatedAt) " +
                    "VALUES ('plan_legacy', '$OWNER_ID', '2026-11', 2500000, 500000, 300000, 1, " +
                    "'{\"expectedIncome\":\"OWNER_INPUT\"}', 1700000000000, 1700000000000)",
            )
            raw.execSQL(
                "INSERT INTO budget_limits (planId, categoryCode, amount) VALUES " +
                    "('plan_legacy', 'food', 1500000), ('plan_legacy', 'transport', 1000000)",
            )
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
            raw.close()
        }
    }

    /** Reads the committed Room schema artifact next to the module (never a hand-copied schema). */
    private fun readSchema(version: Int): JSONObject {
        val relative = "schemas/com.pam.recap.data.local.PamDatabase/$version.json"
        val candidates = listOf(
            File(relative),
            File(System.getProperty("user.dir") ?: ".", relative),
            File("app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Room schema artifact $relative not found from ${File(".").absolutePath}")
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private companion object {
        const val DB_NAME = "pam-migration-test.db"
        const val OWNER_ID = "u_owner"
    }
}
