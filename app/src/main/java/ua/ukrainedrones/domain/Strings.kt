package ua.ukrainedrones

object Strings {
    fun get(lang: AppLanguage): StringSet = when (lang) {
        AppLanguage.UA -> UA
        AppLanguage.EN -> EN
    }

    data class Onboarding(
        val appTitle: String,
        val officialAlertBanner: String,
        val settingsTitle: String,
        val settingsButton: String,
        val backButton: String,
        val languageLabel: String,
        val languageChooseTitle: String,
        val languageChooseLater: String,
        val okButton: String,
        val nextButton: String,
        val onboardingTipsTitle: String,
        val onboardingTipTap: String,
        val onboardingTipSettings: String,
        val onboardingTipSiren: String,
        val onboardingTipGradual: String,
        val onboardingFeaturesTitle: String,
        val onboardingIntro: String,
        val relaunchSetupTitle: String,
        val wizardCareTitle: String,
        val wizardCareSubtitle: String,
        val wizardCareSubtitleGearSuffix: String,
        val wizardCareOn: String,
        val wizardCareOff: String,
        val wizardStartButton: String,
        val wizardLocationTitle: String,
        val wizardLocationSubtitle: String,
        val wizardZonesTitle: String,
        val wizardZonesSubtitle: String,
        val wizardEditZonesHint: String,
        val zoneRedLabel: String,
        val zoneYellowLabel: String,
        val wizardShelterTitle: String,
        val wizardShelterDesc: String,
        val wizardJustFunTitle: String,
        val wizardJustFunDesc: String,
        val wizardJustFunDescGearSuffix: String,
        val wizardNeptunStatus: String,
        val settingsSearchHint: String,
        val settingsSearchClear: String,
        val settingsNoResults: String,
        val settingsSearchRelated: String,
        val settingsDidYouMean: String,
        val fitMapLabel: String
    )

    data class Settings(
        val dayZonesTitle: String,
        val nightZonesTitle: String,
        val alertZonesTitle: String,
        val dayShortLabel: String,
        val nightModeHeaderDesc: String,
        val redZoneLabel: String,
        val yellowZoneLabel: String,
        val alertsLabel: String,
        val redZoneAlertsTitle: String,
        val redZoneAlertsDesc: String,
        val yellowZoneAlertsTitle: String,
        val yellowZoneAlertsDesc: String,
        val officialAlertsTitle: String,
        val officialAlertsDesc: String,
        val officialAlertsRedTridentNote: String,
        val officialAlertScopeTitle: String,
        val officialAlertScopeDesc: String,
        val sirenOverrideTitle: String,
        val sirenOverrideDesc: String,
        val nightModeLabel: String,
        val nightModeDesc: String,
        val nightStartTimeLabel: String,
        val nightEndTimeLabel: String,
        val nightSoundLabel: String,
        val nightZoneSirenOverrideTitle: String,
        val nightZoneSirenOverrideDesc: String,
        val nightOfficialSirenOverrideTitle: String,
        val nightOfficialSirenOverrideDesc: String,
        val nightCustomZonesTitle: String,
        val nightCustomZonesDesc: String,
        val nightMuteExitNote: String,
        val allAlertsOffLabel: String,
        val notificationsDisabledLabel: String,
        val zoneButtonRed: String,
        val zoneButtonYellow: String,
        val slowSectionLabel: String,
        val fastSectionLabel: String,
        val threatsLabel: String,
        val threatMapLabel: String,
        val threatAlertLabel: String,
        val fastGroupLabel: String,
        val slowGroupLabel: String,
        val fastGroupIconDesc: String,
        val slowGroupIconDesc: String,
        val mapToggleHintPrefix: String,
        val mapToggleHintRest: String,
        val alertToggleHintPrefix: String,
        val alertToggleHintRest: String,
        val disclaimerTitle: String,
        val disclaimerBody: String,
        val exitButton: String,
        val systemSectionTitle: String,
        val locationSectionTitle: String,
        val justFunSectionTitle: String,
        val flybyAnimationLabel: String,
        val flybyAnimationDesc: String,
        val cardSizeSmallLabel: String,
        val cardSizeLargeLabel: String
    )

    data class Status(
        val redZoneAlert: String,
        val yellowZoneAlert: String,
        val notifOngoingTitle: String,
        val notifMonitoringCityFormat: String,
        val notifChannelName: String,
        val notifChannelDesc: String,
        val notifBodyRegion: String,
        val neutralizedNotifChannelName: String,
        val neutralizedChannelDesc: String,
        val notifUpdateTitle: String,
        val notifUpdateText: String,
        val notifUpdateChannelName: String,
        val notifUpdateChannelDesc: String,
        val attributionText: String,
        val madeBy: String,
        val connOnline: String,
        val connOffline: String,
        val connDegraded: String,
        val connDegradedBody: String,
        val connServerLine: String,
        val connStatusTitle: String,
        val connNeptunLabel: String,
        val connActiveLabel: String,
        val reliabilityLow: String,
        val reliabilityMedium: String,
        val reliabilityHigh: String,
        val reliabilityUnknown: String,
        val reliabilityLabel: String,
        val reliabilityShort: String,
        val alertsOffLabel: String,
        val speedUnit: String,
        val groupLabel: String,
        val uncertaintyLabel: String,
        val noRegion: String,
        val unknownLocation: String,
        val minutesAgoSuffix: String,
        val justNow: String,
        val hoursAgoSuffix: String,
        val daysAgoSuffix: String,
        val mixedTimeFormat: String,
        val noThreatsMessage: String
    )

    data class Updates(
        val updateAvailableTitle: String,
        val updateVersionLabel: String,
        val updateNotesTitle: String,
        val updateDownload: String,
        val updateDownloading: String,
        val updateInstall: String,
        val updateLater: String,
        val updateRetry: String,
        val updateFailedTitle: String,
        val updateInstallPermissionTitle: String,
        val updateInstallPermissionBody: String,
        val updateOpenSettings: String,
        val updateReadyToInstallTitle: String,
        val updateReadyToInstallBody: String,
        val updateUpToDate: String,
        val updateCheckFailed: String,
        val checkForUpdates: String,
        val updateButton: String,
        val updateAvailableButton: String,
        val updateAvailableOnOpen: String
    )

    data class Threat(
        val advisoryLabel: String,
        val areaOnlyLabel: String,
        val cardSkullNote: String,
        val distanceLabel: String,
        val distanceToCityFormat: String,
        val etaLabel: String,
        val etaUnit: String,
        val approxNote: String,
        val pillDistanceCd: String,
        val gpsOffLabel: String,
        val inRedZone: String,
        val inYellowZone: String,
        val outsideZones: String,
        val editZonesLabel: String,
        val alertsBellToggle: String,
        val kmUnit: String,
        val minUnit: String,
        val meterUnit: String,
        val moreInfoLabel: String,
        val offLabel: String,
        val mapCenterLabel: String,
        val followMeTitle: String,
        val followMeDesc: String,
        val pinCityTitle: String,
        val pinCityDesc: String,
        val alertBannerFormat: String,
        val notifBodyRegionFormat: String,
        val notifOfficialFormat: String,
        val notifOfficialWithReasonFormat: String,
        val offlineStatusTitle: String,
        val offlineBodyFormat: String,
        val offlineOfficialSirensLine: String,
        val offlineRetryAction: String,
        val offlineChannelName: String,
        val offlineChannelDesc: String,
        val offlineMilestone3Min: String,
        val offlineMilestone6Min: String,
        val offlineMilestone10Min: String,
        val offlineMilestone20Min: String,
        val offlineCriticalChannelName: String,
        val offlineCriticalChannelDesc: String,
        val offlineCritical5Min: String,
        val offlineCriticalOverrideTitle: String,
        val offlineCriticalOverrideDesc: String,
        val offlineCriticalBypassSilentTitle: String,
        val offlineCriticalBypassSilentDesc: String,
        val offlineIgnoreAction: String,
        val offlinePausedBody: String,
        val offlineLiveFormat: String,
        val alertChannelName: String,
        val alertChannelDesc: String,
        val outerAlertChannelName: String,
        val outerAlertChannelDesc: String,
        val allClearChannelName: String,
        val allClearChannelDesc: String,
        val alarmAlertChannelName: String,
        val alarmAlertChannelDesc: String,
        val outerAlarmAlertChannelName: String,
        val outerAlarmAlertChannelDesc: String
    )

    data class Misc(
        val connLogTitle: String,
        val connLogEmpty: String,
        val connLogDurFormat: String,
        val allClearTitle: String,
        val allClearText: String,
        val batteryTitle: String,
        val batteryBody: String,
        val batteryAllowButton: String,
        val batteryLater: String,
        val batteryGranted: String,
        val batteryOemTitle: String,
        val batteryOemBody: String,
        val batteryGenericBody: String,
        val resetTipsTitle: String,
        val resetTipsDesc: String,
        val tipsResetToast: String,
        val vibrationOff: String,
        val vibrationSoft: String,
        val vibrationMedium: String,
        val vibrationStrong: String,
        val hapticsTitle: String,
        val hapticsDesc: String,
        val vibrationUrgent: String,
        val logDistanceFormat: String,
        val alertAgeSecSuffix: String,
        val alertAgeMinSuffix: String,
        val alertAgeHrSuffix: String,
        val logsTitle: String,
        val logsFilterConnections: String,
        val logsFilterDecisions: String,
        val logsShowMore: String,
        val logsEmptyConnections: String,
        val logsGroupTimeline: String,
        val logsGroupProximity: String,
        val logsGroupType: String,
        val logsShownOnly: String,
        val logsNotified: String,
        val logsProxOblast: String,
        val logsSortDesc: String,
        val logsSortNewest: String,
        val logsSortOldest: String,
        val logsFlourishToggle: String,
        val logsSubtitleFormat: String,
        val logsSortDistance: String,
        val logsSortAge: String,
        val debugLogEmpty: String,
        val debugLogClear: String,
        val debugLogOpen: String,
        val debugLogDay: String,
        val debugLogNight: String,
        val debugLogShown: String,
        val debugLogSuppressed: String,
        val debugLogSoundOverride: String,
        val debugLogSoundFollows: String,
        val debugGroupOfficial: String,
        val debugGroupLeft: String,
        val debugBandCloseFormat: String,
        val closeButton: String,
        val debugBandMidFormat: String,
        val debugBandFarFormat: String,
        val debugBandFarthestFormat: String,
        val debugBandCountFormat: String,
        val debugReasonBellMuted: String,
        val debugReasonAlreadyNotified: String,
        val debugReasonCoalesced: String,
        val debugReasonTypeOff: String,
        val debugReasonAdvisory: String,
        val debugReasonStale: String,
        val debugReasonOutsideZones: String,
        val debugReasonToggleOff: String,
        val debugReasonLeft: String,
        val debugKindOfficialOn: String,
        val debugKindOfficialOff: String,
        val debugKindZoneEnter: String,
        val debugKindZoneExit: String,
        val debugKindRegionThreat: String,
        val debugKindFlourish: String,
        val flourishLogDetailFormat: String,
        val flourishEjectToast: String,
        val debugKindRegionFormat: String,
        val debugTierRed: String,
        val debugTierYellow: String,
        val connEventLost: String,
        val connEventRetry: String,
        val connEventNoNetwork: String,
        val connEventDegraded: String,
        val connEventMin3: String,
        val connEventMin5: String,
        val connEventMin6: String,
        val connEventMin10: String,
        val connEventMin20: String,
        val connEventGaveUp: String,
        val connEventPaused: String,
        val cardSizeLabel: String,
        val additionalSettingsTitle: String,
        val showMapScaleTitle: String,
        val showMapScaleDesc: String,
        val cityLabelsTitle: String,
        val cityLabelsDesc: String,
        val mediumCitiesChip: String,
        val smallCitiesChip: String,
        val calmMessagesTitle: String,
        val calmMessagesDesc: String,
        val deathAnimationTitle: String,
        val deathAnimationDesc: String,
        val followBulletTitle: String,
        val followBulletDesc: String,
        val neutralizedTallyTitle: String,
        val neutralizedTallyDesc: String,
        val neutralizedTallyAllUkraineTitle: String,
        val neutralizedTallyAllUkraineDesc: String,
        val justFunNote: String,
        val iconSetTitle: String,
        val iconSetPhotoLabel: String,
        val iconSetArmyLabel: String,
        val iconSetComicLabel: String,
        val iconSetRussianLabel: String,
        val shelterButtonLabel: String,
        val shelterScreenTitle: String,
        val shelterOpenInMaps: String,
        val shelterEmpty: String,
        val shelterSettingsTitle: String,
        val shelterSettingsDesc: String,
        val shelterDistanceM: String,
        val shelterDistanceKm: String,
        val shelterWalkMinutes: String,
        val shelterWalkAdultLabel: String,
        val shelterWalkKidLabel: String,
        val shelterSectionTitle: String,
        val shelterWithKidsTitle: String,
        val shelterWithKidsDesc: String,
        val shelterShowOnMap: String,
        val shelterGpsUnknown: String,
        val periodicGpsTitle: String,
        val periodicGpsDesc: String,
        val calibrateGpsNow: String,
        val calibratingGps: String,
        val lastGpsFixFormat: String,
        val gpsFixJustNow: String,
        val gpsPreciseBlocked: String,
        val gpsOpenSettings: String,
        val gpsFixFresh: String,
        val gpsStatusTitle: String,
        val networkLocationOnly: String,
        val updatingPreciseGpsToast: String,
        val gpsFallbackOdesa: String,
        val shelterLongPressTip: String,
        val shelterTapTip: String,
        val shelterViewListLabel: String,
        val shelterViewListDesc: String,
        val shelterTypeBasic: String,
        val shelterTypeMobile: String,
        val shelterTypeBunker: String,
        val logsFilterSystem: String,
        val apiSdkChanged: String,
        val apiSdkCheckFailed: String,
        val apiSystemEmpty: String,
        val apiMalformedFrame: String,
        val apiSdkViewManifest: String,
        val apiUnknownType: String,
        val logsLegend: String,
        val logsFilterTests: String
    )

    data class Widget(
        val threatsLabel: String,
        val noThreats: String,
        val active: String,
        val updatedFormat: String,
        val updatedNowLabel: String,
        val threatsAwayFormat: String,
        val refreshLabel: String,
        val officialAlertLabel: String
    )

    data class Guide(
        val guideTitle: String,
        val guideSettingsButton: String,
        val guideCategoryMap: String,
        val guideCategoryZones: String,
        val guideCategoryLocation: String,
        val guideCategoryCards: String,
        val guideCategoryWidget: String,
        val guideCategorySettings: String,
        val guideLiveTitle: String,
        val guideLiveSummary: String,
        val guideLiveD1: String,
        val guideLiveD2: String,
        val guideLiveD3: String,
        val guideStripTitle: String,
        val guideStripSummary: String,
        val guideStripD1: String,
        val guideStripD2: String,
        val guideStripD3: String,
        val guideConnTitle: String,
        val guideConnSummary: String,
        val guideConnD1: String,
        val guideConnD2: String,
        val guideConnD3: String,
        val guideZonesTitle: String,
        val guideZonesSummary: String,
        val guideZonesD1: String,
        val guideZonesD2: String,
        val guideZonesD3: String,
        val guideEditZonesTitle: String,
        val guideEditZonesSummary: String,
        val guideEditZonesD1: String,
        val guideEditZonesD2: String,
        val guideEditZonesD3: String,
        val guideNotifTitle: String,
        val guideNotifSummary: String,
        val guideNotifD1: String,
        val guideNotifD2: String,
        val guideNotifD3: String,
        val guideFastTitle: String,
        val guideFastSummary: String,
        val guideFastD1: String,
        val guideFastD2: String,
        val guideFastD3: String,
        val guideNightTitle: String,
        val guideNightSummary: String,
        val guideNightD1: String,
        val guideNightD2: String,
        val guideNightD3: String,
        val guideFollowTitle: String,
        val guideFollowSummary: String,
        val guideFollowD1: String,
        val guideFollowD2: String,
        val guideFollowD3: String,
        val guideShelterTitle: String,
        val guideShelterSummary: String,
        val guideShelterD1: String,
        val guideShelterD2: String,
        val guideShelterD3: String,
        val guidePinTitle: String,
        val guidePinSummary: String,
        val guidePinD1: String,
        val guidePinD2: String,
        val guideCardSizeTitle: String,
        val guideCardSizeSummary: String,
        val guideCardSizeD1: String,
        val guideCardSizeD3: String,
        val guideCardReadTitle: String,
        val guideCardReadSummary: String,
        val guideCardReadD1: String,
        val guideCardReadD2: String,
        val guideCardReadD3: String,
        val guideLangTitle: String,
        val guideLangSummary: String,
        val guideLangD1: String,
        val guideLangD2: String,
        val guideLangD3: String,
        val guideTogglesTitle: String,
        val guideTogglesSummary: String,
        val guideTogglesD1: String,
        val guideTogglesD2: String,
        val guideTogglesD3: String,
        val guideUpdateTitle: String,
        val guideUpdateSummary: String,
        val guideUpdateD1: String,
        val guideUpdateD2: String,
        val guideUpdateD3: String,
        val guideWidgetTitle: String,
        val guideWidgetSummary: String,
        val guideWidgetD1: String,
        val guideWidgetD2: String,
        val guideWidgetD3: String,
        val lastSeenAgoFormat: String,
        val neutralizedLabel: String,
        val neutralizedNote: String,
        val neutralizingLabel: String,
        val neutralizingNote: String,
        val fakeNeutralizingLabel: String,
        val fakeNeutralizingNote: String,
        val flourishDisabledToastFormat: String
    )

