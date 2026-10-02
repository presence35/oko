package com.odesaplay.oko

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

    @Test
    fun `isTrustedApkUrl accepts only the configured https origin`() {
        assertTrue(UpdateManager.isTrustedApkUrl("https://odesaplay.com.ua/other_apps/oko/app-release.apk"))
        assertTrue(UpdateManager.isTrustedApkUrl("https://ODESAPLAY.COM.UA/other_apps/oko/app-release.apk"))

        // A tampered version.json must not get to choose where the APK comes from — including
        // suffix and userinfo look-alikes.
        assertFalse(UpdateManager.isTrustedApkUrl("https://evil.example/app-release.apk"))
        assertFalse(UpdateManager.isTrustedApkUrl("https://odesaplay.com.ua.evil.example/app-release.apk"))
        assertFalse(UpdateManager.isTrustedApkUrl("https://odesaplay.com.ua@evil.example/app-release.apk"))

        // Scheme and port are part of the origin.
        assertFalse(UpdateManager.isTrustedApkUrl("http://odesaplay.com.ua/app-release.apk"))
        assertFalse(UpdateManager.isTrustedApkUrl("https://odesaplay.com.ua:8443/app-release.apk"))
        assertFalse(UpdateManager.isTrustedApkUrl("not a url"))
    }

    @Test
    fun `sha256Matches requires an exact digest`() {
        assertTrue(UpdateManager.sha256Matches(" ABC123 ", "abc123"))
        assertFalse(UpdateManager.sha256Matches("abc123", "abc124"))
    }

    @Test
    fun `signersMatch requires readable, identical signer sets`() {
        val signers = setOf("aa", "bb")
        assertTrue(UpdateManager.signersMatch(signers, setOf("aa", "bb")))
        assertFalse(UpdateManager.signersMatch(signers, setOf("aa")))
        assertFalse(UpdateManager.signersMatch(signers, setOf("aa", "cc")))
        // Unreadable signers are disqualifying, never a free pass.
        assertFalse(UpdateManager.signersMatch(null, signers))
        assertFalse(UpdateManager.signersMatch(signers, null))
        assertFalse(UpdateManager.signersMatch(emptySet(), emptySet()))
    }
}