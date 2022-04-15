package com.google.android.libraries.car.trustagent

import android.bluetooth.BluetoothDevice

/**
 * A channel for out-of-band verification.
 *
 * This class performs no-op. It is a pass-through of the input [oobData] by immediately making a
 * success callback in [startOobDataExchange].
 *
 * Usually the [oobData] is received before association is started, i.e. via QR code or NFC.
 */
internal class PassThroughOobChannel(private val oobData: OobData) : OobChannel {
  override var callback: OobChannel.Callback? = null
  override fun startOobDataExchange(device: BluetoothDevice) {
    callback?.onSuccess(oobData)
  }
  override fun stopOobDataExchange() {}
}
