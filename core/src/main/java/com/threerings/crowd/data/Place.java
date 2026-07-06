//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.crowd.data;

import com.threerings.io.SimpleStreamableObject;

/**
 * Contains information on the current place occupied by a body.
 */
public class Place extends SimpleStreamableObject
{
    /** The oid of this place's {@link PlaceObject}. */
    public final int placeOid;

    /**
     * Creates a place with the supplied oid.
     */
    public Place (int placeOid)
    {
        this.placeOid = placeOid;
    }

    @Override // from Object
    public boolean equals (Object other)
    {
        if (other == null) {
            return false;
        }
        return getClass().equals(other.getClass()) ? placeOid == ((Place)other).placeOid : false;
    }

    @Override // from Object
    public int hashCode ()
    {
        return placeOid;
    }

    // from interface Streamable
    public void readObject (com.threerings.io.ObjectInputStream ins)
        throws java.io.IOException, java.lang.ClassNotFoundException
    {
        com.threerings.io.GenStreamUtil.readField(com.threerings.crowd.data.Place.class, "placeOid", this, ins);
    }

    // from interface Streamable
    public void writeObject (com.threerings.io.ObjectOutputStream out)
        throws java.io.IOException
    {
        com.threerings.io.GenStreamUtil.writeField(com.threerings.crowd.data.Place.class, "placeOid", this, out);
    }
}
