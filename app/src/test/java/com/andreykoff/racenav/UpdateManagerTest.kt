package com.andreykoff.racenav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun versionCodeTakesPrecedenceOverVersionName() {
        assertTrue(
            UpdateManager.isUpdateAvailable(
                remoteVersion = "2.9.22",
                remoteVersionCode = 408,
                localVersion = "2.9.97",
                localVersionCode = 407
            )
        )

        assertFalse(
            UpdateManager.isUpdateAvailable(
                remoteVersion = "2.9.99",
                remoteVersionCode = 407,
                localVersion = "2.9.22",
                localVersionCode = 408
            )
        )
    }

    @Test
    fun fallsBackToVersionNameWhenVersionCodeIsMissing() {
        assertTrue(
            UpdateManager.isUpdateAvailable(
                remoteVersion = "2.9.98",
                remoteVersionCode = 0,
                localVersion = "2.9.97",
                localVersionCode = 409
            )
        )
    }
}
