//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.peer.server;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests the server-side pieces of peer authentication: nonce replay rejection
 * ({@link PeerNonceCache}) and the weak-secret guard ({@link PeerManager#checkPeerSecret}).
 */
public class PeerAuthTest
{
    @Test
    public void testNonceReplayRejected ()
    {
        PeerNonceCache cache = new PeerNonceCache(1000L);
        byte[] nonce = new byte[] { 1, 2, 3, 4 };
        long now = 10000L;
        assertTrue("first sighting of a nonce is accepted", cache.noteNonce("nodeA", nonce, now));
        assertFalse("replay of the same nonce is rejected",
                    cache.noteNonce("nodeA", nonce, now + 10));
        assertTrue("a different nonce is accepted",
                   cache.noteNonce("nodeA", new byte[] { 9, 9 }, now + 10));
        assertTrue("the same nonce from a different node is independent",
                   cache.noteNonce("nodeB", nonce, now + 10));
    }

    @Test
    public void testNonceReusableAfterWindow ()
    {
        PeerNonceCache cache = new PeerNonceCache(1000L);
        byte[] nonce = new byte[] { 7, 7, 7 };
        assertTrue(cache.noteNonce("nodeA", nonce, 0L));
        assertFalse("still remembered inside the window", cache.noteNonce("nodeA", nonce, 500L));
        // once the retention window elapses the entry is pruned and the nonce can be seen again
        assertTrue("accepted again after the window", cache.noteNonce("nodeA", nonce, 2000L));
    }

    @Test
    public void testExpiredEntriesPruned ()
    {
        PeerNonceCache cache = new PeerNonceCache(1000L);
        cache.noteNonce("nodeA", new byte[] { 1 }, 0L);
        cache.noteNonce("nodeA", new byte[] { 2 }, 0L);
        assertEquals(2, cache.size());
        // inserting past the window prunes the two stale entries before recording the new one
        cache.noteNonce("nodeA", new byte[] { 3 }, 5000L);
        assertEquals(1, cache.size());
    }

    @Test
    public void testWeakSecretRejected ()
    {
        assertRejected(null);
        assertRejected("");
        assertRejected("short");
        assertRejected("fifteen-char-ke"); // 15 chars, one short of the minimum
    }

    @Test
    public void testStrongSecretAccepted ()
    {
        // exactly the minimum length and longer must be accepted (no exception)
        PeerManager.checkPeerSecret("sixteen-char-key");        // 16 chars
        PeerManager.checkPeerSecret("a-nice-long-shared-secret-value");
    }

    protected static void assertRejected (String secret)
    {
        try {
            PeerManager.checkPeerSecret(secret);
            fail("expected IllegalArgumentException for weak secret: " + secret);
        } catch (IllegalArgumentException iae) {
            // expected
        }
    }
}
