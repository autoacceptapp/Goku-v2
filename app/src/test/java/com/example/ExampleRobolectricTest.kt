package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.automation.GokuJsBridge
import com.goku.ai.body.UniversalAppPilot
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.voice.GokuEarAndVoiceEngine
import com.goku.ai.voice.GokuKillSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Goku", appName)
  }

  @Test
  fun `goku js bridge sleep aborts rapidly when kill switch triggers`() {
    GokuKillSwitch.reset()
    val context = ApplicationProvider.getApplicationContext<Context>()
    val vault = GokuVaultRepository.getInstance(context)
    val adb = LocalAdbEngine.getInstance(context)
    val pilot = UniversalAppPilot(context, adb)
    val voice = GokuEarAndVoiceEngine.getInstance(context)
    val bridge = GokuJsBridge(
      vault = vault,
      adbEngine = adb,
      pilot = pilot,
      voiceEngine = voice,
      dryRun = false,
      outputLog = StringBuilder()
    )

    val stopThread = thread {
      Thread.sleep(60L)
      GokuKillSwitch.triggerStop("RobolectricTest")
    }

    val startMs = System.currentTimeMillis()
    var thrown: Throwable? = null
    try {
      bridge.sleep(2500L)
    } catch (t: Throwable) {
      thrown = t
    } finally {
      stopThread.join(1000L)
      GokuKillSwitch.reset()
    }
    val elapsedMs = System.currentTimeMillis() - startMs

    assertTrue("Expected SecurityException on KillSwitch, got: $thrown", thrown is SecurityException)
    assertTrue("Expected sleep abort well under 750ms, took ${elapsedMs}ms", elapsedMs < 750L)
  }
}
