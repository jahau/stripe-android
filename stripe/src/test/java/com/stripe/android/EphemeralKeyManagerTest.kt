package com.stripe.android

import com.nhaarman.mockitokotlin2.KArgumentCaptor
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.stripe.android.model.PaymentMethod
import com.stripe.android.testharness.TestEphemeralKeyProvider
import java.net.HttpURLConnection
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner

/**
 * Test class for [EphemeralKeyManager].
 */
@RunWith(RobolectricTestRunner::class)
class EphemeralKeyManagerTest {

    private val keyManagerListener: EphemeralKeyManager.KeyManagerListener = mock()

    private val operationIdFactory = OperationIdFactory.get()

    private val operationCaptor: KArgumentCaptor<EphemeralOperation> = argumentCaptor()
    private val ephemeralKeyArgumentCaptor: KArgumentCaptor<EphemeralKey> = argumentCaptor()
    private val testEphemeralKeyProvider: TestEphemeralKeyProvider = TestEphemeralKeyProvider()

    @Test
    fun shouldRefreshKey_whenKeyIsNullAndTimeIsInFuture_returnsTrue() {
        val futureTime = Calendar.getInstance().apply {
            add(Calendar.YEAR, 1)
        }.timeInMillis
        assertTrue(
            createEphemeralKeyManager(timeSupplier = { futureTime })
                .shouldRefreshKey(null)
        )
    }

    @Test
    fun shouldRefreshKey_whenKeyIsNullAndTimeIsInPast_returnsTrue() {
        val pastTime = Calendar.getInstance().apply {
            add(Calendar.YEAR, -1)
        }.timeInMillis
        assertTrue(
            createEphemeralKeyManager(timeSupplier = { pastTime })
                .shouldRefreshKey(null)
        )
    }

    @Test
    fun shouldRefreshKey_whenKeyExpiryIsAfterBufferFromPresent_returnsFalse() {
        val currentTime =
            TimeUnit.SECONDS.toMillis(DEFAULT_EXPIRES + 2 * TEST_SECONDS_BUFFER)
        val key = EphemeralKeyFixtures.FIRST.copy(expires = currentTime)

        assertFalse(
            createEphemeralKeyManager(timeSupplier = { currentTime })
                .shouldRefreshKey(key)
        )
    }

    @Test
    fun shouldRefreshKey_whenKeyExpiryIsInThePast_returnsTrue() {
        val currentTime = Calendar.getInstance().apply {
            timeInMillis -= 100L
        }.timeInMillis
        val key = EphemeralKeyFixtures.FIRST.copy(
            expires = TimeUnit.MILLISECONDS.toSeconds(currentTime)
        )
        assertTrue(
            createEphemeralKeyManager(timeSupplier = { currentTime })
                .shouldRefreshKey(key)
        )
    }

    @Test
    fun shouldRefreshKey_whenKeyExpiryIsInFutureButWithinBuffer_returnsTrue() {
        val parsedExpiryTimeInMillis = TimeUnit.SECONDS
            .toMillis(EphemeralKeyFixtures.FIRST.expires)
        val bufferTimeInMillis = TimeUnit.SECONDS.toMillis(TEST_SECONDS_BUFFER)

        val notFarEnoughInTheFuture = parsedExpiryTimeInMillis + bufferTimeInMillis / 2

        assertTrue(
            createEphemeralKeyManager(timeSupplier = { notFarEnoughInTheFuture })
                .shouldRefreshKey(EphemeralKeyFixtures.FIRST)
        )
    }

