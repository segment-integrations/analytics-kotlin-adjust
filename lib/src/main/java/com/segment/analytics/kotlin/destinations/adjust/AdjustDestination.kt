package com.segment.analytics.kotlin.destinations.adjust

import android.app.Activity
import android.content.Context
import com.adjust.sdk.*
import com.segment.analytics.kotlin.android.plugins.AndroidLifecycle
import com.segment.analytics.kotlin.core.*
import com.segment.analytics.kotlin.core.platform.DestinationPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.platform.plugins.logger.log
import com.segment.analytics.kotlin.core.utilities.getDouble
import com.segment.analytics.kotlin.core.utilities.getString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Adjust is a business intelligence platform for mobile app marketers, combining attribution for
 * advertising sources with analytics and store statistics.
 *
 * @see <a href="https://www.adjust.com">Adjust</a>
 * @see <a href="https://segment.com/docs/integrations/adjust/">Adjust Integration</a>
 * @see <a href="https://github.com/adjust/android_sdk">Adjust Android SDK</a>
 */
class AdjustDestination(
    private var adjustInstance: AdjustInstance = Adjust.getDefaultInstance()
) : DestinationPlugin(), AndroidLifecycle {

    internal var settings: AdjustSettings? = null

    private val REVENUE_KEY = "revenue"
    private val CURRENCY_KEY = "currency"

    override val key: String = "Adjust"

    override fun update(settings: Settings, type: Plugin.UpdateType) {
        super.update(settings, type)
        if (settings.hasIntegrationSettings(this)) {
            analytics.log("Adjust Destination is enabled")
            this.settings = settings.destinationSettings(key, AdjustSettings.serializer())
            if (type == Plugin.UpdateType.Initial) {
                this.settings?.let {
                    val environment =
                        if (it.setEnvironmentProduction)
                            AdjustConfig.ENVIRONMENT_PRODUCTION
                        else
                            AdjustConfig.ENVIRONMENT_SANDBOX
                    val adjustConfig = AdjustConfig(
                        analytics.configuration.application as Context?,
                        it.appToken, environment
                    )

                    if (it.setEventBufferingEnabled) {
                        adjustConfig.setEventBufferingEnabled(true)
                    }
                    if (it.trackAttributionData) {
//                        registering a delegate callback to notify tracker attribution changes.
                        val listener: OnAttributionChangedListener =
                            AdjustSegmentAttributionChangedListener(analytics)
                        adjustConfig.setOnAttributionChangedListener(listener)
                    }
                    adjustInstance.onCreate(adjustConfig)
                    analytics.log("Adjust Destination loaded")
                }
            }
        }

    }

    override fun identify(payload: IdentifyEvent): BaseEvent? {
        setPartnerParams(payload)
        return super.identify(payload)
    }

    override fun track(payload: TrackEvent): BaseEvent? {
        setPartnerParams(payload)

        val token: String? = settings?.customEvents?.getString(payload.event)
        token ?: return null

        val properties: Properties = payload.properties
        val event = AdjustEvent(token)
        properties.let {
            for ((propertyKey, propertyValue) in it) {
                event.addCallbackParameter(propertyKey, propertyValue.toString())
            }
        }
        val revenue: Double = properties.getDouble(REVENUE_KEY) ?: 0.0
        val currency: String = properties.getString(CURRENCY_KEY) ?: ""
        if (revenue != 0.0 && currency.isNotEmpty()) {
            event.setRevenue(revenue, currency)
        }
        analytics.log("Adjust.getDefaultInstance().trackEvent($event);")
        adjustInstance.trackEvent(event)
        return payload
    }

    override fun reset() {
        super.reset()
        adjustInstance.resetSessionPartnerParameters()
        analytics.log("Adjust.getDefaultInstance().resetSessionPartnerParameters()")
    }

    /**
     * AndroidActivity Lifecycle Methods
     */
    override fun onActivityResumed(activity: Activity?) {
        super.onActivityResumed(activity)
        adjustInstance.onResume()
    }

    override fun onActivityPaused(activity: Activity?) {
        super.onActivityPaused(activity)
        adjustInstance.onPause()
    }

    /**
     * adding session Partner parameters to Adjust. It will merge session partner parameters with event partner parameter.
     */
    private fun setPartnerParams(payload: BaseEvent) {
        if (payload.userId.isNotEmpty()) {
            adjustInstance.addSessionPartnerParameter("userId", payload.userId)
            analytics.log("Adjust.getDefaultInstance().addSessionPartnerParameter(userId, ${payload.userId})")
        }
        if (payload.anonymousId.isNotEmpty()) {
            adjustInstance.addSessionPartnerParameter("anonymousId", payload.anonymousId)
            analytics.log("Adjust.getDefaultInstance().addSessionPartnerParameter(anonymousId, ${payload.anonymousId})")
        }
    }

    /**
     * A listener class to receive a callback on tracker attribution changes.
     * Need to add the attribution callback to config instance before starting the Adjust SDK.
     */
    internal class AdjustSegmentAttributionChangedListener(val analytics: Analytics) :
        OnAttributionChangedListener {
        override fun onAttributionChanged(attribution: AdjustAttribution) {
            val properties = buildJsonObject {
                put("provider", "Adjust")
                put("trackerToken", attribution.trackerToken)
                put("trackerName", attribution.trackerName)
                put("campaign", buildJsonObject {
                    put("source", attribution.network)
                    put("name", attribution.campaign)
                    put("content", attribution.clickLabel)
                    put("adCreative", attribution.creative)
                    put("adGroup", attribution.adgroup)
                })
            }
//            Analytic core event.
            analytics.track(
                "Install Attributed",
                properties
            )
        }
    }
}

/**
 * Adjust Settings data class.
 */
@Serializable
data class AdjustSettings(
    // Adjust App Token
    var appToken: String,
    // Adjust Segment value for Send to Production Environment on Adjust
    var setEnvironmentProduction: Boolean = false,
    //    Adjust Segment value to Buffer and batch events sent to Adjust
    var setEventBufferingEnabled: Boolean = false,
    //    Adjust Segment value to track Attribution Data
    var trackAttributionData: Boolean = false,
    //    JSON Custom events
    val customEvents: JsonObject
)