    /** One-time explainer copy. [items] order: threatToggles, officialAlerts, sirenOverride,
     *  followMe, cardSize, nightMode — each a (title, visual, scenario) triple. */
    data class ExplainerStrings(
        val visualLabel: String,
        val scenarioLabel: String,
        val gotIt: String,
        val items: List<Triple<String, String, String>>
    )

    data class StringSet(
        val language: AppLanguage,
        val onboarding: Onboarding,
        val settings: Settings,
        val status: Status,
        val updates: Updates,
        val threat: Threat,
        val misc: Misc,
        val widget: Widget,
        val guide: Guide,
        val explainers: ExplainerStrings
    ) {
        val appTitle: String get() = onboarding.appTitle
        val officialAlertBanner: String get() = onboarding.officialAlertBanner
        val settingsTitle: String get() = onboarding.settingsTitle
        val settingsSearchHint: String get() = onboarding.settingsSearchHint
        val settingsSearchClear: String get() = onboarding.settingsSearchClear
        val settingsNoResults: String get() = onboarding.settingsNoResults
        val settingsSearchRelated: String get() = onboarding.settingsSearchRelated
        val settingsDidYouMean: String get() = onboarding.settingsDidYouMean
        val settingsButton: String get() = onboarding.settingsButton
        val backButton: String get() = onboarding.backButton
        val languageLabel: String get() = onboarding.languageLabel
        val languageChooseTitle: String get() = onboarding.languageChooseTitle
        val languageChooseLater: String get() = onboarding.languageChooseLater
        val okButton: String get() = onboarding.okButton
        val nextButton: String get() = onboarding.nextButton
        val onboardingTipsTitle: String get() = onboarding.onboardingTipsTitle
        val onboardingTipTap: String get() = onboarding.onboardingTipTap
        val onboardingTipSettings: String get() = onboarding.onboardingTipSettings
        val onboardingTipSiren: String get() = onboarding.onboardingTipSiren
        val onboardingTipGradual: String get() = onboarding.onboardingTipGradual
        val onboardingFeaturesTitle: String get() = onboarding.onboardingFeaturesTitle
        val onboardingIntro: String get() = onboarding.onboardingIntro
        val relaunchSetupTitle: String get() = onboarding.relaunchSetupTitle
        val wizardCareTitle: String get() = onboarding.wizardCareTitle
        val wizardCareSubtitle: String get() = onboarding.wizardCareSubtitle
        val wizardCareSubtitleGearSuffix: String get() = onboarding.wizardCareSubtitleGearSuffix
        val wizardCareOn: String get() = onboarding.wizardCareOn
        val wizardCareOff: String get() = onboarding.wizardCareOff
        val wizardStartButton: String get() = onboarding.wizardStartButton
        val wizardLocationTitle: String get() = onboarding.wizardLocationTitle
        val wizardLocationSubtitle: String get() = onboarding.wizardLocationSubtitle
        val wizardZonesTitle: String get() = onboarding.wizardZonesTitle
        val wizardZonesSubtitle: String get() = onboarding.wizardZonesSubtitle
        val zoneRedLabel: String get() = onboarding.zoneRedLabel
        val zoneYellowLabel: String get() = onboarding.zoneYellowLabel
        val wizardEditZonesHint: String get() = onboarding.wizardEditZonesHint
        val wizardShelterTitle: String get() = onboarding.wizardShelterTitle
        val wizardShelterDesc: String get() = onboarding.wizardShelterDesc
        val wizardJustFunTitle: String get() = onboarding.wizardJustFunTitle
        val wizardJustFunDesc: String get() = onboarding.wizardJustFunDesc
        val wizardJustFunDescGearSuffix: String get() = onboarding.wizardJustFunDescGearSuffix
        val wizardNeptunStatus: String get() = onboarding.wizardNeptunStatus
        val fitMapLabel: String get() = onboarding.fitMapLabel
        val dayZonesTitle: String get() = settings.dayZonesTitle
        val nightZonesTitle: String get() = settings.nightZonesTitle
        val alertZonesTitle: String get() = settings.alertZonesTitle
        val dayShortLabel: String get() = settings.dayShortLabel
        val nightModeHeaderDesc: String get() = settings.nightModeHeaderDesc
        val redZoneLabel: String get() = settings.redZoneLabel
        val yellowZoneLabel: String get() = settings.yellowZoneLabel
        val alertsLabel: String get() = settings.alertsLabel
        val redZoneAlertsTitle: String get() = settings.redZoneAlertsTitle
        val redZoneAlertsDesc: String get() = settings.redZoneAlertsDesc
        val yellowZoneAlertsTitle: String get() = settings.yellowZoneAlertsTitle
        val yellowZoneAlertsDesc: String get() = settings.yellowZoneAlertsDesc
        val officialAlertsTitle: String get() = settings.officialAlertsTitle
        val officialAlertsDesc: String get() = settings.officialAlertsDesc
        val officialAlertsRedTridentNote: String get() = settings.officialAlertsRedTridentNote
        val officialAlertScopeTitle: String get() = settings.officialAlertScopeTitle
        val officialAlertScopeDesc: String get() = settings.officialAlertScopeDesc
        val sirenOverrideTitle: String get() = settings.sirenOverrideTitle
        val sirenOverrideDesc: String get() = settings.sirenOverrideDesc
        val nightModeLabel: String get() = settings.nightModeLabel
        val nightModeDesc: String get() = settings.nightModeDesc
        val nightStartTimeLabel: String get() = settings.nightStartTimeLabel
        val nightEndTimeLabel: String get() = settings.nightEndTimeLabel
        val nightSoundLabel: String get() = settings.nightSoundLabel
        val nightZoneSirenOverrideTitle: String get() = settings.nightZoneSirenOverrideTitle
        val nightZoneSirenOverrideDesc: String get() = settings.nightZoneSirenOverrideDesc
        val nightOfficialSirenOverrideTitle: String get() = settings.nightOfficialSirenOverrideTitle
        val nightOfficialSirenOverrideDesc: String get() = settings.nightOfficialSirenOverrideDesc
        val nightCustomZonesTitle: String get() = settings.nightCustomZonesTitle
        val nightCustomZonesDesc: String get() = settings.nightCustomZonesDesc
        val nightMuteExitNote: String get() = settings.nightMuteExitNote
        val allAlertsOffLabel: String get() = settings.allAlertsOffLabel
        val notificationsDisabledLabel: String get() = settings.notificationsDisabledLabel
        val zoneButtonRed: String get() = settings.zoneButtonRed
        val zoneButtonYellow: String get() = settings.zoneButtonYellow
        val slowSectionLabel: String get() = settings.slowSectionLabel
        val fastSectionLabel: String get() = settings.fastSectionLabel
        val threatsLabel: String get() = settings.threatsLabel
        val threatMapLabel: String get() = settings.threatMapLabel
        val threatAlertLabel: String get() = settings.threatAlertLabel
        val fastGroupLabel: String get() = settings.fastGroupLabel
        val slowGroupLabel: String get() = settings.slowGroupLabel
        val fastGroupIconDesc: String get() = settings.fastGroupIconDesc
        val slowGroupIconDesc: String get() = settings.slowGroupIconDesc
        val mapToggleHintPrefix: String get() = settings.mapToggleHintPrefix
        val mapToggleHintRest: String get() = settings.mapToggleHintRest
        val alertToggleHintPrefix: String get() = settings.alertToggleHintPrefix
        val alertToggleHintRest: String get() = settings.alertToggleHintRest
        val disclaimerTitle: String get() = settings.disclaimerTitle
        val disclaimerBody: String get() = settings.disclaimerBody
        val exitButton: String get() = settings.exitButton
        val systemSectionTitle: String get() = settings.systemSectionTitle
        val locationSectionTitle: String get() = settings.locationSectionTitle
        val justFunSectionTitle: String get() = settings.justFunSectionTitle
        val flybyAnimationLabel: String get() = settings.flybyAnimationLabel
        val flybyAnimationDesc: String get() = settings.flybyAnimationDesc
        val cardSizeSmallLabel: String get() = settings.cardSizeSmallLabel
        val cardSizeLargeLabel: String get() = settings.cardSizeLargeLabel

        fun locationSubtitle(followMe: Boolean, pinnedCityName: String?, periodicGps: Boolean = false): String =
            if (followMe) {
                if (periodicGps) {
                    if (language == AppLanguage.UA) "GPS слідування · 15-хв синхронізація" else "GPS follow me · 15m sync"
                } else {
                    if (language == AppLanguage.UA) "GPS слідування увімкнено" else "GPS follow me active"
                }
            } else if (!pinnedCityName.isNullOrBlank()) {
                if (language == AppLanguage.UA) "Закріплено: $pinnedCityName" else "Pinned: $pinnedCityName"
            } else {
                if (language == AppLanguage.UA) "Ручне позиціонування" else "Manual location"
            }

        fun nightSubtitle(enabled: Boolean, startMin: Int, endMin: Int, sirenOverride: Boolean, useCustomZones: Boolean): String =
            if (!enabled) {
                if (language == AppLanguage.UA) "Вимкнено · Стандартний радіус тривог" else "Disabled · Standard alert radius"
            } else {
                val timeStr = String.format(java.util.Locale.US, "%02d:%02d–%02d:%02d", startMin / 60, startMin % 60, endMin / 60, endMin % 60)
                val sirenStr = if (sirenOverride) {
                    if (language == AppLanguage.UA) " · Сирена завжди" else " · Siren override ON"
                } else ""
                val zonesStr = if (useCustomZones) {
                    if (language == AppLanguage.UA) " · Нічні зони" else " · Night zones"
                } else ""
                if (language == AppLanguage.UA) "Увімкнено · $timeStr$sirenStr$zonesStr" else "Active · $timeStr$sirenStr$zonesStr"
            }

        fun alertsSubtitle(officialAlerts: Boolean, sirenOverride: Boolean): String {
            val isUa = language == AppLanguage.UA
            val parts = mutableListOf<String>()
            parts.add(if (isUa) "Офіційні: " + (if (officialAlerts) "УВІМК" else "ВИМК") else "Official: " + (if (officialAlerts) "ON" else "OFF"))
            if (sirenOverride) {
                parts.add(if (isUa) "Сирена завжди: УВІМК" else "Siren override: ON")
            }
            return parts.joinToString(" · ")
        }

        fun justFunSubtitle(animation: Boolean, tally: Boolean): String {
            val isUa = language == AppLanguage.UA
            val parts = mutableListOf<String>()
            parts.add(if (isUa) "Анімація: " + (if (animation) "УВІМК" else "ВИМК") else "Animation: " + (if (animation) "ON" else "OFF"))
            if (tally) {
                parts.add(if (isUa) "Лічильник: УВІМК" else "Neutralized count: ON")
            }
            return parts.joinToString(" · ")
        }

        fun sheltersSubtitle(enabled: Boolean): String {
            val isUa = language == AppLanguage.UA
            return if (isUa) "Кнопка укриття: " + (if (enabled) "УВІМК" else "ВИМК")
            else "Shelter button: " + (if (enabled) "ON" else "OFF")
        }

        fun threatsSubtitle(hiddenCount: Int, silencedCount: Int, totalCount: Int = 8): String {
            val isUa = language == AppLanguage.UA
            return if (hiddenCount == 0 && silencedCount == 0) {
                if (isUa) "Усі $totalCount типів активні" else "All $totalCount types active"
            } else if (hiddenCount > 0 && silencedCount > 0) {
                if (isUa) "Приховано: $hiddenCount · Без звуку: $silencedCount" else "Hidden: $hiddenCount · Silenced: $silencedCount"
            } else if (hiddenCount > 0) {
                if (isUa) "Приховано на мапі: $hiddenCount" else "Hidden on map: $hiddenCount"
            } else {
                if (isUa) "Без звуку: $silencedCount" else "Silenced: $silencedCount"
            }
        }

        fun systemSubtitle(appLang: AppLanguage, cardSize: ThreatCardSize, iconSet: ThreatIconSet): String {
            val langName = if (appLang == AppLanguage.UA) "Українська" else "English"
            val sizeName = when (cardSize) {
                ThreatCardSize.SMALL -> cardSizeSmallLabel
                ThreatCardSize.LARGE -> cardSizeLargeLabel
            }
            val iconName = when (iconSet) {
                ThreatIconSet.PHOTO -> iconSetPhotoLabel
                ThreatIconSet.ARMY -> iconSetArmyLabel
                ThreatIconSet.COMIC -> iconSetComicLabel
                ThreatIconSet.RUSSIAN -> iconSetRussianLabel
            }
            return "$langName · $sizeName · $iconName"
        }
        val redZoneAlert: String get() = status.redZoneAlert
        val yellowZoneAlert: String get() = status.yellowZoneAlert
        val notifOngoingTitle: String get() = status.notifOngoingTitle
        val notifMonitoringCityFormat: String get() = status.notifMonitoringCityFormat
        val notifChannelName: String get() = status.notifChannelName
        val notifChannelDesc: String get() = status.notifChannelDesc
        val notifBodyRegion: String get() = status.notifBodyRegion
        val neutralizedNotifChannelName: String get() = status.neutralizedNotifChannelName
        val neutralizedChannelDesc: String get() = status.neutralizedChannelDesc
        val notifUpdateTitle: String get() = status.notifUpdateTitle
        val notifUpdateText: String get() = status.notifUpdateText
        val notifUpdateChannelName: String get() = status.notifUpdateChannelName
        val notifUpdateChannelDesc: String get() = status.notifUpdateChannelDesc
        val attributionText: String get() = status.attributionText
        val madeBy: String get() = status.madeBy
        val connOnline: String get() = status.connOnline
        val connOffline: String get() = status.connOffline
        val connDegraded: String get() = status.connDegraded
        val connDegradedBody: String get() = status.connDegradedBody
        val connServerLine: String get() = status.connServerLine
        val connStatusTitle: String get() = status.connStatusTitle
        val connNeptunLabel: String get() = status.connNeptunLabel
        val connActiveLabel: String get() = status.connActiveLabel
        val reliabilityLow: String get() = status.reliabilityLow
        val reliabilityMedium: String get() = status.reliabilityMedium
        val reliabilityHigh: String get() = status.reliabilityHigh
        val reliabilityUnknown: String get() = status.reliabilityUnknown
        val reliabilityLabel: String get() = status.reliabilityLabel
        val reliabilityShort: String get() = status.reliabilityShort
        val alertsOffLabel: String get() = status.alertsOffLabel
        val speedUnit: String get() = status.speedUnit
        val groupLabel: String get() = status.groupLabel
        val uncertaintyLabel: String get() = status.uncertaintyLabel
        val noRegion: String get() = status.noRegion
        val unknownLocation: String get() = status.unknownLocation
        val minutesAgoSuffix: String get() = status.minutesAgoSuffix
        val justNow: String get() = status.justNow
        val hoursAgoSuffix: String get() = status.hoursAgoSuffix
        val daysAgoSuffix: String get() = status.daysAgoSuffix
        val mixedTimeFormat: String get() = status.mixedTimeFormat
        val noThreatsMessage: String get() = status.noThreatsMessage
        val updateAvailableTitle: String get() = updates.updateAvailableTitle
        val updateVersionLabel: String get() = updates.updateVersionLabel
        val updateNotesTitle: String get() = updates.updateNotesTitle
        val updateDownload: String get() = updates.updateDownload
        val updateDownloading: String get() = updates.updateDownloading
        val updateInstall: String get() = updates.updateInstall
        val updateLater: String get() = updates.updateLater
        val updateRetry: String get() = updates.updateRetry
        val updateFailedTitle: String get() = updates.updateFailedTitle
        val updateInstallPermissionTitle: String get() = updates.updateInstallPermissionTitle
        val updateInstallPermissionBody: String get() = updates.updateInstallPermissionBody
        val updateOpenSettings: String get() = updates.updateOpenSettings
        val updateReadyToInstallTitle: String get() = updates.updateReadyToInstallTitle
        val updateReadyToInstallBody: String get() = updates.updateReadyToInstallBody
        val updateUpToDate: String get() = updates.updateUpToDate
        val updateCheckFailed: String get() = updates.updateCheckFailed
        val checkForUpdates: String get() = updates.checkForUpdates
        val updateButton: String get() = updates.updateButton
        val updateAvailableButton: String get() = updates.updateAvailableButton
        val updateAvailableOnOpen: String get() = updates.updateAvailableOnOpen
        val advisoryLabel: String get() = threat.advisoryLabel
        val areaOnlyLabel: String get() = threat.areaOnlyLabel
        val cardSkullNote: String get() = threat.cardSkullNote
        val distanceLabel: String get() = threat.distanceLabel
        val distanceToCityFormat: String get() = threat.distanceToCityFormat
        val etaLabel: String get() = threat.etaLabel
        val etaUnit: String get() = threat.etaUnit
        val approxNote: String get() = threat.approxNote
        val pillDistanceCd: String get() = threat.pillDistanceCd
        val gpsOffLabel: String get() = threat.gpsOffLabel
        val inRedZone: String get() = threat.inRedZone
        val inYellowZone: String get() = threat.inYellowZone
        val outsideZones: String get() = threat.outsideZones
        val editZonesLabel: String get() = threat.editZonesLabel
        val alertsBellToggle: String get() = threat.alertsBellToggle
        val kmUnit: String get() = threat.kmUnit
        val minUnit: String get() = threat.minUnit
        val meterUnit: String get() = threat.meterUnit
        val moreInfoLabel: String get() = threat.moreInfoLabel
        val offLabel: String get() = threat.offLabel
        val mapCenterLabel: String get() = threat.mapCenterLabel
        val followMeTitle: String get() = threat.followMeTitle
        val followMeDesc: String get() = threat.followMeDesc
        val pinCityTitle: String get() = threat.pinCityTitle
        val pinCityDesc: String get() = threat.pinCityDesc
        val alertBannerFormat: String get() = threat.alertBannerFormat
        val notifBodyRegionFormat: String get() = threat.notifBodyRegionFormat
        val notifOfficialFormat: String get() = threat.notifOfficialFormat
        val notifOfficialWithReasonFormat: String get() = threat.notifOfficialWithReasonFormat
        val offlineStatusTitle: String get() = threat.offlineStatusTitle
        val offlineBodyFormat: String get() = threat.offlineBodyFormat
        val offlineOfficialSirensLine: String get() = threat.offlineOfficialSirensLine
        val offlineRetryAction: String get() = threat.offlineRetryAction
        val offlineChannelName: String get() = threat.offlineChannelName
        val offlineChannelDesc: String get() = threat.offlineChannelDesc
        val offlineMilestone3Min: String get() = threat.offlineMilestone3Min
        val offlineMilestone6Min: String get() = threat.offlineMilestone6Min
        val offlineMilestone10Min: String get() = threat.offlineMilestone10Min
        val offlineMilestone20Min: String get() = threat.offlineMilestone20Min
        val offlineCriticalChannelName: String get() = threat.offlineCriticalChannelName
        val offlineCriticalChannelDesc: String get() = threat.offlineCriticalChannelDesc
        val offlineCritical5Min: String get() = threat.offlineCritical5Min
        val offlineCriticalOverrideTitle: String get() = threat.offlineCriticalOverrideTitle
        val offlineCriticalOverrideDesc: String get() = threat.offlineCriticalOverrideDesc
        val offlineCriticalBypassSilentTitle: String get() = threat.offlineCriticalBypassSilentTitle
        val offlineCriticalBypassSilentDesc: String get() = threat.offlineCriticalBypassSilentDesc
        val offlineIgnoreAction: String get() = threat.offlineIgnoreAction
        val offlinePausedBody: String get() = threat.offlinePausedBody
        val offlineLiveFormat: String get() = threat.offlineLiveFormat
        val alertChannelName: String get() = threat.alertChannelName
        val alertChannelDesc: String get() = threat.alertChannelDesc
        val outerAlertChannelName: String get() = threat.outerAlertChannelName
        val outerAlertChannelDesc: String get() = threat.outerAlertChannelDesc
        val allClearChannelName: String get() = threat.allClearChannelName
        val allClearChannelDesc: String get() = threat.allClearChannelDesc
        val alarmAlertChannelName: String get() = threat.alarmAlertChannelName
        val alarmAlertChannelDesc: String get() = threat.alarmAlertChannelDesc
        val outerAlarmAlertChannelName: String get() = threat.outerAlarmAlertChannelName
        val outerAlarmAlertChannelDesc: String get() = threat.outerAlarmAlertChannelDesc
        val connLogTitle: String get() = misc.connLogTitle
        val connLogEmpty: String get() = misc.connLogEmpty
        val connLogDurFormat: String get() = misc.connLogDurFormat
        val allClearTitle: String get() = misc.allClearTitle
        val allClearText: String get() = misc.allClearText
        val batteryTitle: String get() = misc.batteryTitle
        val batteryBody: String get() = misc.batteryBody
        val batteryAllowButton: String get() = misc.batteryAllowButton
        val batteryLater: String get() = misc.batteryLater
        val batteryGranted: String get() = misc.batteryGranted
        val batteryOemTitle: String get() = misc.batteryOemTitle
        val batteryOemBody: String get() = misc.batteryOemBody
        val batteryGenericBody: String get() = misc.batteryGenericBody
        val resetTipsTitle: String get() = misc.resetTipsTitle
        val resetTipsDesc: String get() = misc.resetTipsDesc
        val tipsResetToast: String get() = misc.tipsResetToast
        val vibrationOff: String get() = misc.vibrationOff
        val vibrationSoft: String get() = misc.vibrationSoft
        val vibrationMedium: String get() = misc.vibrationMedium
        val vibrationStrong: String get() = misc.vibrationStrong
        val hapticsTitle: String get() = misc.hapticsTitle
        val hapticsDesc: String get() = misc.hapticsDesc
        val vibrationUrgent: String get() = misc.vibrationUrgent
        val logDistanceFormat: String get() = misc.logDistanceFormat
        val alertAgeSecSuffix: String get() = misc.alertAgeSecSuffix
        val alertAgeMinSuffix: String get() = misc.alertAgeMinSuffix
        val alertAgeHrSuffix: String get() = misc.alertAgeHrSuffix
        val logsTitle: String get() = misc.logsTitle
        val logsFilterConnections: String get() = misc.logsFilterConnections
        val logsFilterDecisions: String get() = misc.logsFilterDecisions
        val logsShowMore: String get() = misc.logsShowMore
        val logsEmptyConnections: String get() = misc.logsEmptyConnections
        val logsGroupTimeline: String get() = misc.logsGroupTimeline
        val logsGroupProximity: String get() = misc.logsGroupProximity
        val logsGroupType: String get() = misc.logsGroupType
        val logsShownOnly: String get() = misc.logsShownOnly
        val logsNotified: String get() = misc.logsNotified
        val logsProxOblast: String get() = misc.logsProxOblast
        val logsSortDesc: String get() = misc.logsSortDesc
        val logsSortNewest: String get() = misc.logsSortNewest
        val logsSortOldest: String get() = misc.logsSortOldest
        val logsFlourishToggle: String get() = misc.logsFlourishToggle
        val logsSubtitleFormat: String get() = misc.logsSubtitleFormat
        val logsSortDistance: String get() = misc.logsSortDistance
        val logsSortAge: String get() = misc.logsSortAge
        val debugLogEmpty: String get() = misc.debugLogEmpty
        val debugLogClear: String get() = misc.debugLogClear
        val debugLogOpen: String get() = misc.debugLogOpen
        val debugLogDay: String get() = misc.debugLogDay
        val debugLogNight: String get() = misc.debugLogNight
        val debugLogShown: String get() = misc.debugLogShown
        val debugLogSuppressed: String get() = misc.debugLogSuppressed
        val debugLogSoundOverride: String get() = misc.debugLogSoundOverride
        val debugLogSoundFollows: String get() = misc.debugLogSoundFollows
        val debugGroupOfficial: String get() = misc.debugGroupOfficial
        val debugGroupLeft: String get() = misc.debugGroupLeft
        val debugBandCloseFormat: String get() = misc.debugBandCloseFormat
        val closeButton: String get() = misc.closeButton
        val debugBandMidFormat: String get() = misc.debugBandMidFormat
        val debugBandFarFormat: String get() = misc.debugBandFarFormat
        val debugBandFarthestFormat: String get() = misc.debugBandFarthestFormat
        val debugBandCountFormat: String get() = misc.debugBandCountFormat
        val debugReasonBellMuted: String get() = misc.debugReasonBellMuted
        val debugReasonAlreadyNotified: String get() = misc.debugReasonAlreadyNotified
        val debugReasonCoalesced: String get() = misc.debugReasonCoalesced
        val debugReasonTypeOff: String get() = misc.debugReasonTypeOff
        val debugReasonAdvisory: String get() = misc.debugReasonAdvisory
        val debugReasonStale: String get() = misc.debugReasonStale
        val debugReasonOutsideZones: String get() = misc.debugReasonOutsideZones
        val debugReasonToggleOff: String get() = misc.debugReasonToggleOff
        val debugReasonLeft: String get() = misc.debugReasonLeft
        val debugKindOfficialOn: String get() = misc.debugKindOfficialOn
        val debugKindOfficialOff: String get() = misc.debugKindOfficialOff
        val debugKindZoneEnter: String get() = misc.debugKindZoneEnter
        val debugKindZoneExit: String get() = misc.debugKindZoneExit
        val debugKindRegionThreat: String get() = misc.debugKindRegionThreat
        val debugKindFlourish: String get() = misc.debugKindFlourish
        val flourishLogDetailFormat: String get() = misc.flourishLogDetailFormat
        val flourishEjectToast: String get() = misc.flourishEjectToast
        val debugKindRegionFormat: String get() = misc.debugKindRegionFormat
        val debugTierRed: String get() = misc.debugTierRed
        val debugTierYellow: String get() = misc.debugTierYellow
        val connEventLost: String get() = misc.connEventLost
        val connEventRetry: String get() = misc.connEventRetry
        val connEventNoNetwork: String get() = misc.connEventNoNetwork
        val connEventDegraded: String get() = misc.connEventDegraded
        val connEventMin3: String get() = misc.connEventMin3
        val connEventMin5: String get() = misc.connEventMin5
        val connEventMin6: String get() = misc.connEventMin6
        val connEventMin10: String get() = misc.connEventMin10
        val connEventMin20: String get() = misc.connEventMin20
        val connEventGaveUp: String get() = misc.connEventGaveUp
        val connEventPaused: String get() = misc.connEventPaused
        val cardSizeLabel: String get() = misc.cardSizeLabel
        val additionalSettingsTitle: String get() = misc.additionalSettingsTitle
        val showMapScaleTitle: String get() = misc.showMapScaleTitle
        val showMapScaleDesc: String get() = misc.showMapScaleDesc
        val cityLabelsTitle: String get() = misc.cityLabelsTitle
        val cityLabelsDesc: String get() = misc.cityLabelsDesc
        val mediumCitiesChip: String get() = misc.mediumCitiesChip
        val smallCitiesChip: String get() = misc.smallCitiesChip
        val calmMessagesTitle: String get() = misc.calmMessagesTitle
        val calmMessagesDesc: String get() = misc.calmMessagesDesc
        val deathAnimationTitle: String get() = misc.deathAnimationTitle
        val deathAnimationDesc: String get() = misc.deathAnimationDesc
        val followBulletTitle: String get() = misc.followBulletTitle
        val followBulletDesc: String get() = misc.followBulletDesc
        val neutralizedTallyTitle: String get() = misc.neutralizedTallyTitle
        val neutralizedTallyDesc: String get() = misc.neutralizedTallyDesc
        val justFunNote: String get() = misc.justFunNote
        val neutralizedTallyAllUkraineTitle: String get() = misc.neutralizedTallyAllUkraineTitle
        val neutralizedTallyAllUkraineDesc: String get() = misc.neutralizedTallyAllUkraineDesc
val iconSetTitle: String get() = misc.iconSetTitle
    val iconSetPhotoLabel: String get() = misc.iconSetPhotoLabel
        val iconSetArmyLabel: String get() = misc.iconSetArmyLabel
        val iconSetComicLabel: String get() = misc.iconSetComicLabel
        val iconSetRussianLabel: String get() = misc.iconSetRussianLabel
        val shelterButtonLabel: String get() = misc.shelterButtonLabel
        val shelterScreenTitle: String get() = misc.shelterScreenTitle
        val shelterOpenInMaps: String get() = misc.shelterOpenInMaps
        val shelterEmpty: String get() = misc.shelterEmpty
        val shelterSettingsTitle: String get() = misc.shelterSettingsTitle
        val shelterSettingsDesc: String get() = misc.shelterSettingsDesc
        val shelterDistanceM: String get() = misc.shelterDistanceM
        val shelterDistanceKm: String get() = misc.shelterDistanceKm
        val shelterWalkMinutes: String get() = misc.shelterWalkMinutes
        val shelterWalkAdultLabel: String get() = misc.shelterWalkAdultLabel
        val shelterWalkKidLabel: String get() = misc.shelterWalkKidLabel
        val shelterSectionTitle: String get() = misc.shelterSectionTitle
        val shelterWithKidsTitle: String get() = misc.shelterWithKidsTitle
        val shelterWithKidsDesc: String get() = misc.shelterWithKidsDesc
        val shelterShowOnMap: String get() = misc.shelterShowOnMap
        val shelterGpsUnknown: String get() = misc.shelterGpsUnknown
        val periodicGpsTitle: String get() = misc.periodicGpsTitle
        val periodicGpsDesc: String get() = misc.periodicGpsDesc
        val calibrateGpsNow: String get() = misc.calibrateGpsNow
        val calibratingGps: String get() = misc.calibratingGps
        val lastGpsFixFormat: String get() = misc.lastGpsFixFormat
        val gpsFixJustNow: String get() = misc.gpsFixJustNow
        val gpsPreciseBlocked: String get() = misc.gpsPreciseBlocked
        val gpsOpenSettings: String get() = misc.gpsOpenSettings
        val gpsFixFresh: String get() = misc.gpsFixFresh
        val gpsFallbackOdesa: String get() = misc.gpsFallbackOdesa
        val gpsStatusTitle: String get() = misc.gpsStatusTitle
        val networkLocationOnly: String get() = misc.networkLocationOnly
        val updatingPreciseGpsToast: String get() = misc.updatingPreciseGpsToast
        val shelterLongPressTip: String get() = misc.shelterLongPressTip
        val shelterTapTip: String get() = misc.shelterTapTip
        val shelterViewListLabel: String get() = misc.shelterViewListLabel
        val shelterViewListDesc: String get() = misc.shelterViewListDesc
        val shelterTypeBasic: String get() = misc.shelterTypeBasic
        val shelterTypeMobile: String get() = misc.shelterTypeMobile
        val shelterTypeBunker: String get() = misc.shelterTypeBunker
        val logsFilterSystem: String get() = misc.logsFilterSystem
        val apiSdkChanged: String get() = misc.apiSdkChanged
        val apiSdkCheckFailed: String get() = misc.apiSdkCheckFailed
        val apiSystemEmpty: String get() = misc.apiSystemEmpty
        val apiMalformedFrame: String get() = misc.apiMalformedFrame
        val apiSdkViewManifest: String get() = misc.apiSdkViewManifest
        val apiUnknownType: String get() = misc.apiUnknownType
        val logsLegend: String get() = misc.logsLegend
        val logsFilterTests: String get() = misc.logsFilterTests
        val guideTitle: String get() = guide.guideTitle
        val guideSettingsButton: String get() = guide.guideSettingsButton
        val guideCategoryMap: String get() = guide.guideCategoryMap
        val guideCategoryZones: String get() = guide.guideCategoryZones
        val guideCategoryLocation: String get() = guide.guideCategoryLocation
        val guideCategoryCards: String get() = guide.guideCategoryCards
        val guideCategoryWidget: String get() = guide.guideCategoryWidget
        val guideCategorySettings: String get() = guide.guideCategorySettings
        val guideLiveTitle: String get() = guide.guideLiveTitle
        val guideLiveSummary: String get() = guide.guideLiveSummary
        val guideLiveD1: String get() = guide.guideLiveD1
        val guideLiveD2: String get() = guide.guideLiveD2
        val guideLiveD3: String get() = guide.guideLiveD3
        val guideStripTitle: String get() = guide.guideStripTitle
        val guideStripSummary: String get() = guide.guideStripSummary
        val guideStripD1: String get() = guide.guideStripD1
        val guideStripD2: String get() = guide.guideStripD2
        val guideStripD3: String get() = guide.guideStripD3
        val guideConnTitle: String get() = guide.guideConnTitle
        val guideConnSummary: String get() = guide.guideConnSummary
        val guideConnD1: String get() = guide.guideConnD1
        val guideConnD2: String get() = guide.guideConnD2
        val guideConnD3: String get() = guide.guideConnD3
        val guideZonesTitle: String get() = guide.guideZonesTitle
        val guideZonesSummary: String get() = guide.guideZonesSummary
        val guideZonesD1: String get() = guide.guideZonesD1
        val guideZonesD2: String get() = guide.guideZonesD2
        val guideZonesD3: String get() = guide.guideZonesD3
        val guideEditZonesTitle: String get() = guide.guideEditZonesTitle
        val guideEditZonesSummary: String get() = guide.guideEditZonesSummary
        val guideEditZonesD1: String get() = guide.guideEditZonesD1
        val guideEditZonesD2: String get() = guide.guideEditZonesD2
        val guideEditZonesD3: String get() = guide.guideEditZonesD3
        val guideNotifTitle: String get() = guide.guideNotifTitle
        val guideNotifSummary: String get() = guide.guideNotifSummary
        val guideNotifD1: String get() = guide.guideNotifD1
        val guideNotifD2: String get() = guide.guideNotifD2
        val guideNotifD3: String get() = guide.guideNotifD3
        val guideFastTitle: String get() = guide.guideFastTitle
        val guideFastSummary: String get() = guide.guideFastSummary
        val guideFastD1: String get() = guide.guideFastD1
        val guideFastD2: String get() = guide.guideFastD2
        val guideFastD3: String get() = guide.guideFastD3
        val guideNightTitle: String get() = guide.guideNightTitle
        val guideNightSummary: String get() = guide.guideNightSummary
        val guideNightD1: String get() = guide.guideNightD1
        val guideNightD2: String get() = guide.guideNightD2
        val guideNightD3: String get() = guide.guideNightD3
        val guideFollowTitle: String get() = guide.guideFollowTitle
        val guideFollowSummary: String get() = guide.guideFollowSummary
        val guideFollowD1: String get() = guide.guideFollowD1
        val guideFollowD2: String get() = guide.guideFollowD2
        val guideFollowD3: String get() = guide.guideFollowD3
        val guideShelterTitle: String get() = guide.guideShelterTitle
        val guideShelterSummary: String get() = guide.guideShelterSummary
        val guideShelterD1: String get() = guide.guideShelterD1
        val guideShelterD2: String get() = guide.guideShelterD2
        val guideShelterD3: String get() = guide.guideShelterD3
        val guidePinTitle: String get() = guide.guidePinTitle
        val guidePinSummary: String get() = guide.guidePinSummary
        val guidePinD1: String get() = guide.guidePinD1
        val guidePinD2: String get() = guide.guidePinD2
        val guideCardSizeTitle: String get() = guide.guideCardSizeTitle
        val guideCardSizeSummary: String get() = guide.guideCardSizeSummary
        val guideCardSizeD1: String get() = guide.guideCardSizeD1
        val guideCardSizeD3: String get() = guide.guideCardSizeD3
        val guideCardReadTitle: String get() = guide.guideCardReadTitle
        val guideCardReadSummary: String get() = guide.guideCardReadSummary
        val guideCardReadD1: String get() = guide.guideCardReadD1
        val guideCardReadD2: String get() = guide.guideCardReadD2
        val guideCardReadD3: String get() = guide.guideCardReadD3
        val guideLangTitle: String get() = guide.guideLangTitle
        val guideLangSummary: String get() = guide.guideLangSummary
        val guideLangD1: String get() = guide.guideLangD1
        val guideLangD2: String get() = guide.guideLangD2
        val guideLangD3: String get() = guide.guideLangD3
        val guideTogglesTitle: String get() = guide.guideTogglesTitle
        val guideTogglesSummary: String get() = guide.guideTogglesSummary
        val guideTogglesD1: String get() = guide.guideTogglesD1
        val guideTogglesD2: String get() = guide.guideTogglesD2
        val guideTogglesD3: String get() = guide.guideTogglesD3
        val guideUpdateTitle: String get() = guide.guideUpdateTitle
        val guideUpdateSummary: String get() = guide.guideUpdateSummary
        val guideUpdateD1: String get() = guide.guideUpdateD1
        val guideUpdateD2: String get() = guide.guideUpdateD2
        val guideUpdateD3: String get() = guide.guideUpdateD3
        val guideWidgetTitle: String get() = guide.guideWidgetTitle
        val guideWidgetSummary: String get() = guide.guideWidgetSummary
        val guideWidgetD1: String get() = guide.guideWidgetD1
        val guideWidgetD2: String get() = guide.guideWidgetD2
        val guideWidgetD3: String get() = guide.guideWidgetD3
        val lastSeenAgoFormat: String get() = guide.lastSeenAgoFormat
        val neutralizedLabel: String get() = guide.neutralizedLabel
        val neutralizedNote: String get() = guide.neutralizedNote
        val neutralizingLabel: String get() = guide.neutralizingLabel
        val neutralizingNote: String get() = guide.neutralizingNote
        val fakeNeutralizingLabel: String get() = guide.fakeNeutralizingLabel
        val fakeNeutralizingNote: String get() = guide.fakeNeutralizingNote
        val flourishDisabledToastFormat: String get() = guide.flourishDisabledToastFormat
        val explainerVisualLabel: String get() = explainers.visualLabel
        val explainerScenarioLabel: String get() = explainers.scenarioLabel
        val explainerGotIt: String get() = explainers.gotIt
    }

