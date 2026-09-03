package ua.ukrainedrones

data class Exhaust(
    val anchorXFrac: Float,
    val anchorYFrac: Float,
    val angleBiasDeg: Float = 0f
)

data class AviationGeometry(
    val facingDeg: Float,
    val exhausts: List<Exhaust>
)

data class IconGeometry(
    val facingDeg: Float,
    val anchorXFrac: Float,
    val anchorYFrac: Float
)
