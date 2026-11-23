package com.cl600.obd.music

enum class MusicMode {
    WEB,
    APP
}

enum class MusicAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    LIKE,
    DISLIKE
}

enum class LikeStatus {
    NONE,
    LIKED,
    DISLIKED
}

data class MusicState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val likeStatus: LikeStatus = LikeStatus.NONE,
    val currentSec: Float = 0f,
    val durationSec: Float = 0f,
    val mode: MusicMode = MusicMode.WEB,
    val signedIn: Boolean = false,
    val hasActiveSession: Boolean = false
) {
    fun progressFraction(): Float =
        if (durationSec > 0.5f) (currentSec / durationSec).coerceIn(0f, 1f) else 0f
}

/**
 * Pure Kotlin helpers for the YouTube Music WebView JS bridge, Chrome UA sanitization,
 * Google OAuth block detection, and track state parsing.
 */
object YtmScriptBridge {

    const val YTM_HOME_URL = "https://music.youtube.com/"
    private const val FALLBACK_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val WV_TOKEN_REGEX = Regex("""(?i)\s*;\s*wv\b""")
    private val VERSION_TOKEN_REGEX = Regex("""\bVersion/\d+(?:\.\d+)*\s*""")
    private val MULTI_SPACE_REGEX = Regex(""" {2,}""")
    private val PAREN_SEMI_REGEX = Regex("""\(\s*;\s*""")
    private val SEMI_PAREN_REGEX = Regex("""\s*;\s*\)""")

    fun sanitizeChromeUserAgent(defaultUa: String): String {
        if (defaultUa.isBlank()) return FALLBACK_CHROME_UA
        var cleaned = defaultUa
            .replace(WV_TOKEN_REGEX, "")
            .replace(VERSION_TOKEN_REGEX, "")
            .replace(PAREN_SEMI_REGEX, "(")
            .replace(SEMI_PAREN_REGEX, ")")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        if (!cleaned.contains("Chrome/", ignoreCase = true)) {
            return FALLBACK_CHROME_UA
        }
        return cleaned
    }

    fun isGoogleWebViewAuthBlocked(url: String?, pageTitle: String?, bodySnippet: String?): Boolean {
        val u = url.orEmpty().lowercase()
        val t = pageTitle.orEmpty().lowercase()
        val b = bodySnippet.orEmpty().lowercase()

        if (u.contains("disallowed_useragent") || b.contains("disallowed_useragent")) {
            return true
        }
        val onGoogleAuthOrError =
            u.contains("accounts.google.com") || t.contains("error 403") || t.contains("403")
        if (!onGoogleAuthOrError) return false

        return b.contains("couldn't sign you in") ||
            b.contains("couldn\u2019t sign you in") ||
            b.contains("this browser or app may not be secure") ||
            b.contains("use a supported browser") ||
            (t.contains("403") && (b.contains("disallowed") || b.contains("useragent")))
    }

    fun buildStatePollingJs(): String = """
        (function() {
            try {
                var bar = document.querySelector('ytmusic-player-bar');
                var titleEl = bar ? bar.querySelector('.title.ytmusic-player-bar, yt-formatted-string.title') : null;
                var bylineEl = bar ? bar.querySelector('.byline.ytmusic-player-bar, yt-formatted-string.byline, .subtitle.ytmusic-player-bar') : null;
                var title = titleEl ? (titleEl.textContent || '').trim() : '';
                var artist = bylineEl ? (bylineEl.textContent || '').replace(/\s+/g, ' ').trim() : '';
                var video = document.querySelector('video');
                var playBtn = bar ? bar.querySelector('#play-pause-button button, #play-pause-button') : null;
                var playLabel = playBtn ? ((playBtn.getAttribute('aria-label') || playBtn.title || '') + '') : '';
                var playing = false;
                if (video && video.src) {
                    playing = !video.paused && !video.ended && video.readyState > 1;
                } else if (playLabel) {
                    playing = /pause/i.test(playLabel);
                }
                var cur = (video && isFinite(video.currentTime)) ? Number(video.currentTime) : 0;
                var dur = (video && isFinite(video.duration)) ? Number(video.duration) : 0;
                if (dur <= 0 && bar) {
                    var slider = bar.querySelector('#progress-bar');
                    if (slider) {
                        var nowAttr = parseFloat(slider.getAttribute('aria-valuenow') || '0');
                        var maxAttr = parseFloat(slider.getAttribute('aria-valuemax') || '0');
                        if (isFinite(nowAttr)) cur = nowAttr;
                        if (isFinite(maxAttr)) dur = maxAttr;
                    }
                }
                var likeRenderer = bar ? bar.querySelector('ytmusic-like-button-renderer') : null;
                var likeAttr = likeRenderer ? (likeRenderer.getAttribute('like-status') || '') : '';
                var like = 'NONE';
                if (likeAttr === 'LIKE') like = 'LIKED';
                else if (likeAttr === 'DISLIKE') like = 'DISLIKED';
                else if (likeRenderer) {
                    var upBtn = likeRenderer.querySelector('#button-shape-like button, button.like, .like button');
                    var downBtn = likeRenderer.querySelector('#button-shape-dislike button, button.dislike, .dislike button');
                    if (upBtn && upBtn.getAttribute('aria-pressed') === 'true') like = 'LIKED';
                    else if (downBtn && downBtn.getAttribute('aria-pressed') === 'true') like = 'DISLIKED';
                }
                var signInLink = document.querySelector('a.sign-in-link, a[href*="accounts.google.com/ServiceLogin"]');
                var avatarBtn = document.querySelector('#avatar-btn, ytmusic-settings-button button img#img, yt-img-shadow#avatar img');
                var signedIn = Boolean(!signInLink && (avatarBtn || document.cookie.indexOf('SID=') !== -1));
                var active = Boolean(title || (video && dur > 0));
                return JSON.stringify({
                    title: title,
                    artist: artist,
                    playing: playing,
                    like: like,
                    cur: cur,
                    dur: dur,
                    signedIn: signedIn,
                    active: active
                });
            } catch (e) {
                return '';
            }
        })();
    """.trimIndent()

