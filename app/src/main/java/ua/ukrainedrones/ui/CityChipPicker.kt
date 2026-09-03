package ua.ukrainedrones

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The major-city chip grid shared by the first-launch wizard and Settings' pin-city row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CityChipGrid(
    lang: AppLanguage,
    selected: City?,
    enabled: Boolean = true,
    onChange: (City?) -> Unit,
    modifier: Modifier = Modifier
) {
    val cities = remember(lang) {
        Cities.ALL.filter { it.major }
            .sortedBy { if (lang == AppLanguage.UA) it.nameUa else it.nameEn }
    }
    val label: (City) -> String = { c -> if (lang == AppLanguage.UA) c.nameUa else c.nameEn }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cities.forEach { city ->
            val on = selected == city && enabled
            val chipInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            on -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    )
                    .pressTick(chipInteraction)
                    .clickable(
                        interactionSource = chipInteraction,
                        indication = ripple(bounded = true),
                        enabled = enabled
                    ) { onChange(city) }
                    .padding(horizontal = 12.dp)
                    .then(if (enabled) Modifier else Modifier.alpha(0.5f))
            ) {
                Text(
                    label(city),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
