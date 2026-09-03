package ua.ukrainedrones

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `versionNameGreater compares patch bumps`() {
        assertTrue(UpdateManager.versionNameGreater("0.3.9", "0.3.8"))
        assertFalse(UpdateManager.versionNameGreater("0.3.8", "0.3.9"))
        assertFalse(UpdateManager.versionNameGreater("0.3.8", "0.3.8"))
    }

    @Test
    fun `versionNameGreater compares across segments`() {
        assertTrue(UpdateManager.versionNameGreater("0.4.0", "0.3.99"))
        assertTrue(UpdateManager.versionNameGreater("1.0.0", "0.9.9"))
        assertFalse(UpdateManager.versionNameGreater("0.10.0", "0.11.0"))
    }

    @Test
    fun `versionNameGreater handles different lengths`() {
        assertTrue(UpdateManager.versionNameGreater("1.2.3", "1.2"))
        assertFalse(UpdateManager.versionNameGreater("1.2", "1.2.3"))
    }

    @Test
    fun `versionNameGreater handles unparseable input`() {
        assertFalse(UpdateManager.versionNameGreater("beta", "0.3.8"))
        assertFalse(UpdateManager.versionNameGreater("", ""))
        // An unparseable "installed" has no segments, so any numeric candidate is newer.
        assertTrue(UpdateManager.versionNameGreater("0.3.8", "beta"))
    }
}