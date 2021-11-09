package com.google.android.libraries.car.trustagent

import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.companionprotos.OutOfBandAssociationToken
import com.google.android.companionprotos.outOfBandAssociationData
import com.google.android.companionprotos.outOfBandAssociationToken
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.util.Random
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UriElementsTest {
  @Test
  fun reservedParameterName_noViolation() {
    val uri = createUri("foo" to "bar")

    UriElements.decodeFrom(uri)
  }
  @Test
  fun reservedParameterName_oob_fails() {
    val uri = createUri("oobFoo" to "value")

    assertThrows(IllegalStateException::class.java) { UriElements.decodeFrom(uri) }
  }

  @Test
  fun reservedParameterName_bat_fails() {
    val uri = createUri("batBar" to "value")

    assertThrows(IllegalStateException::class.java) { UriElements.decodeFrom(uri) }
  }

  @Test
  fun noQueryParameter_noOobData() {
    val uri = createUri()

    val uriElements = UriElements.decodeFrom(uri)

    assertThat(uriElements.oobData).isNull()
  }

  @Test
  fun noQueryParameter_noDeviceIdentifier() {
    val uri = createUri()

    val uriElements = UriElements.decodeFrom(uri)

    assertThat(uriElements.deviceIdentifier).isNull()
  }

  @Test
  fun parseOobEncryptionKey() {
    val oobToken = createToken()
    val encoded =
      Base64.encodeToString(
        outOfBandAssociationData { token = oobToken }.toByteArray(),
        Base64.URL_SAFE
      )
    val uri = createUri(UriElements.OOB_DATA_PARAMETER_KEY to encoded)

    val uriElements = UriElements.decodeFrom(uri)

    assertThat(uriElements.oobData).isNotNull()
    assertThat(uriElements.oobData!!.encryptionKey).isEqualTo(oobToken.encryptionKey.toByteArray())
    assertThat(uriElements.oobData!!.ihuIv).isEqualTo(oobToken.ihuIv.toByteArray())
    assertThat(uriElements.oobData!!.mobileIv).isEqualTo(oobToken.mobileIv.toByteArray())
  }

  @Test
  fun parseDeviceIdentifier() {
    val identifier = byteArrayOf(0x00.toByte(), 0xff.toByte())
    val encoded =
      Base64.encodeToString(
        outOfBandAssociationData { deviceIdentifier = ByteString.copyFrom(identifier) }
          .toByteArray(),
        Base64.URL_SAFE
      )
    val uri = createUri(UriElements.OOB_DATA_PARAMETER_KEY to encoded)

    val uriElements = UriElements.decodeFrom(uri)

    assertThat(uriElements.deviceIdentifier).isEqualTo(identifier)
  }

  @Test
  fun protoFieldsNotSet_classPropertyIsNull() {
    val encoded = Base64.encodeToString(outOfBandAssociationData {}.toByteArray(), Base64.URL_SAFE)
    val uri = createUri(UriElements.OOB_DATA_PARAMETER_KEY to encoded)

    val uriElements = UriElements.decodeFrom(uri)

    assertThat(uriElements.oobData).isNull()
    assertThat(uriElements.deviceIdentifier).isNull()
  }

  companion object {
    // Length 5 is arbitrary.
    private const val OOB_DATA_LENGTH = 5
    /**
     * Creates a default URI with parameters specified by [pairs].
     *
     * @param pairs contains the key and value of a URI query parameter.
     */
    private fun createUri(vararg pairs: Pair<String, String>): Uri =
      Uri.Builder().run {
        scheme("https")
        authority("www.google.com")
        appendPath("a")
        pairs.forEach { appendQueryParameter(it.first, it.second) }
        build()
      }

    /** Creates an OOB token with randomly filled bytes. */
    private fun createToken(): OutOfBandAssociationToken {
      val random = Random()
      val encryptionKey = ByteArray(OOB_DATA_LENGTH).apply { random.nextBytes(this) }
      val ihuIv = ByteArray(OOB_DATA_LENGTH).apply { random.nextBytes(this) }
      val mobileIv = ByteArray(OOB_DATA_LENGTH).apply { random.nextBytes(this) }

      return outOfBandAssociationToken {
        this.encryptionKey = ByteString.copyFrom(encryptionKey)
        this.ihuIv = ByteString.copyFrom(ihuIv)
        this.mobileIv = ByteString.copyFrom(mobileIv)
      }
    }
  }
}
