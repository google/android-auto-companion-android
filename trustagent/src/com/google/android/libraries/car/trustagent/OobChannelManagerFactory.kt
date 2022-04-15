package com.google.android.libraries.car.trustagent

import com.google.android.companionprotos.CapabilitiesExchangeProto.CapabilitiesExchange.OobChannelType
import com.google.android.libraries.car.trustagent.util.loge
import com.google.android.libraries.car.trustagent.util.logi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** A factory of [OobChannelManager]s. */
internal interface OobChannelManagerFactory {
  /** Creates an [OobChannelManager] based on [oobChannelTypes]. */
  fun create(
    oobChannelTypes: List<OobChannelType>,
    oobData: OobData?,
    securityVersion: Int
  ): OobChannelManager
}

internal class OobChannelManagerFactoryImpl() : OobChannelManagerFactory {

  override fun create(
    oobChannelTypes: List<OobChannelType>,
    oobData: OobData?,
    securityVersion: Int
  ): OobChannelManager {
    var executorService: ExecutorService? = null
    val oobChannels: List<OobChannel> =
      oobChannelTypes.mapNotNull { type ->
        when (type) {
          OobChannelType.BT_RFCOMM -> {
            logi(TAG, "Remote supports BT_RFCOMM. Adding BluetoothRfcommChannel")
            executorService = Executors.newSingleThreadExecutor()
            BluetoothRfcommChannel(
              securityVersion >= MIN_SECURITY_VERSION_FOR_OOB_PROTO,
              executorService!!.asCoroutineDispatcher()
            )
          }
          OobChannelType.PRE_ASSOCIATION -> {
            if (oobData != null) {
              PassThroughOobChannel(oobData)
            } else {
              loge(TAG, "Remote supports PRE_ASSOCIATION but out-of-band data is null.")
              null
            }
          }
          OobChannelType.OOB_CHANNEL_UNKNOWN, OobChannelType.UNRECOGNIZED -> null
        }
      }
    return OobChannelManager(oobChannels, executorService)
  }

  companion object {
    private const val TAG = "OobChannelManagerFactory"

    private const val MIN_SECURITY_VERSION_FOR_OOB_PROTO = 4
  }
}
