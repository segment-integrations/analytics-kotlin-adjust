package com.segment.analytics.kotlin.destinations.adjust

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAttribution
import com.segment.analytics.kotlin.core.*
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.LenientJson
import com.segment.analytics.kotlin.core.utilities.getString
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O_MR1])
class AdjustDestinationTests {
    @MockK
    lateinit var mockApplication: Application

    @MockK
    lateinit var mockedContext: Context

    @MockK(relaxUnitFun = true)
    lateinit var mockedAnalytics: Analytics

    private lateinit var adjustDestination: AdjustDestination

    private val sampleAdjustSettings: Settings = LenientJson.decodeFromString(
        """
            {
              "integrations": {
                "Adjust": {
                  "appToken": "xyz1234",
                  "setEnvironmentProduction": true,
                  "setEventBufferingEnabled": true,
                  "trackAttributionData": true,
                   "customEvents": {
                     "foo": "bar"
                   }
                }
              }
            }
        """.trimIndent()
    )

    init {
        MockKAnnotations.init(this)
    }

    @Before
    fun setUp() {
        mockkStatic(Adjust::class)
        adjustDestination = AdjustDestination()
        every { mockedAnalytics.configuration.application } returns mockApplication
        every { mockApplication.applicationContext } returns mockedContext
        mockedAnalytics.configuration.application = mockedContext
        adjustDestination.analytics = mockedAnalytics
    }


    @Test
    fun `settings are updated correctly`() {
        // An adjust example settings
        val adjustSettings: Settings = sampleAdjustSettings

        adjustDestination.update(adjustSettings, Plugin.UpdateType.Initial)

        /* assertions Adjust config */
        assertNotNull(adjustDestination.settings)
        with(adjustDestination.settings!!) {
            assertTrue(setEnvironmentProduction)
            assertTrue(setEventBufferingEnabled)
            assertTrue(trackAttributionData)
            assertEquals(appToken, "xyz1234")
            assertNotNull(customEvents)
        }
    }

    @Test
    fun `identify is handled correctly`() {
        val sampleIdentifyEvent = IdentifyEvent(
            userId = "adjust-UserID-123",
            traits = buildJsonObject {
                put("email", "adjustUserID@abc.com")
                put("firstName", "adjust")
                put("lastName", "user")
            }
        ).apply {
            messageId = "abc-message-1234"
            anonymousId = "adjust-anonId-123"
            integrations = emptyJsonObject
            context = emptyJsonObject
            timestamp = "2022-11-22T11:16:09"
        }

        val identifyEvent = adjustDestination.identify(sampleIdentifyEvent)
        assertNotNull(identifyEvent)

        with(identifyEvent as IdentifyEvent) {
            assertEquals("adjust-UserID-123", userId)
            with(traits) {
                assertEquals("adjust", getString("firstName"))
                assertEquals("user", getString("lastName"))
                assertEquals("adjustUserID@abc.com", getString("email"))
            }
        }
    }

    @Test
    fun `identify is handled correctly with userId`() {
        val sampleIdentifyEvent = IdentifyEvent(
            userId = "adjust-UserID-123",
            traits = buildJsonObject {
                put("email", "adjustUserID@abc.com")
                put("firstName", "adjust")
                put("lastName", "user")
            }
        ).apply {
            messageId = "abc-message-1234"
            anonymousId = "adjust-anonId-123"
            integrations = emptyJsonObject
            context = emptyJsonObject
            timestamp = "2022-11-22T11:16:09"
        }
        val identifyEvent = adjustDestination.identify(sampleIdentifyEvent)
        assertNotNull(identifyEvent)
        verify { Adjust.addGlobalPartnerParameter("userId", "adjust-UserID-123") }
    }

    @Test
    fun `identify is handled correctly with anonymousId`() {
        val sampleIdentifyEvent = IdentifyEvent(
            userId = "adjust-UserID-123",
            traits = buildJsonObject {
                put("email", "adjustUserID@abc.com")
                put("firstName", "adjust")
                put("lastName", "user")
            }
        ).apply {
            messageId = "abc-message-1234"
            anonymousId = "adjust-anonId-123"
            integrations = emptyJsonObject
            context = emptyJsonObject
            timestamp = "2022-11-22T11:16:09"
        }

        val identifyEvent = adjustDestination.identify(sampleIdentifyEvent)
        assertNotNull(identifyEvent)
        verify {
            Adjust.addGlobalPartnerParameter(
                "anonymousId",
                "adjust-anonId-123"
            )
        }
    }

    @Test
    fun `reset is handled correctly`() {
        adjustDestination.reset()
        verify { Adjust.removeGlobalPartnerParameters() }
    }

    @Test
    fun `track is handled correctly`() {
        adjustDestination.update(sampleAdjustSettings, Plugin.UpdateType.Initial)
        val sampleTrackEvent = TrackEvent(
            event = "foo",
            properties = buildJsonObject {
                put("revenue", 200.0f)
                put("currency", "USD")
            }
        ).apply {
            messageId = "abc-message-1234"
            anonymousId = "adjust-anonId-123"
            integrations = emptyJsonObject
            context = emptyJsonObject
            timestamp = "2022-11-22T11:16:09"
        }
        val trackEvent = adjustDestination.track(sampleTrackEvent)
        assertNotNull(trackEvent)
        verify { Adjust.trackEvent(any()) }
    }

    @Test
    fun `trackAttribution data sent correctly to analytics`() {
        val segmentAttributionChangedListener =
            AdjustDestination.AdjustSegmentAttributionChangedListener(mockedAnalytics)
        val attributionData = AdjustAttribution().apply {
            network = "Adjust Network"
            campaign = "Adjust Campaign Name"
            clickLabel = "Adjust Click Label"
            creative = "Adjust creative"
            adgroup = "Adjust Ad group"
            trackerToken = "foo"
            trackerName = "bar"
        }
        segmentAttributionChangedListener.onAttributionChanged(attributionData)
        verify {
            mockedAnalytics.track("Install Attributed", buildJsonObject {
                put("provider", "Adjust")
                put("trackerToken", "foo")
                put("trackerName", "bar")
                put("campaign", buildJsonObject {
                    put("source", "Adjust Network")
                    put("name", "Adjust Campaign Name")
                    put("content", "Adjust Click Label")
                    put("adCreative", "Adjust creative")
                    put("adGroup", "Adjust Ad group")
                })
            })
        }
    }

    @Test
    fun `onActivityResumed() handled correctly`() {
        adjustDestination.onActivityResumed(mockkClass(Activity::class))
        verify { Adjust.onResume() }
    }

    @Test
    fun `onActivityPaused() handled correctly`() {
        adjustDestination.onActivityPaused(mockkClass(Activity::class))
        verify { Adjust.onPause() }
    }
}