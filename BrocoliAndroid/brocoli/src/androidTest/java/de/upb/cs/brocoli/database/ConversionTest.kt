package de.upb.cs.brocoli.database

import android.support.test.runner.AndroidJUnit4
import de.upb.cs.brocoli.database.util.fromBase64ToByteArray
import de.upb.cs.brocoli.database.util.toBase64String
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test is put into AndroidTests, because the underlying method uses the Base64 class, which
 * returns null on method calls if run outside an Android environment.
 */
@RunWith(AndroidJUnit4::class)
class ConversionTest {
    @Test
    fun testByteArrayToBase64() {
        val input = "this is a text that is gonna be converted"
        val inputBytes = input.toByteArray()
        val base64String = inputBytes.toBase64String()
        val outputBytes = base64String.fromBase64ToByteArray()
        val output = String(outputBytes)

        assertEquals("input == output", input, output)
    }
}