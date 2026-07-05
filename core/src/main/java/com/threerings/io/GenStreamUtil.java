//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.io;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import java.io.IOException;

/**
 * Runtime support for the explicit {@code readObject}/{@code writeObject} methods emitted by {@link
 * com.threerings.presents.tools.GenStreamableTask} in "flatten" mode.
 *
 * <p>The default reflective {@link Streamer} streams a class's fields in {@link
 * com.samskivert.util.ClassUtil#getFields} order, which is <em>superclass-first</em> but relies on
 * {@link Class#getDeclaredFields} <em>within</em> each class. That per-class order is not defined by
 * the JVM spec and differs between HotSpot (server/desktop) and ART (Android) &mdash; so a default
 * streamed object written by one and read by the other misaligns. The generated methods stream each
 * field explicitly, in an order captured from HotSpot at build time, by calling {@link #readField}/
 * {@link #writeField} here. Because those methods delegate to the very same {@link FieldMarshaller}
 * the reflective streamer uses, the produced bytes are identical to the legacy reflective form on
 * HotSpot (so existing persisted boards and DB blobs read unchanged, and desktop&harr;server is
 * unaffected) while being deterministic on ART.
 */
public final class GenStreamUtil
{
    /**
     * Reads the named field of {@code target} (declared by {@code declaringClass}) from the stream,
     * using the same {@link FieldMarshaller} the reflective streamer would use.
     */
    public static void readField (
        Class<?> declaringClass, String name, Object target, ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        Entry entry = entry(declaringClass, name);
        try {
            entry.marshaller.readField(entry.field, target, in);
        } catch (IOException ioe) {
            throw ioe;
        } catch (ClassNotFoundException cnfe) {
            throw cnfe;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IOException("Failure reading field [class=" + declaringClass.getName() +
                                  ", field=" + name + "]", e);
        }
    }

    /**
     * Writes the named field of {@code source} (declared by {@code declaringClass}) to the stream,
     * using the same {@link FieldMarshaller} the reflective streamer would use.
     */
    public static void writeField (
        Class<?> declaringClass, String name, Object source, ObjectOutputStream out)
        throws IOException
    {
        Entry entry = entry(declaringClass, name);
        try {
            entry.marshaller.writeField(entry.field, source, out);
        } catch (IOException ioe) {
            throw ioe;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IOException("Failure writing field [class=" + declaringClass.getName() +
                                  ", field=" + name + "]", e);
        }
    }

    protected static Entry entry (Class<?> declaringClass, String name)
    {
        String key = declaringClass.getName() + "#" + name;
        Entry entry = _cache.get(key);
        if (entry == null) {
            Field field;
            try {
                field = declaringClass.getDeclaredField(name);
            } catch (NoSuchFieldException nsfe) {
                throw new RuntimeException("Missing streamed field [class=" +
                                           declaringClass.getName() + ", field=" + name + "]", nsfe);
            }
            field.setAccessible(true);
            entry = new Entry(field, FieldMarshaller.getFieldMarshaller(field));
            _cache.put(key, entry);
        }
        return entry;
    }

    protected static final class Entry
    {
        public final Field field;
        public final FieldMarshaller marshaller;
        public Entry (Field field, FieldMarshaller marshaller) {
            this.field = field;
            this.marshaller = marshaller;
        }
    }

    /** Caches the resolved (accessible) field + its marshaller, keyed by declaring class + name. */
    protected static final ConcurrentHashMap<String, Entry> _cache = new ConcurrentHashMap<String, Entry>();

    private GenStreamUtil () {}
}
