package io.aeron;

import io.aeron.driver.FlowControlSupplier;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import io.aeron.driver.status.SenderLimit;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.status.HeartbeatTimestamp;
import io.aeron.test.MediaDriverTestWatcher;
import io.aeron.test.TestMediaDriver;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.SystemUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.aeron.FlowControlTests.waitForConnectionAndStatusMessages;
import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MinFlowControlSystemTest
{
    private static final String MULTICAST_URI = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost";
    private static final int STREAM_ID = 1001;

    private static final int TERM_BUFFER_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int NUM_MESSAGES_PER_TERM = 64;
    private static final int MESSAGE_LENGTH =
        (TERM_BUFFER_LENGTH / NUM_MESSAGES_PER_TERM) - DataHeaderFlyweight.HEADER_LENGTH;
    private static final String ROOT_DIR =
        SystemUtil.tmpDirName() + "aeron-system-tests-" + UUID.randomUUID().toString() + File.separator;

    private final MediaDriver.Context driverAContext = new MediaDriver.Context();
    private final MediaDriver.Context driverBContext = new MediaDriver.Context();

    private Aeron clientA;
    private Aeron clientB;
    private TestMediaDriver driverA;
    private TestMediaDriver driverB;
    private Publication publication;
    private Subscription subscriptionA;
    private Subscription subscriptionB;

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
    private final FragmentHandler fragmentHandlerA = mock(FragmentHandler.class);
    private final FragmentHandler fragmentHandlerB = mock(FragmentHandler.class);

    @RegisterExtension
    public MediaDriverTestWatcher testWatcher = new MediaDriverTestWatcher();

    private void launch()
    {
        buffer.putInt(0, 1);

        final String baseDirA = ROOT_DIR + "A";
        final String baseDirB = ROOT_DIR + "B";

        driverAContext.publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirA)
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
            .errorHandler(Throwable::printStackTrace)
            .threadingMode(ThreadingMode.SHARED);

        driverBContext.publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirB)
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
            .errorHandler(Throwable::printStackTrace)
            .threadingMode(ThreadingMode.SHARED);

        driverA = TestMediaDriver.launch(driverAContext, testWatcher);
        driverB = TestMediaDriver.launch(driverBContext, testWatcher);
        clientA = Aeron.connect(
            new Aeron.Context()
                .errorHandler(Throwable::printStackTrace)
                .aeronDirectoryName(driverAContext.aeronDirectoryName()));

        clientB = Aeron.connect(
            new Aeron.Context()
                .errorHandler(Throwable::printStackTrace)
                .aeronDirectoryName(driverBContext.aeronDirectoryName()));
    }

    @AfterEach
    public void after()
    {
        CloseHelper.quietCloseAll(clientB, clientA, driverB, driverA);
        IoUtil.delete(new File(ROOT_DIR), true);
    }

    private static Stream<Arguments> strategyConfigurations()
    {
        return Stream.of(
            Arguments.of(new MinMulticastFlowControlSupplier(), ""),
            Arguments.of(null, "|fc=min"),
            Arguments.of(null, "|fc=min,g:/1"));
    }

    @ParameterizedTest
    @MethodSource("strategyConfigurations")
    public void shouldSlowToMinMulticastFlowControlStrategy(
        final FlowControlSupplier flowControlSupplier,
        final String publisherUriParams)
    {
        assertTimeoutPreemptively(ofSeconds(20), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
            int numMessagesLeftToSend = numMessagesToSend;
            int numFragmentsFromB = 0;

            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));
            if (null != flowControlSupplier)
            {
                driverAContext.multicastFlowControlSupplier(flowControlSupplier);
            }
            driverAContext.flowControlReceiverGroupMinSize(1);

            launch();

            subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
            subscriptionB = clientB.addSubscription(MULTICAST_URI, STREAM_ID);
            publication = clientA.addPublication(MULTICAST_URI + publisherUriParams, STREAM_ID);

            while (!subscriptionA.isConnected() || !subscriptionB.isConnected() || !publication.isConnected())
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            for (long i = 0; numFragmentsFromB < numMessagesToSend; i++)
            {
                if (numMessagesLeftToSend > 0)
                {
                    final long result = publication.offer(buffer, 0, buffer.capacity());
                    if (result >= 0L)
                    {
                        numMessagesLeftToSend--;
                    }
                    else if (Publication.NOT_CONNECTED == result)
                    {
                        fail("Publication not connected, numMessagesLeftToSend=" + numMessagesLeftToSend);
                    }
                }

                Thread.yield();
                Tests.checkInterruptStatus();

                // A keeps up
                subscriptionA.poll(fragmentHandlerA, 10);

                // B receives slowly
                if ((i % 2) == 0)
                {
                    final int bFragments = subscriptionB.poll(fragmentHandlerB, 1);
                    if (0 == bFragments && !subscriptionB.isConnected())
                    {
                        if (subscriptionB.isClosed())
                        {
                            fail("Subscription B is closed, numFragmentsFromB=" + numFragmentsFromB);
                        }

                        fail("Subscription B not connected, numFragmentsFromB=" + numFragmentsFromB);
                    }

                    numFragmentsFromB += bFragments;
                }
            }

            verify(fragmentHandlerB, times(numMessagesToSend)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));
        });
    }

    @Test
    public void shouldRemoveDeadTaggedReceiverWithMinMulticastFlowControlStrategy()
    {
        assertTimeoutPreemptively(ofSeconds(200), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
            int numMessagesLeftToSend = numMessagesToSend;
            int numFragmentsReadFromA = 0, numFragmentsReadFromB = 0;
            boolean isBClosed = false;

            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));
            driverAContext.multicastFlowControlSupplier(new MinMulticastFlowControlSupplier());

            launch();

            subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
            subscriptionB = clientB.addSubscription(MULTICAST_URI, STREAM_ID);
            publication = clientA.addPublication(MULTICAST_URI, STREAM_ID);

            while (!subscriptionA.isConnected() || !subscriptionB.isConnected() || !publication.isConnected())
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            while (numFragmentsReadFromA < numMessagesToSend)
            {
                if (numMessagesLeftToSend > 0)
                {
                    if (publication.offer(buffer, 0, buffer.capacity()) >= 0L)
                    {
                        numMessagesLeftToSend--;
                    }
                }

                // A keeps up
                numFragmentsReadFromA += subscriptionA.poll(fragmentHandlerA, 10);

                // B receives up to 1/8 of the messages, then stops
                if (numFragmentsReadFromB < (numMessagesToSend / 8))
                {
                    numFragmentsReadFromB += subscriptionB.poll(fragmentHandlerB, 10);
                }
                else if (!isBClosed)
                {
                    subscriptionB.close();
                    isBClosed = true;
                }
            }

            verify(fragmentHandlerA, times(numMessagesToSend)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));
        });
    }

    @SuppressWarnings("methodlength")
    @Test
    void shouldPreventConnectionUntilGroupMinSizeIsMet()
    {
        final Integer groupSize = 3;

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder()
            .media("udp")
            .endpoint("224.20.30.39:24326")
            .networkInterface("localhost");

        final String uriPlain = builder
            .flowControl((String)null)
            .build();

        final String uriWithMinFlowControl = builder
            .receiverTag((Long)null)
            .minFlowControl(groupSize, null)
            .build();

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
        {
            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));

            launch();

            final CountersReader countersReader = clientA.countersReader();

            TestMediaDriver driverC = null;
            Aeron clientC = null;

            Publication publication = null;
            Subscription subscription0 = null;
            Subscription subscription1 = null;
            Subscription subscription2 = null;

            try
            {
                driverC = TestMediaDriver.launch(
                    new MediaDriver.Context().publicationTermBufferLength(TERM_BUFFER_LENGTH)
                        .aeronDirectoryName(ROOT_DIR + "C")
                        .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
                        .errorHandler(Throwable::printStackTrace)
                        .threadingMode(ThreadingMode.SHARED),
                    testWatcher);

                clientC = Aeron.connect(
                    new Aeron.Context()
                        .errorHandler(Throwable::printStackTrace)
                        .aeronDirectoryName(driverC.aeronDirectoryName()));

                publication = clientA.addPublication(uriWithMinFlowControl, STREAM_ID);
                subscription0 = clientA.addSubscription(uriPlain, STREAM_ID);
                subscription1 = clientB.addSubscription(uriPlain, STREAM_ID);

                waitForConnectionAndStatusMessages(countersReader, subscription0, subscription1);

                assertFalse(publication.isConnected());

                subscription2 = clientC.addSubscription(uriPlain, STREAM_ID);

                // Should now have 3 receivers and publication should eventually be connected.
                while (!publication.isConnected())
                {
                    Tests.sleep(1);
                }

                subscription2.close();
                subscription2 = null;

                // Lost a receiver and publication should eventually be disconnected.
                while (publication.isConnected())
                {
                    Tests.sleep(1);
                }

                subscription2 = clientC.addSubscription(uriPlain, STREAM_ID);

                while (!publication.isConnected())
                {
                    Tests.sleep(1);
                }
            }
            finally
            {
                CloseHelper.closeAll(
                    publication,
                    subscription0, subscription1, subscription2,
                    clientC,
                    driverC
                );
            }
        });
    }

    @Test
    void shouldPreventConnectionUntilAtLeastOneSubscriberConnectedWithRequiredGroupSizeZero()
    {
        final Integer groupSize = 0;

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder()
            .media("udp")
            .endpoint("224.20.30.41:24326")
            .networkInterface("localhost");

        final String plainUri = builder
            .flowControl((String)null)
            .build();

        final String uriWithMinFlowControl = builder
            .receiverTag((Long)null)
            .minFlowControl(groupSize, null)
            .build();

        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
        {
            launch();

            publication = clientA.addPublication(uriWithMinFlowControl, STREAM_ID);
            final Publication otherPublication = clientA.addPublication(plainUri, STREAM_ID + 1);

            clientA.addSubscription(plainUri, STREAM_ID + 1);

            while (!otherPublication.isConnected())
            {
                Tests.sleep(1);
            }
            // We know another publication on the same channel is connected

            assertFalse(publication.isConnected());

            subscriptionA = clientA.addSubscription(plainUri, STREAM_ID);

            while (!publication.isConnected())
            {
                Tests.sleep(1);
            }
        });
    }

    @Test
    public void shouldHandleSenderLimitCorrectlyWithMinGroupSize()
    {
        final String publisherUri = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost|fc=tagged,g:123/1";
        final String groupSubscriberUri = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost|rtag=123";
        final String subscriberUri = "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost";

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));

            launch();

            publication = clientA.addPublication(publisherUri, STREAM_ID);

            final CountersReader countersReader = clientA.countersReader();

            final int senderLimitCounterId = HeartbeatTimestamp.findCounterIdByRegistrationId(
                countersReader, SenderLimit.SENDER_LIMIT_TYPE_ID, publication.registrationId);
            final long currentSenderLimit = countersReader.getCounterValue(senderLimitCounterId);

            subscriptionA = clientA.addSubscription(subscriberUri, STREAM_ID);

            waitForConnectionAndStatusMessages(countersReader, subscriptionA);

            assertEquals(currentSenderLimit, countersReader.getCounterValue(senderLimitCounterId));

            subscriptionB = clientB.addSubscription(groupSubscriberUri, STREAM_ID);

            while (currentSenderLimit == countersReader.getCounterValue(senderLimitCounterId))
            {
                Tests.sleep(1);
            }
        });
    }
}
