//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.peer.server;

import com.threerings.presents.peer.net.PeerCreds;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests the server-side pieces of peer authentication: the full
 * {@link PeerManager#isAuthenticPeer} check (HMAC verification plus replay rejection, and their
 * ordering), nonce replay rejection ({@link PeerNonceCache}) and the weak-secret guard
 * ({@link PeerManager#checkPeerSecret}).
 */
public class PeerAuthTest
{
    public static final String SECRET = "peer-test-shared-secret";
    public static final long SKEW = PeerCreds.DEFAULT_SKEW_MILLIS;

    @Test
    public void testIsAuthenticPeerAcceptsOnceThenRejectsReplay ()
    {
        PeerNonceCache cache = new PeerNonceCache(2 * SKEW);
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        long now = System.currentTimeMillis();
        assertTrue("valid creds accepted on first presentation",
                   PeerManager.isAuthenticPeer(creds, SECRET, SKEW, cache, now));
        assertFalse("byte-identical replay of the same creds rejected",
                    PeerManager.isAuthenticPeer(creds, SECRET, SKEW, cache, now + 10));
        assertTrue("a fresh set of creds from the same node is accepted",
                   PeerManager.isAuthenticPeer(new PeerCreds("nodeA", SECRET),
                                               SECRET, SKEW, cache, now + 20));
    }

    @Test
    public void testFailedVerifyDoesNotConsumeNonce ()
    {
        // the HMAC/freshness check must short-circuit BEFORE the nonce is recorded: an attacker
        // presenting a bogus request must not be able to pollute the replay cache and thereby
        // block the legitimate presentation of the same nonce
        PeerNonceCache cache = new PeerNonceCache(2 * SKEW);
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        long now = System.currentTimeMillis();
        creds.hmac[0] ^= 0x01; // tamper: verify() will fail
        assertFalse("tampered creds rejected",
                    PeerManager.isAuthenticPeer(creds, SECRET, SKEW, cache, now));
        assertEquals("failed attempt must not record the nonce", 0, cache.size());
        creds.hmac[0] ^= 0x01; // restore the genuine HMAC
        assertTrue("the same nonce still succeeds once the creds verify",
                   PeerManager.isAuthenticPeer(creds, SECRET, SKEW, cache, now + 10));
    }

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
