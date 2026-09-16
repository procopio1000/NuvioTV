from pathlib import Path

path = Path("app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import androidx.compose.runtime.remember\n",
    "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n",
    "rememberCoroutineScope import",
)

replace_once(
    "    var externalHandoffInProgress by remember { mutableStateOf(false) }\n\n    val exitPlayer: () -> Unit = exitPlayer@{",
    '''    var externalHandoffInProgress by remember { mutableStateOf(false) }\n\n    // Embedded subtitle translation stays session-scoped and opt-in. The bridge discovers\n    // a compatible installed addon by its manifest resource and never receives the video URL.\n    val subtitleTranslationScope = rememberCoroutineScope()\n    val subtitleTranslationSession = remember(subtitleTranslationScope) {\n        SubtitleTranslationSession(subtitleTranslationScope)\n    }\n    val installedAddons by viewModel.controller.addonRepository\n        .getInstalledAddons()\n        .collectAsState(initial = emptyList())\n    val subtitleTranslationProvider = remember(installedAddons) {\n        findSubtitleTranslationProvider(installedAddons)\n    }\n    val subtitleTranslationTargetLanguage = remember(uiState.subtitleStyle.preferredLanguage) {\n        resolveSubtitleTranslationTargetLanguage(uiState.subtitleStyle.preferredLanguage)\n    }\n    val subtitleTranslationSource = remember(\n        uiState.subtitleTracks,\n        uiState.selectedSubtitleTrackIndex,\n        subtitleTranslationTargetLanguage\n    ) {\n        subtitleTranslationTargetLanguage?.let { targetLanguage ->\n            chooseSubtitleTranslationSource(\n                tracks = uiState.subtitleTracks,\n                selectedInternalIndex = uiState.selectedSubtitleTrackIndex,\n                targetLanguage = targetLanguage\n            )\n        }\n    }\n    val subtitleTranslationSupported =\n        uiState.internalPlayerEngine != InternalPlayerEngine.MVP_PLAYER &&\n            !uiState.useLibass &&\n            subtitleTranslationProvider != null &&\n            subtitleTranslationTargetLanguage != null &&\n            subtitleTranslationSource != null\n    val subtitleTranslationOption = remember(\n        subtitleTranslationProvider,\n        subtitleTranslationTargetLanguage,\n        subtitleTranslationSupported\n    ) {\n        if (!subtitleTranslationSupported) {\n            null\n        } else {\n            buildSubtitleTranslationOption(\n                provider = requireNotNull(subtitleTranslationProvider),\n                targetLanguage = requireNotNull(subtitleTranslationTargetLanguage)\n            )\n        }\n    }\n    val subtitleOptionsWithTranslation = remember(uiState.addonSubtitles, subtitleTranslationOption) {\n        val base = uiState.addonSubtitles.filterNot(Subtitle::isSubtitleTranslationOption)\n        subtitleTranslationOption?.let { base + it } ?: base\n    }\n\n    DisposableEffect(viewModel.exoPlayer, subtitleTranslationSession) {\n        val player = viewModel.exoPlayer\n        subtitleTranslationSession.attachPlayer(player)\n        onDispose { subtitleTranslationSession.detachPlayer(player) }\n    }\n    DisposableEffect(subtitleTranslationSession) {\n        onDispose { subtitleTranslationSession.close() }\n    }\n    LaunchedEffect(\n        subtitleTranslationSupported,\n        subtitleTranslationProvider?.addonId,\n        subtitleTranslationTargetLanguage\n    ) {\n        if (!subtitleTranslationSupported && subtitleTranslationSession.isEnabled) {\n            subtitleTranslationSession.disable()\n        }\n    }\n\n    val exitPlayer: () -> Unit = exitPlayer@{''',
    "translation session setup",
)

replace_once(
    "                            onBindSubtitleView = viewModel::bindExoSubtitleView,\n",
    '''                            onBindSubtitleView = { subtitleView ->\n                                viewModel.bindExoSubtitleView(subtitleView)\n                                subtitleTranslationSession.bindSubtitleView(subtitleView)\n                            },\n''',
    "subtitle view binding",
)

replace_once(
    "            addonSubtitles = uiState.addonSubtitles,\n            selectedAddonSubtitle = uiState.selectedAddonSubtitle,\n",
    '''            addonSubtitles = subtitleOptionsWithTranslation,\n            selectedAddonSubtitle = if (subtitleTranslationSession.isEnabled) {\n                subtitleTranslationOption\n            } else {\n                uiState.selectedAddonSubtitle\n            },\n''',
    "subtitle overlay data",
)

replace_once(
    "            onInternalTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(it)) },\n            onAddonSubtitleSelected = { viewModel.onEvent(PlayerEvent.OnSelectAddonSubtitle(it)) },\n            onDisableSubtitles = { viewModel.onEvent(PlayerEvent.OnDisableSubtitles) },\n",
    '''            onInternalTrackSelected = { trackIndex ->\n                subtitleTranslationSession.disable()\n                viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(trackIndex))\n            },\n            onAddonSubtitleSelected = { subtitle ->\n                if (subtitle.isSubtitleTranslationOption()) {\n                    val provider = subtitleTranslationProvider\n                    val targetLanguage = subtitleTranslationTargetLanguage\n                    val source = subtitleTranslationSource\n                    if (provider != null && targetLanguage != null && source != null) {\n                        // Select the embedded source track without persisting it as the user's\n                        // explicit subtitle preference. The UI remains on the translation option.\n                        viewModel.controller.selectSubtitleTrack(source.index)\n                        subtitleTranslationSession.enable(\n                            provider = provider,\n                            targetLanguage = targetLanguage,\n                            sourceLanguage = source.language\n                        )\n                    }\n                } else {\n                    subtitleTranslationSession.disable()\n                    viewModel.onEvent(PlayerEvent.OnSelectAddonSubtitle(subtitle))\n                }\n            },\n            onDisableSubtitles = {\n                subtitleTranslationSession.disable()\n                viewModel.onEvent(PlayerEvent.OnDisableSubtitles)\n            },\n''',
    "subtitle selection callbacks",
)

path.write_text(text, encoding="utf-8")
print("PlayerScreen.kt patched successfully")
