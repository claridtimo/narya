//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.dobj;

/**
 * A message event that only goes to the server. If generated on the server then it never leaves
 * the server.
 */
public class ServerMessageEvent extends MessageEvent
{
    /**
     * Constructs a new message event on the specified target object with the supplied name and
     * arguments.
     *
     * @param targetOid the object id of the object whose attribute has changed.
     * @param name the name of the message event.
     * @param args the arguments for this message. This array should contain only values of valid
     * distributed object types.
     */
    public ServerMessageEvent (int targetOid, String name, Object[] args)
    {
        super(targetOid, name, args);
    }

    @Override
    public boolean isPrivate ()
    {
        // this is what makes us server-only
        return true;
    }

    // AUTO-GENERATED: METHODS START
    // from interface Streamable
    public void readObject (com.threerings.io.ObjectInputStream ins)
        throws java.io.IOException, java.lang.ClassNotFoundException
    {
        com.threerings.io.GenStreamUtil.readField(com.threerings.presents.dobj.DEvent.class, "_toid", this, ins);
        com.threerings.io.GenStreamUtil.readField(com.threerings.presents.dobj.NamedEvent.class, "_name", this, ins);
        com.threerings.io.GenStreamUtil.readField(com.threerings.presents.dobj.MessageEvent.class, "_args", this, ins);
    }

    // from interface Streamable
    public void writeObject (com.threerings.io.ObjectOutputStream out)
        throws java.io.IOException
    {
        com.threerings.io.GenStreamUtil.writeField(com.threerings.presents.dobj.DEvent.class, "_toid", this, out);
        com.threerings.io.GenStreamUtil.writeField(com.threerings.presents.dobj.NamedEvent.class, "_name", this, out);
        com.threerings.io.GenStreamUtil.writeField(com.threerings.presents.dobj.MessageEvent.class, "_args", this, out);
    }
    // AUTO-GENERATED: METHODS END
}
