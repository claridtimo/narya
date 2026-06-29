//
// $Id$
//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2012 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import static com.threerings.presents.Log.log;

/**
 * Builds the {@link SSLContext}s used to give the Presents NIO transport full-session TLS.
 *
 * <p>The server side loads a keystore holding its certificate + private key. The client side
 * loads a truststore holding the certificate(s) it is willing to trust (for a self-hosted /
 * community game with no public CA, this is the pinned server certificate). Both default to
 * TLS 1.3 only.
 *
 * <p>This is deliberately a thin wrapper over standard JSSE: the actual TLS is driven by an
 * {@code SSLEngine} (wrapped by the tls-channel library) in the connection manager and the
 * client communicator. Nothing here rolls its own crypto.
 */
public class TlsContextFactory
{
    /** The TLS protocol we negotiate. TLS 1.3 gives us forward secrecy and authenticated
     * encryption with none of the legacy-cipher / IV footguns. */
    public static final String PROTOCOL = "TLSv1.3";

    /**
     * Builds a server {@link SSLContext} from a keystore containing the server certificate and its
     * private key.
     *
     * @param keystorePath path to a PKCS12 (or JKS) keystore.
     * @param storePassword password protecting the keystore (and assumed to also protect the key
     * entry; standard for a single-entry server keystore).
     */
    public static SSLContext serverContext (String keystorePath, char[] storePassword)
        throws GeneralSecurityException, IOException
    {
        KeyStore ks = loadStore(keystorePath, storePassword);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, storePassword);

        SSLContext ctx = SSLContext.getInstance(PROTOCOL);
        ctx.init(kmf.getKeyManagers(), null, null);
        log.info("Initialized server TLS context", "protocol", PROTOCOL, "keystore", keystorePath);
        return ctx;
    }

    /**
     * Builds a client {@link SSLContext} that trusts exactly the certificate(s) in the supplied
     * truststore. For our self-signed deployment this is effectively certificate pinning: the
     * client trusts only the server cert we shipped it, not the public CA roots.
     *
     * @param truststorePath path to a PKCS12 (or JKS) truststore.
     * @param storePassword password protecting the truststore.
     */
    public static SSLContext clientContext (String truststorePath, char[] storePassword)
        throws GeneralSecurityException, IOException
    {
        KeyStore ts = loadStore(truststorePath, storePassword);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext ctx = SSLContext.getInstance(PROTOCOL);
        ctx.init(null, tmf.getTrustManagers(), null);
        log.info("Initialized client TLS context", "protocol", PROTOCOL, "truststore",
                 truststorePath);
        return ctx;
    }

    /**
     * Loads a keystore/truststore, inferring the type (PKCS12 vs JKS) from the file extension and
     * falling back to the platform default.
     */
    protected static KeyStore loadStore (String path, char[] password)
        throws GeneralSecurityException, IOException
    {
        Path p = Paths.get(path);
        if (!Files.isReadable(p)) {
            throw new IOException("TLS keystore not found or unreadable: " + path);
        }
        String type = path.endsWith(".jks") ? "JKS" :
            (path.endsWith(".p12") || path.endsWith(".pfx") || path.endsWith(".pkcs12")) ?
            "PKCS12" : KeyStore.getDefaultType();
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, password);
        }
        return ks;
    }
}
