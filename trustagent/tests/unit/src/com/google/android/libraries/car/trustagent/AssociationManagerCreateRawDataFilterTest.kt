package com.google.android.libraries.car.trustagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [AssociationManager.createRawDataFilter].
 *
 * ktfmt splits list items into single lined, so keep these tests in a separate file to avoid
 * constant updates.
 */
@RunWith(AndroidJUnit4::class)
class AssociationManagerCreateRawDataFilterTest {

  /** Raw data is generated based on analysis in b/187241458. */
  @Test
  fun testCreateRawDataFilter_associationUuid() {
    val associationUuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    // We cannot directly use byteArrayOf() here because 0xFF is an Int and kotlin doesn't
    // automatically convert Int to Byte.
    val expected =
      intArrayOf(
          // First element: 2 bytes of size and type, then 1 byte of flag.
          0x00, 0x00,
          0x00,
          // Second Element: 2 bytes of size and type, then 16 bytes of UUID.
          0x00, 0x00,
          0xef, 0xcd, 0xab, 0x89, 0x67, 0x45, 0x23, 0x01, 0xef, 0xcd, 0xab, 0x89, 0x67, 0x45, 0x23,
          0x01,
          // Third element: 2 bytes of size and type, then 2 bytes of short UUID 2000, then the
          // null advertised data.
          0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        .map { it.toByte() }
        .toByteArray()

    val actual = AssociationManager.createRawDataFilter(associationUuid, advertisedData = null)

    assertThat(actual).isEqualTo(expected)
  }

  /** Raw data is generated based on analysis in b/187241458. */
  @Test
  fun testCreateRawDataFilter_associationUuidAndAdvertisedData() {
    val associationUuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    val advertisedData = intArrayOf(0x12, 0x34).map { it.toByte() }.toByteArray()
    // We cannot directly use byteArrayOf() here because 0xFF is an Int and kotlin doesn't
    // automatically convert Int to Byte.
    val expected =
      intArrayOf(
          // First element: 2 bytes of size and type, then 1 byte of flag.
          0x00, 0x00,
          0x00,
          // Second Element: 2 bytes of size and type, then 16 bytes of UUID.
          0x00, 0x00,
          0xef, 0xcd, 0xab, 0x89, 0x67, 0x45, 0x23, 0x01, 0xef, 0xcd, 0xab, 0x89, 0x67, 0x45, 0x23,
          0x01,
          // Third element: 2 bytes of size and type, then 2 bytes of short UUID 2000, then the
          // advertised data.
          0x00, 0x00,
          0x00, 0x00, 0x12, 0x34, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        .map { it.toByte() }
        .toByteArray()

    val actual = AssociationManager.createRawDataFilter(associationUuid, advertisedData)

    assertThat(actual).isEqualTo(expected)
  }
}
