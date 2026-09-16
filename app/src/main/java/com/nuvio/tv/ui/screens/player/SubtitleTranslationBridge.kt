package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "SubtitleTranslation"
private const val TRANSLATION_RESOURCE = "subtitleTranslate"
private const val TRANSLATION_SUBTITLE_ID_PREFIX = "nuvio:subtitle-translate:"
private const val TRANSLATION_URL_SCHEME = "nuvio-translate"

private val TRANSLATION_RESOURCE_ALIASES = setOf(
    "subtitletranslate",
    "subtitle-translate",
    "subtitle_translation"
)

internal data class SubtitleTranslationProvider(
    val addonId: String,
    val displayName: String,
    val logo: String?,
    val endpoint: String
)

internal fun findSubtitleTranslationProvider(addons: List<Addon>): SubtitleTranslationProvider? {
    val addon = addons.firstOrNull { candidate ->
        candidate.enabled && candidate.resources.any { resource ->
            resource.name.trim().lowercase(Locale.US) in TRANSLATION_RESOURCE_ALIASES
        }
    } ?: return null

    return SubtitleTranslationProvider(
        addonId = addon.id,
        displayName = addon.displayName,
        logo = addon.logo,
        endpoint = translationEndpoint(addon.baseUrl)
    )
}

internal fun resolveSubtitleTranslationTargetLanguage(preferredLanguage: String?): String? {
    if (preferredLanguage.isNullOrBlank()) return null
    val normalized = PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)
    return normalized
        .takeUnless { it.equals("none", ignoreCase = true) || it == SUBTITLE_LANGUAGE_FORCED }
        ?.takeIf { it.isNotBlank() }
}

internal fun buildSubtitleTranslationOption(
    provider: SubtitleTranslationProvider,
    targetLanguage: String
): Subtitle = Subtitle(
    id = "$TRANSLATION_SUBTITLE_ID_PREFIX${provider.addonId}",
    url = "$TRANSLATION_URL_SCHEME://${provider.addonId.hashCode().toUInt().toString(16)}",
    lang = targetLanguage,
    addonName = provider.displayName,
    addonLogo = provider.logo
)

internal fun Subtitle.isSubtitleTranslationOption(): Boolean =
    id.startsWith(TRANSLATION_SUBTITLE_ID_PREFIX) ||
        url.startsWith("$TRANSLATION_URL_SCHEME://")

internal fun chooseSubtitleTranslationSource(
    tracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    targetLanguage: String
): TrackInfo? {
    val normalizedTarget = normalizedLanguage(targetLanguage)
    val eligible = tracks.filter { track ->
        !track.isForced &&
            !track.isKnownBitmapSubtitle() &&
            normalizedLanguage(track.language) != normalizedTarget
    }
    if (eligible.isEmpty()) return null

    eligible.firstOrNull { it.index == selectedInternalIndex }?.let { return it }
    eligible.firstOrNull { normalizedLanguage(it.language) == "en" }?.let { return it }
    return eligible.first()
}

private fun TrackInfo.isKnownBitmapSubtitle(): Boolean {
    val value = listOfNotNull(codec, name, trackId)
        .joinToString(" ")
        .lowercase(Locale.US)
    return listOf(
        "pgs",
        "hdmv",
        "dvdsub",
        "dvd_subtitle",
        "vobsub",
        "dvb",
        "arib"
    ).any(value::contains)
}

private fun normalizedLanguage(language: String?): String {
    if (language.isNullOrBlank()) return "und"
    return PlayerSubtitleUtils.normalizeLanguageCode(language)
        .lowercase(Locale.US)
        .substringBefore('-')
        .substringBefore('_')
}

private fun translationEndpoint(baseUrl: String): String {
    val canonical = baseUrl.trim().trimEnd('/')
    val queryIndex = canonical.indexOf('?')
    val basePath = if (queryIndex >= 0) canonical.substring(0, queryIndex).trimEnd('/') else canonical
    val query = if (queryIndex >= 0) canonical.substring(queryIndex) else ""
    return "$basePath/$TRANSLATION_RESOURCE$query"
}

/**
 * Lightweight bridge between Media3 subtitle cues and an external translation addon.
 *
 * The Nuvio client sends only subtitle text/language metadata. It never forwards the
 * playback URL, debrid headers, cookies, or provider API keys.
 */
