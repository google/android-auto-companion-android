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

package com.google.android.libraries.car.trustagent.util

import android.Manifest.permission
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.ParcelUuid
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max

private const val UUID_LENGTH = 16
private const val TAG = "Util"

/** Converts [uuid] to byte array. To convert back, use [bytesToUuid] */
fun uuidToBytes(uuid: UUID): ByteArray {
  return ByteBuffer.allocate(UUID_LENGTH)
    .order(ByteOrder.BIG_ENDIAN)
    .putLong(uuid.mostSignificantBits)
    .putLong(uuid.leastSignificantBits)
    .array()
}

/**
 * Converts a byte array to UUID. [bytes] should be created by [uuidToBytes].
 *
 * Throws exception if conversion fails.
 */
fun bytesToUuid(bytes: ByteArray): UUID {
  check(bytes.size == UUID_LENGTH) {
    "Input bytes does not meet expectation: ${bytes.toHexString()}"
  }
  with(ByteBuffer.wrap(bytes)) {
    return UUID(getLong(), getLong())
  }
}

fun ByteArray.toHexString(): String {
  return joinToString(separator = "", prefix = "0x") { "%02x".format(it) }
}

fun Bitmap.toByteString(compressFormat: Bitmap.CompressFormat, quality: Int): ByteString {
  val output = ByteString.newOutput()
  compress(compressFormat, quality, output)
  return output.toByteString()
}

fun Bitmap.toByteArray(compressFormat: Bitmap.CompressFormat, quality: Int): ByteArray {
  val stream = ByteArrayOutputStream()
  compress(compressFormat, quality, stream)
  return stream.toByteArray()
}

/**
 * Returns the bitmap scaled relative to a maximum size.
 *
 * If either width or height is greater than maximum size, the bitmap is scaled proportionally.
 *
 * If the width and height is less than or equal to, the initial bitmap is not altered and is
 * returned as is.
 *
 * @maxSize the maximum size in pixels that the bitmap should have on both width and height
 */
fun Bitmap.reduceSize(maxSize: Int): Bitmap {
  val scalingFactor: Float = maxSize.toFloat() / max(width, height)
  return if (scalingFactor < 1.0) {
    Bitmap.createScaledBitmap(
      this,
      (width * scalingFactor).toInt(),
      (height * scalingFactor).toInt(),
      /* filter= */ true
    )
  } else {
    this
  }
}

fun Drawable.toBitmap(): Bitmap {
  if (this is BitmapDrawable) return bitmap
  val bmp = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  setBounds(0, 0, canvas.width, canvas.height)
  draw(canvas)
  return bmp
}

fun getAppName(context: Context, packageName: String): String {
  return try {
    val packageManager = context.packageManager
    val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    packageManager.getApplicationLabel(info) as String
  } catch (e: PackageManager.NameNotFoundException) {
    // TODO
    e.printStackTrace()
    ""
  }
}

/**
 * Attempts to parse service data of GATT [serviceUuid] as device ID out of [scanResult].
 *
 * Returns `null` [scanResult] does not contain GATT service or service data does not exist.
 */
fun retrieveDeviceIdFromScanResult(scanResult: ScanResult, serviceUuid: UUID): UUID? =
  scanResult.scanRecord?.getServiceData(ParcelUuid(serviceUuid))?.let { bytesToUuid(it) }

/**
 * Returns the string according to the given [stringResName]. Returns [defaultValue] if the string
 * resource is not found in given [context].
 */
fun getStringResourceByNameOrDefault(
  context: Context,
  resName: String,
  defaultValue: String,
  vararg args: String?
): String {
  val stringId = context.resources.getIdentifier(resName, "string", context.packageName)
  // Can not find the string with the given name.
  if (stringId == 0) {
    return defaultValue
  }
  return context.getString(stringId, *args)
}

/** Returns `true` if the [context] has the necessary permissions granted. */
fun checkPermissionsForBleScanner(context: Context): Boolean {
  val requiredPermissions =
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
      listOf(permission.ACCESS_FINE_LOCATION)
    } else {
      listOf(permission.BLUETOOTH_SCAN)
    }
  // Either permission of FINE/COARSE_LOCATION is sufficient.
  if (requiredPermissions.any { !checkPermission(context, it) }) {
    loge(TAG, "Missing required permission. No-op")
    return false
  }
  // Soft check of ACCESS_BACKGROUND_LOCATION - logs error if not granted.
  // Background location permission is only necessary for background BLE scanning.
  if (Build.VERSION.SDK_INT in listOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.R)) {
    if (!checkPermission(context, permission.ACCESS_BACKGROUND_LOCATION)) {
      loge(TAG, "Missing ACCESS_BACKGROUND_LOCATION permission. Continue.")
    }
  }
  return true
}

/** Returns `true` if the [context] has the required permissions granted. */
fun checkPermissionsForBluetoothConnection(context: Context): Boolean =
  Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
    checkPermission(context, permission.BLUETOOTH_CONNECT)

private fun checkPermission(context: Context, permission: String): Boolean {
  val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
  if (!granted) {
    loge(TAG, "Required $permission is not granted.")
  }
  return granted
}
