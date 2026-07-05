//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.net;

/**
 * Communicates failure to subscribe to an object.
 */
public class FailureResponse extends DownstreamMessage
{
    /**
     * Zero argument constructor used when unserializing an instance.
     */
    public FailureResponse ()
    {
        super();
    }

    /**
     * Constructs a failure response in response to a request for the specified oid.
     */
    public FailureResponse (int oid, String message)
    {
        _oid = oid;
        _message = message;
    }

    public int getOid ()
    {
        return _oid;
    }

    public String getMessage ()
    {
        return _message;
    }

    @Override
    public String toString ()
    {
        return "[type=FAIL, msgid=" + messageId + ", oid=" + _oid + ", msg=" + _message + "]";
    }

    protected int _oid;
    protected String _message;

    // AUTO-GENERATED: METHODS START
    // from interface Streamable
    public void readObject (com.threerings.io.ObjectInputStream ins)
        throws java.io.IOException, java.lang.ClassNotFoundException
    {
        com.threerings.io.GenStreamUtil.readField(com.threerings.presents.net.DownstreamMessage.class, "messageId", this, ins);
        com.threerings.io.GenStreamUtil.readField(com.threerings.presents.net.FailureResponse.class, "_oid", this, ins);
        com.threerings.io.GenStreamUtil.readField(com.threerings.presents.net.FailureResponse.class, "_message", this, ins);
    }

    // from interface Streamable
    public void writeObject (com.threerings.io.ObjectOutputStream out)
        throws java.io.IOException
    {
        com.threerings.io.GenStreamUtil.writeField(com.threerings.presents.net.DownstreamMessage.class, "messageId", this, out);
        com.threerings.io.GenStreamUtil.writeField(com.threerings.presents.net.FailureResponse.class, "_oid", this, out);
        com.threerings.io.GenStreamUtil.writeField(com.threerings.presents.net.FailureResponse.class, "_message", this, out);
    }
    // AUTO-GENERATED: METHODS END
}
