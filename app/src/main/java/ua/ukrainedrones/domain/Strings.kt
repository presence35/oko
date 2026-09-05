package ua.ukrainedrones

object Strings {
    fun get(lang: AppLanguage): StringSet = when (lang) {
        AppLanguage.UA -> Ukrainian
        AppLanguage.EN -> English
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
        val cardSizeLargeLabel: String,
        val bootRestartTitle: String,
        val bootRestartDesc: String,
        val bootRestartWarningTitle: String,
        val bootRestartWarningBody: String,
        val bootRestartDisableButton: String
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
        val connOff: String,
        val connDegraded: String,
        val connDegradedBody: String,
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
        val noThreatsMessage: String,
        val serviceOfflineBanner: String
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
        val logsFilterSources: String,
        val logsEmptySources: String,
        val sourceTypeWs: String,
        val sourceTypeRest: String,
        val sourceModeStreaming: String,
        val sourceModePolling: String,
        val sourceModeStandby: String,
        val sourceFallbackLabel: String,
        val sourceEnabledLabel: String,
        val sourceTestLabel: String,
        val sourceActivityLabel: String,
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
        val connEventFallbackActive: String,
        val connEventFallbackRestored: String,
        val connEventSourceToggled: String,
        val connEventPaused: String,
        val cardSizeLabel: String,
        val additionalSettingsTitle: String,
        val showMapScaleTitle: String,
        val showMapScaleDesc: String,
        val cityLabelsTitle: String,
        val cityLabelsDesc: String,
        val mediumCitiesChip: String,
        val smallCitiesChip: String,
        val fillAlertRegionsTitle: String,
        val fillAlertRegionsDesc: String,
        val calmMessagesTitle: String,
        val calmMessagesDesc: String,
        val threatIconZoomTitle: String,
        val threatIconZoomDesc: String,
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
        val shelterShowSheltersOnMap: String,
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
        val gpsUnavailableFollowMe: String,
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
        val apiUbillingSchemaChanged: String,
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
        val flourishDisabledToastFormat: String,
        val tapToCancelLabel: String
    )

    /** One-time explainer copy. [items] order: threatToggles, officialAlerts, sirenOverride,
     *  followMe, cardSize, nightMode — each a (title, visual, scenario) triple. */
    data class ExplainerStrings(
        val visualLabel: String,
        val scenarioLabel: String,
        val gotIt: String,
        val items: List<Triple<String, String, String>>
    )

    data class Subtitles(
        val locationFollowMeActive: String,
        val locationFollowMePeriodicGps: String,
        val locationPinnedFormat: String,
        val locationManual: String,
        val nightDisabled: String,
        val nightEnabledPrefix: String,
        val nightSirenSuffix: String,
        val nightZonesSuffix: String,
        val alertsOfficialPrefix: String,
        val alertsSirenOverride: String,
        val onWord: String,
        val offWord: String,
        val justFunAnimationPrefix: String,
        val justFunTallyOn: String,
        val sheltersPrefix: String,
        val threatsAllActiveFormat: String,
        val threatsHiddenSilencedFormat: String,
        val threatsHiddenMapFormat: String,
        val threatsSilencedFormat: String,
        val selfLanguageName: String,
    )