    private fun uaOnboarding() = Onboarding(
        appTitle = "Українські дрони",
        officialAlertBanner = "Одеса: тривога",
        settingsTitle = "Налаштування",
        settingsButton = "Налаштування",
        backButton = "Назад",
        languageLabel = "Мова",
        languageChooseTitle = "Оберіть мову",
        languageChooseLater = "Пропустити",
        okButton = "ОК",
        nextButton = "Далі",
        onboardingTipsTitle = "Кілька підказок",
        onboardingTipTap = "Натисни будь-яку загрозу на мапі, щоб відкрити її картку.",
        onboardingTipSettings = "У Налаштуваннях — путівник по функціях та інші просунуті налаштування. Зазирни туди!",
        onboardingTipSiren = "Сирена за замовчуванням слідує за режимом звуку телефона — увімкни «Сирена звучить завжди», щоб змінити.",
        onboardingTipGradual = "У застосунку багато функцій — під час користування він буде підказувати, як ними користуватися.",
        onboardingFeaturesTitle = "Основні можливості",
        onboardingIntro = "Це налаштування важливе — у застосунку є просунуті функції безпеки, про які варто знати. Виділи 30 секунд і ознайомся зараз, щоб бути готовим, коли це важливо.",
        relaunchSetupTitle = "Повторити початкове налаштування",
        wizardCareTitle = "Які загрози для вас важливі?",
        wizardCareSubtitle = "Усі загрози показано на мапі за замовчуванням, а тут ви вмикаєте лише сповіщення. Змінити можна будь-коли в",
        wizardCareSubtitleGearSuffix = "налаштуваннях.",        wizardCareOn = "Сповіщати",
        wizardCareOff = "Вимкнено",
        wizardStartButton = "Будьте в безпеці…",
        wizardLocationTitle = "Де ви перебуваєте?",
        wizardLocationSubtitle = "Оберіть, звідки брати вашу позицію — від цього залежать зони та сповіщення.",
        wizardZonesTitle = "Зони тривог",
        wizardZonesSubtitle = "На мапі є червона та жовта зони, а також зона на час для швидких об'єктів. Налаштуй під себе.",
        wizardEditZonesHint = "Повзунок повільно рухає коло на мапі — червона зона завжди всередині жовтої.",
        zoneRedLabel = "Натисни, щоб наблизити",
        zoneYellowLabel = "Жовта зона",
        wizardShelterTitle = "Укриття поруч",
        wizardShelterDesc = "Найближчі укриття з часом пішки.",
        wizardJustFunTitle = "Розваги",
        wizardJustFunDesc = "Додай трохи розваг — анімації, прольоти, лічильник. Налаштуй в",
        wizardJustFunDescGearSuffix = "налаштуваннях.",
        wizardNeptunStatus = "Джерело даних — Neptun. Загрози оновлюються в реальному часі.",
        settingsSearchHint = "Пошук налаштувань",
        settingsSearchClear = "Очистити пошук",
        settingsNoResults = "Нічого не знайдено",
        settingsSearchRelated = "Можливо, ви також шукаєте",
        settingsDidYouMean = "Можливо, ви мали на увазі",
        fitMapLabel = "Показати всю Україну на мапі",
    )

