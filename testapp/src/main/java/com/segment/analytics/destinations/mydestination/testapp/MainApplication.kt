package com.segment.analytics.destinations.mydestination.testapp

import android.app.Application
import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.manuallyEnableDestination
import com.segment.analytics.kotlin.destinations.adjust.AdjustDestination

class MainApplication : Application() {
    companion object {
        lateinit var analytics: Analytics
    }

    override fun onCreate() {
        super.onCreate()

        analytics = Analytics("<writekey>", applicationContext) {
            flushAt = 1
            flushInterval = 10
        }

        Analytics.debugLogsEnabled = true
        val adjust = AdjustDestination()
        analytics.add(adjust)
    }
}