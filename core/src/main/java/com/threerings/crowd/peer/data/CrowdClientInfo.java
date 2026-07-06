//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.crowd.peer.data;

import com.threerings.util.Name;

import com.threerings.presents.peer.data.ClientInfo;

/**
 * Extends the standard {@link ClientInfo} with Crowd bits.
 */
public class CrowdClientInfo extends ClientInfo
{
    /** The client's visible name, which is used for chatting. */
    public Name visibleName;

    // from interface Streamable
    public void readObject (com.threerings.io.ObjectInputStream ins)
        throws java.io.IOException, java.lang.ClassNotFoundException
    {
        com.threerings.io.GenStreamUtil.readField(com.threerings.presents.peer.data.ClientInfo.class, "username", this, ins);
        com.threerings.io.GenStreamUtil.readField(com.threerings.crowd.peer.data.CrowdClientInfo.class, "visibleName", this, ins);
    }

    // from interface Streamable
    public void writeObject (com.threerings.io.ObjectOutputStream out)
        throws java.io.IOException
    {
        com.threerings.io.GenStreamUtil.writeField(com.threerings.presents.peer.data.ClientInfo.class, "username", this, out);
        com.threerings.io.GenStreamUtil.writeField(com.threerings.crowd.peer.data.CrowdClientInfo.class, "visibleName", this, out);
    }
}