    private fun uaSettings() = Settings(
        dayZonesTitle = "Денні зони",
        nightZonesTitle = "Нічні зони",
        alertZonesTitle = "Зони тривог",
        dayShortLabel = "день",
        nightModeHeaderDesc = "Нічний режим активний",
        redZoneLabel = "Червона зона",
        yellowZoneLabel = "Жовта зона",
        alertsLabel = "Оповіщення",
        redZoneAlertsTitle = "Оповіщення червоної зони",
        redZoneAlertsDesc = "Сирена при загрозі в червоній зоні.",
        yellowZoneAlertsTitle = "Оповіщення жовтої зони",
        yellowZoneAlertsDesc = "Попереджувальний звук при загрозі в жовтій зоні.",
        officialAlertsTitle = "Офіційні сповіщення",
        officialAlertsDesc = "Сповіщення про офіційну повітряну тривогу в області. Не впливає на червону/жовту зони.",
        officialAlertsRedTridentNote = "у шапці означає, що офіційна тривога активна",
        officialAlertScopeTitle = "Міський рівень",
        officialAlertScopeDesc = "Уся область за замовчуванням. Увімкни, щоб сповіщало лише коли тривога у твоєму місті.",
        sirenOverrideTitle = "Сирена звучить завжди",
        sirenOverrideDesc = "Сигнал тривоги звучить, навіть коли телефон у вібро- чи беззвучному режимі. За замовчуванням вимкнено — звук слідує за налаштуваннями телефону.",
        nightModeLabel = "Нічний режим",
        nightModeDesc = "Окреме налаштування звуку, вібрації та зон для нічного вікна.",
        nightStartTimeLabel = "Початок",
        nightEndTimeLabel = "Кінець",
        nightSoundLabel = "Звук уночі",
        nightZoneSirenOverrideTitle = "Сирена звучить завжди",
        nightZoneSirenOverrideDesc = "Зонні сирени вночі дзвонять навіть у вібро- чи беззвучному режимі. Вимкнено — звук слідує за налаштуваннями телефону.",
        nightOfficialSirenOverrideTitle = "Офіційні сповіщення звучать завжди",
        nightOfficialSirenOverrideDesc = "Офіційна тривога вночі дзвонить навіть у вібро- чи беззвучному режимі. Чи це вся область, чи лише ваше місто — визначає перемикач «Міський рівень» у розділі «Сповіщення».",
        nightCustomZonesTitle = "Окремі нічні зони",
        nightCustomZonesDesc = "Власні відстані/час і червона/жовта зони на час нічного вікна.",
        nightMuteExitNote = "Якщо справді хочеш повної тиші — скористайся кнопкою «Зупинити моніторинг і вийти» нижче; інакше легко забути повернути налаштування вранці.",
        allAlertsOffLabel = "Усі сповіщення вимкнено",
        notificationsDisabledLabel = "Увімкніть сповіщення",
        zoneButtonRed = "Червона зона: натисни, щоб наблизити",
        zoneButtonYellow = "Жовта зона: натисни, щоб наблизити",
        slowSectionLabel = "Повільні — відстань",
        fastSectionLabel = "Швидкі — час до прильоту",
        threatsLabel = "Загрози",
        threatMapLabel = "Мапа",
        threatAlertLabel = "Сповіщення",
        fastGroupLabel = "Швидкі",
        slowGroupLabel = "Повільні",
        fastGroupIconDesc = "Блискавка — швидкі загрози",
        slowGroupIconDesc = "Черепаха — повільні загрози",
        mapToggleHintPrefix = "Мапа",
        mapToggleHintRest = " вимкнена — ховає тип і замовчує його тривоги.",
        alertToggleHintPrefix = "Сповіщення",
        alertToggleHintRest = " вимкнені — лишають тип на мапі, лише затемненим.",
        disclaimerTitle = "Застереження",
        disclaimerBody = "Завжди керуйся офіційними сигналами повітряної тривоги — цей застосунок не є офіційним джерелом тривог.\n\nКоординати, відстані, ETA та швидкість — приблизні: противник не транслює дані точно, навіть коли фіксація позначена як підтверджена.\n\nПоказник рівня загрози (0–10) — приблизна оцінка, а не офіційний рейтинг.\n\nТривоги залежать від приблизного місцезнаходження телефона — неправильна точка означає неправильну зону.\n\nЗастосунок не може гарантувати вашу безпеку. Все це лише орієнтир.",
        exitButton = "Зупинити моніторинг і вийти",
        systemSectionTitle = "Система та інтерфейс",
        locationSectionTitle = "Локація та фокус",
        justFunSectionTitle = "Просто розвага",
        flybyAnimationLabel = "Анімація польоту МіГ",
        flybyAnimationDesc = "Увімкнути або вимкнути анімацію та звук польоту МіГ-31К",
        cardSizeSmallLabel = "Компактні картки",
        cardSizeLargeLabel = "Великі картки",
    )

    private fun uaStatus() = Status(
        redZoneAlert = "Червона тривога",
        yellowZoneAlert = "Жовта тривога",
        notifOngoingTitle = "Моніторинг GPS",
        notifMonitoringCityFormat = "Моніторинг %s",
        notifChannelName = "Фоновий статус",
        notifChannelDesc = "Моніторинг працює у фоновому режимі. Вимкнення цього лише приховує статусне сповіщення — сповіщення про повітряну тривогу все одно спрацьовуватимуть.",
        notifBodyRegion = "Одеський регіон",
        neutralizedNotifChannelName = "Завершені загрози",
        neutralizedChannelDesc = "Лічильник завершених поблизу загроз — живе, поки його не прибрати.",
        notifUpdateTitle = "Доступна нова версія",
        notifUpdateText = "Версія %s готова — торкнись, щоб оновити",
        notifUpdateChannelName = "Оновлення",
        notifUpdateChannelDesc = "Сповіщення про доступні оновлення застосунку.",
        attributionText = "Карта повітряних тривог — NEPTUN",
        madeBy = "Зроблено Presaince",
connOnline = "Онлайн",
        connOffline = "Офлайн",
        connDegraded = "Запасний",
        connDegradedBody = "Запасне з'єднання — дані про загрози можуть затримуватися.",
        connServerLine = "З'єднання з сервером повітряних тривог NEPTUN.",
        connStatusTitle = "Статус системи",
        connNeptunLabel = "NEPTUN",
        connActiveLabel = "Активне",
        reliabilityLow = "Достовірність: Низька",
        reliabilityMedium = "Достовірність: Середня",
        reliabilityHigh = "Достовірність: Висока",
        reliabilityUnknown = "Достовірність: Невідома",
        reliabilityLabel = "Достовірність",
        reliabilityShort = "Д",
        alertsOffLabel = "вимк",
        speedUnit = "кмг",
        groupLabel = "Хвиля",
        uncertaintyLabel = "Точність",
        noRegion = "Одеська область",
        unknownLocation = "Невідоме місцеположення",
        minutesAgoSuffix = "хв тому",
        justNow = "щойно",
        hoursAgoSuffix = "год тому",
        daysAgoSuffix = "дн тому",
        mixedTimeFormat = "%1\$d год %2\$d хв тому",
        noThreatsMessage = "Немає актуальних загроз",
    )

