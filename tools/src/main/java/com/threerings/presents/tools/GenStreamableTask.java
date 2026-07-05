//
// Narya library - tools for developing networked games
// Copyright (C) 2002-2025 Three Rings Design, Inc., All Rights Reserved
// https://github.com/threerings/narya/blob/master/LICENSE

package com.threerings.presents.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;

import com.google.common.collect.Lists;

import com.samskivert.util.ClassUtil;

import com.threerings.io.NotStreamable;
import com.threerings.io.Streamable;

import com.threerings.presents.dobj.DObject;

/**
 * Generates <code>readObject()</code> and <code>writeObject()</code> methods for {@link
 * Streamable} classes that have protected or private members so that they can be used in a
 * sandboxed environment.
 */
public class GenStreamableTask extends GenTask
{
    /**
     * Adds a nested &lt;fileset&gt; element which enumerates streamable source
     * files.
     */
    @Override
    public void addFileset(FileSet set) {
        _filesets.add(set);
    }

    /**
     * If set, generate <em>self-contained</em> declaration-ordered {@code read/writeObject} methods
     * that stream the entire flattened field set (in {@link ClassUtil#getFields} order, captured on
     * HotSpot at build time) via {@link com.threerings.io.GenStreamUtil}, rather than the legacy
     * per-class {@code super.read/writeObject(...)} + local-fields form. In this mode {@link DObject}
     * subclasses are processed too, and a pass-through {@code readObject}/{@code writeObject} (one
     * that calls {@code defaultReadObject}/{@code defaultWriteObject}) has that call replaced in
     * place with the explicit field streaming (preserving any surrounding logic). This is what makes
     * Streamables deterministic on ART (Android) while staying byte-identical on HotSpot. See {@link
     * com.threerings.io.GenStreamUtil}.
     */
    public void setFlatten (boolean flatten) {
        _flatten = flatten;
    }

    @Override
    public void execute ()
    {
        for (FileSet fs : _filesets) {
            DirectoryScanner ds = fs.getDirectoryScanner(getProject());
            File fromDir = fs.getDir(getProject());
            String[] srcFiles = ds.getIncludedFiles();
            for (String srcFile : srcFiles) {
                processClass(new File(fromDir, srcFile));
            }
        }
    }

