/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.status.ReadableCounter;
import io.aeron.test.TestMediaDriver;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CounterTest
{
    private static final int COUNTER_TYPE_ID = 1101;
    private static final String COUNTER_LABEL = "counter label";

    private final UnsafeBuffer labelBuffer = new UnsafeBuffer(new byte[COUNTER_LABEL.length()]);

    private Aeron clientA;
    private Aeron clientB;
    private TestMediaDriver driver;

    private final AvailableCounterHandler availableCounterHandlerClientA = mock(AvailableCounterHandler.class);
    private final UnavailableCounterHandler unavailableCounterHandlerClientA = mock(UnavailableCounterHandler.class);
    private AvailableCounterHandler availableCounterHandlerClientB = mock(AvailableCounterHandler.class);
    private UnavailableCounterHandler unavailableCounterHandlerClientB = mock(UnavailableCounterHandler.class);

    private volatile ReadableCounter readableCounter;

    private void launch()
    {
        labelBuffer.putStringWithoutLengthAscii(0, COUNTER_LABEL);

        driver = TestMediaDriver.launch(
            new MediaDriver.Context()
                .errorHandler(Throwable::printStackTrace)
                .threadingMode(ThreadingMode.SHARED));

        clientA = Aeron.connect(
            new Aeron.Context()
                .availableCounterHandler(availableCounterHandlerClientA)
                .unavailableCounterHandler(unavailableCounterHandlerClientA));

        clientB = Aeron.connect(
            new Aeron.Context()
                .availableCounterHandler(availableCounterHandlerClientB)
                .unavailableCounterHandler(unavailableCounterHandlerClientB));
    }

    @AfterEach
    public void after()
    {
        CloseHelper.closeAll(clientA, clientB, driver);

        driver.context().deleteDirectory();
    }

    @Test
    public void shouldBeAbleToAddCounter()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            launch();

            final Counter counter = clientA.addCounter(
                COUNTER_TYPE_ID,
                null,
                0,
                0,
                labelBuffer,
                0,
                COUNTER_LABEL.length());

            assertFalse(counter.isClosed());

            verify(availableCounterHandlerClientA, timeout(5000L))
                .onAvailableCounter(any(CountersReader.class), eq(counter.registrationId()), eq(counter.id()));
            verify(availableCounterHandlerClientB, timeout(5000L))
                .onAvailableCounter(any(CountersReader.class), eq(counter.registrationId()), eq(counter.id()));
        });
    }

    @Test
    public void shouldBeAbleToAddReadableCounterWithinHandler()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            availableCounterHandlerClientB = this::createReadableCounter;

            launch();

            final Counter counter = clientA.addCounter(
                COUNTER_TYPE_ID,
                null,
                0,
                0,
                labelBuffer,
                0,
                COUNTER_LABEL.length());

            while (null == readableCounter)
            {
                Tests.sleep(1);
            }

            assertEquals(CountersReader.RECORD_ALLOCATED, readableCounter.state());
            assertEquals(counter.id(), readableCounter.counterId());
            assertEquals(counter.registrationId(), readableCounter.registrationId());
        });
    }

    @Test
    public void shouldCloseReadableCounterOnUnavailableCounter()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            availableCounterHandlerClientB = this::createReadableCounter;
            unavailableCounterHandlerClientB = this::unavailableCounterHandler;

            launch();

            final Counter counter = clientA.addCounter(
                COUNTER_TYPE_ID,
                null,
                0,
                0,
                labelBuffer,
                0,
                COUNTER_LABEL.length());

            while (null == readableCounter)
            {
                Tests.sleep(1);
            }

            assertFalse(readableCounter.isClosed());
            assertEquals(CountersReader.RECORD_ALLOCATED, readableCounter.state());

            counter.close();

            while (!readableCounter.isClosed())
            {
                Tests.sleep(1);
            }
        });
    }

    private void createReadableCounter(
        final CountersReader countersReader, final long registrationId, final int counterId)
    {
        readableCounter = new ReadableCounter(countersReader, registrationId, counterId);
    }

    private void unavailableCounterHandler(
        @SuppressWarnings("unused") final CountersReader countersReader, final long registrationId, final int counterId)
    {
        assertEquals(readableCounter.registrationId(), registrationId);
        assertEquals(readableCounter.counterId(), counterId);

        readableCounter.close();
    }
}