    private fun uaUpdates() = Updates(
        updateAvailableTitle = "Доступна нова версія",
        updateVersionLabel = "Версія",
        updateNotesTitle = "Що нового",
        updateDownload = "Завантажити",
        updateDownloading = "Завантаження… %1\$d%%",
        updateInstall = "Встановити",
        updateLater = "Пізніше",
        updateRetry = "Спробувати знову",
        updateFailedTitle = "Не вдалося завантажити оновлення",
        updateInstallPermissionTitle = "Дозвіл на встановлення",
        updateInstallPermissionBody = "Щоб встановити оновлення, дозволь цьому застосунку встановлювати інші застосунки.",
        updateOpenSettings = "Відкрити налаштування",
        updateReadyToInstallTitle = "Оновлення готове",
        updateReadyToInstallBody = "Оновлення завантажено. Натисни «Встановити», щоб застосувати.",
        updateUpToDate = "У вас остання версія",
        updateCheckFailed = "Не вдалося перевірити оновлення",
        checkForUpdates = "Перевірити оновлення",
        updateButton = "Оновити",
        updateAvailableButton = "Доступне оновлення",
        updateAvailableOnOpen = "Доступне оновлення v%s",
    )

    private fun uaThreat() = Threat(
        advisoryLabel = "Спостереження",
        areaOnlyLabel = "Лише область (без точної точки)",
        cardSkullNote = "Череп (0–10) — приблизна оцінка: тип, відстань, достовірність, джерела. Не офіційний рейтинг і не гарантія вашої безпеки.",
        distanceLabel = "Відстань",
        distanceToCityFormat = "Відстань до %1\$s",
        etaLabel = "Час підльоту",
        etaUnit = "хв",
        approxNote = "Усі числа в застосунку приблизні",
        pillDistanceCd = "Відстань до %1\$s: %2\$d км",
        gpsOffLabel = "GPS вимкнено — зональні дані недоступні",
        inRedZone = "У червоній зоні",
        inYellowZone = "У жовтій зоні",
        outsideZones = "Поза зонами тривог",
        editZonesLabel = "Змінити зони",
        alertsBellToggle = "Увімкнути або вимкнути оповіщення",
        kmUnit = "км",
        minUnit = "хв",
        meterUnit = "м",
        moreInfoLabel = "Детальніше",
        offLabel = "ВИМК",
        mapCenterLabel = "Центр карти",
        followMeTitle = "Слідувати за мною",
        followMeDesc = "Карта та зони слідують за твоїм GPS-положенням.",
        pinCityTitle = "Прикріпити до міста",
        pinCityDesc = "Центр карти та зони закріплюються за вибраним містом.",
        alertBannerFormat = "%1\$s: тривога",
        notifBodyRegionFormat = "%1\$s область",
        notifOfficialFormat = "Офіційна тривога в %1\$s",
        notifOfficialWithReasonFormat = "Офіційна тривога в %1\$s: %2\$s",
        offlineStatusTitle = "Офлайн",
        offlineBodyFormat = "Офлайн — з'єднання з NEPTUN втрачено. Натисни, щоб повторити.",
        offlineOfficialSirensLine = "Покладайся на офіційні сирени.",
        offlineRetryAction = "Повторити",
        offlineChannelName = "Офлайн-моніторинг",
        offlineChannelDesc = "Повідомлення, коли з'єднання із сервером тривог втрачено.",
        offlineMilestone3Min = "Офлайн — повторюємо у фоні.",
        offlineMilestone6Min = "Офлайн 6 хв — повторюємо.",
        offlineMilestone10Min = "Офлайн 10 хв — повторюємо, затримки зростають.",
        offlineMilestone20Min = "20 хв офлайн — перепідключення зупинено. Увімкніть режим літака, або відкрийте застосунок і натисніть «Офлайн», щоб спробувати зараз. Інакше застосунок зупиниться на 30 хвилин.",
        offlineCriticalChannelName = "Критичний офлайн",
        offlineCriticalChannelDesc = "Дзвінок після 5 хвилин втрати з'єднання з сервером тривог.",
        offlineCritical5Min = "5 хв офлайн — натисніть «Повторити».",
        offlineCriticalOverrideTitle = "Критичний офлайн-сигнал",
        offlineCriticalOverrideDesc = "Нагадувати після 5 хвилин без з'єднання з сервером тривог.",
        offlineCriticalBypassSilentTitle = "Обійти тишу",
        offlineCriticalBypassSilentDesc = "Грати звук навіть коли телефон на тиші. Не обходить режим «Не турбувати» — увімкніть відповідний параметр у налаштуваннях каналу, якщо потрібно.",
        offlineIgnoreAction = "Ігнорувати 30 хв",
        offlinePausedBody = "Повтор призупинено на 30 хв — натисніть «Офлайн», щоб відновити раніше.",
        offlineLiveFormat = "Офлайн %1\$d/%2\$d хв · спроба %3\$d",
        alertChannelName = "Повітряні тривоги",
        alertChannelDesc = "Сирени повітряної тривоги та термінові зональні сповіщення",
        outerAlertChannelName = "Сповіщення регіону",
        outerAlertChannelDesc = "Попереджувальні сповіщення зовнішньої зони (Регіон)",
        allClearChannelName = "Відбій тривоги",
        allClearChannelDesc = "Приємний сигнал, коли офіційна повітряна тривога завершується",
        alarmAlertChannelName = "Повітряні тривоги — завжди зі звуком",
        alarmAlertChannelDesc = "Сирени повітряної тривоги та термінові зональні сповіщення, навіть у беззвучному режимі",
        outerAlarmAlertChannelName = "Сповіщення регіону — завжди зі звуком",
        outerAlarmAlertChannelDesc = "Попереджувальні сповіщення зовнішньої зони (Регіон), навіть у беззвучному режимі",
    )

    private fun uaMisc() = Misc(
        connLogTitle = "Живий журнал з'єднання",
        connLogEmpty = "Подій ще немає",
        connLogDurFormat = "%1\$d хв %2\$d с",
        allClearTitle = "%1\$s: відбій тривоги",
        allClearText = "Офіційна повітряна тривога завершилася.",
        batteryTitle = "Фонова робота",
        batteryBody = "Android може ставити тривоги на паузу у фоні. Дозвіл фонової роботи гарантує, що сповіщення лунатимуть, — застосунок майже не витрачає заряд.",
        batteryAllowButton = "Дозволити",
        batteryLater = "Пізніше",
        batteryGranted = "Без обмежень у фоні",
        batteryOemTitle = "Обмеження фону на цьому пристрої",
        batteryOemBody = "Цей пристрій може обмежувати фонову роботу. Щоб отримувати сповіщення, дозвільте необмежену фонову роботу для цього застосунку.",
        batteryGenericBody = "Цей пристрій може обмежувати фонову роботу. Дозвіл необмеженої фонової роботи гарантує, що сповіщення лунатимуть.",
        resetTipsTitle = "Скинути всі підказки",
        resetTipsDesc = "Показувати підказки першого запуску знову",
        tipsResetToast = "Підказки скинуто",
        vibrationOff = "Вимк",
        vibrationSoft = "М'яка",
        vibrationMedium = "Середня",
        vibrationStrong = "Сильна",
        hapticsTitle = "Тактильний відгук",
        hapticsDesc = "Невеликий віброімпульс під час натискань у застосунку.",
        vibrationUrgent = "Термінова",
        logDistanceFormat = "%1\$d км",
        alertAgeSecSuffix = "сек",
        alertAgeMinSuffix = "хв",
        alertAgeHrSuffix = "год",
        logsTitle = "Журнал",
        logsFilterConnections = "З'єднання",
        logsFilterDecisions = "Рішення",
        logsShowMore = "Показати ще",
        logsEmptyConnections = "Змін з'єднання ще не було",
        logsGroupTimeline = "Хронологія",
        logsGroupProximity = "Відстань",
        logsGroupType = "Тип",
        logsShownOnly = "Лише показані",
        logsNotified = "Сповіщені",
        logsProxOblast = "У області",
        logsSortDesc = "Новіші зверху / старіші зверху",
        logsSortNewest = "Новіші",
        logsSortOldest = "Старіші",
        logsFlourishToggle = "вирішено",
        logsSubtitleFormat = "Останні 24 год · %1\$d",
        logsSortDistance = "Відстань",
        logsSortAge = "Час",
        debugLogEmpty = "Рішень ще не зафіксовано",
        debugLogClear = "Очистити",
        debugLogOpen = "Журнал",
        debugLogDay = "День",
        debugLogNight = "Ніч",
        debugLogShown = "Сповіщено",
        debugLogSuppressed = "Без сповіщення — %1\$s",
        debugLogSoundOverride = "Сигнал завжди",
        debugLogSoundFollows = "За режимом звуку",
        debugGroupOfficial = "Офіційні тривоги",
        debugGroupLeft = "Покинули регіон",
        debugBandCloseFormat = "≤ %1\$d км",
        closeButton = "Закрити",
        debugBandMidFormat = "%1\$d–%2\$d км",
        debugBandFarFormat = "%1\$d–%2\$d км",
        debugBandFarthestFormat = "> %1\$d км",
        debugBandCountFormat = "%1\$d",
        debugReasonBellMuted = "дзвінок вимкнено для цього типу",
        debugReasonAlreadyNotified = "вже сповіщено для цього рівня",
        debugReasonCoalesced = "спочатку надіслано іншу тривогу",
        debugReasonTypeOff = "сповіщення типу вимкнено",
        debugReasonAdvisory = "спостереження — ніколи не тривожить",
        debugReasonStale = "застаріла",
        debugReasonOutsideZones = "у регіоні, поза зонами тривоги",
        debugReasonToggleOff = "сповіщення про офіційні тривоги вимкнено",
        debugReasonLeft = "покинула регіон",
        debugKindOfficialOn = "Офіційна тривога увімкнена",
        debugKindOfficialOff = "Офіційна тривога завершена",
        debugKindZoneEnter = "Увійшла в зону",
        debugKindZoneExit = "Покинула зону",
        debugKindRegionThreat = "Загроза в регіоні",
        debugKindFlourish = "Шоу знешкодження",
        flourishLogDetailFormat = "Пострілів: %1\$d · Груп: %2\$d",
        flourishEjectToast = "Шоу знешкодження почекає, доки ви не повернетеся на мапу",
        debugKindRegionFormat = "Загроза у %1\$s",
        debugTierRed = "Червона зона",
        debugTierYellow = "Жовта зона",
        connEventLost = "З'єднання втрачено",
        connEventRetry = "Повтор через %1\$ds · спроба %2\$d",
        connEventNoNetwork = "Немає мережі — чекаємо",
        connEventDegraded = "Запасний зв'язок",
        connEventMin3 = "3 хв офлайн",
        connEventMin5 = "5 хв офлайн — тривога",
        connEventMin6 = "6 хв офлайн",
        connEventMin10 = "10 хв офлайн",
        connEventMin20 = "20 хв офлайн — перепідключення зупинено",
        connEventGaveUp = "Зупинено — повтор призупинено",
        connEventPaused = "Повтор призупинено на 30 хв",
        cardSizeLabel = "Розмір і деталізація картки загрози",
        additionalSettingsTitle = "Додаткові налаштування",
        showMapScaleTitle = "Показати масштаб",
        showMapScaleDesc = "Масштабна шкала в кутку карти.",
        cityLabelsTitle = "Міста на карті",
        cityLabelsDesc = "Підписи середніх і дрібних міст.",
        mediumCitiesChip = "Середні",
        smallCitiesChip = "Дрібні",
        calmMessagesTitle = "Заспокійливі повідомлення",
        calmMessagesDesc = "Показувати короткі підбадьорювання в нижній панелі, коли загроз немає. Вимкни, щоб залишати лише «Немає актуальних загроз».",
        deathAnimationTitle = "Збиття загрози",
        deathAnimationDesc = "Коли загрозу перестають відстежувати, прилітає куля і збиває її. Просто для розваги.",
        followBulletTitle = "Слідувати за снарядом",
        followBulletDesc = "Снаряд летить до цілі, а камера слідує за ним до точки удару.",
        neutralizedTallyTitle = "Лічильник знешкоджених",
        neutralizedTallyDesc = "Показувати сповіщення з лічильником знешкоджених загроз у твоїй області, доки працює моніторинг. Натискання повторює розвагу на карті.",
        neutralizedTallyAllUkraineTitle = "Уся Україна",
        neutralizedTallyAllUkraineDesc = "Враховувати знешкоджені загрози по всій Україні, а не лише у твоїй області.",
        justFunNote = "Це просто розвага — жодного впливу на моніторинг чи сповіщення. Під час активної тривоги повтор усе одно доступний (це ваш власний дотик), але якщо нова тривога почнеться посеред шоу — воно миттєво зникне, щоб сигнал володів екраном. У сумнівах довіряйте офіційній сирені, а не феєрверкам.",
        iconSetTitle = "Оберіть свої улюблені іконки загроз!",
        iconSetPhotoLabel = "Фото",
        iconSetArmyLabel = "Армія",
        iconSetComicLabel = "Комікс",
        iconSetRussianLabel = "Російські",
        shelterButtonLabel = "Укриття",
        shelterScreenTitle = "Укриття поруч",
        shelterOpenInMaps = "Відкрити",
        shelterEmpty = "Укриттів поруч не знайдено",
        shelterSettingsTitle = "Кнопка укриття",
        shelterSettingsDesc = "Кнопка «До укриття» на карті (дані Одеської міськради).",
        shelterDistanceM = "%d м",
        shelterDistanceKm = "%.1f км",
        shelterWalkMinutes = "~%d хв",
        shelterWalkAdultLabel = "дорослим",
        shelterWalkKidLabel = "з дітьми",
        shelterSectionTitle = "Укриття",
        shelterWithKidsTitle = "З дітьми",
        shelterWithKidsDesc = "З дітьми йдуть повільніше — час ходьби рахуємо трохи довше.",
        shelterShowOnMap = "Показати на мапі",
        shelterGpsUnknown = "GPS: —",
        periodicGpsTitle = "Періодична GPS-синхронізація (15 хв)",
        periodicGpsDesc = "Звичайне фонове відстеження використовує вежі зв'язку для збереження батареї. Синхронізація вмикає GPS на кілька секунд кожні 15 хвилин, щоб уникнути зміщення дистанції до загроз та розрахунку укриттів.",
        calibrateGpsNow = "Оновити GPS",
        calibratingGps = "Пошук GPS…",
        lastGpsFixFormat = "Точний GPS: %s",
        gpsFixJustNow = "щойно",
        gpsPreciseBlocked = "Система більше не запитує. Увімкніть точний GPS у налаштуваннях.",
        gpsOpenSettings = "Налаштування",
        gpsFixFresh = "Точний GPS: свіжий",
        gpsStatusTitle = "Точний GPS",
        networkLocationOnly = "Мережева локація (вежі зв'язку)",
        updatingPreciseGpsToast = "Оновлення точного GPS…",
        gpsFallbackOdesa = "GPS недоступний — використовуємо Одесу",
        shelterLongPressTip = "Довго натисни кнопку «Укриття», щоб відкрити повний список",
        shelterTapTip = "Торкнись кнопки «Укриття», щоб побачити укриття поруч",
        shelterViewListLabel = "Список укриттів (довідник)",
        shelterViewListDesc = "Переглянути повний список усіх укриттів міста",
        shelterTypeBasic = "Найпростіше укриття",
        shelterTypeMobile = "Мобільне укриття",
        shelterTypeBunker = "Бомбосховище (ЗСЦЗ)",
        logsFilterSystem = "Система",
        apiSdkChanged = "SDK змінено",
        apiSdkCheckFailed = "Перевірка SDK не вдалася",
        apiSystemEmpty = "Змін системи не зафіксовано",
        apiMalformedFrame = "Кадр з помилкою",
        apiSdkViewManifest = "Переглянути маніфест →",
        apiUnknownType = "Невідомий тип: %s",
        logsLegend = "Легенда",
        logsFilterTests = "Тести",
    )

    private fun uaWidget() = Widget(
        threatsLabel = "Загрози",
        noThreats = "Загроз немає",
        active = "АКТИВНО",
        updatedFormat = "Оновлено %1\$s",
        updatedNowLabel = "Оновлено щойно",
        threatsAwayFormat = "Загрози ~%1\$d км",
        refreshLabel = "Оновити",
        officialAlertLabel = "Повітряна тривога",
    )

