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
     * The top-level class AND every named member class (recursively) are processed — a nested
     * Streamable (e.g. {@code BangConfig.Round}) streams reflectively just like a top-level one,
     * so it needs the same deterministic methods on ART. All detection/substitution/insertion is
     * scoped to each class's own body region so an instrumented outer class never masks (or
     * poisons) a nested one and vice versa.
     */
    protected void processFlatten (File source, Class<?> sclass)
        throws IOException
    {
        String src = new String(Files.readAllBytes(source.toPath()));
        String out = processFlattenTree(src, sclass);
        if (!out.equals(src)) {
            writeFile(source.getAbsolutePath(), out);
        }
    }

    /**
     * Processes {@code sclass} then recurses into its named member classes. Recursion is NOT gated
     * on the enclosing class's eligibility: an abstract (or non-Streamable) outer class can still
     * declare concrete nested Streamables that need instrumenting.
     */
    protected String processFlattenTree (String src, Class<?> sclass)
    {
        src = processFlattenClass(src, sclass);
        for (Class<?> nested : sclass.getDeclaredClasses()) {
            src = processFlattenTree(src, nested);
        }
        return src;
    }

    /**
     * Instruments a single class (top-level or nested) within {@code src}, returning the updated
     * source text (or {@code src} unchanged if the class is ineligible or already instrumented).
     */
    protected String processFlattenClass (String src, Class<?> sclass)
    {
        if (!Streamable.class.isAssignableFrom(sclass) || sclass.isInterface() || sclass.isEnum()) {
            return src;
        }
        // ABSTRACT classes get SUBSTITUTION ONLY: an existing default*Object() pass-through is
        // replaced with the class's own flattened prefix (so the method becomes a safe super-call
        // target for generated subclass methods — reflective defaultReadObject reads the full
        // DYNAMIC-class set, which would double-read the subclass tail), but never whole generated
        // methods (those would "poison" any concrete subclass that didn't get its own — silently
        // dropping its fields from the stream)
        boolean isAbstract = Modifier.isAbstract(sclass.getModifiers());

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

        // detection and substitution look only at THIS class's body, with any nested member class
        // bodies blanked out (space-padded, so offsets in the masked text map 1:1 onto src)
        int[] region = findClassRegion(src, sclass);
        String masked = maskNestedClasses(src, region, sclass);

        boolean hasRead = masked.contains("public void readObject");
        boolean hasWrite = masked.contains("public void writeObject");
        boolean hasReadDefault = DEFAULT_READ.matcher(masked).find();
        boolean hasWriteDefault = DEFAULT_WRITE.matcher(masked).find();

        // a field-less class has nothing to generate, but an existing pass-through must STILL be
        // substituted (to an empty read/write — the reflective call would stream the full
        // dynamic-class set when reached via a subclass's super-call)
        if (fields.isEmpty() && !hasReadDefault && !hasWriteDefault) {
            return src;
        }

        // if either method is hand-written custom streaming (present, but NOT a defaultXObject
        // pass-through), the class fully manages its own streaming and is already deterministic;
        // leave it entirely alone rather than risk a generated/hand-written mismatch
        if ((hasRead && !hasReadDefault) || (hasWrite && !hasWriteDefault)) {
            return src;
        }

        // an ancestor that declares its own read/writeObject is the class's streaming "base": a
        // generated method here must super-call it (preserving its side effects — Prop.init(),
        // InvocationMarshaller's _invdir rebind, Item's _nondb extras) and then stream only the
        // fields declared BELOW it. A self-contained full-set method would silently drop those
        // side effects for every subclass that previously just inherited the ancestor's method
        // (the Phase 5(b) Prop._vscale server NPE / Item._itemId wire bug). Byte layout on the
        // stream is unchanged either way: the ancestor's method covers the flattened prefix.
        Class<?> readAncestor = findStreamMethodAncestor(
            sclass, "readObject", com.threerings.io.ObjectInputStream.class);
        Class<?> writeAncestor = findStreamMethodAncestor(
            sclass, "writeObject", com.threerings.io.ObjectOutputStream.class);
        List<Field> readFields = fieldsBelow(fields, readAncestor);
        List<Field> writeFields = fieldsBelow(fields, writeAncestor);

        // methods are indented one level deeper than the class declaration's nesting depth
        int depth = 0;
        for (Class<?> c = sclass; c.getEnclosingClass() != null; c = c.getEnclosingClass()) {
            depth++;
        }
        String ind = indent(depth + 1);

        // build whole methods for any streaming direction that has no method at all — but when an
        // ancestor declares the method and this class adds no fields of its own, plain inheritance
        // is already complete and deterministic: generate nothing (the field-free-marshaller case)
        StringBuilder methods = new StringBuilder();
        if (!hasRead && !isAbstract && !readFields.isEmpty()) {
            methods.append(ind).append("// from interface Streamable\n")
                .append(ind).append("public void readObject (com.threerings.io.ObjectInputStream ins)\n")
                .append(ind).append("    throws java.io.IOException, java.lang.ClassNotFoundException\n")
                .append(ind).append("{\n");
            if (readAncestor != null) {
                methods.append(ind).append("    super.readObject(ins);\n");
            }
            for (Field field : readFields) {
                methods.append(ind).append("    ").append(flatReadStatement(field, "ins")).append("\n");
            }
            methods.append(ind).append("}\n");
        }
        if (!hasWrite && !isAbstract && !writeFields.isEmpty()) {
            if (methods.length() > 0) {
                methods.append("\n");
            }
            methods.append(ind).append("// from interface Streamable\n")
                .append(ind).append("public void writeObject (com.threerings.io.ObjectOutputStream out)\n")
                .append(ind).append("    throws java.io.IOException\n")
                .append(ind).append("{\n");
            if (writeAncestor != null) {
                methods.append(ind).append("    super.writeObject(out);\n");
            }
            for (Field field : writeFields) {
                methods.append(ind).append("    ").append(flatWriteStatement(field, "out")).append("\n");
            }
            methods.append(ind).append("}\n");
        }

        boolean changed = false;
        // replace pass-through default read/write calls in place, preserving surrounding logic
        // (matched against the masked body so a nested class's pass-through is never rewritten
        // with THIS class's fields — it gets its own substitution when its class is processed)
        if (hasReadDefault || hasWriteDefault) {
            src = substituteDefaults(src, masked, region, fields);
            changed = true;
        }
        // insert whole methods just before this class's closing brace, so we NEVER disturb the
        // "// AUTO-GENERATED: METHODS" section (which holds the gendobj/genservice accessors) —
        // SourceFile.generate() would replace that section wholesale and drop the accessors
        if (methods.length() > 0) {
            int closeLineStart = src.lastIndexOf('\n', region[1]) + 1;
            src = src.substring(0, closeLineStart) + "\n" + methods + src.substring(closeLineStart);
            changed = true;
        }
        if (changed) {
            System.err.println("Converting (flatten) " + sclass.getName() + "...");
        }
        return src;
    }

    /**
     * Locates the body of {@code sclass}'s declaration in {@code src}, searching only within the
     * enclosing class's own region for nested classes (so same-simple-named classes elsewhere in
     * the file can't be confused). Returns {@code {bodyStart, closeBrace}}: the offset just after
     * the opening brace and the offset of the matching closing brace.
     */
    protected int[] findClassRegion (String src, Class<?> sclass)
    {
        int searchStart = 0, searchEnd = src.length();
        Class<?> outer = sclass.getEnclosingClass();
        if (outer != null) {
            int[] outerRegion = findClassRegion(src, outer);
            searchStart = outerRegion[0];
            searchEnd = outerRegion[1];
        }
        Pattern decl = Pattern.compile(
            "\\b(?:class|interface|enum)\\s+" + Pattern.quote(sclass.getSimpleName()) + "\\b");
        Matcher m = decl.matcher(src);
        m.region(searchStart, searchEnd);
        if (!m.find()) {
            throw new RuntimeException("Cannot locate declaration of " + sclass.getName());
        }
        int open = src.indexOf('{', m.end());
        if (open < 0 || open >= searchEnd) {
            throw new RuntimeException("Cannot locate body of " + sclass.getName());
        }
        return new int[] { open + 1, matchBrace(src, open) };
    }

    /**
     * Returns the offset of the brace closing the one at {@code open}, skipping braces inside
     * string/char literals and comments.
     */
    protected int matchBrace (String src, int open)
    {
        int braces = 0;
        for (int ii = open; ii < src.length(); ii++) {
            char c = src.charAt(ii);
            switch (c) {
            case '{':
                braces++;
                break;
            case '}':
                if (--braces == 0) {
                    return ii;
                }
                break;
            case '"':
            case '\'':
                char quote = c;
                for (ii++; ii < src.length(); ii++) {
                    char d = src.charAt(ii);
                    if (d == '\\') {
                        ii++;
                    } else if (d == quote) {
                        break;
                    }
                }
                break;
            case '/':
                if (ii + 1 < src.length()) {
                    char d = src.charAt(ii + 1);
                    if (d == '/') {
                        int nl = src.indexOf('\n', ii);
                        ii = (nl < 0) ? src.length() : nl;
                    } else if (d == '*') {
                        int end = src.indexOf("*/", ii + 2);
                        if (end < 0) {
                            throw new RuntimeException("Unterminated block comment");
                        }
                        ii = end + 1;
                    }
                }
                break;
            }
        }
        throw new RuntimeException("Unbalanced braces scanning class body");
    }

    /**
     * Returns {@code src} with everything outside {@code region} AND every named member class body
     * within it replaced by spaces — same length as {@code src}, so match offsets in the masked
     * text index directly into the original.
     */
    protected String maskNestedClasses (String src, int[] region, Class<?> sclass)
    {
        StringBuilder sb = new StringBuilder(src);
        for (int ii = 0; ii < region[0]; ii++) {
            blank(sb, ii);
        }
        for (int ii = region[1]; ii < sb.length(); ii++) {
            blank(sb, ii);
        }
        for (Class<?> nested : sclass.getDeclaredClasses()) {
            int[] nregion;
            try {
                nregion = findClassRegion(src, nested);
            } catch (RuntimeException e) {
                continue; // e.g. synthetic member with no source declaration
            }
            for (int ii = nregion[0]; ii < nregion[1]; ii++) {
                blank(sb, ii);
            }
        }
        return sb.toString();
    }

    protected static void blank (StringBuilder sb, int ii)
    {
        if (sb.charAt(ii) != '\n') {
            sb.setCharAt(ii, ' ');
        }
    }

    /**
     * Replaces every pass-through {@code recv.defaultReadObject()} / {@code recv.defaultWriteObject()}
     * statement found in {@code masked} with an explicit, same-indentation block that streams every
     * field via {@link com.threerings.io.GenStreamUtil}, reusing the receiver variable name.
     * Matching runs on the masked text (this class's body only); edits apply to the real source.
     */
    protected String substituteDefaults (String src, String masked, int[] region, List<Field> fields)
    {
        // collect matches from both patterns, then apply in reverse offset order so earlier
        // replacements don't shift later match positions
        List<int[]> matches = Lists.newArrayList(); // {start, end, isRead}
        for (Pattern pat : new Pattern[] { DEFAULT_READ, DEFAULT_WRITE }) {
            Matcher m = pat.matcher(masked);
            while (m.find()) {
                matches.add(new int[] { m.start(), m.end(), (pat == DEFAULT_READ) ? 1 : 0 });
            }
        }
        matches.sort((a, b) -> b[0] - a[0]);

        StringBuilder sb = new StringBuilder(src);
        for (int[] match : matches) {
            Matcher m = ((match[2] == 1) ? DEFAULT_READ : DEFAULT_WRITE).matcher(masked);
            m.region(match[0], match[1]);
            if (!m.find()) {
                throw new RuntimeException("Lost a default*Object match on re-find");
            }
            String indent = m.group(1), recv = m.group(2);
            StringBuilder block = new StringBuilder();
            for (int ii = 0; ii < fields.size(); ii++) {
                if (ii > 0) {
                    block.append("\n");
                }
                Field field = fields.get(ii);
                block.append(indent).append((match[2] == 1)
                    ? flatReadStatement(field, recv) : flatWriteStatement(field, recv));
            }
            sb.replace(match[0], match[1], block.toString());
        }
        return sb.toString();
    }

    protected static String indent (int levels)
    {
        StringBuilder sb = new StringBuilder();
        for (int ii = 0; ii < levels; ii++) {
            sb.append("    ");
        }
        return sb.toString();
    }

    /**
     * Returns the nearest strict ancestor of {@code sclass} that declares the given streaming
     * method, or null. Such an ancestor is a safe super-call target because flatten mode
     * guarantees its method streams exactly the ancestor's own flattened prefix: hand-written
     * methods with a {@code default*Object()} pass-through get it substituted in place (abstract
     * classes included — see {@link #processFlattenClass}), and generated methods are explicit by
     * construction. REQUIREMENT: every Streamable ancestor's source must be covered by this
     * task's filesets (or already be prefix-exact, like narya's own InvocationMarshaller) — an
     * out-of-fileset ancestor whose method still calls the reflective pass-through would read the
     * full dynamic-class field set and double-read the subclass tail.
     */
    protected Class<?> findStreamMethodAncestor (Class<?> sclass, String name, Class<?> arg)
    {
        for (Class<?> c = sclass.getSuperclass(); c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(name, arg);
                return c;
            } catch (NoSuchMethodException nsme) {
                // keep walking
            }
        }
        return null;
    }

    /** Returns the subset of {@code fields} declared strictly below {@code ancestor} (all of
     * them when {@code ancestor} is null). */
    protected static List<Field> fieldsBelow (List<Field> fields, Class<?> ancestor)
    {
        if (ancestor == null) {
            return fields;
        }
        List<Field> below = Lists.newArrayList();
        for (Field field : fields) {
            Class<?> declaring = field.getDeclaringClass();
            if (declaring != ancestor && ancestor.isAssignableFrom(declaring)) {
                below.add(field);
            }
        }
        return below;
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
