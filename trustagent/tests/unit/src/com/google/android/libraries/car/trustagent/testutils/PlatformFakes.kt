// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.android.libraries.car.trustagent.testutils

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import com.google.android.libraries.car.trustagent.util.uuidToBytes
import java.util.UUID

// A collection to create fakes of Android platform classes.

/** Creates a [ScanRecord] with reflection since its constructor is private. */
fun createScanRecord(
  name: String? = null,
  serviceUuids: List<UUID>,
  serviceData: Map<UUID, ByteArray>
): ScanRecord {
  val advertisementData = createAdvertisementData(name, serviceUuids, serviceData)
  return ScanRecord::class
    .java
    .getMethod("parseFromBytes", ByteArray::class.java)
    .invoke(null, advertisementData) as
    ScanRecord
}

/**
 * Create the advertisement data according to the given local [name], [serviceUuids] and
 * [serviceData].
 *
 * Here is an example of how the advertisement data is constructed:
 * 020102 1107D7AB6F9746451E8DF943BE27A4682A5E 051620004323 03081023
 *        |<-------------------------------->|Completed list of 128 bit service UUID
 *                                            |<---------->|Service data(16 bit UUID + data)
 *                                                          |<---->|Local name
 * Every entry consists of length, tag and content. e.g.
 * 03 08 1023
 *  < Total number of bytes.
 *     < Tag of local name.
 *       < Bytes representative of the local name.
 *
 * @see [Advertising data basic](https://community.silabs.com/s/article/kba-bt-0201-bluetooth-advertising-data-basics?language=en_US)
 */
private fun createAdvertisementData(
  name: String? = null,
  serviceUuids: List<UUID>,
  serviceData: Map<UUID, ByteArray>
): ByteArray {
  var uuidBytes = byteArrayOf()
  for (uuid in serviceUuids) {
    uuidBytes += uuidToBytes(uuid)
  }
  uuidBytes.reverse()
  val uuidLength = Integer.toHexString(1 + 16 * serviceUuids.size)
  var result = "020102${uuidLength}07".hexToByteArray() + uuidBytes

  if (name != null) {
    val nameBytes = name.toByteArray()
    val nameLength = (name.length + 1).toByte()
    result = result + nameLength + "08".hexToByteArray() + nameBytes
  }

  if (serviceData.isNotEmpty()) {
    for (entry in serviceData) {
      val dataUuidBytes = shortUuidToBytes(entry.key)
      val serviceDataLengthByte = (dataUuidBytes.size + entry.value.size + 1).toByte()
      result = result + serviceDataLengthByte + "16".hexToByteArray() + dataUuidBytes + entry.value
    }
  }

  return result
}

/** Converts 16 bits [uuid] to byte array. */
private fun shortUuidToBytes(uuid: UUID): ByteArray {
  val uuidBytes = ByteArray(2)
  val uuidVal = getServiceIdentifierFromParcelUuid(uuid)
  uuidBytes[0] = (uuidVal and 0xFF).toByte()
  uuidBytes[1] = ((uuidVal and 0xFF00) shr 8).toByte()
  return uuidBytes
}

/**
 * Extract the Service Identifier or the actual uuid from the Parcel Uuid.
 *
 * For example, if 00000020-0000-1000-8000-00805f9b34fb is the parcel Uuid,
 * this function will return 0020.
 */
private fun getServiceIdentifierFromParcelUuid(uuid: UUID) =
  (uuid.mostSignificantBits ushr 32).toInt()

private fun String.hexToByteArray(): ByteArray {
  return chunked(2).map { byteStr -> byteStr.toUByte(16).toByte() }.toByteArray()
}

/** Creates a [ScanResult] that contains [scanRecord]. */
fun createScanResult(
  scanRecord: ScanRecord,
  device: BluetoothDevice =
    BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:AA:BB")
) =
  ScanResult(
    device,
    /* eventType= */ 0,
    /* primaryPhy= */ 0,
    /* secondaryPhy= */ 0,
    /* advertisingSid= */ 0,
    /* txPower= */ 0,
    /* rssi= */ 0,
    /* periodicAdveritsingInterval= */ 0,
    scanRecord,
    System.currentTimeMillis()
  )