    private fun uaGuide() = Guide(
        guideTitle = "Путівник по функціях",
        guideSettingsButton = "Путівник по функціях",
        guideCategoryMap = "Карта",
        guideCategoryZones = "Зони та тривоги",
        guideCategoryLocation = "Місцезнаходження",
        guideCategoryCards = "Картка загрози",
        guideCategoryWidget = "Віджет",
        guideCategorySettings = "Налаштування",
        guideLiveTitle = "Жива мапа загроз",
        guideLiveSummary = "Жива мапа всіх відстежуваних загроз по всій країні.",
        guideLiveD1 = "Маркери показують усі 8 типів загроз — дрони, ракети, КАБ та інше.",
        guideLiveD2 = "Під час тривоги мапа розширюється, щоб бачити інші регіони.",
        guideLiveD3 = "Натисни маркер, щоб відкрити картку; натисни порожню мапу, щоб закрити.",
        guideStripTitle = "Рядок загроз",
        guideStripSummary = "Смуга внизу — живий лічильник загроз за типами.",
        guideStripD1 = "У смузі лише типи, які зараз є поруч — кожна плитка показує живу кількість.",
        guideStripD2 = "Тип без загроз не показується; коли порожньо все — «Немає актуальних загроз».",
        guideStripD3 = "Вимкнений у Налаштуваннях тип з'являється тьмяним (сірим).",
        guideConnTitle = "Зв'язок і шкала",
        guideConnSummary = "Пілюля зверху — зв'язок із сервером тривог; шкала — відстані.",
        guideConnD1 = "Онлайн — дані оновлюються; Офлайн — дані можуть бути застарілі.",
        guideConnD2 = "Натисни пілюлю, щоб побачити статус сервера та джерело NEPTUN.",
        guideConnD3 = "Шкала масштабу в кутку показує реальні відстані на мапі.",
        guideZonesTitle = "Червона та жовта зони",
        guideZonesSummary = "Повільні загрози оцінюються за відстанню, швидкі — за часом до прильоту.",
        // NOTE: 20/50 км — значення за замовчуванням, див. UserPrefs.
        guideZonesD1 = "Повільні (дрони): червона зона — до 20 км, жовта — до 50 км.",
        // NOTE: 5/20 хв — значення за замовчуванням, див. UserPrefs.
        guideZonesD2 = "Швидкі (ракети, КАБ): червона зона — до 5 хв, жовта — до 20 хв до прильоту.",
        guideZonesD3 = "Кола на мапі показують відстань повільних зон і слідують за твоїм положенням.",
        guideEditZonesTitle = "Зони та оповіщення",
        guideEditZonesSummary = "Панель під мапою налаштовує обидві зони та Fast/Slow-перемикачі.",
        guideEditZonesD1 = "Повзунки оновлюють кола на мапі одразу — все видно без додаткових дій.",
        guideEditZonesD2 = "Дзвіночок вмикає чи вимикає оповіщення зони.",
        guideEditZonesD3 = "Зміни застосовуються одразу і зберігаються.",
        guideNotifTitle = "Сповіщення",
        guideNotifSummary = "Застосунок слухає у фоні та сповіщає про зони.",
        guideNotifD1 = "Червона зона — сирена; жовта — звук попередження; офіційна тривога — свій звук.",
        guideNotifD2 = "Тихий статус-запис показує, що моніторинг працює.",
        guideNotifD3 = "Моніторинг триває, навіть коли застосунок закрито.",
        guideFastTitle = "Сповіщення про зони",
        guideFastSummary = "Зони часу до прильоту працюють однаково для всіх об'єктів.",
        guideFastD1 = "Чим швидший об'єкт, тим раніше він перетинає зону — сирена звучить миттєво.",
        guideFastD2 = "Офіційні сповіщення: додає сигнал повітряної тривоги області.",
        guideFastD3 = "Налаштування зон — у кнопці зони на мапі або Налаштуваннях.",
        guideNightTitle = "Нічний режим",
        guideNightSummary = "Нічне вікно з власними зонами, звуком і дзвіночками — щоб не залишитися без оповіщень.",
        guideNightD1 = "Вікно з початком і кінцем; підтримується перехід через північ (наприклад, 22:00–07:00).",
        guideNightD2 = "Поки вікно активне, діють власні нічні зони та дзвіночки — а нічні сирени можуть звучати навіть на беззвучному.",
        guideNightD3 = "Увімкнено за замовчуванням — власні нічні налаштування діють одразу.",
        guideFollowTitle = "Слідувати за мною",
        guideFollowSummary = "Мапа та обидві зони слідують за твоїм GPS — або закріпи карту за містом.",
        guideFollowD1 = "Твоє положення — синя точка, що світиться.",
        guideFollowD2 = "Зони рухаються разом із тобою, тож ти завжди знаєш, чи загроза поруч.",
        guideFollowD3 = "Перемикач — у Налаштуваннях → Центр карти.",
        guideShelterTitle = "Укриття поруч",
        guideShelterSummary = "Кнопка «Укриття» на мапі відкриває список найближчих укриттів.",
        guideShelterD1 = "Укриття відсортовані за відстанню з часом пішки — для дорослого та з дитиною.",
        guideShelterD2 = "Кнопка GPS оновлює твоє положення; перетягни список униз, щоб оновити дані.",
        guideShelterD3 = "«Відкрити в мапах» прокладає маршрут до вибраного укриття.",
        guidePinTitle = "Прикріпити до міста",
        guidePinSummary = "Закріпи мапу та зони за вибраним містом замість GPS.",
        guidePinD1 = "Зручно, коли стежиш за рідними в іншому місті.",
        guidePinD2 = "Відстань на картках міряється до цього міста, а не до тебе.",
        guideCardSizeTitle = "Розмір картки",
        guideCardSizeSummary = "Два розміри керують деталізацією картки загрози.",
        guideCardSizeD1 = "Мала — череп, тип, час підльоту і достовірність.",
        guideCardSizeD3 = "Велика — все, включно з курсом і точністю.",
        guideCardReadTitle = "Як читати картку",
        guideCardReadSummary = "Картка загрози — коротке досьє на один об'єкт.",
        guideCardReadD1 = "Шкала-череп (0–10) — приблизна оцінка рівня загрози.",
        guideCardReadD2 = "Відстань і час підльоту — від твого положення (або прикріпленого міста).",
        guideCardReadD3 = "Швидкість, хвиля, достовірність, точність і час фіксації — решта.",
        guideLangTitle = "Мова",
        guideLangSummary = "Перемикайся між українською та англійською будь-коли.",
        guideLangD1 = "Два прапори вгорі Налаштувань.",
        guideLangD2 = "Діє всюди — мапа, картки, сповіщення, цей гід.",
        guideLangD3 = "Вибрана мова зберігається для наступних запусків.",
        guideTogglesTitle = "Перемикачі типів загроз",
        guideTogglesSummary = "У кожного з 8 типів загроз — окремі перемикачі «Мапа» та «Сповіщення».",
        guideTogglesD1 = "Вимкнення «Мапи» ховає тип з мапи та замовчує його тривоги. Вимкнення «Сповіщень» лишає тип на мапі, але тьмяним.",
        guideTogglesD2 = "Тип з вимкненими «Сповіщеннями» чи «Мапою» не показується в смузі загроз.",
        guideTogglesD3 = "У кожній картці — деталі, типова швидкість і фото з вікі.",
        guideUpdateTitle = "Оновлення",
        guideUpdateSummary = "Застосунок перевіряє оновлення щодня та може оновитися сам.",
        guideUpdateD1 = "Нова версія показує список «Що нового».",
        guideUpdateD2 = "Завантажуй і встановлюй просто з діалогу.",
        guideUpdateD3 = "Можна також натиснути «Перевірити оновлення» в Налаштуваннях.",
        guideWidgetTitle = "Віджет на головному екрані",
        guideWidgetSummary = "Швидкий погляд на загрози без відкриття застосунку.",
        guideWidgetD1 = "Три розміри: компактний 2×1 показує лічильник і точку статусу, стандартний 4×2 додає рядок зони, детальний 4×3 — ще й джерело даних.",
        guideWidgetD2 = "Золотий тризуб угорі, піктограми за типами загроз і червоний рядок повітряної тривоги, коли вона оголошена.",
        guideWidgetD3 = "Оновлюється сам під час моніторингу; натисни віджет, щоб відкрити мапу. Додай його з меню віджетів системи Android.",
        lastSeenAgoFormat = "Востаннє %1\$s тому",
        neutralizedLabel = "Загрозу знешкоджено",
        neutralizedNote = "Більше не відстежується мережею.",
        neutralizingLabel = "Знешкодження…",
        neutralizingNote = "Більше не відстежується мережею.",
        fakeNeutralizingLabel = "Тільки бажання…",
        fakeNeutralizingNote = "Надсилаю сигнал у штаб…",
        flourishDisabledToastFormat = "Увімкніть «%1\$s» у налаштуваннях, щоб повторити шоу",
    )

    private fun uaExplainers() = ExplainerStrings(
        visualLabel = "Як це працює",
        scenarioLabel = "Приклад із життя",
        gotIt = "Зрозуміло",
        items = listOf(
            Triple(
                "Свої типи загроз",
                "Кожен тип має окремі перемикачі «Мапа» та «Сповіщення» — на мапі й у сирені буде лише те, що ти обрав.",
                "Вимкни нічний шум (розвідку чи FPV), але лиши ракети й БпЛА — критичні тривоги не проґавиш."
            ),
            Triple(
                "Офіційні тривоги",
                "Сповіщення обласних адміністрацій про повітряну тривогу, незалежне від даних про дрони.",
                "Офіційна сирена — найнадійніший сигнал: дій за нею, навіть якщо координати дронів ще неточні."
            ),
            Triple(
                "Сирена завжди",
                "Сирена дзвонить навіть у беззвучному чи вібро-режимі телефону.",
                "Увімкни, щоб почути тривогу в навушниках або коли звук телефону вимкнено."
            ),
            Triple(
                "Слідувати за мною",
                "Карта та зони тривог автоматично слідують за твоїм GPS-положенням.",
                "Під час руху містом зони оновлюються навколо тебе — карту не треба рухати вручну."
            ),
            Triple(
                "Розмір карток",
                "Розмір карток загроз, що виїжджають знизу при натисканні на об'єкт.",
                "Мала картка менше перекриває мапу — курс і точність залишаються на екрані."
            ),
            Triple(
                "Нічний режим",
                "Окрема конфігурація зон і сирен на нічні години.",
                "Вночі можна дзвонити лише для критичних загроз — щоб не будити родину."
            )
        )
    )

    private val UA = StringSet(
        AppLanguage.UA,
        uaOnboarding(),
        uaSettings(),
        uaStatus(),
        uaUpdates(),
        uaThreat(),
        uaMisc(),
        uaWidget(),
        uaGuide(),
        uaExplainers(),
    )

    private fun enOnboarding() = Onboarding(
        appTitle = "Ukraine Drones",
        officialAlertBanner = "Odesa: alert",
        settingsTitle = "Settings",
        settingsButton = "Settings",
        backButton = "Back",
        languageLabel = "Language",
        languageChooseTitle = "Choose language",
        languageChooseLater = "Skip",
        okButton = "OK",
        nextButton = "Next",
        onboardingTipsTitle = "A few tips",
        onboardingTipTap = "Tap any threat on the map to open its details card.",
        onboardingTipSettings = "Settings holds the feature guide and other advanced settings — check it out!",
        onboardingTipSiren = "Sirens follow your phone's sound mode by default — turn on \"Sirens always sound\" to change it.",
        onboardingTipGradual = "This app has many features — as you use it, it'll give you tips on how to use them.",
        onboardingFeaturesTitle = "Core features",
        onboardingIntro = "This setup is important — this app has advanced safety features you should know about. Take 30 seconds to get familiar now, so you're ready when it matters.",
        relaunchSetupTitle = "Replay first launch",
        wizardCareTitle = "What threats matter to you?",
        wizardCareSubtitle = "Every threat is shown on the map by default, but you can toggle only the alerts here. Change either in",
        wizardCareSubtitleGearSuffix = "settings.",
        wizardCareOn = "Alert",
        wizardCareOff = "Off",
        wizardStartButton = "Stay safe…",
        wizardLocationTitle = "Where are you?",
        wizardLocationSubtitle = "Choose where your position comes from — it drives your zones and alerts.",
        wizardZonesTitle = "Alert zones",
        wizardZonesSubtitle = "The map has red and yellow zones, as well as a time-based zone for fast objects. Adjust them to your comfort.",
        wizardEditZonesHint = "Drag the slider for your comfort zones.",
        zoneRedLabel = "Tap to zoom",
        zoneYellowLabel = "Yellow zone",
        wizardShelterTitle = "Nearby shelters",
        wizardShelterDesc = "Nearest shelters with walking time.",
        wizardJustFunTitle = "Just fun)))",
        wizardJustFunDesc = "Animations, flybys, tally — cosmetic only. Tune in",
        wizardJustFunDescGearSuffix = "settings.",
        wizardNeptunStatus = "Your threat data comes from Neptun — updated in real time.",
        settingsSearchHint = "Search",
        settingsSearchClear = "Clear",
        settingsNoResults = "Nothing found",
        settingsSearchRelated = "You might also look for",
        settingsDidYouMean = "Did you mean",
        fitMapLabel = "Center map on Ukraine",
    )

    private fun enSettings() = Settings(
        dayZonesTitle = "Day zones",
        nightZonesTitle = "Night zones",
        alertZonesTitle = "Alert zones",
        dayShortLabel = "day",
        nightModeHeaderDesc = "Night mode active",
        redZoneLabel = "Red zone",
        yellowZoneLabel = "Yellow zone",
        alertsLabel = "Alerts",
        redZoneAlertsTitle = "Red zone alerts",
        redZoneAlertsDesc = "Siren for threats in the red zone.",
        yellowZoneAlertsTitle = "Yellow zone alerts",
        yellowZoneAlertsDesc = "Warning chime for threats in the yellow zone.",
        officialAlertsTitle = "Official alerts",
        officialAlertsDesc = "Alerts for the official oblast air-raid signal. Does not affect your Red/Yellow zone alerts.",
        officialAlertsRedTridentNote = "in the header means the official alert is on",
        officialAlertScopeTitle = "City level",
        officialAlertScopeDesc = "Whole oblast by default. Alert only when your city is covered.",
        sirenOverrideTitle = "Sirens always sound",
        sirenOverrideDesc = "Ring the siren even when the phone is on vibrate or silent. Off by default — alerts follow your phone's sound settings.",
        nightModeLabel = "Night mode",
        nightModeDesc = "Separate sound, vibration and zone settings for the night window.",
        nightStartTimeLabel = "Start",
        nightEndTimeLabel = "End",
        nightSoundLabel = "Sound at night",
        nightZoneSirenOverrideTitle = "Sirens always sound",
        nightZoneSirenOverrideDesc = "Zone sirens ring at night even on vibrate/silent. Off — sound follows your phone's settings.",
        nightOfficialSirenOverrideTitle = "Official alerts always sound",
        nightOfficialSirenOverrideDesc = "Official alerts ring at night even on vibrate/silent. The City level setting in Alerts decides whether the whole oblast or only your city rings.",
        nightCustomZonesTitle = "Separate night zones",
        nightCustomZonesDesc = "Own distances/times and red/yellow zones for the night window.",
        nightMuteExitNote = "If you truly want total silence, use \"Stop Monitoring & Exit\" below instead — otherwise you may forget to switch alerts back on tomorrow.",
        allAlertsOffLabel = "All alerts are off",
        notificationsDisabledLabel = "Enable notifications",
        zoneButtonRed = "Red zone: tap to zoom in",
        zoneButtonYellow = "Yellow zone: tap to zoom in",
        slowSectionLabel = "Slow — distance",
        fastSectionLabel = "Fast — time to arrival",
        threatsLabel = "Threats",
        threatMapLabel = "Map",
        threatAlertLabel = "Alerts",
        fastGroupLabel = "Fast",
        slowGroupLabel = "Slow",
        fastGroupIconDesc = "Lightning — fast threats",
        slowGroupIconDesc = "Turtle — slow threats",
        mapToggleHintPrefix = "Map",
        mapToggleHintRest = " off hides the type and silences its alerts.",
        alertToggleHintPrefix = "Alerts",
        alertToggleHintRest = " off keeps it on the map, just dimmed.",
        disclaimerTitle = "Disclaimers",
        disclaimerBody = "Always follow official air-raid signals — this app is not an official alert source.\n\nPositions, distances, ETA and speeds are approximate: the enemy isn't broadcasting precisely, even when a fix is marked confirmed.\n\nThe skull threat level (0–10) is a rough estimate, not an official rating.\n\nAlerts depend on your phone's coarse location — a wrong fix means a wrong zone.\n\nThis app cannot guarantee your safety. Treat everything as an approximate guide.",
        exitButton = "Stop Monitoring & Exit",
        systemSectionTitle = "System & Display",
        locationSectionTitle = "Location & Focus",
        justFunSectionTitle = "Just Fun",
        flybyAnimationLabel = "MiG flyby animation",
        flybyAnimationDesc = "Enable or disable the MiG-31K flyby animation and sound",
        cardSizeSmallLabel = "Small cards",
        cardSizeLargeLabel = "Large cards",
    )

