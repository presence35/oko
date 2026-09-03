package ua.ukrainedrones

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** Resolves the walk-time figure icons. The adult is randomly a man or a woman so the UI
 *  feels inclusive; when [withKids] is on the child is drawn to the right of the adult.
 *  The adult is always on the left. */
@Composable
fun walkIconPair(withKids: Boolean): Pair<Int, Int?> {
    val man = remember { Random.nextBoolean() }
    val adult = if (man) R.drawable.ic_walk_man else R.drawable.ic_walk_woman
    val child = if (withKids) R.drawable.ic_walk_child else null
    return adult to child
}

/** True width:height of each silhouette (its SVG viewBox), so the figure is never squashed
 *  and the child reads as shorter than the adult. */
private fun walkAspect(resId: Int): Float = when (resId) {
    R.drawable.ic_walk_man -> 165f / 373f
    R.drawable.ic_walk_woman -> 185f / 380f
    R.drawable.ic_walk_child -> 125f / 212f
    else -> 1f
}

/** Walk figure sized by [height] with its natural aspect ratio preserved. */
@Composable
fun WalkFigureIcon(resId: Int, height: Dp, tint: Color, modifier: Modifier = Modifier) {
    val width = height * walkAspect(resId)
    Icon(
        painter = painterResource(resId),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(width, height)
    )
}