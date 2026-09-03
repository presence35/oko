package ua.ukrainedrones

import android.content.Context
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import ua.ukrainedrones.UA_WIDE_MIN_LAT
import ua.ukrainedrones.UA_WIDE_MAX_LAT
import ua.ukrainedrones.UA_WIDE_MIN_LON
import ua.ukrainedrones.UA_WIDE_MAX_LON
import org.osmdroid.util.TileSystemWebMercator

// Ukraine plus a ~2 degree margin so bordering areas still get a base map.
private val UA_MIN_LAT = UA_WIDE_MIN_LAT
private val UA_MAX_LAT = UA_WIDE_MAX_LAT
private val UA_MIN_LON = UA_WIDE_MIN_LON
private val UA_MAX_LON = UA_WIDE_MAX_LON

private val tileSystem = TileSystemWebMercator()

private fun insideUkraine(packedTile: Long): Boolean {
    val x = MapTileIndex.getX(packedTile)
    val y = MapTileIndex.getY(packedTile)
    val zoom = MapTileIndex.getZoom(packedTile)
    val north = tileSystem.getLatitudeFromTileY(y, zoom)
    val south = tileSystem.getLatitudeFromTileY(y + 1, zoom)
    val west = tileSystem.getLongitudeFromTileX(x, zoom)
    val east = tileSystem.getLongitudeFromTileX(x + 1, zoom)
    return north >= UA_MIN_LAT && south <= UA_MAX_LAT &&
        west <= UA_MAX_LON && east >= UA_MIN_LON
}

/** Refuses to download (and so never caches) tiles whose bounds miss Ukraine. */
private class UkraineTileDownloader : TileDownloader() {
    override fun downloadTile(
        pTileIndex: Long,
        pSize: Int,
        pURL: String,
        pFilesystemCache: IFilesystemCache,
        pSource: OnlineTileSourceBase
    ): Drawable? {
        if (!insideUkraine(pTileIndex)) return null
        return super.downloadTile(pTileIndex, pSize, pURL, pFilesystemCache, pSource)
    }
}

/** Map tile provider that only fetches map tiles intersecting Ukraine (plus margin). */
class UkraineTileProvider(context: Context) : MapTileProviderBasic(context, DARK_TILE_SOURCE) {

    override fun createDownloaderProvider(
        pNetworkAvailablityCheck: INetworkAvailablityCheck,
        pTileSource: ITileSource
    ): MapTileDownloader = MapTileDownloader(pTileSource, tileWriter, pNetworkAvailablityCheck).apply {
        setTileDownloader(UkraineTileDownloader())
    }
}