    private fun enStatus() = Status(
        redZoneAlert = "Red alert",
        yellowZoneAlert = "Yellow alert",
        notifOngoingTitle = "Monitoring GPS",
        notifMonitoringCityFormat = "Monitoring %s",
        notifChannelName = "Background status",
        notifChannelDesc = "Monitoring runs in the background. Turning this off only hides this status notification — air-raid alerts still sound.",
        notifBodyRegion = "Odesa region",
        neutralizedNotifChannelName = "Resolved threats",
        neutralizedChannelDesc = "Tally of threats resolved near you — kept until dismissed.",
        notifUpdateTitle = "New version available",
        notifUpdateText = "Version %s is ready — tap to update",
        notifUpdateChannelName = "Updates",
        notifUpdateChannelDesc = "Notifications about available app updates.",
        attributionText = "Air threat map — NEPTUN",
        madeBy = "Made by Presaince",
connOnline = "Online",
        connOffline = "Offline",
        connDegraded = "Backup",
        connDegradedBody = "Backup connection active — threat data may be delayed.",
        connServerLine = "Connection to the NEPTUN air-threat server.",
        connStatusTitle = "System status",
        connNeptunLabel = "NEPTUN",
        connActiveLabel = "Active",
        reliabilityLow = "Reliability: Low",
        reliabilityMedium = "Reliability: Average",
        reliabilityHigh = "Reliability: High",
        reliabilityUnknown = "Reliability: Unknown",
        reliabilityLabel = "Reliability",
        reliabilityShort = "R",
        alertsOffLabel = "off",
        speedUnit = "kmh",
        groupLabel = "Wave size",
        uncertaintyLabel = "Precision",
        noRegion = "Odesa region",
        unknownLocation = "Unknown location",
        minutesAgoSuffix = "min ago",
        justNow = "just now",
        hoursAgoSuffix = "h ago",
        daysAgoSuffix = "d ago",
        mixedTimeFormat = "%1\$d h %2\$d min ago",
        noThreatsMessage = "No relevant threats",
    )

    private fun enUpdates() = Updates(
        updateAvailableTitle = "New version available",
        updateVersionLabel = "Version",
        updateNotesTitle = "What's new",
        updateDownload = "Download",
        updateDownloading = "Downloading… %1\$d%%",
        updateInstall = "Install",
        updateLater = "Later",
        updateRetry = "Try again",
        updateFailedTitle = "Failed to download update",
        updateInstallPermissionTitle = "Install permission",
        updateInstallPermissionBody = "To install the update, allow this app to install other apps.",
        updateOpenSettings = "Open settings",
        updateReadyToInstallTitle = "Update ready",
        updateReadyToInstallBody = "The update has been downloaded. Tap Install to apply it.",
        updateUpToDate = "You have the latest version",
        updateCheckFailed = "Update check failed",
        checkForUpdates = "Check for updates",
        updateButton = "Update",
        updateAvailableButton = "Update available",
        updateAvailableOnOpen = "Update v%s is available",
    )

    private fun enThreat() = Threat(
        advisoryLabel = "Observation",
        areaOnlyLabel = "Area-level (no precise point)",
        cardSkullNote = "Skull (0–10) is an approximate estimate — type, distance, reliability, sources. Not an official rating or a guarantee of your safety.",
        distanceLabel = "Distance",
        distanceToCityFormat = "Distance to %1\$s",
        etaLabel = "ETA",
        etaUnit = "min",
        approxNote = "All numbers in the app are approximate",
        pillDistanceCd = "Distance to %1\$s: %2\$d km",
        gpsOffLabel = "GPS off — no zone data",
        inRedZone = "In red zone",
        inYellowZone = "In yellow zone",
        outsideZones = "Outside alert zones",
        editZonesLabel = "Edit zones",
        alertsBellToggle = "Toggle alerts on/off",
        kmUnit = "km",
        minUnit = "min",
        meterUnit = "m",
        moreInfoLabel = "More info",
        offLabel = "OFF",
        mapCenterLabel = "Map centre",
        followMeTitle = "Follow me",
        followMeDesc = "The map and zones follow your GPS position.",
        pinCityTitle = "Pin to a city",
        pinCityDesc = "The map centre and zones pin to the selected city.",
alertBannerFormat = "%1\$s: alert",
        notifBodyRegionFormat = "%1\$s oblast",
        notifOfficialFormat = "Official alert in %1\$s",
        notifOfficialWithReasonFormat = "Official alert in %1\$s: %2\$s",
        offlineStatusTitle = "Offline",
        offlineBodyFormat = "Offline — connection to NEPTUN lost. Tap to retry.",
        offlineOfficialSirensLine = "Rely on official sirens.",
        offlineRetryAction = "Retry",
        offlineChannelName = "Offline monitoring",
        offlineChannelDesc = "Alerts when the connection to the threat server is lost.",
        offlineMilestone3Min = "Offline — retrying in background.",
        offlineMilestone6Min = "Offline 6 min — retrying.",
        offlineMilestone10Min = "Offline 10 min — retrying, delays growing.",
        offlineMilestone20Min = "20 min offline — reconnection stopped. Toggle Airplane mode, or open the app and tap the Offline pill to retry now. Otherwise the app gives up for 30 min.",
        offlineCriticalChannelName = "Critical offline",
        offlineCriticalChannelDesc = "Rings after 5 minutes of lost connection to the threat server.",
        offlineCritical5Min = "5 min offline — tap Retry.",
        offlineCriticalOverrideTitle = "Critical offline alert",
        offlineCriticalOverrideDesc = "Notify after 5 minutes without a connection to the threat server.",
        offlineCriticalBypassSilentTitle = "Override silent mode",
        offlineCriticalBypassSilentDesc = "Play sound even when the phone is on silent. Does not bypass Do Not Disturb — enable the override in system channel settings if needed.",
        offlineIgnoreAction = "Ignore 30 min",
        offlinePausedBody = "Retrying paused for 30 min — tap Offline to resume early.",
        offlineLiveFormat = "Offline %1\$d/%2\$d min · attempt %3\$d",
        alertChannelName = "Air alerts",
        alertChannelDesc = "Air-raid sirens and urgent zone alerts",
        outerAlertChannelName = "Region alerts",
        outerAlertChannelDesc = "OUTER zone (Регіон) warning alerts",
        allClearChannelName = "All clear",
        allClearChannelDesc = "Cheerful chime when the official air-raid alert ends",
        alarmAlertChannelName = "Air alerts — always sound",
        alarmAlertChannelDesc = "Air-raid sirens and urgent zone alerts, even on vibrate/silent",
        outerAlarmAlertChannelName = "Region alerts — always sound",
        outerAlarmAlertChannelDesc = "OUTER zone (Регіон) warning alerts, even on vibrate/silent",
    )

    private fun enMisc() = Misc(
        connLogTitle = "Live connection log",
        connLogEmpty = "No events yet",
        connLogDurFormat = "%1\$dm %2\$ds",
        allClearTitle = "%1\$s: all clear",
        allClearText = "The official air-raid alert has ended.",
        batteryTitle = "Battery exemption",
        batteryBody = "Android may pause alerts in the background. Allowing unrestricted background use keeps alerts ringing — the app uses very little power.",
        batteryAllowButton = "Allow",
        batteryLater = "Later",
        batteryGranted = "Unrestricted in background",
        batteryOemTitle = "Background restriction on this device",
        batteryOemBody = "This device may restrict background apps. Allow unrestricted background use to keep air-raid alerts ringing.",
        batteryGenericBody = "This device may restrict background apps. Unrestricted background use keeps alerts alive.",
        resetTipsTitle = "Reset all tips",
        resetTipsDesc = "Show first-run hints again",
        tipsResetToast = "Tips reset",
        vibrationOff = "Off",
        vibrationSoft = "Soft",
        vibrationMedium = "Medium",
        vibrationStrong = "Strong",
        hapticsTitle = "Haptic feedback",
        hapticsDesc = "A small vibration tick on presses across the app.",
        vibrationUrgent = "Urgent",
        logDistanceFormat = "%1\$d km",
        alertAgeSecSuffix = "sec",
        alertAgeMinSuffix = "min",
        alertAgeHrSuffix = "hr",
        logsTitle = "Logs",
        logsFilterConnections = "Connections",
        logsFilterDecisions = "Decisions",
        logsShowMore = "Show more",
        logsEmptyConnections = "No connection changes yet",
        logsGroupTimeline = "Timeline",
        logsGroupProximity = "Proximity",
        logsGroupType = "Type",
        logsShownOnly = "Shown only",
        logsNotified = "Notified",
        logsProxOblast = "In oblast",
        logsSortDesc = "Toggle newest/oldest first",
        logsSortNewest = "Newest",
        logsSortOldest = "Oldest",
        logsFlourishToggle = "Resolved",
        logsSubtitleFormat = "Last 24h · %1\$d",
        logsSortDistance = "Distance",
        logsSortAge = "Age",
        debugLogEmpty = "No decisions logged yet",
        debugLogClear = "Clear",
        debugLogOpen = "Logs",
        debugLogDay = "Day",
        debugLogNight = "Night",
        debugLogShown = "Notified",
        debugLogSuppressed = "No notification — %1\$s",
        debugLogSoundOverride = "Siren override",
        debugLogSoundFollows = "Follows ringer",
        debugGroupOfficial = "Official alerts",
        debugGroupLeft = "Left the region",
        debugBandCloseFormat = "≤ %1\$d km",
        closeButton = "Close",
        debugBandMidFormat = "%1\$d–%2\$d km",
        debugBandFarFormat = "%1\$d–%2\$d km",
        debugBandFarthestFormat = "> %1\$d km",
        debugBandCountFormat = "%1\$d",
        debugReasonBellMuted = "bell muted for this type",
        debugReasonAlreadyNotified = "already notified for this tier",
        debugReasonCoalesced = "another alert posted first",
        debugReasonTypeOff = "type alerts off in settings",
        debugReasonAdvisory = "observation threat — never alerts",
        debugReasonStale = "stale",
        debugReasonOutsideZones = "inside region, outside alert zones",
        debugReasonToggleOff = "official-alert notifications off",
        debugReasonLeft = "left the region",
        debugKindOfficialOn = "Official alert on",
        debugKindOfficialOff = "Official alert off",
        debugKindZoneEnter = "Entered zone",
        debugKindZoneExit = "Left zone",
        debugKindRegionThreat = "Threat in region",
        debugKindFlourish = "Shoot-down show",
        flourishLogDetailFormat = "Shots: %1\$d · Groups: %2\$d",
        flourishEjectToast = "Shoot-down show waits until you're back on the map",
        debugKindRegionFormat = "Threat in %1\$s",
        debugTierRed = "Red zone",
        debugTierYellow = "Yellow zone",
        connEventLost = "Connection lost",
        connEventRetry = "Retrying in %1\$ds · attempt %2\$d",
        connEventNoNetwork = "No network — waiting to retry",
        connEventDegraded = "Backup connection",
        connEventMin3 = "3 min offline",
        connEventMin5 = "5 min offline — alarm",
        connEventMin6 = "6 min offline",
        connEventMin10 = "10 min offline",
        connEventMin20 = "20 min offline — reconnection stopped",
        connEventGaveUp = "Gave up — reconnect paused",
        connEventPaused = "Retrying paused for 30 min",
        cardSizeLabel = "Threat card size and detail",
        additionalSettingsTitle = "Additional settings",
        showMapScaleTitle = "Show scale",
        showMapScaleDesc = "Scale bar in the map's corner.",
        cityLabelsTitle = "City labels",
        cityLabelsDesc = "Labels for medium and small towns.",
        mediumCitiesChip = "Mid-size",
        smallCitiesChip = "Small",
        calmMessagesTitle = "Calm messages",
        calmMessagesDesc = "Show short encouragements in the bottom bar when there are no threats. Turn off to just say \"No relevant threats\".",
        deathAnimationTitle = "Shoot-down animation",
        deathAnimationDesc = "When a threat is no longer tracked, a bullet flies in and shoots it down. Just for fun.",
        followBulletTitle = "Follow the bullet",
        followBulletDesc = "The projectile flies in toward the target, and the camera glides onto the strike.",
        neutralizedTallyTitle = "Neutralized count",
        neutralizedTallyDesc = "Show a notification counting threats neutralized in your oblast while monitoring is running. Tapping replays the fun on the map.",
        neutralizedTallyAllUkraineTitle = "All of Ukraine",
        neutralizedTallyAllUkraineDesc = "Also count neutralized threats anywhere in Ukraine, not just your oblast.",
        justFunNote = "These are just for fun — they never affect monitoring or alerts. During a live air alert you can still replay a show (it's your own tap), but if a new alert starts mid-show it clears instantly so the siren owns your screen. When in doubt, trust the official siren over any fireworks.",
        iconSetTitle = "Choose your favourite threat icons!",
        iconSetPhotoLabel = "Photos",
        iconSetArmyLabel = "Army",
        iconSetComicLabel = "Comic",
        iconSetRussianLabel = "Russian",
        shelterButtonLabel = "Shelter",
        shelterScreenTitle = "Nearby shelters",
        shelterOpenInMaps = "Open",
        shelterEmpty = "No shelters found nearby",
        shelterSettingsTitle = "Shelter button",
        shelterSettingsDesc = "\"Go to shelter\" button on the map.",
        shelterDistanceM = "%d m",
        shelterDistanceKm = "%.1f km",
        shelterWalkMinutes = "~%d min",
        shelterWalkAdultLabel = "adult",
        shelterWalkKidLabel = "with kids",
        shelterSectionTitle = "Shelter",
        shelterWithKidsTitle = "With kids",
        shelterWithKidsDesc = "Kids walk slower.",
        shelterShowOnMap = "Show on map",
        shelterGpsUnknown = "GPS: —",
        periodicGpsTitle = "Periodic GPS sync (15 min)",
        periodicGpsDesc = "Normal background monitoring uses low-power cell towers. Periodic sync wakes GPS for a few seconds every 15 min to prevent cell-tower drift from skewing threat distance and shelter walking times.",
        calibrateGpsNow = "Calibrate GPS",
        calibratingGps = "Acquiring GPS…",
        lastGpsFixFormat = "Precise GPS: %s",
        gpsFixJustNow = "just now",
        gpsPreciseBlocked = "The system won't ask again. Enable precise GPS in Settings.",
        gpsOpenSettings = "Settings",
        gpsFixFresh = "Precise GPS: fresh",
        gpsStatusTitle = "Precise GPS",
        networkLocationOnly = "Cell tower location only",
        updatingPreciseGpsToast = "Updating precise GPS…",
        gpsFallbackOdesa = "GPS unavailable — using Odesa",
        shelterLongPressTip = "Long-press the shelter button to open the full list",
        shelterTapTip = "Tap the shelter button to see shelters near you",
        shelterViewListLabel = "Shelter directory (reference)",
        shelterViewListDesc = "Browse the full directory of all city shelters",
        shelterTypeBasic = "Basic shelter",
        shelterTypeMobile = "Mobile shelter",
        shelterTypeBunker = "Civil defense bunker (CDPS)",
        logsFilterSystem = "System",
        apiSdkChanged = "SDK changed",
        apiSdkCheckFailed = "SDK check failed",
        apiSystemEmpty = "No system changes recorded",
        apiMalformedFrame = "Malformed frame",
        apiSdkViewManifest = "View manifest →",
        apiUnknownType = "Unknown type: %s",
        logsLegend = "Legend",
        logsFilterTests = "Tests",
    )

    private fun enWidget() = Widget(
        threatsLabel = "Threats",
        noThreats = "No threats",
        active = "ACTIVE",
        updatedFormat = "Updated %1\$s",
        updatedNowLabel = "Updated now",
        threatsAwayFormat = "Threats ~%1\$d km",
        refreshLabel = "Refresh",
        officialAlertLabel = "Air alert",
    )