internal class SubtitleTranslationSession(
    private val scope: CoroutineScope
) : Player.Listener {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, String>()

    private var player: ExoPlayer? = null
    private var subtitleView: SubtitleView? = null
    private var provider: SubtitleTranslationProvider? = null
    private var targetLanguage: String? = null
    private var sourceLanguage: String? = null
    private var cueGeneration: Long = 0L

    var isEnabled by mutableStateOf(false)
        private set

    fun attachPlayer(newPlayer: ExoPlayer?) {
        if (player === newPlayer) return
        player?.removeListener(this)
        player = newPlayer
        newPlayer?.addListener(this)
    }

    fun detachPlayer(expectedPlayer: ExoPlayer?) {
        if (player !== expectedPlayer) return
        player?.removeListener(this)
        player = null
        cueGeneration++
    }

    fun bindSubtitleView(view: SubtitleView?) {
        subtitleView = view
    }

    fun enable(
        provider: SubtitleTranslationProvider,
        targetLanguage: String,
        sourceLanguage: String?
    ) {
        this.provider = provider
        this.targetLanguage = targetLanguage
        this.sourceLanguage = sourceLanguage
        cueGeneration++
        isEnabled = true
        Log.i(
            TAG,
            "translation enabled provider=${provider.displayName} source=${sourceLanguage ?: "und"} target=$targetLanguage"
        )
    }

    fun disable() {
        if (!isEnabled && provider == null) return
        isEnabled = false
        provider = null
        targetLanguage = null
        sourceLanguage = null
        cueGeneration++
        Log.i(TAG, "translation disabled")
    }

    fun close() {
        disable()
        player?.removeListener(this)
        player = null
        subtitleView = null
    }

    override fun onCues(cueGroup: CueGroup) {
        if (!isEnabled) return

        val activeProvider = provider ?: return
        val activeTarget = targetLanguage ?: return
        val generation = ++cueGeneration
        val cues = cueGroup.cues
        if (cues.isEmpty()) return

        val textIndexes = cues.indices.filter { index ->
            val cue = cues[index]
            cue.bitmap == null && !cue.text?.toString().isNullOrBlank()
        }
        if (textIndexes.isEmpty()) return

        val texts = textIndexes.map { index -> cues[index].text!!.toString() }
        val cachedTranslations = texts.map { text -> cache[cacheKey(text, activeTarget)] }
        if (cachedTranslations.all { it != null }) {
            renderTranslatedCues(
                original = cues,
                textIndexes = textIndexes,
                translations = cachedTranslations.filterNotNull(),
                generation = generation
            )
            return
        }

        scope.launch {
            val translations = try {
                translateBatch(
                    provider = activeProvider,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = activeTarget,
                    texts = texts
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "translation request failed: ${e.message}")
                null
            } ?: return@launch

            if (translations.size != texts.size) return@launch
            texts.forEachIndexed { index, text ->
                cache[cacheKey(text, activeTarget)] = translations[index]
            }
            renderTranslatedCues(
                original = cues,
                textIndexes = textIndexes,
                translations = translations,
                generation = generation
            )
        }
    }

    private fun renderTranslatedCues(
        original: List<Cue>,
        textIndexes: List<Int>,
        translations: List<String>,
        generation: Long
    ) {
        if (!isEnabled || generation != cueGeneration) return
        val translatedByIndex = textIndexes.zip(translations).toMap()
        val translatedCues = original.mapIndexed { index, cue ->
            translatedByIndex[index]?.let { translated ->
                cue.buildUpon().setText(translated).build()
            } ?: cue
        }
        mainHandler.post {
            if (isEnabled && generation == cueGeneration) {
                subtitleView?.setCues(translatedCues)
            }
        }
    }

    private fun cacheKey(text: String, targetLanguage: String): String =
        "${sourceLanguage.orEmpty()}|$targetLanguage|$text"

    private suspend fun translateBatch(
        provider: SubtitleTranslationProvider,
        sourceLanguage: String?,
        targetLanguage: String,
        texts: List<String>
    ): List<String>? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("sourceLanguage", sourceLanguage ?: JSONObject.NULL)
            put("targetLanguage", targetLanguage)
            put("cues", JSONArray().apply {
                texts.forEachIndexed { index, text ->
                    put(JSONObject().apply {
                        put("id", index.toString())
                        put("text", text)
                    })
                }
            })
        }

        val request = Request.Builder()
            .url(provider.endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "NuvioTV subtitle-translation-bridge")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "translation provider HTTP ${response.code}")
                return@withContext null
            }
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) return@withContext null
            parseTranslations(responseBody, texts.size)
        }
    }

    private fun parseTranslations(rawJson: String, expectedCount: Int): List<String>? {
        val root = JSONObject(rawJson)
        root.optJSONArray("cues")?.let { cues ->
            val byId = mutableMapOf<Int, String>()
            for (i in 0 until cues.length()) {
                val item = cues.optJSONObject(i) ?: continue
                val id = item.optString("id").toIntOrNull() ?: i
                val text = item.optString("text").ifBlank { item.optString("translatedText") }
                if (text.isNotBlank()) byId[id] = text
            }
            if ((0 until expectedCount).all(byId::containsKey)) {
                return (0 until expectedCount).map { index -> requireNotNull(byId[index]) }
            }
        }

        root.optJSONArray("translations")?.let { translations ->
            if (translations.length() == expectedCount) {
                return List(expectedCount) { index -> translations.optString(index) }
                    .takeIf { values -> values.all { it.isNotBlank() } }
            }
        }
        return null
    }
}
