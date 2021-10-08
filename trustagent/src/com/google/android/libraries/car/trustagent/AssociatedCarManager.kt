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

package com.google.android.libraries.car.trustagent

import android.content.Context
import com.google.android.libraries.car.trustagent.storage.CryptoHelper
import com.google.android.libraries.car.trustagent.storage.KeyStoreCryptoHelper
import com.google.android.libraries.car.trustagent.util.logw
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the cars for which this device has been associated with.
 *
 * @property connectedCarDatabase The database to write and read car association info from.
 * @property coroutineContext The context with which to access the database. Should be a background
 * dispatcher.
 * @property cryptoHelper The class responsible for encrypting the encryption keys of cars before
 * storing into the database.
 */
internal class AssociatedCarManager
internal constructor(
  context: Context,
  connectedCarDatabase: ConnectedCarDatabase = DatabaseProvider.getInstance(context).database,
  private val cryptoHelper: CryptoHelper = KeyStoreCryptoHelper()
) {
  private val keyGenerator =
    KeyGenerator.getInstance(IDENTIFICATION_KEY_ALGORITHM).apply {
      init(IDENTIFICATION_KEY_SIZE_BITS)
    }
  private val database = connectedCarDatabase.associatedCarDao()

  /** Returns `true` if this device is associated with at least one car. */
  suspend fun loadIsAssociated(): Boolean = database.loadIsAssociated()

  /** Returns `true` if a car with the given [deviceId] is associated with this phone. */
  suspend fun loadIsAssociated(deviceId: UUID): Boolean =
    database.loadIsAssociatedByCarId(deviceId.toString())

  /** Returns `true` if a car with the given [macAddress] is associated with this phone. */
  suspend fun loadIsAssociated(macAddress: String): Boolean =
    database.loadIsAssociatedByMacAddress(macAddress)

  /** Returns the cars that this device has been associated with. */
  suspend fun retrieveAssociatedCars(): List<AssociatedCar> =
    database.loadAllAssociatedCars().map {
      AssociatedCar(
        UUID.fromString(it.id),
        it.name,
        it.macAddress,
        it.identificationKey.toSecretKey()
      )
    }

  /** Stores a [Car] as an associated device and returns `true` if the operation succeeded. */
  suspend fun add(car: Car): Boolean {
    val key = car.messageStream.encryptionKey
    if (key == null) {
      return false
    }
    val encryptedKey = cryptoHelper.encrypt(key.asBytes()) ?: return false

    val entity =
      AssociatedCarEntity(
        car.deviceId.toString(),
        encryptedKey,
        car.identificationKey.toEncryptedString(),
        car.name,
        car.bluetoothDevice.address,
        isUserRenamed = false
      )
    database.insertAssociatedCar(entity)
    return true
  }

  /**
   * Removes a car from the stored associated car list and returns `true` if the operation
   * succeeded.
   */
  suspend fun clear(deviceId: UUID): Boolean {
    return database.deleteAssociatedCar(deviceId.toString()) > 0
  }

  /** Removes all stored associated cars and returns `true` if the operation succeeded. */
  suspend fun clearAll(): Boolean {
    return database.deleteAllAssociatedCars() > 0
  }

  /**
   * Updates any stored encryption key for the given [car] to match the new key that is present in
   * the [car] and returns `true` if the operation succeeded.
   */
  suspend fun updateEncryptionKey(car: Car): Boolean {
    val key = car.messageStream.encryptionKey
    if (key == null) {
      return false
    }
    val encryptedKey = cryptoHelper.encrypt(key.asBytes()) ?: return false

    val entity = AssociatedCarKey(car.deviceId.toString(), encryptedKey)
    return database.updateEncryptionKey(entity) > 0
  }

  /**
   * Returns the encryption key for the car with the given [deviceId] or `null` if that car is not
   * currently associated with this phone.
   */
  suspend fun loadEncryptionKey(deviceId: UUID): ByteArray? =
    database.loadEncryptionKey(deviceId.toString())?.let { cryptoHelper.decrypt(it.encryptionKey) }

  /** Returns the mac address of the associated car with id [deviceId]. */
  suspend fun loadMacAddress(deviceId: UUID): String =
    database.loadMacAddressByCarId(deviceId.toString())

  /** Returns the name of the associated car with id [deviceId]. */
  suspend fun loadName(deviceId: UUID): String = database.loadNameByCarId(deviceId.toString())

  /**
   * Renames a car with the given [deviceId] to the specified [name] and returns `true` if the
   * operation succeeded.
   *
   * The given name should be non-empty, or this call will do nothing.
   */
  suspend fun rename(deviceId: UUID, name: String): Boolean {
    if (name.isEmpty()) {
      logw(TAG, "Rename called with an empty car name. Ignoring.")
      return false
    }

    val entity = AssociatedCarName(deviceId.toString(), name, isUserRenamed = true)
    return database.updateName(entity) > 0
  }

  /** Generates a [SecretKey]. */
  fun generateKey(): SecretKey = keyGenerator.generateKey()

  /**
   * Converts [this] String to a SecretKey.
   *
   * This String must be an encrypted value of a SecretKey. See [toEncryptedString].
   */
  private fun String.toSecretKey(): SecretKey =
    SecretKeySpec(cryptoHelper.decrypt(this), AssociatedCarManager.IDENTIFICATION_KEY_ALGORITHM)

  /**
   * Encrypts [this] SecretKey to a String.
   *
   * The reverse can be done by [String.toSecretKey].
   */
  private fun SecretKey.toEncryptedString(): String {
    val encrypted = cryptoHelper.encrypt(encoded)
    if (encrypted == null) {
      // TODO(b/155932001): CryptoHelper should not be able to return null.
      throw IllegalStateException("Could not encrypt secret key.")
    }
    return encrypted
  }

  companion object {
    private const val TAG = "AssociatedCarManager"
    private const val IDENTIFICATION_KEY_SIZE_BITS = 256

    internal const val IDENTIFICATION_KEY_ALGORITHM = "HmacSHA256"
  }
}