    private fun enGuide() = Guide(
        guideTitle = "Feature guide",
        guideSettingsButton = "Feature guide",
        guideCategoryMap = "Map",
        guideCategoryZones = "Zones & alerts",
        guideCategoryLocation = "Location",
        guideCategoryCards = "Threat cards",
        guideCategoryWidget = "Widget",
        guideCategorySettings = "Settings",
        guideLiveTitle = "Live threat map",
        guideLiveSummary = "See every tracked threat moving across the whole country.",
        guideLiveD1 = "Markers show all 8 threat types — drones, missiles, guided bombs and more.",
        guideLiveD2 = "During an alert the map widens so you can check other regions at a glance.",
        guideLiveD3 = "Tap a marker to open its detail card; tap empty map to close it.",
        guideStripTitle = "Threat strip",
        guideStripSummary = "The strip at the bottom is a live scoreboard of threats by type.",
        guideStripD1 = "Only the types present right now appear — each tile shows its live count.",
        guideStripD2 = "A type with no threats is hidden; when nothing is around, the strip shows 'No relevant threats'.",
        guideStripD3 = "A type disabled in Settings appears greyed out.",
        guideConnTitle = "Connection & scale",
        guideConnSummary = "The top pill shows your link to the alert server; the bar shows distance.",
        guideConnD1 = "Online means data is updating live; Offline warns data may be stale.",
        guideConnD2 = "Tap the pill to see server status and the NEPTUN attribution.",
        guideConnD3 = "The scale bar in the corner gives you real distances on the map.",
        guideZonesTitle = "Red & yellow zones",
        guideZonesSummary = "Slow threats are measured by distance, fast threats by time-to-arrival.",
        // NOTE: 20/50 km are defaults — see UserPrefs. Guide uses them as illustrative examples.
        guideZonesD1 = "Slow (drones): red zone within 20 km, yellow within 50 km.",
        // NOTE: 5/20 min are defaults — see UserPrefs. Guide uses them as illustrative examples.
        guideZonesD2 = "Fast (missiles, guided bombs): red zone within 5 min, yellow within 20 min to arrival.",
        guideZonesD3 = "The circles on the map show the slow zones' distance and follow your position.",
        guideEditZonesTitle = "Zones & alerts",
        guideEditZonesSummary = "The panel under the map tunes both zones and the Fast/Slow toggles.",
        guideEditZonesD1 = "Sliders change the circles on the map live — everything is visible at once.",
        guideEditZonesD2 = "Tap the bell to arm or mute a zone's alerts.",
        guideEditZonesD3 = "Changes apply immediately and stay saved.",
        guideNotifTitle = "Notifications",
        guideNotifSummary = "The app keeps listening in the background and sounds zone alerts.",
        guideNotifD1 = "Red zone → siren; yellow zone → chime; official oblast alert → its own sound.",
        guideNotifD2 = "A quiet status notification shows monitoring is running.",
        guideNotifD3 = "Your phone stays monitored even when the app is closed.",
        guideFastTitle = "Zone alerts",
        guideFastSummary = "Time-to-arrival zones work the same for every object type.",
        guideFastD1 = "The faster the object, the sooner it crosses a zone — the siren sounds immediately.",
        guideFastD2 = "Official alerts: adds the oblast air-raid signal on top.",
        guideFastD3 = "Zone settings live in the zone button on the map or in Settings.",
        guideNightTitle = "Night mode",
        guideNightSummary = "A night window with its own zones, sound and bells — so you never miss an alert.",
        guideNightD1 = "A window with a start and end; overnight schedules (past midnight) work, e.g. 22:00–07:00.",
        guideNightD2 = "While the window is active, its own zones and bells apply — and night sirens can ring even on silent.",
        guideNightD3 = "On by default — your separate night settings apply right away.",
        guideFollowTitle = "Follow me",
        guideFollowSummary = "The map and both zones follow your GPS position — or pin the map to a city instead.",
        guideFollowD1 = "Your position shows as a glowing blue dot.",
        guideFollowD2 = "Zones move with you, so you always know if a threat is near.",
        guideFollowD3 = "Toggle it in Settings → Map centre.",
        guideShelterTitle = "Nearby shelters",
        guideShelterSummary = "The \"Shelter\" button on the map opens the list of the nearest shelters.",
        guideShelterD1 = "Shelters are sorted by distance with walking times — as an adult and with a child.",
        guideShelterD2 = "The GPS button refreshes your position; drag the list down to refresh the data.",
        guideShelterD3 = "\"Open in maps\" routes you to the chosen shelter.",
        guidePinTitle = "Pin to a city",
        guidePinSummary = "Fix the map and zones on a chosen city instead of your GPS.",
        guidePinD1 = "Useful when you're watching over family elsewhere.",
        guidePinD2 = "Distance on threat cards is measured to that city, not to you.",
        guideCardSizeTitle = "Card sizes",
        guideCardSizeSummary = "Two sizes control how much each threat card shows.",
        guideCardSizeD1 = "Small — skull, type, ETA and reliability.",
        guideCardSizeD3 = "Large — everything, including course and precision.",
        guideCardReadTitle = "Reading a card",
        guideCardReadSummary = "A threat card is a quick dossier on one object.",
        guideCardReadD1 = "The skull gauge (0–10) is an approximate threat level.",
        guideCardReadD2 = "Distance and ETA are computed from your position (or pinned city).",
        guideCardReadD3 = "Speed, wave size, reliability, precision and age make up the rest.",
        guideLangTitle = "Language",
        guideLangSummary = "Switch between Ukrainian and English anytime.",
        guideLangD1 = "Two flags at the top of Settings.",
        guideLangD2 = "Applies everywhere — map, cards, notifications, this guide.",
        guideLangD3 = "Your choice is remembered on next launches.",
        guideTogglesTitle = "Threat toggles",
        guideTogglesSummary = "Each of the 8 threat types has separate Map and Alerts controls.",
        guideTogglesD1 = "Turning off Map hides the type and silences its alerts. Turning off Alerts keeps it on the map, but dimmed.",
        guideTogglesD2 = "A type with Alerts or Map off is hidden from the threat strip.",
        guideTogglesD3 = "Each card has details, typical speed and a wiki photo.",
        guideUpdateTitle = "Updates",
        guideUpdateSummary = "The app checks daily and can update itself.",
        guideUpdateD1 = "A new version shows a What's new note.",
        guideUpdateD2 = "Download and install straight from the dialog.",
        guideUpdateD3 = "You can also tap Check for updates in Settings.",
        guideWidgetTitle = "Home screen widget",
        guideWidgetSummary = "A quick look at threats without opening the app.",
        guideWidgetD1 = "Three sizes: compact 2×1 shows the count and a status dot; standard 4×2 adds a zone line; detailed 4×3 adds the data source line.",
        guideWidgetD2 = "A gold trident header, per-type threat icons, and a red air-alert strip when one is declared.",
        guideWidgetD3 = "It updates on its own while monitoring; tap the widget to open the map. Add it from the Android widget picker.",
        lastSeenAgoFormat = "Last seen %1\$s ago",
        neutralizedLabel = "Threat neutralized",
        neutralizedNote = "No longer tracked by the network.",
        neutralizingLabel = "Neutralizing threat…",
        neutralizingNote = "No longer tracked by the network.",
        fakeNeutralizingLabel = "Wishful thinking…",
        fakeNeutralizingNote = "Sending signal to HQ…",
        flourishDisabledToastFormat = "Turn on \"%1\$s\" in Settings to replay the show",
    )

    private fun enExplainers() = ExplainerStrings(
        visualLabel = "How it works",
        scenarioLabel = "In real life",
        gotIt = "Got it",
        items = listOf(
            Triple(
                "Your threat types",
                "Each type has separate Map and Alerts switches — the map and siren only show what you picked.",
                "Turn off the nightly noise (recon or FPV) but keep missiles and UAVs on — you won't miss critical alerts."
            ),
            Triple(
                "Official alerts",
                "Oblast-level air-raid warnings, independent of the drone feed.",
                "An official siren is the most reliable signal — follow it even when drone positions are still vague."
            ),
            Triple(
                "Sirens always sound",
                "Sirens ring even on silent or vibrate.",
                "Turn it on to hear an alert through headphones or when your phone is muted."
            ),
            Triple(
                "Follow me",
                "The map and alert zones automatically follow your GPS position.",
                "Moving through the city, zones update around you — no need to pan the map manually."
            ),
            Triple(
                "Card sizes",
                "The size of threat cards that slide up when you tap an object.",
                "A small card covers less of the map — course and precision stay on screen."
            ),
            Triple(
                "Night mode",
                "A separate set of zones and sirens for night hours.",
                "At night you can ring only for critical threats — so you don't wake the family."
            )
        )
    )

    private val EN = StringSet(
        AppLanguage.EN,
        enOnboarding(),
        enSettings(),
        enStatus(),
        enUpdates(),
        enThreat(),
        enMisc(),
        enWidget(),
        enGuide(),
        enExplainers(),
    )

}

/**
 * Glossary for translating common Ukrainian words inside shelter names into English.
 * Applied before transliteration when the UI language is English.
 * Phrase entries must be replaced before single-word entries.
 */
val SHELTER_NAME_GLOSSARY: Map<String, String> = mapOf(
    "№" to "No.",
    "найпростіше укриття" to "simplest shelter",
    "первинне (мобільне) укриття" to "primary (mobile) shelter",
    "укриття: ст. м." to "Shelter: metro st.",
    "укриття: ст. швидк. трамвая" to "Shelter: fast tram st.",
    "ст. м." to "metro st.",
    "ст. швидк. трамвая" to "fast tram st.",
    "метро" to "metro",
    "укриття" to "shelter",
    "зсцз" to "CDS",
    "вулиця" to "street",
    "вул." to "St",
    "вул" to "St",
    "дорога" to "road",
    "бульвар" to "boulevard",
    "бул" to "Blvd",
    "проспект" to "prospect",
    "просп." to "Ave",
    "просп" to "Ave",
    "майдан" to "sq.",
    "площа" to "sq.",
    "пл" to "sq.",
    "провулок" to "lane",
    "пров" to "Ln",
    "жк" to "complex",
    "перлина" to "Pearl",
    "пляж" to "beach",
    "парк" to "park",
)

/**
 * Pluralized "source(s)" word for the given count, per language.
 * English: 1 "source" / N "sources". Ukrainian: 1 "джерело" (21, 31…),
 * 2–4 "джерела" (22–24…), else "джерел" (5–20, 25–30…).
 */
fun sourcesWord(count: Int, lang: AppLanguage): String = when (lang) {
    AppLanguage.EN -> if (count == 1) "source" else "sources"
    AppLanguage.UA -> {
        val n10 = count % 10
        val n100 = count % 100
        when {
            n10 == 1 && n100 != 11 -> "джерело"
            n10 in 2..4 && n100 !in 12..14 -> "джерела"
            else -> "джерел"
        }
    }
}

/**
 * Pluralized "N threats resolved" headline for the tally notification.
 * English: "1 threat resolved" / "N threats resolved".
 * Ukrainian: "1 загрозу" (21, 31…), "2–4 загрози" (22–24…), else "загроз" (5–20, 25–30…).
 */
fun resolvedThreatsPhrase(count: Int, lang: AppLanguage): String = when (lang) {
    AppLanguage.EN -> if (count == 1) "1 threat resolved" else "$count threats resolved"
    AppLanguage.UA -> {
        val n10 = count % 10
        val n100 = count % 100
        val form = when {
            n10 == 1 && n100 != 11 -> "загрозу"
            n10 in 2..4 && n100 !in 12..14 -> "загрози"
            else -> "загроз"
        }
        "Завершено $count $form"
    }
}

/** Footer copy during the tally-tap replay: "Resolving N threats" for the current group.
 *  Same UA accusative plural forms as [resolvedThreatsPhrase]. */
fun resolvingThreatsPhrase(count: Int, lang: AppLanguage): String = when (lang) {
    AppLanguage.EN -> if (count == 1) "Resolving 1 threat" else "Resolving $count threats"
    AppLanguage.UA -> {
        val n10 = count % 10
        val n100 = count % 100
        val form = when {
            n10 == 1 && n100 != 11 -> "загрозу"
            n10 in 2..4 && n100 !in 12..14 -> "загрози"
            else -> "загроз"
        }
        "Знешкоджуємо $count $form"
    }
}

/** Light encouragement shown in the footer strip when no threats are around — re-rolled at
 *  random each time the calm state is entered (first launch / after an all-clear). When the
 *  user turns the calm messages off, the footer always reads just "No relevant threats".
 */
fun noThreatsMessage(lang: AppLanguage, calmMessages: Boolean = true): String {
    if (!calmMessages) return if (lang == AppLanguage.UA) "Немає актуальних загроз" else "No relevant threats"
    val en = listOf(
        "Quiet — go touch grass",
        "Breathe",
        "Say I love you today",
        "Be grateful for this calm",
        "Enjoy the silence",
        "Check in on someone you love",
        "A good day to rest",
        "All quiet on your front",
        "A calm sky is still a gift",
        "Step outside for a minute",
        "Make some tea while it's quiet",
        "Hug someone today",
        "Put your phone down for a while",
        "Quiet is not emptiness",
        "Rest now, so you're ready later",
        "Peace looks good on you"
    )
    val ua = listOf(
        "Тихо — піди погуляй",
        "Подихай",
        "Скажи сьогодні «люблю тебе»",
        "Будь вдячним за цей спокій",
        "Насолоджуйся тишею",
        "Напиши тому, кого любиш",
        "Гарний день відпочити",
        "У тебе все тихо",
        "Спокійне небо — це теж подарунок",
        "Вийди на хвилинку надвір",
        "Зроби собі чаю, поки тихо",
        "Обійми сьогодні когось",
        "Відкладіть телефон на деякий час",
        "Тиша — це не порожнеча",
        "Відпочинь зараз, щоб потім бути готовим",
        "Тобі личить мир"
    )
    val list = if (lang == AppLanguage.UA) ua else en
    return list.random()
}

/**
 * Age of the last precise GPS fix on the shelter screen, with proper plurals per language.
 * English: "GPS fix is 1 minute old" / "3 minutes old". Ukrainian: "GPS-фікс застарілий на
 * 1 хвилину" / "3 хвилини" / "5 хвилин" (21, 22-24, 25-30… follow the same rule).
 */
fun preciseGpsAgePhrase(minutes: Long, lang: AppLanguage): String = when (lang) {
    AppLanguage.EN -> if (minutes == 1L) "Precise GPS: 1 min ago" else "Precise GPS: $minutes min ago"
    AppLanguage.UA -> {
        val n10 = minutes % 10
        val n100 = minutes % 100
        val form = when {
            n10 == 1L && n100 != 11L -> "хвилину"
            n10 in 2L..4L && n100 !in 12L..14L -> "хвилини"
            else -> "хвилин"
        }
        "Точний GPS: $minutes $form тому"
    }
}

/** Formats elapsed time since a threat's last fix as `m:ss` (e.g. 3:47, 0:05). */
fun formatElapsedMss(updatedAtMillis: Long?, nowMillis: Long): String {
    if (updatedAtMillis == null) return "–"
    val secs = ((nowMillis - updatedAtMillis) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(secs / 60, secs % 60)
}

/** Formats a relative time string like NEPTUN's "2 J. 4 minutes ago" pattern, simplified. */
fun formatRelativeTime(updatedAtIso: String?, lang: AppLanguage): String {
    if (updatedAtIso.isNullOrBlank()) return Strings.get(lang).justNow
    return try {
        val then = java.time.Instant.parse(updatedAtIso)
        val now = java.time.Instant.now()
        val minutes = java.time.Duration.between(then, now).toMinutes()
        val s = Strings.get(lang)
        when {
            minutes < 1 -> s.justNow
            minutes < 60 -> "$minutes ${s.minutesAgoSuffix}"
            minutes < 60 * 24 -> {
                val h = minutes / 60
                val m = minutes % 60
                if (m == 0L) "$h ${s.hoursAgoSuffix}" else String.format(s.mixedTimeFormat, h, m)
            }
            else -> {
                val d = minutes / (60 * 24)
                val h = (minutes % (60 * 24)) / 60
                if (h == 0L) "$d ${s.daysAgoSuffix}" else String.format(s.mixedTimeFormat, d, h)
            }
        }
    } catch (e: Exception) {
        Strings.get(lang).justNow
    }
}

/**
 * Age of an alert-history entry in compact buckets: "1-59 sec", "1-59 min", "1-6 hr"
 * (older entries are pruned at 6 hours). Rendered per the selected language.
 */
fun formatAlertAge(nowMillis: Long, atMillis: Long, s: Strings.StringSet): String {
    val secs = ((nowMillis - atMillis) / 1000).coerceAtLeast(1)
    return when {
        secs < 60 -> "$secs ${s.alertAgeSecSuffix}"
        secs < 3600 -> "${secs / 60} ${s.alertAgeMinSuffix}"
        else -> "${secs / 3600} ${s.alertAgeHrSuffix}"
    }
}

/**
 * Absolute timestamp rendered per the selected app language, not the device locale — the single
 * site-wide datetime formatter (Logs screen). UA: "17.08, 14:30", EN: "Aug 17, 14:30".
 */
fun formatDateTime(lang: AppLanguage, millis: Long): String {
    val zoned = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    val pattern = if (lang == AppLanguage.UA) "dd.MM, HH:mm" else "MMM d, HH:mm"
    val locale = if (lang == AppLanguage.UA) java.util.Locale("uk") else java.util.Locale.ENGLISH
    return zoned.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}
