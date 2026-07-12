//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.peer.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.threerings.io.ObjectInputStream;
import com.threerings.io.ObjectOutputStream;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests the nonce'd-HMAC peer authentication token implemented by {@link PeerCreds}.
 */
public class PeerCredsTest
{
    public static final String SECRET = "peer-test-shared-secret";
    public static final long SKEW = PeerCreds.DEFAULT_SKEW_MILLIS;

    @Test
    public void testValidRoundtrip ()
    {
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        assertTrue("freshly created creds verify with the right secret",
                   creds.verify(SECRET, SKEW));
        assertTrue("areValid delegates to verify with the default window",
                   creds.areValid(SECRET));
        assertEquals("nodeA", creds.clientId);
        assertEquals(PeerCreds.NONCE_LENGTH, creds.nonce.length);
    }

    @Test
    public void testWireRoundtrip ()
        throws IOException, ClassNotFoundException
    {
        // stream the creds through narya's wire format and confirm they still verify; this proves
        // the (protocol-changing) nonce/timestamp/hmac fields survive serialization
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        PeerCreds decoded = (PeerCreds)unflatten(flatten(creds));
        assertEquals(creds.clientId, decoded.clientId);
        assertEquals(creds.timestamp, decoded.timestamp);
        assertArrayEquals(creds.nonce, decoded.nonce);
        assertArrayEquals(creds.hmac, decoded.hmac);
        assertTrue(decoded.verify(SECRET, SKEW));
    }

    @Test
    public void testWrongSecret ()
    {
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        assertFalse("creds must not verify against a different secret",
                    creds.verify("some-other-shared-secret", SKEW));
    }

    @Test
    public void testExpiredTimestamp ()
    {
        // a token whose HMAC is valid but whose timestamp is well in the past must be rejected
        long old = System.currentTimeMillis() - 10 * 60 * 1000L;
        PeerCreds creds = signedAt("nodeA", old);
        assertFalse("expired token rejected within default window", creds.verify(SECRET, SKEW));
        // and to prove the rejection is due to freshness (not a bad HMAC), a wide-enough window
        // accepts the very same token
        assertTrue("same token accepted with a wide window", creds.verify(SECRET, 30 * 60 * 1000L));
    }

    @Test
    public void testFutureTimestampBeyondSkew ()
    {
        long future = System.currentTimeMillis() + 10 * 60 * 1000L;
        PeerCreds creds = signedAt("nodeA", future);
        assertFalse("future token beyond skew rejected", creds.verify(SECRET, SKEW));
        assertTrue("same token accepted with a wide window", creds.verify(SECRET, 30 * 60 * 1000L));
    }

    @Test
    public void testTamperedHmacRejected ()
    {
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        creds.hmac[0] ^= 0x01; // flip a single bit
        assertFalse("tampered HMAC rejected", creds.verify(SECRET, SKEW));
    }

    @Test
    public void testTruncatedHmacRejectedWithoutError ()
    {
        // MessageDigest.isEqual handles unequal-length arrays gracefully (no exception)
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        byte[] shorter = new byte[creds.hmac.length - 1];
        System.arraycopy(creds.hmac, 0, shorter, 0, shorter.length);
        creds.hmac = shorter;
        assertFalse("truncated HMAC rejected", creds.verify(SECRET, SKEW));
    }

    @Test
    public void testTamperedNodeNameRejected ()
    {
        // recomputing the HMAC over a different node name (spoofing identity) must not verify
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        creds.clientId = "nodeB";
        assertFalse("HMAC is bound to the node name", creds.verify(SECRET, SKEW));
    }

    @Test
    public void testTamperedNonceRejected ()
    {
        PeerCreds creds = new PeerCreds("nodeA", SECRET);
        creds.nonce[0] ^= 0x01;
        assertFalse("HMAC is bound to the nonce", creds.verify(SECRET, SKEW));
    }

    @Test
    public void testNullFieldsRejected ()
    {
        PeerCreds creds = new PeerCreds();
        assertFalse("empty creds (null fields) never verify", creds.verify(SECRET, SKEW));
    }

    /**
     * Structural test: the verifier must compare HMACs with a constant-time primitive
     * ({@link java.security.MessageDigest#isEqual}) rather than a data-dependent comparison such as
     * {@code Arrays.equals}/{@code String.equals}, to avoid a timing oracle on the expected HMAC.
     */
    @Test
    public void testConstantTimeComparisonUsed ()
        throws IOException
    {
        String src = readPeerCredsSource();
        assertTrue("PeerCreds.verify must use MessageDigest.isEqual for the HMAC comparison",
                   src.contains("MessageDigest.isEqual(expected, hmac)"));
        assertFalse("PeerCreds must not compare the HMAC with the non-constant-time Arrays.equals",
                    src.contains("Arrays.equals"));
    }

    protected static PeerCreds signedAt (String nodeName, long timestamp)
    {
        PeerCreds creds = new PeerCreds(nodeName, SECRET);
        creds.timestamp = timestamp;
        creds.hmac = PeerCreds.computeHMAC(nodeName, creds.nonce, timestamp, SECRET);
        return creds;
    }

    protected static String readPeerCredsSource ()
        throws IOException
    {
        String rel = "src/main/java/com/threerings/presents/peer/net/PeerCreds.java";
        for (String base : new String[] { "", "core/" }) {
            File f = new File(base + rel);
            if (f.exists()) {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Could not locate PeerCreds.java source (cwd=" +
                              new File(".").getAbsolutePath() + ")");
    }

    protected static byte[] flatten (Object object)
        throws IOException
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream oout = new ObjectOutputStream(bout);
        oout.writeObject(object);
        return bout.toByteArray();
    }

    protected static Object unflatten (byte[] data)
        throws IOException, ClassNotFoundException
    {
        return new ObjectInputStream(new ByteArrayInputStream(data)).readObject();
    }
}
