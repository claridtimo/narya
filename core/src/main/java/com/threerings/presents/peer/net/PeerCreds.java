//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.peer.net;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.samskivert.util.StringUtil;

import com.threerings.presents.net.ServiceCreds;

/**
 * Used by peer servers in a cluster installation to authenticate with one another.
 *
 * <p>Historically these credentials carried a <em>static</em> bearer token,
 * {@code md5hex(nodeName + sharedSecret)} (inherited from {@link ServiceCreds}). That value never
 * changed, so anyone who captured a single peer authentication request could replay it forever.
 *
 * <p>Each set of credentials now carries a fresh random {@link #nonce}, a {@link #timestamp} and an
 * {@link #hmac} (HMAC-SHA256 over {@code nodeName | nonce | timestamp}, keyed by the cluster's
 * shared secret). The verifier (see
 * {@link com.threerings.presents.peer.server.PeerManager#isAuthenticPeer}) recomputes the HMAC and
 * compares it in constant time ({@link MessageDigest#isEqual}), enforces a freshness window
 * ({@link #verify}) and rejects nonce reuse within that window (a small in-memory cache on the
 * verifying node). A captured token is therefore only usable inside a narrow time window and only
 * once.
 *
 * <p><b>Wire format:</b> this is a narya {@link com.threerings.io.Streamable}; {@link #nonce},
 * {@link #timestamp} and {@link #hmac} (plus the inherited {@code clientId}) are part of the peer
 * wire protocol. Changing them changes the protocol, so every peer in a deployment must run the
 * same narya version. The inherited {@code _authToken} field is left null (we no longer use the
 * static bearer token) and streams as a single null marker.
 */
public class PeerCreds extends ServiceCreds
{
    /** The HMAC algorithm used to sign the challenge with the shared secret. */
    public static final String HMAC_ALGORITHM = "HmacSHA256";

    /** The number of random bytes in a {@link #nonce}. */
    public static final int NONCE_LENGTH = 16;

    /** The default freshness window (plus or minus): five minutes, in milliseconds. */
    public static final long DEFAULT_SKEW_MILLIS = 5 * 60 * 1000L;

    /** A fresh random nonce, unique to this authentication attempt. */
    public byte[] nonce;

    /** The time (millis since the epoch) at which these credentials were created. */
    public long timestamp;

    /** HMAC-SHA256 over {@code nodeName | nonce | timestamp}, keyed by the shared secret. */
    public byte[] hmac;

    /**
     * Creates signed credentials for the specified node using the supplied shared secret.
     */
    public PeerCreds (String nodeName, String sharedSecret)
    {
        // NOTE: we deliberately bypass ServiceCreds' static md5hex bearer token; we set clientId
        // directly and authenticate via the nonce'd HMAC below. _authToken remains null.
        this.clientId = nodeName;
        this.nonce = makeNonce();
        this.timestamp = System.currentTimeMillis();
        this.hmac = computeHMAC(nodeName, this.nonce, this.timestamp, sharedSecret);
    }

    /**
     * Used when unserializing an instance from the network.
     */
    public PeerCreds ()
    {
    }

    /**
     * Verifies these credentials against the supplied shared secret, tolerating up to
     * {@code skewMillis} of clock skew between the creating and verifying nodes. This performs a
     * constant-time HMAC comparison and enforces the freshness window; it does <em>not</em> detect
     * nonce replay (that requires per-verifier state and is handled by
     * {@link com.threerings.presents.peer.server.PeerManager}).
     *
     * @return true only if the HMAC recomputes correctly and the timestamp is within
     * {@code skewMillis} of now.
     */
    public boolean verify (String sharedSecret, long skewMillis)
    {
        if (nonce == null || hmac == null || clientId == null) {
            return false;
        }
        // freshness: reject tokens that are too old or too far in the future
        long delta = System.currentTimeMillis() - timestamp;
        if (delta > skewMillis || delta < -skewMillis) {
            return false;
        }
        byte[] expected = computeHMAC(clientId, nonce, timestamp, sharedSecret);
        // constant-time comparison to avoid leaking the expected HMAC via timing
        return MessageDigest.isEqual(expected, hmac);
    }

    /**
     * Validates these credentials against the supplied shared secret using the
     * {@link #DEFAULT_SKEW_MILLIS default freshness window}.
     *
     * <p>Overrides {@link ServiceCreds#areValid} (which compared the removed static token) so that
     * any caller reaching the base API still gets the HMAC + freshness check and fails closed. The
     * canonical peer verification path,
     * {@link com.threerings.presents.peer.server.PeerManager#isAuthenticPeer}, calls
     * {@link #verify} directly and additionally enforces nonce replay protection.
     */
    @Override
    public boolean areValid (String sharedSecret)
    {
        return verify(sharedSecret, DEFAULT_SKEW_MILLIS);
    }

    @Override // from Object
    public String toString ()
    {
        return getClass().getSimpleName() + "[id=" + clientId + ", ts=" + timestamp +
            ", nonce=" + (nonce == null ? "null" : StringUtil.hexlate(nonce)) +
            ", hmac=" + (hmac == null ? "null" : StringUtil.hexlate(hmac)) + "]";
    }

    /**
     * Computes the HMAC-SHA256 of {@code nodeName | nonce | timestamp} keyed by the shared secret.
     * Each variable-length component is length-prefixed so that distinct inputs cannot produce the
     * same signed message.
     */
    protected static byte[] computeHMAC (
        String nodeName, byte[] nonce, long timestamp, String sharedSecret)
    {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(bytes(sharedSecret), HMAC_ALGORITHM));
            byte[] nameBytes = bytes(nodeName);
            mac.update(intToBytes(nameBytes.length));
            mac.update(nameBytes);
            mac.update(intToBytes(nonce.length));
            mac.update(nonce);
            mac.update(longToBytes(timestamp));
            return mac.doFinal();
        } catch (GeneralSecurityException gse) {
            // HmacSHA256 is a required JCA algorithm and the key is non-empty when peering is
            // properly configured, so this indicates a broken JVM/config, not a runtime condition.
            throw new IllegalStateException("Unable to compute peer auth HMAC", gse);
        }
    }

    /** Generates a fresh {@link #NONCE_LENGTH}-byte nonce from a {@link SecureRandom}. */
    protected static byte[] makeNonce ()
    {
        byte[] bytes = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    protected static byte[] bytes (String value)
    {
        return (value == null ? "" : value).getBytes(UTF8);
    }

    protected static byte[] intToBytes (int value)
    {
        return new byte[] {
            (byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte)value };
    }

    protected static byte[] longToBytes (long value)
    {
        byte[] bytes = new byte[8];
        for (int ii = 7; ii >= 0; ii--) {
            bytes[ii] = (byte)value;
            value >>>= 8;
        }
        return bytes;
    }

    /** Used to generate nonces. {@link SecureRandom} is thread-safe. */
    protected static final SecureRandom RANDOM = new SecureRandom();

    /** The charset used to turn strings into HMAC input bytes. */
    protected static final Charset UTF8 = Charset.forName("UTF-8");
}