    @Test
    fun createKeyManager_updatesEphemeralKey_notifiesListener() {
        testEphemeralKeyProvider
            .setNextRawEphemeralKey(EphemeralKeyFixtures.FIRST_JSON)
        createEphemeralKeyManager(operationIdFactory)

        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener).onKeyUpdate(
            ephemeralKeyArgumentCaptor.capture(),
            any<EphemeralOperation.RetrieveKey>()
        )
        val ephemeralKey = ephemeralKeyArgumentCaptor.firstValue
        assertEquals(EphemeralKeyFixtures.FIRST.id, ephemeralKey.id)
    }

    @Test
    fun retrieveEphemeralKey_whenUpdateNecessary_returnsUpdateAndArguments() {
        testEphemeralKeyProvider
            .setNextRawEphemeralKey(EphemeralKeyFixtures.FIRST_JSON)

        val keyManager = createEphemeralKeyManager(operationIdFactory)

        val operation = EphemeralOperation.Customer.PaymentMethods(
            type = PaymentMethod.Type.Card,
            id = operationIdFactory.create()
        )
        keyManager.retrieveEphemeralKey(operation)

        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener, times(2))
            .onKeyUpdate(
                ephemeralKeyArgumentCaptor.capture(),
                operationCaptor.capture()
            )

        // first retrieve the key
        assertNotNull(ephemeralKeyArgumentCaptor.firstValue)
        assertTrue(operationCaptor.firstValue is EphemeralOperation.RetrieveKey)

        // then perform the operation
        assertNotNull(ephemeralKeyArgumentCaptor.secondValue)
        assertEquals(operation, operationCaptor.secondValue)
    }

    @Test
    fun updateKeyIfNecessary_whenReturnsError_setsExistingKeyToNull() {
        testEphemeralKeyProvider
            .setNextRawEphemeralKey(EphemeralKeyFixtures.FIRST_JSON)
        val keyManager = createEphemeralKeyManager(operationIdFactory) {
            TimeUnit.SECONDS.toMillis(EphemeralKeyFixtures.FIRST.expires) + 1L
        }

        // Make sure we're in a good state
        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener)
            .onKeyUpdate(
                ephemeralKeyArgumentCaptor.capture(),
                any<EphemeralOperation.RetrieveKey>()
            )
        assertNotNull(ephemeralKeyArgumentCaptor.firstValue)

        // Set up the error
        val errorMessage = "This is an error"
        testEphemeralKeyProvider.setNextError(404, errorMessage)

        // It should be necessary to update because the key is expired.
        val operationId = operationIdFactory.create()
        keyManager.retrieveEphemeralKey(EphemeralOperation.RetrieveKey(operationId))

        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener)
            .onKeyError(operationId, 404, errorMessage)
        verifyNoMoreInteractions(keyManagerListener)
    }

    @Test
    fun triggerCorrectErrorOnInvalidRawKey() {
        val operationId = "12345"
        val operationIdFactory: OperationIdFactory = mock()
        `when`(operationIdFactory.create()).thenReturn(operationId)

        testEphemeralKeyProvider.setNextRawEphemeralKey("Not_a_JSON")
        createEphemeralKeyManager(operationIdFactory)

        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener, never())
            .onKeyUpdate(
                any(),
                any<EphemeralOperation.RetrieveKey>()
            )
        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener)
            .onKeyError(operationId,
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "EphemeralKeyUpdateListener.onKeyUpdate was passed a value that " +
                    "could not be JSON parsed: [Value Not_a_JSON of type java.lang.String " +
                    "cannot be converted to JSONObject]. The raw body from Stripe's " +
                    "response should be passed.")
    }

    @Test
    fun triggerCorrectErrorOnInvalidJsonKey() {
        val operationId = "12345"
        val operationIdFactory: OperationIdFactory = mock()
        `when`(operationIdFactory.create()).thenReturn(operationId)

        testEphemeralKeyProvider.setNextRawEphemeralKey("{}")
        createEphemeralKeyManager(operationIdFactory)

        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener, never())
            .onKeyUpdate(
                any(),
                any<EphemeralOperation.RetrieveKey>()
            )
        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener)
            .onKeyError(operationId,
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "EphemeralKeyUpdateListener.onKeyUpdate was passed a value that " +
                    "could not be JSON parsed: [No value for created]. The raw body from " +
                    "Stripe's response should be passed."
            )
    }

    @Test
    fun triggerCorrectErrorOnEmptyKey() {
        val operationId = "12345"
        val operationIdFactory: OperationIdFactory = mock()
        `when`(operationIdFactory.create()).thenReturn(operationId)

        testEphemeralKeyProvider.setNextRawEphemeralKey("")
        createEphemeralKeyManager(operationIdFactory)

        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener, never())
            .onKeyUpdate(
                any(),
                any<EphemeralOperation.RetrieveKey>()
            )
        verify<EphemeralKeyManager.KeyManagerListener>(keyManagerListener)
            .onKeyError(
                operationId,
                HttpURLConnection.HTTP_INTERNAL_ERROR,
                "EphemeralKeyUpdateListener.onKeyUpdate was passed a value that " +
                    "could not be JSON parsed: [End of input at character 0 of ]. The raw body " +
                    "from Stripe's response should be passed."
            )
    }

    @Test
    fun init_whenShouldPrefetchEphemeralKeyIsFalse_shouldNotFetch() {
        val operationIdFactory: OperationIdFactory = mock()
        EphemeralKeyManager(
            testEphemeralKeyProvider,
            keyManagerListener,
            TEST_SECONDS_BUFFER,
            operationIdFactory,
            false
        )
        verify(operationIdFactory, never()).create()
    }

    private fun createEphemeralKeyManager(
        operationIdFactory: OperationIdFactory = OperationIdFactory.get(),
        timeSupplier: TimeSupplier = { Calendar.getInstance().timeInMillis }
    ): EphemeralKeyManager {
        return EphemeralKeyManager(
            testEphemeralKeyProvider,
            keyManagerListener,
            TEST_SECONDS_BUFFER,
            operationIdFactory,
            true,
            timeSupplier
        )
    }

    private companion object {
        private const val TEST_SECONDS_BUFFER = 10L
        private const val DEFAULT_EXPIRES = 1501199335L
    }
}
