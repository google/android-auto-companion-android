package com.google.android.libraries.car.trustagent

import android.app.Activity
import android.companion.AssociationRequest as CdmAssociationRequest
import android.companion.CompanionDeviceManager

open class TestAssociationHandler() : AssociationHandler {
  var request: CdmAssociationRequest? = null
  val associatedDevices: MutableList<String> = mutableListOf()

  override val associations = associatedDevices

  override fun associate(
    activity: Activity,
    request: CdmAssociationRequest,
    callback: CompanionDeviceManager.Callback
  ) {
    this.request = request
  }

  override fun disassociate(macAddress: String) = true
}
