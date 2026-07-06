//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.crowd.chat.data;

import com.threerings.util.Name;

/**
 * A system message triggered by the activity of another user. If the user is muted we can suppress
 * this message, unlike a normal system message.
 */
public class UserSystemMessage extends SystemMessage
{
    /** The "speaker" of this message, the user that triggered that this message be sent to us. */
    public Name speaker;

    /**
     * Construct a INFO-level UserSystemMessage.
     */
    public static UserSystemMessage create (Name sender, String message, String bundle)
    {
        return new UserSystemMessage(sender, message, bundle, INFO);
    }

    /**
     * Construct a UserSystemMessage.
     */
    public UserSystemMessage (Name sender, String message, String bundle, byte attentionLevel)
    {
        super(message, bundle, attentionLevel);
        this.speaker = sender;
    }

    // from interface Streamable
    public void readObject (com.threerings.io.ObjectInputStream ins)
        throws java.io.IOException, java.lang.ClassNotFoundException
    {
        super.readObject(ins);
        com.threerings.io.GenStreamUtil.readField(com.threerings.crowd.chat.data.UserSystemMessage.class, "speaker", this, ins);
    }

    // from interface Streamable
    public void writeObject (com.threerings.io.ObjectOutputStream out)
        throws java.io.IOException
    {
        super.writeObject(out);
        com.threerings.io.GenStreamUtil.writeField(com.threerings.crowd.chat.data.UserSystemMessage.class, "speaker", this, out);
    }
}
