package com.google.android.libraries.car.trustagent

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OobChannelManagerTest {
  private lateinit var manager: OobChannelManager
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testBluetoothDevice =
    context
      .getSystemService(BluetoothManager::class.java)
      .adapter
      .getRemoteDevice("00:11:22:33:AA:BB")

  @Test
  fun noOobChannel_nullOobConnectionManager() =
    runTest(UnconfinedTestDispatcher()) {
      val manager = OobChannelManager(emptyList(), executorService = null)

      assertThat(manager.readOobData(testBluetoothDevice)).isNull()
    }

  @Test
  fun start_allOobChannelsStarted() {
    val oobChannels = listOf<OobChannel>(FakeOobChannel(), FakeOobChannel())
    val manager = OobChannelManager(oobChannels, executorService = null)

    CoroutineScope(Dispatchers.Main).launch { manager.readOobData(testBluetoothDevice) }

    shadowOf(Looper.getMainLooper()).idle()
    assertThat(oobChannels.all { (it as FakeOobChannel).isStarted }).isTrue()
  }

  @Test
  fun oneChannelReceivesOobData_restStopped() {
    val oobChannels = listOf<OobChannel>(FakeOobChannel(), FakeOobChannel())
    val manager = OobChannelManager(oobChannels, executorService = null)

    val deferred =
      CoroutineScope(Dispatchers.Main).async { manager.readOobData(testBluetoothDevice) }
    shadowOf(Looper.getMainLooper()).idle()

    oobChannels[0].callback?.onSuccess(createOobData())
    shadowOf(Looper.getMainLooper()).idle()

    assertThat(deferred.getCompleted()).isNotNull()
    assertThat(oobChannels.none { (it as FakeOobChannel).isStarted }).isTrue()
  }

  @Test
  fun allChannelsFailed_nullOobConnectionManager() {
    val oobChannels = listOf<OobChannel>(FakeOobChannel(), FakeOobChannel())
    val manager = OobChannelManager(oobChannels, executorService = null)

    val deferred =
      CoroutineScope(Dispatchers.Main).async { manager.readOobData(testBluetoothDevice) }
    shadowOf(Looper.getMainLooper()).idle()

    oobChannels.forEach { it.callback?.onFailure() }
    shadowOf(Looper.getMainLooper()).idle()

    assertThat(deferred.getCompleted()).isNull()
  }

  @Test
  fun allChannelsFailed_executorServiceShutdown() {
    val executorService = MoreExecutors.newDirectExecutorService()
    val oobChannels = listOf<OobChannel>(FakeOobChannel(), FakeOobChannel())
    val manager = OobChannelManager(oobChannels, executorService)

    CoroutineScope(Dispatchers.Main).launch { manager.readOobData(testBluetoothDevice) }
    shadowOf(Looper.getMainLooper()).idle()

    oobChannels.forEach { it.callback?.onFailure() }
    shadowOf(Looper.getMainLooper()).idle()

    assertThat(executorService.isShutdown()).isTrue()
  }

  private fun createOobData() =
    OobData(
      ByteArray(10).apply { Random().nextBytes(this) },
      ByteArray(10).apply { Random().nextBytes(this) },
      ByteArray(10).apply { Random().nextBytes(this) },
    )

  class FakeOobChannel : OobChannel {
    var isStarted = false

    override var callback: OobChannel.Callback? = null

    override fun startOobDataExchange(device: BluetoothDevice) {
      isStarted = true
    }

    override fun stopOobDataExchange() {
      isStarted = false
    }
  }
}
