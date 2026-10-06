package com.odesaplay.oko

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneId

/**
 * Guards the Debug tab against the one failure it exists to prevent: a payload that grows a field
 * the screen never mentions, so the tester reads a reassuring list while something else ships.
 *
 * The coverage tests below are written against a **real** `LogBundle`, not a hand-built fixture —
 * a fixture would only ever prove the projection agrees with itself.
 */
@RunWith(RobolectricTestRunner::class)
class LogPreviewTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    /** `LogBundle` reads the source registry, which only the app's own entry points normally
     *  initialise. Done here so the bundle under test is the real one, registry block and all. */
    @Before
    fun setUp() {
        AppSources.init(context)
    }

    private val bundle: JSONObject by lazy { runBlocking { LogBundle.build(context, UserPrefs(context)) } }

    /**
     * Blocks the tab deliberately does not enumerate: dozens of pure numeric diagnostics with no
     * personal content. Naming them here IS the review step — a new top-level key fails the test
     * until someone decides which side of the line it belongs on, and moving a block into this set
     * is a visible edit rather than a silent omission.
     */
    private val unenumerated = setOf("battery", "cpu", "memory", "exitSummary")

    /** Shown in the tab's header rather than as a row of its own. */
    private val chrome = setOf("fileName")

    private fun rows() = LogPreview.project(bundle, ZoneId.of("UTC")).flatMap { it.rows }

    @Test
    fun `every top level key is either previewed or deliberately excluded`() {
        val previewed = rows().map { it.path.substringBefore('.') }.toSet()
        val unaccounted = bundle.keys().asSequence().toSet() - (previewed + unenumerated + chrome)
        assertEquals(emptySet<String>(), unaccounted)
    }

    @Test
    fun `enumerated blocks list every field they carry`() {
        val shown = rows().filter { it.path.contains('.') }
            .map { it.path.substringBefore('.') to it.path.substringAfter('.').substringBefore('.').removeSuffix("[]") }
            .toSet()
        listOf("device", "app", "sources", "logs").forEach { block ->
            val obj = bundle.optJSONObject(block)
            // A key present with JSON null was read but declined by the OS, so there is no value to
            // show and the projection omits it (the bundle's own absent-key convention). It is the
            // keys that DO carry a value that must be visible.
            val carried = obj?.keys()?.asSequence()?.filter { !obj.isNull(it) }?.toSet().orEmpty()
            val covered = shown.filter { it.first == block }.map { it.second }.toSet()
            assertEquals("$block fields not shown", emptySet<String>(), carried - covered)
        }
    }

    @Test
    fun `sections are one per id in enum order`() {
        assertEquals(PreviewSectionId.values().toList(), LogPreview.project(bundle).map { it.id })
    }

    /** The city the tester saved is the payload's sharpest personal datum: shown as stored, flagged. */
    @Test
    fun `the saved city is shown verbatim and flagged`() {
        val saved = bundle.optJSONObject("prefs")?.optString("pinned_city").orEmpty()
        val row = rows().firstOrNull { it.path == "prefs.pinned_city" }
        if (saved.isBlank()) {
            assertNull(row)
        } else {
            assertNotNull(row)
            assertTrue(row!!.sensitive)
            assertEquals(saved, row.value)
        }
    }

    /** Absent means "not readable on this device" and must not be rendered as a value at all. */
    @Test
    fun `a key the device did not report produces no row`() {
        val bare = JSONObject().put("device", JSONObject().put("brand", "samsung"))
        val paths = LogPreview.project(bare, ZoneId.of("UTC")).flatMap { it.rows }.map { it.path }
        assertEquals(listOf("device.brand"), paths)
    }

    /** Alert-city names are the one location signal in the payload, so they are listed, not counted. */
    @Test
    fun `alert localities are listed verbatim`() {
        val decisions = bundle.optJSONObject("logs")?.optJSONArray("decisions")
        val row = rows().firstOrNull { it.path == "logs.decisions[].locality" }
        val expected = (0 until (decisions?.length() ?: 0))
            .mapNotNull { decisions!!.optJSONObject(it)?.optString("locality") }
            .filter { it.isNotBlank() }
            .distinct()
        if (expected.isEmpty()) {
            assertNull(row)
        } else {
            assertNotNull(row)
            assertTrue(row!!.sensitive)
            assertTrue(row.value.startsWith(expected.first()))
        }
    }
}
