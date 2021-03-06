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
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.test.TestMediaDriver;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static io.aeron.logbuffer.FrameDescriptor.END_FRAG_FLAG;
import static java.time.Duration.ofSeconds;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

public class FragmentedMessageTest
{
    private static List<String> channels()
    {
        return asList(
            CommonContext.IPC_CHANNEL,
            "aeron:udp?endpoint=localhost:24325",
            "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost");
    }

    private static final int STREAM_ID = 1001;
    private static final int FRAGMENT_COUNT_LIMIT = 10;

    private final FragmentHandler mockFragmentHandler = mock(FragmentHandler.class);

    private final TestMediaDriver driver = TestMediaDriver.launch(new MediaDriver.Context()
        .publicationTermBufferLength(LogBufferDescriptor.TERM_MIN_LENGTH)
        .errorHandler(Throwable::printStackTrace)
        .threadingMode(ThreadingMode.SHARED));

    private final Aeron aeron = Aeron.connect();

    @AfterEach
    public void after()
    {
        CloseHelper.closeAll(aeron, driver);
        driver.context().deleteDirectory();
    }

    @ParameterizedTest
    @MethodSource("channels")
    public void shouldReceivePublishedMessage(final String channel)
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final FragmentAssembler assembler = new FragmentAssembler(mockFragmentHandler);

            try (Subscription subscription = aeron.addSubscription(channel, STREAM_ID);
                Publication publication = aeron.addPublication(channel, STREAM_ID))
            {
                final UnsafeBuffer srcBuffer = new UnsafeBuffer(new byte[driver.context().mtuLength() * 4]);
                final int offset = 0;
                final int length = srcBuffer.capacity() / 4;

                for (int i = 0; i < 4; i++)
                {
                    srcBuffer.setMemory(i * length, length, (byte)(65 + i));
                }

                while (publication.offer(srcBuffer, offset, srcBuffer.capacity()) < 0L)
                {
                    Thread.yield();
                    Tests.checkInterruptStatus();
                }

                final int expectedFragmentsBecauseOfHeader = 5;
                int numFragments = 0;
                do
                {
                    final int fragments = subscription.poll(assembler, FRAGMENT_COUNT_LIMIT);
                    if (0 == fragments)
                    {
                        Thread.yield();
                        Tests.checkInterruptStatus();
                    }
                    numFragments += fragments;
                }
                while (numFragments < expectedFragmentsBecauseOfHeader);

                final ArgumentCaptor<DirectBuffer> bufferArg = ArgumentCaptor.forClass(DirectBuffer.class);
                final ArgumentCaptor<Header> headerArg = ArgumentCaptor.forClass(Header.class);

                verify(mockFragmentHandler, times(1)).onFragment(
                    bufferArg.capture(), eq(offset), eq(srcBuffer.capacity()), headerArg.capture());

                final DirectBuffer capturedBuffer = bufferArg.getValue();
                for (int i = 0; i < srcBuffer.capacity(); i++)
                {
                    assertEquals(srcBuffer.getByte(i), capturedBuffer.getByte(i), "same at i=" + i);
                }

                assertEquals(END_FRAG_FLAG, headerArg.getValue().flags());
            }
        });
    }
}
