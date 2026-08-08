package com.yugma.terrawatch.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val dir = File(System.getProperty("user.home"), ".terrawatch").apply { mkdirs() }
        val dbFile = File(dir, "terrawatch.db")
        val isNew = !dbFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (isNew) TerraWatchDb.Schema.create(driver)
        return driver
    }
}
