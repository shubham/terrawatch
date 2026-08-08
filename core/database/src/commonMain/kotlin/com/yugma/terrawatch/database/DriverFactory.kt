package com.yugma.terrawatch.database

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DriverFactory): TerraWatchDb = TerraWatchDb(driverFactory.createDriver())
