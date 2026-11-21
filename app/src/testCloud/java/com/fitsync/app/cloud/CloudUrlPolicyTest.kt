package com.fitsync.app.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudUrlPolicyTest {
    @Test
    fun normalizeBaseUrlEnforcesHttpsInRelease() {
        assertThat(CloudUrlPolicy.normalizeBaseUrl("https://fit.example.com/", allowCleartext = false))
            .isEqualTo("https://fit.example.com")
        assertThat(CloudUrlPolicy.normalizeBaseUrl("http://10.0.2.2:5273/", allowCleartext = false))
            .isNull()
        assertThat(CloudUrlPolicy.normalizeBaseUrl("http://10.0.2.2:5273/", allowCleartext = true))
            .isEqualTo("http://10.0.2.2:5273")
        assertThat(CloudUrlPolicy.normalizeBaseUrl("javascript:alert(1)", allowCleartext = true))
            .isNull()
    }

    @Test
    fun originAndApiChecksRejectCrossOriginAndSpoofing() {
        assertThat(CloudUrlPolicy.isTrustedWebOrigin("https://fit.example.com/#/pages/overview/index", "https://fit.example.com"))
            .isTrue()
        assertThat(CloudUrlPolicy.isTrustedWebOrigin("https://fit.example.com:443/index.html", "https://fit.example.com"))
            .isTrue()
        assertThat(CloudUrlPolicy.isTrustedWebOrigin("https://evil.example.com/", "https://fit.example.com"))
            .isFalse()
        assertThat(
            CloudUrlPolicy.isTrustedApiUrl(
                "https://fit.example.com/api",
                "https://fit.example.com/api",
                "https://fit.example.com",
            ),
        ).isTrue()
        assertThat(
            CloudUrlPolicy.isTrustedApiUrl(
                "https://attacker.com/api",
                "https://fit.example.com/api",
                "https://fit.example.com",
            ),
        ).isFalse()
    }

    @Test
    fun oauthExternalUrlDetectsGoogleAndStravaOnly() {
        assertThat(CloudUrlPolicy.isOAuthExternalUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=1"))
            .isTrue()
        assertThat(CloudUrlPolicy.isOAuthExternalUrl("https://www.strava.com/oauth/authorize?client_id=1"))
            .isTrue()
        assertThat(CloudUrlPolicy.isOAuthExternalUrl("https://fit.example.com/#/pages/sync/index"))
            .isFalse()
        assertThat(CloudUrlPolicy.isOAuthExternalUrl("http://accounts.google.com/o/oauth2/v2/auth"))
            .isFalse()
    }

    @Test
    fun buildWebReturnUrlReconstructsGoogleAndStravaSafely() {
        val googleUrl = CloudUrlPolicy.buildWebReturnUrl(
            baseWebUrl = "https://fit.example.com",
            callbackUrl = "fitsync-cloud://oauth?returnPath=%2Fpages%2Foverview%2Findex&googleTicket=t123",
            timestampMs = 1700000000000L,
        )
        assertThat(googleUrl)
            .isEqualTo("https://fit.example.com/?_oauthTs=1700000000000&googleTicket=t123#/pages/overview/index")

        val stravaUrl = CloudUrlPolicy.buildWebReturnUrl(
            baseWebUrl = "https://fit.example.com",
            callbackUrl = "fitsync-cloud://oauth?returnPath=%2Fpages%2Fsync%2Findex&stravaOAuth=success",
            timestampMs = 1700000000000L,
        )
        assertThat(stravaUrl)
            .isEqualTo("https://fit.example.com/?_oauthTs=1700000000000#/pages/sync/index?stravaOAuth=success")

        // 非法 returnPath 必须回落到安全首页，不拼接任意路径或脚本。
        val sanitized = CloudUrlPolicy.buildWebReturnUrl(
            baseWebUrl = "https://fit.example.com",
            callbackUrl = "fitsync-cloud://oauth?returnPath=//evil.com&googleOAuth=error",
            timestampMs = 1700000000000L,
        )
        assertThat(sanitized)
            .isEqualTo("https://fit.example.com/?_oauthTs=1700000000000&googleOAuth=error#/pages/overview/index")

        assertThat(
            CloudUrlPolicy.buildWebReturnUrl(
                baseWebUrl = "https://fit.example.com",
                callbackUrl = "https://evil.com/oauth?googleTicket=t123",
            ),
        ).isNull()
    }
}
