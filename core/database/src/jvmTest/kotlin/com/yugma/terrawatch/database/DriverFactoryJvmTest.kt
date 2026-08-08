package com.yugma.terrawatch.database

import kotlin.test.Test

class DriverFactoryJvmTest {
    @Test fun `creating driver twice against same file does not throw`() {
        val tmpHome = kotlin.io.path.createTempDirectory("twdb").toFile()
        val oldHome = System.getProperty("user.home")
        System.setProperty("user.home", tmpHome.absolutePath)
        try {
            DriverFactory().createDriver().close()
            DriverFactory().createDriver().close()   // <- crashes pre-fix
        } finally {
            System.setProperty("user.home", oldHome)
            tmpHome.deleteRecursively()
        }
    }
}
