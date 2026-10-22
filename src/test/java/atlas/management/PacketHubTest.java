package atlas.management;

import atlas.event.EventManagerTest;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** PacketHub's rules (2026-10-18). */
public class PacketHubTest {

    @Before
    @After
    public void clear() {
        PacketHub.clearForTest();
    }

    private static PacketEvent send() {
        return new PacketEvent(EventType.SEND, new EventManagerTest.TestPacket());
    }

    @Test
    public void aFreePacketIsTakenAndCancelled() {
        PacketEvent event = send();
        assertTrue(PacketHub.take(event, "FakeLag"));
        assertTrue(event.isCancelled());
    }

    @Test
    public void aPacketSomeoneElseCancelledIsNeverTakenTwice() {
        PacketEvent event = send();
        assertTrue(PacketHub.take(event, "FakeLag"));
        assertFalse(PacketHub.take(event, "NoSlow"));
    }

    private static PacketEvent receive() {
        return new PacketEvent(EventType.RECEIVE, new EventManagerTest.TestPacket());
    }

    @Test
    public void anIncomingPacketTwoHoldersWantIsKeptByTheFirstOnly() {
        PacketHub.registerIncoming("Backtrack", () -> 0);
        PacketHub.registerIncoming("KnockbackDelay", () -> 0);
        PacketEvent event = receive();
        assertTrue(PacketHub.take(event, "KnockbackDelay"));
        assertFalse(PacketHub.take(event, "Backtrack"));
    }

    @Test
    public void incomingHoldersTakeTurnsAndNeverTakeOver() {
        PacketHub.registerIncoming("Backtrack", () -> 3);
        PacketHub.registerIncoming("KnockbackDelay", () -> 0);
        assertFalse(PacketHub.take(receive(), "KnockbackDelay"));
        assertEquals("Backtrack", PacketHub.holdingIncoming());
    }

    @Test
    public void theTwoLanesDoNotBlockEachOther() {
        PacketHub.registerIncoming("Backtrack", () -> 3);
        PacketHub.register("FakeLag", PacketHub.MOVEMENT, () -> 4, () -> { });
        assertTrue(PacketHub.take(receive(), "KnockbackDelayUnregistered"));
        PacketHub.registerIncoming("ServerLag", () -> 0);
        assertFalse(PacketHub.take(receive(), "ServerLag"));
        assertTrue(PacketHub.take(send(), "FakeLag"));
        assertEquals("FakeLag", PacketHub.holding());
    }

    @Test
    public void anEqualHolderWithPacketsKeepsTheQueue() {
        PacketHub.register("FakeLag", PacketHub.MOVEMENT, () -> 3, () -> { });
        PacketHub.register("NoSlow", PacketHub.MOVEMENT, () -> 0, () -> { });
        PacketEvent event = send();
        assertFalse(PacketHub.take(event, "NoSlow"));
        assertFalse(event.isCancelled());
        assertTrue(PacketHub.take(send(), "FakeLag"));
        assertEquals("FakeLag", PacketHub.holding());
    }

    @Test
    public void aHigherHolderMakesTheLowerLetGoFirst() {
        AtomicInteger held = new AtomicInteger(4);
        AtomicInteger letGo = new AtomicInteger();
        PacketHub.register("FakeLag", PacketHub.MOVEMENT, held::get, () -> {
            letGo.incrementAndGet();
            held.set(0);
        });
        PacketHub.register("Hit", PacketHub.COMBAT, () -> 0, () -> { });
        assertTrue(PacketHub.take(send(), "Hit"));
        assertEquals(1, letGo.get());
        assertEquals(null, PacketHub.holding());
    }

    @Test
    public void aHolderThatThrowsCountsAsHoldingNothing() {
        PacketHub.register("Broken", PacketHub.COMBAT, () -> {
            throw new IllegalStateException();
        }, () -> { });
        assertTrue(PacketHub.take(send(), "FakeLag"));
    }

    @Test
    public void aSideHolderNeverKeepsAMovementHolderFromHolding() {
        PacketHub.registerSide("PingSpoof", () -> 5);
        PacketHub.register("FakeLag", PacketHub.MOVEMENT, () -> 0, () -> { });
        assertTrue(PacketHub.take(send(), "FakeLag"));
        assertEquals(null, PacketHub.holding());
    }

    @Test
    public void aSideHolderStillNeverTakesWhatAnotherTook() {
        PacketHub.registerSide("PingSpoof", () -> 0);
        PacketEvent event = send();
        assertTrue(PacketHub.take(event, "FakeLag"));
        assertFalse(PacketHub.take(event, "PingSpoof"));
        PacketEvent free = send();
        PacketHub.register("FakeLag", PacketHub.MOVEMENT, () -> 2, () -> { });
        assertTrue(PacketHub.take(free, "PingSpoof"));
    }
}
