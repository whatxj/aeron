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
package io.aeron.agent;

import io.aeron.cluster.Election;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;

import static io.aeron.agent.ClusterEventEncoder.*;
import static io.aeron.agent.CommonEventEncoder.*;
import static io.aeron.agent.EventConfiguration.MAX_EVENT_LENGTH;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.agrona.BitUtil.*;
import static org.agrona.BufferUtil.allocateDirectAligned;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClusterEventEncoderTest
{
    private final UnsafeBuffer buffer = new UnsafeBuffer(allocateDirectAligned(MAX_EVENT_LENGTH, CACHE_LINE_LENGTH));

    @Test
    void testEncodeStateChange()
    {
        final int offset = 24;
        final Election.State from = Election.State.CANDIDATE_BALLOT;
        final Election.State to = Election.State.CANVASS;
        final int memberId = 42;
        final String payload = from.name() + SEPARATOR + to.name();
        final int length = payload.length() + SIZE_OF_INT * 2;
        final int captureLength = captureLength(length);

        final int encodedLength = encodeStateChange(buffer, offset, captureLength, length, from, to, memberId);

        assertEquals(encodedLength(stateChangeLength(from, to)), encodedLength);
        assertEquals(captureLength, buffer.getInt(offset, LITTLE_ENDIAN));
        assertEquals(length, buffer.getInt(offset + SIZE_OF_INT, LITTLE_ENDIAN));
        assertNotEquals(0, buffer.getLong(offset + SIZE_OF_INT * 2, LITTLE_ENDIAN));
        assertEquals(memberId, buffer.getInt(offset + LOG_HEADER_LENGTH));
        assertEquals(payload, buffer.getStringAscii(offset + LOG_HEADER_LENGTH + SIZE_OF_INT));
    }

    @Test
    void testEncodeNewLeadershipTerm()
    {
        final int offset = 200;
        final int captureLength = 18;
        final int length = 54;
        final int logLeadershipTermId = 111;
        final int leadershipTermId = 222;
        final int logPosition = 1024;
        final int timestamp = 32423436;
        final int leaderMemberId = 42;
        final int logSessionId = 18;

        final int encodedLength = encodeNewLeadershipTerm(
            buffer,
            offset,
            captureLength,
            length,
            logLeadershipTermId,
            leadershipTermId,
            logPosition,
            timestamp,
            leaderMemberId,
            logSessionId);

        assertEquals(encodedLength(newLeaderShipTermLength()), encodedLength);
        int relativeOffset = 0;
        assertEquals(captureLength, buffer.getInt(offset + relativeOffset, LITTLE_ENDIAN));
        relativeOffset += SIZE_OF_INT;
        assertEquals(length, buffer.getInt(offset + relativeOffset, LITTLE_ENDIAN));
        relativeOffset += SIZE_OF_INT;
        assertNotEquals(0, buffer.getLong(offset + relativeOffset, LITTLE_ENDIAN));
        relativeOffset += SIZE_OF_LONG;
        assertEquals(logLeadershipTermId, buffer.getLong(offset + relativeOffset, LITTLE_ENDIAN));
        relativeOffset += SIZE_OF_LONG;
        assertEquals(leadershipTermId, buffer.getLong(offset + relativeOffset, LITTLE_ENDIAN));
        relativeOffset += SIZE_OF_LONG;
        assertEquals(logPosition, buffer.getLong(offset + relativeOffset, LITTLE_ENDIAN));
        relativeOffset += SIZE_OF_LONG;
        assertEquals(timestamp, buffer.getLong(offset + relativeOffset, LITTLE_ENDIAN));
        relativeOffset += SIZE_OF_LONG;
        assertEquals(leaderMemberId, buffer.getInt(offset + relativeOffset, LITTLE_ENDIAN));
        relativeOffset += SIZE_OF_INT;
        assertEquals(logSessionId, buffer.getInt(offset + relativeOffset, LITTLE_ENDIAN));
    }

    @Test
    void testNewLeaderShipTermLength()
    {
        assertEquals(SIZE_OF_LONG * 4 + SIZE_OF_INT * 2, newLeaderShipTermLength());
    }

    @Test
    void testStateChangeLength()
    {
        final ChronoUnit from = ChronoUnit.CENTURIES;
        final ChronoUnit to = ChronoUnit.HALF_DAYS;
        final String payload = from.name() + SEPARATOR + to.name();
        assertEquals(payload.length() + SIZE_OF_INT * 2, stateChangeLength(from, to));
    }
}
