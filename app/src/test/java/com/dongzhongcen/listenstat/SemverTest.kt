package com.dongzhongcen.listenstat

import com.dongzhongcen.listenstat.update.Semver
import org.junit.Assert.assertTrue
import org.junit.Test

class SemverTest {
    @Test fun ordering() {
        assertTrue(Semver.compare("0.2.1", "0.2.0") > 0)
        assertTrue(Semver.compare("v0.3.0", "0.2.9") > 0)
        assertTrue(Semver.compare("0.10.0", "0.9.0") > 0)
        assertTrue(Semver.compare("1.0", "1.0.0") == 0)
        assertTrue(Semver.compare("0.2.0", "0.2.0-test") > 0)
        assertTrue(Semver.compare("0.2.0-test", "0.2.0") < 0)
        assertTrue(Semver.compare("0.2.0", "0.2.0") == 0)
        assertTrue(Semver.compare("0.1.1", "0.2.0") < 0)
    }
}