    /**
     * Processes a {@link Streamable} source file.
     */
    protected void processClass (File source)
    {
        // load up the file and determine it's package and classname
        String name = null;
        try {
            name = GenUtil.readClassName(source);
        } catch (Exception e) {
            System.err.println("Failed to parse " + source + ": " + e.getMessage());
            return;
        }

        System.err.println("Considering " + name + "...");

        try {
            // in order for annotations to work, this task and all the classes it uses must be
            // loaded from the same class loader as the classes on which we are going to
            // introspect; this is non-ideal but unavoidable
            processClass(source, getClass().getClassLoader().loadClass(name));
        } catch (ClassNotFoundException cnfe) {
            System.err.println("Failed to load " + name + ".\nMissing class: " + cnfe.getMessage());
            System.err.println("Be sure to set the 'classpathref' attribute to a classpath\n" +
                               "that contains your projects invocation service classes.");
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    /**
     * Processes a resolved {@link Streamable} class instance.
     */
    @Override
    protected void processClass (File source, Class<?> sclass)
        throws IOException
    {
        if (_flatten) {
            processFlatten(source, sclass);
            return;
        }

        StreamableClassRequirements reqs = new StreamableClassRequirements(sclass);
        // we must implement Streamable, not be a DObject and have some fields that need to be
        // streamed
        if (!Streamable.class.isAssignableFrom(sclass) || DObject.class.isAssignableFrom(sclass) ||
                reqs.streamedFields.isEmpty()) {
            // System.err.println("Skipping " + sclass.getName() + "...");
            return;
        }

        // add readObject() and writeObject() definitions
        StringBuilder readbuf = new StringBuilder(READ_OPEN);
        StringBuilder writebuf = new StringBuilder(WRITE_OPEN);

        if (reqs.superclassStreamable) {
            readbuf.append("        super.readObject(ins);\n");
            writebuf.append("        super.writeObject(out);\n");
        }
        for (Field field : reqs.streamedFields) {
            readbuf.append("        ");
            readbuf.append(field.getName()).append(" = ");
            readbuf.append(toReadObject(field));
            readbuf.append(";\n");

            writebuf.append("        out.");
            writebuf.append(toWriteObject(field));
            writebuf.append(";\n");
        }

        readbuf.append(READ_CLOSE);
        writebuf.append(WRITE_CLOSE);

        SourceFile sfile = new SourceFile(_indentWidth);
        try {
            sfile.readFrom(source);
        } catch (IOException ioe) {
            System.err.println("Error reading " + source + ": " + ioe);
        }

        // don't overwrite an existing readObject() or writeObject()
        StringBuilder methods = new StringBuilder();
        if (!sfile.containsString("public void readObject")) {
            methods.append(readbuf);
        }
        if (!sfile.containsString("public void writeObject")) {
            if (methods.length() > 0) {
                methods.append("\n");
            }
            methods.append(writebuf);
        }
        if (methods.length() == 0) {
            return; // nothing to do
        }

        System.err.println("Converting " + sclass.getName() + "...");

        writeFile(source.getAbsolutePath(), sfile.generate(null, methods.toString()));
    }

    /**
     * "Flatten" mode: emit self-contained declaration-ordered {@code read/writeObject} that stream
     * the whole {@link ClassUtil#getFields} field set via {@link com.threerings.io.GenStreamUtil}.
     * Handles {@link DObject}s and replaces pass-through {@code default*Object()} calls in place.
     */
    protected void processFlatten (File source, Class<?> sclass)
        throws IOException
    {
        // must be a concrete Streamable; never instrument abstract bases (their concrete subclasses
        // stream the full flattened field set themselves, so an abstract base carrying a read/write
        // method would "poison" any subclass that didn't get its own — silently dropping fields)
        if (!Streamable.class.isAssignableFrom(sclass) || sclass.isInterface() ||
                sclass.isEnum() || Modifier.isAbstract(sclass.getModifiers())) {
            return;
        }

        // gather the streamed fields in the exact order the reflective streamer uses (superclass
        // first, then this class's declared fields), skipping @NotStreamable and closure refs
        List<Field> fields = Lists.newArrayList();
        for (Field field : ClassUtil.getFields(sclass)) {
            if (field.getAnnotation(NotStreamable.class) != null) {
                continue;
            }
            if (field.isSynthetic() && field.getName().startsWith("this$")) {
                continue;
            }
            fields.add(field);
        }
        if (fields.isEmpty()) {
            return;
        }

        String src = new String(Files.readAllBytes(source.toPath()));

        boolean hasRead = src.contains("public void readObject");
        boolean hasWrite = src.contains("public void writeObject");
        boolean hasReadDefault = DEFAULT_READ.matcher(src).find();
        boolean hasWriteDefault = DEFAULT_WRITE.matcher(src).find();

        // if either method is hand-written custom streaming (present, but NOT a defaultXObject
        // pass-through), the class fully manages its own streaming and is already deterministic;
        // leave it entirely alone rather than risk a generated/hand-written mismatch
        if ((hasRead && !hasReadDefault) || (hasWrite && !hasWriteDefault)) {
            return;
        }

        // build whole methods for any streaming direction that has no method at all
        StringBuilder methods = new StringBuilder();
        if (!hasRead) {
            methods.append(FLAT_READ_OPEN);
            for (Field field : fields) {
                methods.append("        ").append(flatReadStatement(field, "ins")).append("\n");
            }
            methods.append(READ_CLOSE);
        }
        if (!hasWrite) {
            if (methods.length() > 0) {
                methods.append("\n");
            }
            methods.append(FLAT_WRITE_OPEN);
            for (Field field : fields) {
                methods.append("        ").append(flatWriteStatement(field, "out")).append("\n");
            }
            methods.append(WRITE_CLOSE);
        }

        boolean changed = false;
        // replace a pass-through default read/write call in place, preserving surrounding logic
        if (hasReadDefault) {
            src = substituteDefault(src, DEFAULT_READ, fields, true);
            changed = true;
        }
        if (hasWriteDefault) {
            src = substituteDefault(src, DEFAULT_WRITE, fields, false);
            changed = true;
        }
        // insert whole methods just before the class-closing brace, so we NEVER disturb the
        // "// AUTO-GENERATED: METHODS" section (which holds the gendobj/genservice accessors) —
        // SourceFile.generate() would replace that section wholesale and drop the accessors
        if (methods.length() > 0) {
            src = insertBeforeClassClose(src, methods.toString());
            changed = true;
        }
        if (changed) {
            System.err.println("Converting (flatten) " + sclass.getName() + "...");
            writeFile(source.getAbsolutePath(), src);
        }
    }

    /**
     * Inserts {@code methods} (already 4-space indented, newline-terminated) just before the final
     * top-level class-closing brace of {@code src}.
     */
    protected String insertBeforeClassClose (String src, String methods)
    {
        String[] lines = src.split("\n", -1);
        int lastBrace = -1;
        for (int ii = 0; ii < lines.length; ii++) {
            if (lines[ii].trim().equals("}")) {
                lastBrace = ii;
            }
        }
        if (lastBrace < 0) {
            throw new RuntimeException("No class-closing brace found");
        }
        StringBuilder sb = new StringBuilder();
        for (int ii = 0; ii < lines.length; ii++) {
            if (ii == lastBrace) {
                sb.append("\n").append(methods);
            }
            sb.append(lines[ii]);
            if (ii < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Replaces the single {@code recv.defaultReadObject()} / {@code recv.defaultWriteObject()}
     * statement matched by {@code pat} with an explicit, same-indentation block that streams every
     * field via {@link com.threerings.io.GenStreamUtil}, reusing the receiver variable name.
     */
    protected String substituteDefault (String src, Pattern pat, List<Field> fields, boolean read)
    {
        Matcher m = pat.matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String indent = m.group(1), recv = m.group(2);
            StringBuilder block = new StringBuilder();
            for (int ii = 0; ii < fields.size(); ii++) {
                if (ii > 0) {
                    block.append("\n");
                }
                Field field = fields.get(ii);
                block.append(indent).append(
                    read ? flatReadStatement(field, recv) : flatWriteStatement(field, recv));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(block.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    protected String flatReadStatement (Field field, String recv)
    {
        return "com.threerings.io.GenStreamUtil.readField(" +
            field.getDeclaringClass().getCanonicalName() + ".class, \"" + field.getName() +
            "\", this, " + recv + ");";
    }

    protected String flatWriteStatement (Field field, String recv)
    {
        return "com.threerings.io.GenStreamUtil.writeField(" +
            field.getDeclaringClass().getCanonicalName() + ".class, \"" + field.getName() +
            "\", this, " + recv + ");";
    }

    protected String toReadObject (Field field)
    {
        Class<?> type = field.getType();
        if (type.equals(String.class)) {
            return "ins.readUTF()";
        } else if (type.equals(Boolean.TYPE) || type.equals(Boolean.class)) {
            return "ins.readBoolean()";
        } else if (type.equals(Byte.TYPE) || type.equals(Byte.class)) {
            return "ins.readByte()";
        } else if (type.equals(Short.TYPE) || type.equals(Short.class)) {
            return "ins.readShort()";
        } else if (type.equals(Integer.TYPE) || type.equals(Integer.class)) {
            return "ins.readInt()";
        } else if (type.equals(Long.TYPE) || type.equals(Long.class)) {
            return "ins.readLong()";
        } else if (type.equals(Float.TYPE) || type.equals(Float.class)) {
            return "ins.readFloat()";
        } else if (type.equals(Double.TYPE) || type.equals(Double.class)) {
            return "ins.readDouble()";
        } else {
            return "(" + GenUtil.simpleName(field) + ")ins.readObject()";
        }
    }

    protected String toWriteObject (Field field)
    {
        Class<?> type = field.getType();
        String name = field.getName();
        if (type.equals(Boolean.TYPE) || type.equals(Boolean.class)) {
            return "writeBoolean(" + name + ")";
        } else if (type.equals(Byte.TYPE) || type.equals(Byte.class)) {
            return "writeByte(" + name + ")";
        } else if (type.equals(Short.TYPE) || type.equals(Short.class)) {
            return "writeShort(" + name + ")";
        } else if (type.equals(Integer.TYPE) || type.equals(Integer.class)) {
            return "writeInt(" + name + ")";
        } else if (type.equals(Long.TYPE) || type.equals(Long.class)) {
            return "writeLong(" + name + ")";
        } else if (type.equals(Float.TYPE) || type.equals(Float.class)) {
            return "writeFloat(" + name + ")";
        } else if (type.equals(Double.TYPE) || type.equals(Double.class)) {
            return "writeDouble(" + name + ")";
        } else if (type.equals(String.class)) {
            return "writeUTF(" + name + ")";
        } else {
            return "writeObject(" + name + ")";
        }
    }

    /** A list of filesets that contain tile images. */
    protected ArrayList<FileSet> _filesets = Lists.newArrayList();

    /** Whether to generate self-contained flattened methods (Android/ART support). */
    protected boolean _flatten;

    protected static final String READ_OPEN =
        "    // from interface Streamable\n" +
        "    public void readObject (ObjectInputStream ins)\n" +
        "        throws IOException, ClassNotFoundException\n" +
        "    {\n";
    protected static final String READ_CLOSE = "    }\n";

    protected static final String WRITE_OPEN =
        "    // from interface Streamable\n" +
        "    public void writeObject (ObjectOutputStream out)\n" +
        "        throws IOException\n" +
        "    {\n";
    protected static final String WRITE_CLOSE = "    }\n";

    // fully-qualified signatures for flatten mode so no imports are required in the target file
    protected static final String FLAT_READ_OPEN =
        "    // from interface Streamable\n" +
        "    public void readObject (com.threerings.io.ObjectInputStream ins)\n" +
        "        throws java.io.IOException, java.lang.ClassNotFoundException\n" +
        "    {\n";
    protected static final String FLAT_WRITE_OPEN =
        "    // from interface Streamable\n" +
        "    public void writeObject (com.threerings.io.ObjectOutputStream out)\n" +
        "        throws java.io.IOException\n" +
        "    {\n";

    /** Matches a pass-through {@code recv.defaultReadObject();} statement (captures indent, recv). */
    protected static final Pattern DEFAULT_READ =
        Pattern.compile("(?m)^([ \\t]*)(\\w+)\\.defaultReadObject\\s*\\(\\s*\\)\\s*;[ \\t]*$");
    /** Matches a pass-through {@code recv.defaultWriteObject();} statement. */
    protected static final Pattern DEFAULT_WRITE =
        Pattern.compile("(?m)^([ \\t]*)(\\w+)\\.defaultWriteObject\\s*\\(\\s*\\)\\s*;[ \\t]*$");
}
