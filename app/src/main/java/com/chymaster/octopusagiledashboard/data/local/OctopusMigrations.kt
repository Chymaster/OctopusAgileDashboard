package com.chymaster.octopusagiledashboard.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations.
 *
 * The caches are derived data, but the database also holds the user's cached
 * consumption history — the only offline copy — so schema changes are migrated
 * rather than dropped. `fallbackToDestructiveMigration` remains as a last
 * resort for paths with no migration (e.g. a downgrade).
 */
object OctopusMigrations {

    /**
     * v2 → v3: `agile_prices` and `standing_charges` gain a composite primary
     * key `(validFrom, tariffCode)` so several tariffs can be cached at once
     * (the user's tariff history). Existing rows are preserved; their
     * `tariffCode` values are already valid keys.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rebuildWithCompositeKey(db, "agile_prices", "priceExcVat", "priceIncVat")
            rebuildWithCompositeKey(db, "standing_charges", "valueExcVat", "valueIncVat")
        }
    }

    private fun rebuildWithCompositeKey(
        db: SupportSQLiteDatabase,
        table: String,
        excVatColumn: String,
        incVatColumn: String
    ) {
        val temp = "${table}_new"
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$temp` (
                `validFrom` INTEGER NOT NULL,
                `validTo` INTEGER NOT NULL,
                `$excVatColumn` REAL NOT NULL,
                `$incVatColumn` REAL NOT NULL,
                `tariffCode` TEXT NOT NULL,
                PRIMARY KEY(`validFrom`, `tariffCode`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO `$temp`
                (`validFrom`, `validTo`, `$excVatColumn`, `$incVatColumn`, `tariffCode`)
            SELECT `validFrom`, `validTo`, `$excVatColumn`, `$incVatColumn`, `tariffCode`
            FROM `$table`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `$table`")
        db.execSQL("ALTER TABLE `$temp` RENAME TO `$table`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_${table}_validFrom_validTo` " +
                "ON `$table` (`validFrom`, `validTo`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_${table}_tariffCode` " +
                "ON `$table` (`tariffCode`)"
        )
    }
}
