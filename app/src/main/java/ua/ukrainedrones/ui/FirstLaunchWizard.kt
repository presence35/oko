package ua.ukrainedrones

import android.Manifest
import android.content.pm.PackageManager
import ua.ukrainedrones.BatteryOptimization
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription as semanticsContentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

private val UkraineBlue = Color(0xFF005BBB)
private val UkraineYellow = Color(0xFFFFD500)

@Composable
internal fun FirstLaunchWizard(
    current: AppLanguage,
    iconSet: ThreatIconSet,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    followMe: Boolean,
    pinnedCity: City?,
    slowRedKm: Int,
    slowYellowKm: Int,
    slowRedArmed: Boolean,
    slowYellowArmed: Boolean,
    fastRedArmed: Boolean,
    fastYellowArmed: Boolean,
    sheltersEnabled: Boolean,
    justFun: Boolean,
    calmMessagesEnabled: Boolean,
    flybyAnimationEnabled: Boolean,
    deathAnimationEnabled: Boolean,
    followBullet: Boolean,
    neutralizedTallyEnabled: Boolean,
    neutralizedTallyAllUkraine: Boolean,
    iconSetForFun: ThreatIconSet,
    onChoose: (AppLanguage) -> Unit,
    onThreatEnabledToggle: (ThreatType, Boolean) -> Unit,
    onFollowMeChange: (Boolean) -> Unit,
    onPinnedCityChange: (City?) -> Unit,
    onJustFunChange: (Boolean) -> Unit,
    onCalmMessagesChange: (Boolean) -> Unit,
    onFlybyAnimationChange: (Boolean) -> Unit,
    onDeathAnimationChange: (Boolean) -> Unit,
    onFollowBulletChange: (Boolean) -> Unit,
    onNeutralizedTallyChange: (Boolean) -> Unit,
    onNeutralizedTallyAllUkraineChange: (Boolean) -> Unit,
    onIconSetChangeForFun: (ThreatIconSet) -> Unit,
    onSlowRedChange: (Int) -> Unit,
    onSlowYellowChange: (Int) -> Unit,
    onSlowRedArmedChange: (Boolean) -> Unit,
    onSlowYellowArmedChange: (Boolean) -> Unit,
    onComplete: () -> Unit,
    onLater: () -> Unit
) {
    val s = Strings.get(current)
    val other = if (current == AppLanguage.UA) AppLanguage.EN else AppLanguage.UA
    val totalSteps = 5
    var step by remember { mutableStateOf(0) }
    var tipsRevealed by remember { mutableStateOf(false) }
    var locationMode by remember { mutableStateOf<String?>(null) }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val wizardContext = LocalContext.current
    LaunchedEffect(step) {
        if (step == 2) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(wizardContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    BackHandler(enabled = step > 0) { step-- }
    val stepTitle = when (step) {
        0 -> Strings.get(other).languageChooseTitle
        1 -> s.wizardCareTitle
        2 -> s.wizardLocationTitle
        3 -> s.wizardZonesTitle
        else -> s.onboardingFeaturesTitle
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stepTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onLater) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = s.languageChooseLater,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                when (step) {
                    0 -> {
                        SetupLanguageStep(current, onChoose)
                        Spacer(Modifier.height(24.dp))
                        Text(
                            s.onboardingIntro,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!tipsRevealed) {
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { tipsRevealed = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(s.okButton, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Spacer(Modifier.height(28.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(16.dp))
                            OnboardingTipRow(
                                iconRes = IconCatalog.photoRes(ThreatType.SHAHED) ?: R.drawable.ic_threat_shahed,
                                iconTint = Color.Unspecified,
                                text = s.onboardingTipTap
                            )
                            OnboardingTipRow(
                                iconRes = R.drawable.ic_settings_ua,
                                iconTint = Color.Unspecified,
                                text = s.onboardingTipSettings
                            )
                            OnboardingTipRow(
                                iconRes = R.drawable.ic_volume_up,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = s.onboardingTipSiren
                            )
                            Spacer(Modifier.height(6.dp))
                            OnboardingGradualTip(s.onboardingTipGradual)
                            Spacer(Modifier.height(20.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(14.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.neptun),
                                    contentDescription = null,
                                    modifier = Modifier.size(width = 14.dp, height = 14.dp),
                                    colorFilter = ColorFilter.tint(Color(0xFF4CAF50))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "NEPTUN",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.wizardNeptunStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    1 -> {
                        WizardThreatGrid(current, iconSet, hiddenTypes, silencedTypes, onThreatEnabledToggle)
                    }
                    2 -> SetupLocationStep(current, mode = locationMode, pinnedCity = pinnedCity, onModeChange = { locationMode = it }, onFollowMeChange = onFollowMeChange, onPinnedCityChange = onPinnedCityChange)
                    3 -> SetupZoneControlsStep(
                        s = s,
                        lang = current,
                        slowRedKm = slowRedKm,
                        slowYellowKm = slowYellowKm,
                        slowRedArmed = slowRedArmed,
                        slowYellowArmed = slowYellowArmed,
                        fastRedArmed = fastRedArmed,
                        fastYellowArmed = fastYellowArmed,
                        sheltersEnabled = sheltersEnabled,
                        onSlowRedChange = onSlowRedChange,
                        onSlowYellowChange = onSlowYellowChange,
                        onSlowRedArmedChange = onSlowRedArmedChange,
                        onSlowYellowArmedChange = onSlowYellowArmedChange
                    )
                    else -> SetupFeaturesStep(
                        s = s,
                        lang = current,
                        justFun = justFun,
                        calmMessagesEnabled = calmMessagesEnabled,
                        flybyAnimationEnabled = flybyAnimationEnabled,
                        deathAnimationEnabled = deathAnimationEnabled,
                        followBullet = followBullet,
                        neutralizedTallyEnabled = neutralizedTallyEnabled,
                        neutralizedTallyAllUkraine = neutralizedTallyAllUkraine,
                        iconSet = iconSetForFun,
                        onJustFunChange = onJustFunChange,
                        onCalmMessagesChange = onCalmMessagesChange,
                        onFlybyAnimationChange = onFlybyAnimationChange,
                        onDeathAnimationChange = onDeathAnimationChange,
                        onFollowBulletChange = onFollowBulletChange,
                        onNeutralizedTallyChange = onNeutralizedTallyChange,
                        onNeutralizedTallyAllUkraineChange = onNeutralizedTallyAllUkraineChange,
                        onIconSetChange = onIconSetChangeForFun
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val backInteraction = remember { MutableInteractionSource() }
                if (step > 0) {
                    IconButton(
                        onClick = { step-- },
                        modifier = Modifier.pressTick(backInteraction)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = s.backButton,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                val locationReady = locationMode != null && (locationMode != "pin" || pinnedCity != null)
                val progressFrac = if (step == 0 && !tipsRevealed) 0f else (step + 1) / totalSteps.toFloat()
                val nextEnabled = when (step) {
                    0 -> tipsRevealed
                    2 -> locationReady
                    else -> true
                }
                val nextInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (nextEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = { if (step < totalSteps - 1) step++ else onComplete() },
                        enabled = nextEnabled,
                        interactionSource = nextInteraction,
                        modifier = Modifier
                            .fillMaxSize()
                            .pressTick(nextInteraction),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = Color.White.copy(alpha = 0.35f)
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            if (step < totalSteps - 1) s.nextButton else s.wizardStartButton,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth(progressFrac)
                            .height(3.dp)
                            .background(Brush.horizontalGradient(listOf(UkraineYellow, UkraineBlue)))
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupLanguageStep(current: AppLanguage, onChoose: (AppLanguage) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LanguageFlag(
            emoji = "\uD83C\uDDFA\uD83C\uDDE6",
            label = "Українська",
            active = current == AppLanguage.UA,
            onClick = { onChoose(AppLanguage.UA) },
            modifier = Modifier.weight(1f)
        )
        LanguageFlag(
            emoji = "\uD83C\uDDE8\uD83C\uDDE6",
            label = "English",
            active = current == AppLanguage.EN,
            onClick = { onChoose(AppLanguage.EN) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun WizardThreatGrid(
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    onThreatEnabledToggle: (ThreatType, Boolean) -> Unit
) {
    val s = Strings.get(lang)
    Column {
        val gearId = "gear"
        Text(
            buildAnnotatedString {
                append(s.wizardCareSubtitle)
                append(' ')
                appendInlineContent(gearId, "[gear]")
                append(' ')
                append(s.wizardCareSubtitleGearSuffix)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            inlineContent = mapOf(
                gearId to InlineTextContent(
                    Placeholder(18.sp, 18.sp, PlaceholderVerticalAlign.TextCenter)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_ua),
                        contentDescription = s.settingsButton,
                        tint = Color.Unspecified
                    )
                }
            )
        )
        Spacer(Modifier.height(14.dp))
        val groups = fastAndSlowGroups(lang)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            groups.forEach { (groupIcon, groupLabel, types) ->
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = groupIcon),
                            contentDescription = if (groupIcon == R.drawable.ic_lightning) s.fastGroupIconDesc else s.slowGroupIconDesc,
                            tint = if (groupIcon == R.drawable.ic_turtle) TurtleGreen else Color.Unspecified,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            groupLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        types.forEach { type ->
                            val on = type !in hiddenTypes && type !in silencedTypes
                            val info = ThreatTypeCatalog.INFO.getValue(type)
                            val label = if (lang == AppLanguage.UA) {
                                info.shortLabelUa ?: info.labelUa
                            } else {
                                info.shortLabelEn ?: info.labelEn
                            }
                            val onColor = MaterialTheme.colorScheme.onSurface
                            val offColor = MaterialTheme.colorScheme.onSurfaceVariant
                            val cellInteraction = remember { MutableInteractionSource() }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = 1.5.dp,
                                        color = if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .pressTick(cellInteraction)
                                    .clickable(
                                        interactionSource = cellInteraction,
                                        indication = ripple(bounded = true),
                                        onClick = { onThreatEnabledToggle(type, !on) }
                                    )
                                    .padding(vertical = 12.dp, horizontal = 6.dp)
                            ) {
                                ThreatIcon(
                                    type = type,
                                    set = iconSet,
                                    size = 52.dp,
                                    tint = if (on) Color.Unspecified else offColor,
                                    dimmed = !on
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (on) onColor else offColor,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Wizard page: where the user's position comes from — GPS follow-me or a pinned city. */
@Composable
private fun SetupLocationStep(
    lang: AppLanguage,
    mode: String?,
    pinnedCity: City?,
    onModeChange: (String?) -> Unit,
    onFollowMeChange: (Boolean) -> Unit,
    onPinnedCityChange: (City?) -> Unit
) {
    val s = Strings.get(lang)
    val context = LocalContext.current
    val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            s.wizardLocationSubtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WizardLocationCard(
            selected = mode == "follow",
            onClick = {
                onModeChange("follow")
                onFollowMeChange(true)
                if (!fineGranted) {
                    permLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            },
            icon = {
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2196F3))
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
            },
            title = s.followMeTitle,
            desc = s.followMeDesc
        )
        WizardLocationCard(
            selected = mode == "pin",
            onClick = {
                onModeChange("pin")
                onFollowMeChange(false)
            },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = s.pinCityTitle,
            desc = s.pinCityDesc
        )
        AnimatedVisibility(visible = mode == "pin") {
            CityChipGrid(lang, selected = pinnedCity, onChange = onPinnedCityChange)
        }
    }
}

@Composable
private fun WizardLocationCard(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    title: String,
    desc: String
) {
    val cardInteraction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .pressTick(cardInteraction)
            .clickable(
                interactionSource = cardInteraction,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Wizard page with the REAL red-zone slider plus vertical list of the 4 map buttons
 * (shelter, red zoom, gear) with short descriptions — all inside one Card.
 * Yellow slider and turtle caption removed for consistency.
 */
@Composable
private fun SetupZoneControlsStep(
    s: Strings.StringSet,
    lang: AppLanguage,
    slowRedKm: Int,
    slowYellowKm: Int,
    slowRedArmed: Boolean,
    slowYellowArmed: Boolean,
    fastRedArmed: Boolean,
    fastYellowArmed: Boolean,
    sheltersEnabled: Boolean,
    onSlowRedChange: (Int) -> Unit,
    onSlowYellowChange: (Int) -> Unit,
    onSlowRedArmedChange: (Boolean) -> Unit,
    onSlowYellowArmedChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            s.wizardZonesSubtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        FeatureDiagram(
            kind = GuideDiagram.ZONES,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(14.dp))
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                WizardZoneSliderRow(
                    color = ZoneRedColor,
                    armed = slowRedArmed,
                    km = slowRedKm,
                    onArmedChange = onSlowRedArmedChange,
                    onKmChange = onSlowRedChange,
                    kmUnit = s.kmUnit
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    s.wizardEditZonesHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        TeardropShelterIcon(
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        s.wizardShelterDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD32F2F))
                            .border(BorderStroke(2.dp, Color(0xFFD32F2F)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_zoom_in),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        s.zoneRedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = s.editZonesLabel,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        s.editZonesLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** One live zone row (bell + alert switch + radius slider + km label), styled like in-app. */
@Composable
private fun WizardZoneSliderRow(
    color: Color,
    armed: Boolean,
    km: Int,
    onArmedChange: (Boolean) -> Unit,
    onKmChange: (Int) -> Unit,
    kmUnit: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Notifications,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(26.dp)
        )
        Switch(
            checked = armed,
            onCheckedChange = onArmedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = color,
                checkedTrackColor = color.copy(alpha = 0.45f),
                checkedBorderColor = Color.Transparent
            )
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = km.toFloat(),
            onValueChange = { onKmChange(it.roundToInt()) },
            valueRange = 1f..20f,
            enabled = armed,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color
            ),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$km $kmUnit",
            color = if (armed) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun SetupFeaturesStep(
    s: Strings.StringSet,
    lang: AppLanguage,
    justFun: Boolean,
    calmMessagesEnabled: Boolean,
    flybyAnimationEnabled: Boolean,
    deathAnimationEnabled: Boolean,
    followBullet: Boolean,
    neutralizedTallyEnabled: Boolean,
    neutralizedTallyAllUkraine: Boolean,
    iconSet: ThreatIconSet,
    onJustFunChange: (Boolean) -> Unit,
    onCalmMessagesChange: (Boolean) -> Unit,
    onFlybyAnimationChange: (Boolean) -> Unit,
    onDeathAnimationChange: (Boolean) -> Unit,
    onFollowBulletChange: (Boolean) -> Unit,
    onNeutralizedTallyChange: (Boolean) -> Unit,
    onNeutralizedTallyAllUkraineChange: (Boolean) -> Unit,
    onIconSetChange: (ThreatIconSet) -> Unit
) {
    val features = remember(s) {
        guideFeatures(s).filter { it.id in setOf("live", "zones", "night", "shelter") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        features.forEach { f ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FeatureDiagram(
                    kind = f.diagram,
                    modifier = Modifier
                        .size(width = 92.dp, height = 62.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        f.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        f.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    s.wizardJustFunTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                val justFunGearId = "justFunGear"
                Text(
                    buildAnnotatedString {
                        append(s.wizardJustFunDesc)
                        append(' ')
                        appendInlineContent(justFunGearId, "[gear]")
                        append(' ')
                        append(s.wizardJustFunDescGearSuffix)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    inlineContent = mapOf(
                        justFunGearId to InlineTextContent(
                            Placeholder(14.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings_ua),
                                contentDescription = s.settingsButton,
                                tint = Color.Unspecified
                            )
                        }
                    )
                )
            }
            Switch(
                checked = justFun,
                onCheckedChange = onJustFunChange
            )
        }
        AnimatedVisibility(visible = justFun) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        s.iconSetTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    IconSetSelector(
                        lang = lang,
                        selected = iconSet,
                        onChange = onIconSetChange
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = s.calmMessagesTitle,
                    description = s.calmMessagesDesc,
                    checked = calmMessagesEnabled,
                    onCheckedChange = onCalmMessagesChange,
                    icon = painterResource(R.drawable.ic_peace),
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = s.flybyAnimationLabel,
                    description = s.flybyAnimationDesc,
                    checked = flybyAnimationEnabled,
                    onCheckedChange = onFlybyAnimationChange,
                    icon = painterResource(R.drawable.ic_airplay),
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = s.deathAnimationTitle,
                    description = s.deathAnimationDesc,
                    checked = deathAnimationEnabled,
                    onCheckedChange = onDeathAnimationChange,
                    icon = painterResource(R.drawable.ic_explosion),
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AnimatedVisibility(visible = deathAnimationEnabled) {
                    Column(modifier = Modifier.padding(start = 40.dp)) {
                        AlertToggleRow(
                            title = s.followBulletTitle,
                            description = s.followBulletDesc,
                            checked = followBullet,
                            onCheckedChange = onFollowBulletChange,
                            icon = painterResource(R.drawable.bullet),
                            iconTint = null
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = s.neutralizedTallyTitle,
                    description = s.neutralizedTallyDesc,
                    checked = neutralizedTallyEnabled,
                    onCheckedChange = onNeutralizedTallyChange,
                    icon = rememberVectorPainter(Icons.Default.Notifications),
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconBadge = "21"
                )
                if (neutralizedTallyEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.padding(start = 40.dp)) {
                        AlertToggleRow(
                            title = s.neutralizedTallyAllUkraineTitle,
                            description = s.neutralizedTallyAllUkraineDesc,
                            checked = neutralizedTallyAllUkraine,
                            onCheckedChange = onNeutralizedTallyAllUkraineChange,
                            emoji = "🇺🇦"
                        )
                    }
                }
            }
        }
    }
}

/**
 * First-run battery exemption. Appears once, right after the language picker (only when the OS
 * still throttles this app), so MainActivity's deferred system permission dialogs come last.
 * Shows OEM-specific guidance when the device manufacturer is known to restrict background apps.
 */
@Composable
internal fun BatteryOnboardingDialog(
    s: Strings.StringSet,
    onAllow: () -> Unit,
    onLater: () -> Unit
) {
    val oemInfo = remember { BatteryOptimization.getOemInfo() }
    val title = if (oemInfo.isAggressive) s.batteryOemTitle else s.batteryTitle
    val body = if (oemInfo.isAggressive) s.batteryOemBody else s.batteryBody
    AlertDialog(
        onDismissRequest = onLater,
        confirmButton = { TextButton(onClick = onAllow) { Text(s.batteryAllowButton) } },
        dismissButton = { TextButton(onClick = onLater) { Text(s.batteryLater) } },
        title = { Text(title) },
        text = { Text(body) }
    )
}

@Composable
private fun OnboardingTipRow(iconRes: Int, iconTint: Color, text: String) {
    Row(modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The "gradual tips" promise — visually distinct from the icon rows so it reads as a note. */
@Composable
private fun OnboardingGradualTip(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
