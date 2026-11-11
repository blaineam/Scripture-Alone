package com.blainemiller.scripturealone.ui.listen

import android.content.Intent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.robolectric.android.controller.ServiceController
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * A headset's or a car's play button while nothing is reading (Play vitals 2c03f3c7, a Tecno Spark 7 on Android 11):
 * media3's MediaButtonReceiver wakes [ListenPlaybackService] with `startForegroundService`, which the service must answer
 * with `startForeground` — or Android 8–11 kills the app with RemoteServiceException ("did not then call
 * Service.startForeground()"), and Android 12+ with ForegroundServiceDidNotStartInTimeException. media3 only goes foreground
 * once the player shows a notification, so a "continue" that finds nothing to read (or takes too long) left the debt unpaid.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ListenPlaybackServiceTest {

    private fun playButton(): Intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))

    private var controller: ServiceController<ListenPlaybackService>? = null

    /** The service, woken as the system delivers MediaButtonReceiver's startForegroundService intent. */
    private fun wokenByPlayButton(): ListenPlaybackService {
        val built = Robolectric.buildService(ListenPlaybackService::class.java, playButton()).create()
        controller = built
        built.get().onStartCommand(playButton(), 0, 1)
        return built.get()
    }

    @After fun tearDown() {
        controller?.destroy()
    }

    @Test fun aPlayButtonThatWakesTheServiceAlwaysPutsItInTheForeground() {
        val service = wokenByPlayButton()
        ShadowLooper.idleMainLooper()
        assertNotNull(
            "a service started with startForegroundService must call startForeground, whether or not anything is read",
            shadowOf(service).lastForegroundNotification,
        )
    }

    @Test fun whenNothingIsReadTheHoldIsLetGoAndTheServiceStops() {
        val service = wokenByPlayButton()
        // Past the hold's 10-second cap: the "continue" never finishes here (its Bible load runs off the paused main looper).
        repeat(10) { ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS) }
        assertTrue("the placeholder notification is taken down", shadowOf(service).isForegroundStopped)
        assertTrue("nothing is reading, so nothing stays behind", shadowOf(service).isStoppedBySelf)
    }
}
