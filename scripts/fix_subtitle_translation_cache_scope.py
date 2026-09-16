from pathlib import Path

path = Path("app/src/main/java/com/nuvio/tv/ui/screens/player/SubtitleTranslationBridge.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)

replace_once(
    "        val activeProvider = provider ?: return\n        val activeTarget = targetLanguage ?: return\n        val generation = ++cueGeneration\n",
    "        val activeProvider = provider ?: return\n        val activeTarget = targetLanguage ?: return\n        val activeSource = sourceLanguage\n        val generation = ++cueGeneration\n",
    "capture active source",
)

replace_once(
    "        val cachedTranslations = texts.map { text -> cache[cacheKey(text, activeTarget)] }\n",
    "        val cachedTranslations = texts.map { text ->\n            cache[cacheKey(activeProvider.addonId, activeSource, activeTarget, text)]\n        }\n",
    "cache lookup scope",
)

replace_once(
    "                    sourceLanguage = sourceLanguage,\n",
    "                    sourceLanguage = activeSource,\n",
    "request source snapshot",
)

replace_once(
    "                cache[cacheKey(text, activeTarget)] = translations[index]\n",
    "                cache[cacheKey(activeProvider.addonId, activeSource, activeTarget, text)] = translations[index]\n",
    "cache write scope",
)

replace_once(
    "    private fun cacheKey(text: String, targetLanguage: String): String =\n        \"${sourceLanguage.orEmpty()}|$targetLanguage|$text\"\n",
    "    private fun cacheKey(\n        providerId: String,\n        sourceLanguage: String?,\n        targetLanguage: String,\n        text: String\n    ): String = \"$providerId|${sourceLanguage.orEmpty()}|$targetLanguage|$text\"\n",
    "cache key signature",
)

path.write_text(text, encoding="utf-8")
print("SubtitleTranslationBridge.kt race fix applied")
