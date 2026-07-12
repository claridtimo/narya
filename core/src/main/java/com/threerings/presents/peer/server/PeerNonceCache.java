//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.peer.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.samskivert.util.StringUtil;

/**
 * A small in-memory cache used by {@link PeerManager} to reject reuse of a peer authentication
 * nonce within the freshness window. Combined with the HMAC + timestamp check in
 * {@link com.threerings.presents.peer.net.PeerCreds}, this means a captured peer authentication
 * request is usable at most once, and only briefly.
 *
 * <p>This is deliberately <em>per-verifier-process</em> state: each server only needs to detect
 * replays of tokens presented to <em>itself</em>, so no shared/distributed cache is required. A
 * token replayed against a different node would have to survive that node's own freshness window,
 * and the operator's threat model is a captured token being replayed against the node it was
 * originally sent to.
 *
 * <p>Entries are retained for strictly longer than twice the freshness window. A token stamped at
 * T is accepted by any verifier whose clock reads T +/- skew, so its maximum lifetime is 2*skew;
 * retaining a nonce for strictly more than that guarantees the replay record still exists at the
 * last instant the token would otherwise verify. Entries are pruned lazily on each insertion.
 * Access is synchronized because authentication may be processed off the connection manager's
 * threads.
 */
class PeerNonceCache
{
    /**
     * @param ttlMillis how long to remember a nonce; should strictly exceed twice the freshness
     * window used when verifying credentials, so a nonce cannot be pruned while its token still
     * verifies.
     */
    PeerNonceCache (long ttlMillis)
    {
        _ttlMillis = ttlMillis;
    }

    /**
     * Records the supplied nonce as having been seen at {@code now}.
     *
     * @return true if the nonce had not been seen within the window (a fresh, acceptable nonce);
     * false if it is a replay of a nonce still within the window.
     */
    synchronized boolean noteNonce (String clientId, byte[] nonce, long now)
    {
        prune(now);
        String key = clientId + ":" + StringUtil.hexlate(nonce);
        Long expiry = _seen.get(key);
        if (expiry != null && expiry > now) {
            return false; // replay within the window
        }
        _seen.put(key, now + _ttlMillis);
        return true;
    }

    /** Returns the number of live (unpruned) entries; used by tests. */
    synchronized int size ()
    {
        return _seen.size();
    }

    /** Drops entries whose retention window has elapsed. */
    protected void prune (long now)
    {
        for (Iterator<Map.Entry<String, Long>> it = _seen.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    protected final long _ttlMillis;
    protected final Map<String, Long> _seen = new HashMap<String, Long>();
}
