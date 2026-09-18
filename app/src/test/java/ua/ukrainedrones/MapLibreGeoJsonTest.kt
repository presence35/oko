@Test
fun `alertRegions - raion still fills when parent oblast id present but resolves to nothing`() {
    // Simulate the Donetsk case: oblast id is in the set (e.g. NEPTUN sent a whole-oblast
    // alert alongside the raion ones) but its boundary polygon can't produce a fill.
    val geoJson = MapLibreGeoJson.alertRegions(
        oblastIds = setOf("__unresolvable_oblast_id__"),
        raionKeys = setOf("__unresolvable_oblast_id__" to "bakhmutskyi")
    )
    assertTrue(geoJson.contains("\"type\":\"Polygon\""))
}