    data class WordForms(
        val sources: List<String>,
        val resolvedThreats: List<String>,
        val resolvingThreats: List<String>,
        val preciseGps: List<String>,
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
        val explainers: ExplainerStrings,
        val subtitles: Subtitles,
        val wordForms: WordForms,
        val calmMessages: List<String>,
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
        val bootRestartTitle: String get() = settings.bootRestartTitle
        val bootRestartDesc: String get() = settings.bootRestartDesc
        val bootRestartWarningTitle: String get() = settings.bootRestartWarningTitle
        val bootRestartWarningBody: String get() = settings.bootRestartWarningBody
        val bootRestartDisableButton: String get() = settings.bootRestartDisableButton
        val systemSectionTitle: String get() = settings.systemSectionTitle
        val locationSectionTitle: String get() = settings.locationSectionTitle
        val justFunSectionTitle: String get() = settings.justFunSectionTitle
        val flybyAnimationLabel: String get() = settings.flybyAnimationLabel
        val flybyAnimationDesc: String get() = settings.flybyAnimationDesc
        val cardSizeSmallLabel: String get() = settings.cardSizeSmallLabel
        val cardSizeLargeLabel: String get() = settings.cardSizeLargeLabel

        fun locationSubtitle(followMe: Boolean, pinnedCityName: String?, periodicGps: Boolean = false): String =
            if (followMe) {
                if (periodicGps) subtitles.locationFollowMePeriodicGps
                else subtitles.locationFollowMeActive
            } else if (!pinnedCityName.isNullOrBlank()) {
                String.format(subtitles.locationPinnedFormat, pinnedCityName)
            } else {
                subtitles.locationManual
            }

        fun nightSubtitle(enabled: Boolean, startMin: Int, endMin: Int, sirenOverride: Boolean, useCustomZones: Boolean): String =
            if (!enabled) {
                subtitles.nightDisabled
            } else {
                val timeStr = String.format(java.util.Locale.US, "%02d:%02d–%02d:%02d", startMin / 60, startMin % 60, endMin / 60, endMin % 60)
                val sirenStr = if (sirenOverride) " · ${subtitles.nightSirenSuffix}" else ""
                val zonesStr = if (useCustomZones) " · ${subtitles.nightZonesSuffix}" else ""
                "${subtitles.nightEnabledPrefix} · $timeStr$sirenStr$zonesStr"
            }

        fun alertsSubtitle(officialAlerts: Boolean, sirenOverride: Boolean): String {
            val parts = mutableListOf<String>()
            val officialText = String.format(subtitles.alertsOfficialPrefix, if (officialAlerts) subtitles.onWord else subtitles.offWord)
            parts.add(officialText)
            if (sirenOverride) {
                parts.add(subtitles.alertsSirenOverride)
            }
            return parts.joinToString(" · ")
        }

        fun justFunSubtitle(animation: Boolean, tally: Boolean): String {
            val parts = mutableListOf<String>()
            parts.add(subtitles.justFunAnimationPrefix + (if (animation) subtitles.onWord else subtitles.offWord))
            if (tally) {
                parts.add(subtitles.justFunTallyOn)
            }
            return parts.joinToString(" · ")
        }

        fun sheltersSubtitle(enabled: Boolean): String =
            subtitles.sheltersPrefix + (if (enabled) subtitles.onWord else subtitles.offWord)

        fun threatsSubtitle(hiddenCount: Int, silencedCount: Int, totalCount: Int = 8): String =
            if (hiddenCount == 0 && silencedCount == 0) {
                String.format(subtitles.threatsAllActiveFormat, totalCount)
            } else if (hiddenCount > 0 && silencedCount > 0) {
                String.format(subtitles.threatsHiddenSilencedFormat, hiddenCount, silencedCount)
            } else if (hiddenCount > 0) {
                String.format(subtitles.threatsHiddenMapFormat, hiddenCount)
            } else {
                String.format(subtitles.threatsSilencedFormat, silencedCount)
            }

        fun systemSubtitle(cardSize: ThreatCardSize, iconSet: ThreatIconSet): String {
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
            return "${subtitles.selfLanguageName} · $sizeName · $iconName"
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
        val connOff: String get() = status.connOff
        val connOffline: String get() = status.connOffline
        val serviceOfflineBanner: String get() = status.serviceOfflineBanner
        val connDegraded: String get() = status.connDegraded
        val connDegradedBody: String get() = status.connDegradedBody
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
        val logsFilterSources: String get() = misc.logsFilterSources
        val logsEmptySources: String get() = misc.logsEmptySources
        val sourceTypeWs: String get() = misc.sourceTypeWs
        val sourceTypeRest: String get() = misc.sourceTypeRest
        val sourceModeStreaming: String get() = misc.sourceModeStreaming
        val sourceModePolling: String get() = misc.sourceModePolling
        val sourceModeStandby: String get() = misc.sourceModeStandby
        val sourceFallbackLabel: String get() = misc.sourceFallbackLabel
        val sourceEnabledLabel: String get() = misc.sourceEnabledLabel
        val sourceTestLabel: String get() = misc.sourceTestLabel
        val sourceActivityLabel: String get() = misc.sourceActivityLabel
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
        val connEventFallbackActive: String get() = misc.connEventFallbackActive
        val connEventFallbackRestored: String get() = misc.connEventFallbackRestored
        val connEventSourceToggled: String get() = misc.connEventSourceToggled
        val connEventPaused: String get() = misc.connEventPaused
        val cardSizeLabel: String get() = misc.cardSizeLabel
        val additionalSettingsTitle: String get() = misc.additionalSettingsTitle
        val showMapScaleTitle: String get() = misc.showMapScaleTitle
        val showMapScaleDesc: String get() = misc.showMapScaleDesc
        val cityLabelsTitle: String get() = misc.cityLabelsTitle
        val cityLabelsDesc: String get() = misc.cityLabelsDesc
        val mediumCitiesChip: String get() = misc.mediumCitiesChip
        val smallCitiesChip: String get() = misc.smallCitiesChip
        val fillAlertRegionsTitle: String get() = misc.fillAlertRegionsTitle
        val fillAlertRegionsDesc: String get() = misc.fillAlertRegionsDesc
        val calmMessagesTitle: String get() = misc.calmMessagesTitle
        val calmMessagesDesc: String get() = misc.calmMessagesDesc
        val threatIconZoomTitle: String get() = misc.threatIconZoomTitle
        val threatIconZoomDesc: String get() = misc.threatIconZoomDesc
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
        val shelterShowSheltersOnMap: String get() = misc.shelterShowSheltersOnMap
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
        val gpsUnavailableFollowMe: String get() = misc.gpsUnavailableFollowMe
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
        val apiUbillingSchemaChanged: String get() = misc.apiUbillingSchemaChanged
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
        val tapToCancelLabel: String get() = guide.tapToCancelLabel
        val explainerVisualLabel: String get() = explainers.visualLabel
        val explainerScenarioLabel: String get() = explainers.scenarioLabel
        val explainerGotIt: String get() = explainers.gotIt
    }


}

private fun pluralIndex(count: Int, lang: AppLanguage): Int = when (lang) {
    AppLanguage.EN -> if (count == 1) 0 else 1
    AppLanguage.UA -> {
        val n10 = count % 10
        val n100 = count % 100
        when {
            n10 == 1 && n100 != 11 -> 0
            n10 in 2..4 && n100 !in 12..14 -> 1
            else -> 2
        }
    }
}

fun sourcesWord(count: Int, lang: AppLanguage): String {
    val forms = Strings.get(lang).wordForms.sources
    return forms[pluralIndex(count, lang).coerceAtMost(forms.lastIndex)]
}

fun resolvedThreatsPhrase(count: Int, lang: AppLanguage): String {
    val s = Strings.get(lang)
    val form = s.wordForms.resolvedThreats[pluralIndex(count, lang).coerceAtMost(s.wordForms.resolvedThreats.lastIndex)]
    return String.format(form, count)
}

fun resolvingThreatsPhrase(count: Int, lang: AppLanguage): String {
    val s = Strings.get(lang)
    val form = s.wordForms.resolvingThreats[pluralIndex(count, lang).coerceAtMost(s.wordForms.resolvingThreats.lastIndex)]
    return String.format(form, count)
}

fun noThreatsMessage(lang: AppLanguage, calmMessages: Boolean = true): String {
    val s = Strings.get(lang)
    if (!calmMessages) return s.status.noThreatsMessage
    return s.calmMessages.random()
}

fun preciseGpsAgePhrase(minutes: Long, lang: AppLanguage): String {
    val s = Strings.get(lang)
    val idx = pluralIndex(minutes.toInt(), lang).coerceAtMost(s.wordForms.preciseGps.lastIndex)
    return String.format(s.wordForms.preciseGps[idx], minutes)
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
