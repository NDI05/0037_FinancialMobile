package com.pam.recap.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v1 → v2: drop the retired budget plan/limit tables (CR-008).
 *
 * Budget planning is no longer a product path, so its two local tables are removed instead of being
 * left behind as dead schema. Other tables are untouched; migrations stay append-only, and the
 * v1 artifact under `app/schemas` is never edited (a v2 artifact is exported alongside it).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `budget_limits`")
        db.execSQL("DROP TABLE IF EXISTS `budget_plans`")
    }
}
