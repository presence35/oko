# Normalized shelter schema

Every source (Esri JSON, CSV, XLSX) gets mapped into this shape before it's written out.

```json
{
  "id": "kyiv-00042",
  "city": "Kyiv",
  "oblast": "Kyiv",
  "source": "kyiv-arcgis",
  "source_id": "42",
  "lat": 50.4501,
  "lng": 30.5234,
  "address": "вул. Хрещатик, 1",
  "name": null,
  "type": "basement",
  "capacity": null,
  "owner": "КП ЖЕК-123",
  "updated_at": "2026-03-04"
}
```

- `id` — stable, namespaced by source, so re-running the ETL doesn't create dupes
- `type` — normalize into a small fixed enum: `basement | underground_parking |
  metro | pru | bunker | other` — every source calls these something different,
  this is the one field you'll actually filter/render on in the app (`bunker`
  is a hardened shelter/Сховище, distinct from radiation-proof `pru`)
- `capacity` / `name` — nullable, most sources don't have them
- `updated_at` — from the dataset's own "last updated," not scrape time; lets
  you show staleness per-source in the app if you want
