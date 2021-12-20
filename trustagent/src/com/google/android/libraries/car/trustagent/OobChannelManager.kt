package com.google.android.libraries.car.trustagent

import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.car.trustagent.util.logi
import com.google.android.libraries.car.trustagent.util.logwtf
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Manages OobChannels and the received out-of-band data.
 *
 * @property oobChannels channels that can receive OOB data.
 * @property executorService provides the executors that are used by the OOB channels. This executor
 * service is automatically shut down when the OOB process finishes.
 */
internal open class OobChannelManager(
  @get:VisibleForTesting internal val oobChannels: List<OobChannel>,
  private val executorService: ExecutorService?,
) {
  // Tracks the number of non-failure OOB channels.
  private val remainingOobChannelCounter = AtomicInteger(oobChannels.size)

  // Continuation of the OOB channel callback; any success will resume this continuation.
  @GuardedBy("oobDataContinuationLock")
  private var oobDataContinuation: Continuation<OobData?>? = null
  private val oobDataContinuationLock = Any()

  private val channelCallback: OobChannel.Callback =
    object : OobChannel.Callback {
      override fun onSuccess(oobData: OobData) {
        logi(TAG, "Received OOB data - stopping all channels.")
        oobChannels.forEach { it.stopOobDataExchange() }

        finish(oobData)
      }

      override fun onFailure() {
        logi(TAG, "Received OOB channel onFailure() callback.")
        val remaining = remainingOobChannelCounter.decrementAndGet()
        if (remaining <= 0) {
          logi(TAG, "All OOB channels have failed.")
          if (remaining < 0) {
            logwtf(TAG, "The counter of remaining channels must be non-negative.")
          }
          finish(null)
        }
      }
    }

  /**
   * Reads [OobData] by initiating all [oobChannels].
   *
   * Returns the OOB data if any of the channels succeeded; `null` if all of the channels failed.
   */
  open suspend fun readOobData(): OobData? {
    if (oobChannels.isEmpty()) return null

    val oobData =
      suspendCoroutine<OobData?> { cont ->
        synchronized(oobDataContinuationLock) { oobDataContinuation = cont }
        for (channel in oobChannels) {
          channel.callback = channelCallback
          channel.startOobDataExchange()
        }
      }

    return oobData
  }

  private fun finish(oobData: OobData?) {
    synchronized(oobDataContinuationLock) {
      oobDataContinuation?.resume(oobData)
      oobDataContinuation = null
    }
    executorService?.shutdownNow()
  }

  companion object {
    private const val TAG = "OobChannelManager"
  }
}
