package com.yugma.terrawatch.database

import app.cash.sqldelight.db.SqlDriver

// Web storage lands in Plan 3 (worker driver needs async codegen).
// v1 web runs on the in-memory path provided there; this actual keeps the target compiling.
actual class DriverFactory {
    actual fun createDriver(): SqlDriver =
        throw NotImplementedError("Web persistence arrives in Plan 3; wire in-memory repository fallback for wasm.")
}
