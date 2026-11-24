package com.pam.recap.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v1 → v2: the retired budget tables stay in place (CR-008).
 *
 * Budget planning is no longer a product path, so v2 stops declaring the two budget entities. The
 * upgrade is deliberately **non-destructive**: SRS v0.7 §3 forbids deleting stored data as part of
 * a cleanup, so `budget_plans`/`budget_limits` and every row the owner already had are left
 * untouched as orphaned legacy schema. The app never reads or writes them again; removing them
 * later is the owner's decision, not an automatic migration side effect.
 *
 * Room only validates the tables declared by the current schema, so an untouched legacy table does
 * not block the upgrade. Migrations stay append-only and the v1 artifact under `app/schemas` is
 * never edited (the v2 artifact is exported alongside it).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Intentionally empty: v2 drops the entities from the schema, not the user's stored rows.
    }
}
