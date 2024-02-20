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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UriElementsTest {
  @Test
  fun reservedParameterName_arbitraryName_success() {
    val uri = createUri("foo" to "bar")

    assertThat(decode(uri)).isNotNull()
  }

  @Test
  fun reservedParameterName_oob_returnsNull() {
    val uri = createUri("oobFoo" to "value")

    assertThat(decode(uri)).isNull()
  }

  @Test
  fun reservedParameterName_bat_returnsNull() {
    val uri = createUri("batBar" to "value")

    assertThat(decode(uri)).isNull()
  }

  @Test
  fun mismatchedPath_returnsNull() {
    val uri = createUri("batBar" to "value", path = "reconnect")

    assertThat(decode(uri)).isNull()
  }

  @Test
  fun customizedParameterName_success() {
    val customizeKey = "customizeKey"
    val customizeValue = "customizeValue"
    val uri = createUri(customizeKey to customizeValue)

    val elements = decode(uri)!!
    assertThat(elements.queries[customizeKey]).isEqualTo(customizeValue)
  }

  @Test
  fun noQueryParameter_noOobData() {
    val uri = createUri()

    val uriElements = decode(uri)!!

    assertThat(uriElements.oobData).isNull()
  }

  @Test
  fun noQueryParameter_noDeviceIdentifier() {
    val uri = createUri()

    val uriElements = decode(uri)!!

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

    val uriElements = decode(uri)!!

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

    val uriElements = decode(uri)!!

    assertThat(uriElements.deviceIdentifier).isEqualTo(identifier)
  }

  @Test
  fun parseIsSetupProfile_true() {
    val uri = createUri(UriElements.IS_STARTED_FOR_SETUP_PROFILE_KEY to "true")

    val uriElements = decode(uri)!!

    assertThat(uriElements.isSetupProfile).isTrue()
  }

  @Test
  fun parseIsSetupProfile_false() {
    val uri = createUri(UriElements.IS_STARTED_FOR_SETUP_PROFILE_KEY to "false")

    val uriElements = decode(uri)!!

    assertThat(uriElements.isSetupProfile).isFalse()
  }

  @Test
  fun parseIsSetupProfile_defaultToFalse() {
    val uri = createUri()

    val uriElements = decode(uri)!!

    assertThat(uriElements.isSetupProfile).isFalse()
  }

  @Test
  fun protoFieldsNotSet_classPropertyIsNull() {
    val encoded = Base64.encodeToString(outOfBandAssociationData {}.toByteArray(), Base64.URL_SAFE)
    val uri = createUri(UriElements.OOB_DATA_PARAMETER_KEY to encoded)

    val uriElements = decode(uri)!!

    assertThat(uriElements.oobData).isNull()
    assertThat(uriElements.deviceIdentifier).isNull()
  }

  companion object {
    private const val TEST_SCHEME = "test_scheme"
    private const val TEST_AUTHORITY = "test_authority"
    private const val TEST_PATH = "/test_path"

    // Length 5 is arbitrary.
    private const val OOB_DATA_LENGTH = 5
    /**
     * Creates a default URI with parameters specified by [pairs].
     *
     * @param pairs contains the key and value of a URI query parameter.
     */
    private fun createUri(
      vararg pairs: Pair<String, String>,
      scheme: String = TEST_SCHEME,
      authority: String = TEST_AUTHORITY,
      path: String = TEST_PATH,
    ): Uri =
      Uri.Builder().run {
        scheme(scheme)
        authority(authority)
        // Remove the leading / in path.
        appendPath(path.drop(1))
        pairs.forEach { appendQueryParameter(it.first, it.second) }
        build()
      }

    private fun decode(uri: Uri): UriElements? = UriElements.decodeFrom(uri)

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