    fun buildActionJs(action: MusicAction): String {
        val selector = when (action) {
            MusicAction.PLAY_PAUSE ->
                "#play-pause-button button, #play-pause-button"
            MusicAction.NEXT ->
                ".next-button button, .next-button"
            MusicAction.PREVIOUS ->
                ".previous-button button, .previous-button"
            MusicAction.LIKE ->
                "ytmusic-like-button-renderer #button-shape-like button, ytmusic-like-button-renderer .like button, ytmusic-like-button-renderer button.like, ytmusic-like-button-renderer yt-button-shape#button-shape-like button"
            MusicAction.DISLIKE ->
                "ytmusic-like-button-renderer #button-shape-dislike button, ytmusic-like-button-renderer .dislike button, ytmusic-like-button-renderer button.dislike, ytmusic-like-button-renderer yt-button-shape#button-shape-dislike button"
        }
        return """
            (function() {
                var root = document.querySelector('ytmusic-player-bar') || document;
                var btn = root.querySelector('$selector') || document.querySelector('$selector');
                if (btn) {
                    btn.click();
                    return true;
                }
                return false;
            })();
        """.trimIndent()
    }

    /**
     * Parses either a direct JSON object string or a quoted JS evaluation string
     * returned by Android `WebView.evaluateJavascript` (`"\"{\\\"title\\\":...}\""`).
     */
    fun parseTrackPayload(raw: String?, mode: MusicMode = MusicMode.WEB): MusicState? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val unwrapped = unwrapJsString(raw.trim())
        if (!unwrapped.startsWith("{") || !unwrapped.endsWith("}")) return null

        val fields = parseFlatJsonObject(unwrapped) ?: return null
        val title = fields["title"] ?: ""
        val artist = fields["artist"] ?: ""
        val playing = fields["playing"] == "true"
        val like = when (fields["like"]?.uppercase()) {
            "LIKED", "LIKE" -> LikeStatus.LIKED
            "DISLIKED", "DISLIKE" -> LikeStatus.DISLIKED
            else -> LikeStatus.NONE
        }
        val cur = fields["cur"]?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
        val dur = fields["dur"]?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
        val signedIn = fields["signedIn"] == "true"
        val active = fields["active"] == "true" || title.isNotBlank() || dur > 0f

        return MusicState(
            title = title,
            artist = artist,
            isPlaying = playing,
            likeStatus = like,
            currentSec = cur,
            durationSec = dur,
            mode = mode,
            signedIn = signedIn,
            hasActiveSession = active
        )
    }

    private fun unwrapJsString(input: String): String {
        if (!input.startsWith("\"") || !input.endsWith("\"") || input.length < 2) {
            return input
        }
        val inner = input.substring(1, input.length - 1)
        return unescapeJsonString(inner)
    }

    private fun parseFlatJsonObject(json: String): Map<String, String>? {
        val result = LinkedHashMap<String, String>()
        var idx = 1 // skip '{'
        val len = json.length - 1 // stop before '}'

        fun skipWs() {
            while (idx < len && json[idx].isWhitespace()) idx++
        }

        while (idx < len) {
            skipWs()
            if (idx >= len) break
            if (json[idx] == ',') {
                idx++
                continue
            }
            if (json[idx] != '"') return null
            val (key, afterKey) = readJsonString(json, idx) ?: return null
            idx = afterKey
            skipWs()
            if (idx >= len || json[idx] != ':') return null
            idx++ // skip ':'
            skipWs()
            if (idx >= len) return null

            val value: String
            if (json[idx] == '"') {
                val (strVal, afterVal) = readJsonString(json, idx) ?: return null
                value = strVal
                idx = afterVal
            } else {
                val start = idx
                while (idx < len && json[idx] != ',' && json[idx] != '}') idx++
                value = json.substring(start, idx).trim()
            }
            result[key] = value
        }
        return result
    }

    private fun readJsonString(src: String, quoteStart: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = quoteStart + 1
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') {
                if (i + 1 >= src.length) return null
                val next = src[i + 1]
                when (next) {
                    '"', '\\', '/' -> sb.append(next)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 >= src.length) return null
                        val code = src.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else if (c == '"') {
                return sb.toString() to (i + 1)
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    private fun unescapeJsonString(raw: String): String {
        val wrapped = "\"$raw\""
        return readJsonString(wrapped, 0)?.first ?: raw
    }
}
