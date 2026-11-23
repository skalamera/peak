package com.cl600.obd.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtmScriptBridgeTest {

    @Test
    fun sanitizeChromeUserAgent_stripsWebViewMarkerAndVersionToken() {
        val raw = "Mozilla/5.0 (Linux; Android 13; sdk_gphone64_arm64; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/119.0.6045.193 Mobile Safari/537.36"

        val clean = YtmScriptBridge.sanitizeChromeUserAgent(raw)

        assertFalse("Should strip wv marker", clean.contains("; wv"))
        assertFalse("Should strip Version/4.0 token", clean.contains("Version/4.0"))
        assertTrue("Should keep Chrome UA token", clean.contains("Chrome/119.0.6045.193"))
        assertTrue("Should keep Android parenthesis cleanly closed", clean.contains("(Linux; Android 13; sdk_gphone64_arm64)"))
    }

    @Test
    fun sanitizeChromeUserAgent_providesFallbackWhenBlankOrNonChrome() {
        val fromBlank = YtmScriptBridge.sanitizeChromeUserAgent("   ")
        val fromCustom = YtmScriptBridge.sanitizeChromeUserAgent("WeirdAgent/1.0")

        assertTrue(fromBlank.contains("Chrome/"))
        assertTrue(fromCustom.contains("Chrome/"))
    }

    @Test
    fun parseTrackPayload_parsesDirectAndQuotedEvalJson() {
        val directJson = """
            {"title":"Starboy (feat. Daft Punk)","artist":"The Weeknd","playing":true,"like":"LIKED","cur":84.5,"dur":230.0,"signedIn":true,"active":true}
        """.trim()

        val state = YtmScriptBridge.parseTrackPayload(directJson, MusicMode.WEB)
        assertNotNull(state)
        assertEquals("Starboy (feat. Daft Punk)", state!!.title)
        assertEquals("The Weeknd", state.artist)
        assertTrue(state.isPlaying)
        assertEquals(LikeStatus.LIKED, state.likeStatus)
        assertEquals(84.5f, state.currentSec, 0.01f)
        assertEquals(230.0f, state.durationSec, 0.01f)
        assertEquals(84.5f / 230f, state.progressFraction(), 0.005f)
        assertTrue(state.signedIn)
        assertTrue(state.hasActiveSession)

        // WebView evaluateJavascript returns a JSON-escaped string wrapped in double quotes
        val escapedEvalResult =
            "\"{\\\"title\\\":\\\"Nightcall \\\\\\\"Remix\\\\\\\"\\\",\\\"artist\\\":\\\"Kavinsky\\\",\\\"playing\\\":false,\\\"like\\\":\\\"DISLIKED\\\",\\\"cur\\\":10,\\\"dur\\\":258,\\\"signedIn\\\":true,\\\"active\\\":true}\""
        val quotedState = YtmScriptBridge.parseTrackPayload(escapedEvalResult, MusicMode.WEB)
        assertNotNull(quotedState)
        assertEquals("Nightcall \"Remix\"", quotedState!!.title)
        assertEquals("Kavinsky", quotedState.artist)
        assertFalse(quotedState.isPlaying)
        assertEquals(LikeStatus.DISLIKED, quotedState.likeStatus)
    }

    @Test
    fun parseTrackPayload_handlesNullAndBlank() {
        assertNull(YtmScriptBridge.parseTrackPayload(null))
        assertNull(YtmScriptBridge.parseTrackPayload("null"))
        assertNull(YtmScriptBridge.parseTrackPayload(""))
    }

    @Test
    fun buildActionAndPollingScripts_referenceExpectedYtmSelectors() {
        val poll = YtmScriptBridge.buildStatePollingJs()
        assertTrue(poll.contains("ytmusic-player-bar"))
        assertTrue(poll.contains("#play-pause-button"))
        assertTrue(poll.contains("like-status"))

        assertTrue(YtmScriptBridge.buildActionJs(MusicAction.PLAY_PAUSE).contains("#play-pause-button"))
        assertTrue(YtmScriptBridge.buildActionJs(MusicAction.NEXT).contains(".next-button"))
        assertTrue(YtmScriptBridge.buildActionJs(MusicAction.PREVIOUS).contains(".previous-button"))
        assertTrue(YtmScriptBridge.buildActionJs(MusicAction.LIKE).contains("button-shape-like"))
        assertTrue(YtmScriptBridge.buildActionJs(MusicAction.DISLIKE).contains("button-shape-dislike"))
    }

    @Test
    fun isGoogleWebViewAuthBlocked_detectsDisallowedUserAgentWithoutFalsePositives() {
        assertTrue(
            YtmScriptBridge.isGoogleWebViewAuthBlocked(
                url = "https://accounts.google.com/v3/signin/rejected?error=disallowed_useragent",
                pageTitle = "Sign in",
                bodySnippet = ""
            )
        )
        assertTrue(
            YtmScriptBridge.isGoogleWebViewAuthBlocked(
                url = "https://accounts.google.com/v3/signin",
                pageTitle = "Error 403",
                bodySnippet = "Couldn't sign you in. This browser or app may not be secure."
            )
        )
        assertFalse(
            YtmScriptBridge.isGoogleWebViewAuthBlocked(
                url = "https://accounts.google.com/v3/signin",
                pageTitle = "Sign in - Google Accounts",
                bodySnippet = "Use your Google Account to continue to YouTube Music"
            )
        )
        assertFalse(
            YtmScriptBridge.isGoogleWebViewAuthBlocked(
                url = "https://music.youtube.com/",
                pageTitle = "YouTube Music",
                bodySnippet = "Home Explore Library"
            )
        )
    }

    @Test
    fun crit73_playerScriptsRequireExactYtmOriginAndEnabledPlayerBar() {
        val pollJs = YtmScriptBridge.buildStatePollingJs()
        assertTrue(
            "Polling script must reject non-YTM origins before touching DOM",
            pollJs.contains("window.location.origin !== 'https://music.youtube.com'")
        )

        MusicAction.values().forEach { action ->
            val actionJs = YtmScriptBridge.buildActionJs(action)
            assertTrue(
                "Action script for $action must enforce exact YTM origin",
                actionJs.contains("window.location.origin !== 'https://music.youtube.com'")
            )
            assertFalse(
                "Action script for $action must not fall back to document",
                actionJs.contains("|| document")
            )
            assertTrue(
                "Action script for $action must check disabled / aria-disabled",
                actionJs.contains("disabled") && actionJs.contains("aria-disabled")
            )
        }
    }

    @Test
    fun crit73_navigationAllowlistAndPlayerOriginUrlChecks() {
        assertTrue(YtmScriptBridge.isAllowedNavigationUrl("https://music.youtube.com/"))
        assertTrue(YtmScriptBridge.isAllowedNavigationUrl("https://accounts.google.com/v3/signin"))
        assertTrue(YtmScriptBridge.isAllowedNavigationUrl("https://accounts.youtube.com/AccountsSetSID"))
        assertTrue(YtmScriptBridge.isAllowedNavigationUrl("https://consent.google.com/ml?continue=x"))
        assertTrue(YtmScriptBridge.isAllowedNavigationUrl("https://sub.youtube.com/watch"))

        assertFalse("Cleartext HTTP must be blocked", YtmScriptBridge.isAllowedNavigationUrl("http://music.youtube.com/"))
        assertFalse("Arbitrary external host must be blocked", YtmScriptBridge.isAllowedNavigationUrl("https://evil.example.com/login"))
        assertFalse("Suffix spoof host must be blocked", YtmScriptBridge.isAllowedNavigationUrl("https://music.youtube.com.evil.com/"))
        assertFalse("Userinfo spoof must be blocked", YtmScriptBridge.isAllowedNavigationUrl("https://music.youtube.com@evil.com/"))
        assertFalse("Custom scheme must be blocked", YtmScriptBridge.isAllowedNavigationUrl("intent://watch#Intent;end"))
        assertFalse("File scheme must be blocked", YtmScriptBridge.isAllowedNavigationUrl("file:///etc/hosts"))

        assertTrue(YtmScriptBridge.isYtmPlayerUrl("https://music.youtube.com/watch?v=123"))
        assertFalse("Google sign-in is allowed nav but NOT YTM player origin", YtmScriptBridge.isYtmPlayerUrl("https://accounts.google.com/v3/signin"))
        assertFalse("Main youtube.com is NOT music.youtube.com player origin", YtmScriptBridge.isYtmPlayerUrl("https://www.youtube.com/"))
        assertFalse("Cleartext YTM is NOT player origin", YtmScriptBridge.isYtmPlayerUrl("http://music.youtube.com/"))
    }
}
