package eu.exeris.tooling.codegen.core.capability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The cap-tier Wall guard — ADR-024 validation predicate 4, realized per ADR-055.
 *
 * <p>Scans the <b>compiled</b> classes of the cap under build and reports every
 * reference that crosses a forbidden boundary: a host-runtime package, a kernel
 * private package, or a sibling cap's {@code internal} package.
 *
 * <h2>Why bytecode and not sources</h2>
 * <p>Founder-ruled (2026-07-29). A source scan sees only the files the cap itself
 * declares, so it is blind to the case that actually bites in practice: a forbidden
 * type arriving through a <em>dependency</em> change. Bytecode is the artefact that
 * ships, so it is the artefact the Wall must hold. It also means the guard needs no
 * parser and no access to the user's sources — only {@code target/classes}.
 *
 * <h2>The extraction surface is the load-bearing part</h2>
 * <p>A constant-pool walk alone is <b>unsound</b>. The pool records the types a class
 * <em>touches</em> in code, not the types it <em>mentions</em>: a method
 * {@code void configure(ApplicationContext ctx)} whose body ignores its parameter
 * puts {@code ApplicationContext} only in the method <em>descriptor</em>, and a field
 * {@code List<Duration>} puts {@code Duration} only in a generic {@code Signature}
 * attribute. Neither appears as a {@link ClassEntry}. A pool-only guard would wave
 * both through — precisely the Spring leak the Wall exists to stop.
 *
 * <p>So this guard unions five sources:
 * <ol>
 *   <li>constant-pool {@link ClassEntry} — supertypes, code references, catch types;</li>
 *   <li>field descriptors;</li>
 *   <li>method descriptors (parameters + return);</li>
 *   <li>{@code Signature} attributes on the class, its fields and its methods (generics);</li>
 *   <li>annotation types on the class, its fields and its methods.</li>
 * </ol>
 *
 * <p>Uses the JDK-standard Class-File API (JEP 484, final in JDK 24) rather than ASM:
 * this repo enforces JDK exactly {@code [26,27)}, so the API is always present and the
 * guard adds <b>no dependency</b> to the build-time path.
 *
 * <h2>Not caught (documented limits)</h2>
 * <p>Reflective reach-through ({@code Class.forName("org.springframework…")}) is invisible
 * to any static scan — the type name is a string constant, indistinguishable from a log
 * message. String constants are deliberately <em>not</em> scanned: doing so would flag a
 * cap for logging the word "springframework". The Wall is an import-boundary guard, not a
 * sandbox.
 *
 * @since 0.7.0
 */
public final class CapTierWall {

    /**
     * Host-runtime package prefixes, in binary-name form. This is the ADR-024 named floor
     * ("or any host-runtime-specific package" is open-ended by design); extend it here when
     * a new host runtime enters the ecosystem, so the list stays in one place.
     */
    static final List<String> HOST_RUNTIME_PREFIXES = List.of(
            "org.springframework.",
            "io.netty.",
            "reactor.",
            "jakarta.servlet.");

    /** Root of the kernel's package space — internals below it are off-limits to caps. */
    private static final String KERNEL_ROOT = "eu.exeris.kernel.";

    /** Root of the cap package space; the segment after it is the cap name (ADR-024 convention). */
    private static final String CAPS_ROOT = "eu.exeris.caps.";

    /** The private-package segment both the kernel and the cap convention use. */
    private static final String INTERNAL_SEGMENT = "internal";

    /**
     * Pulls binary type names out of a descriptor or a generic signature. Matches the
     * {@code L<internal/name>} head of an object type, stopping at {@code ;} or at the
     * {@code <} that opens type arguments — so {@code Ljava/util/List<Ljava/time/Duration;>;}
     * yields both {@code java/util/List} and {@code java/time/Duration}. Type variables
     * ({@code TT;}) and primitives start with another letter and are skipped.
     */
    private static final Pattern OBJECT_TYPE = Pattern.compile("L([^;<>\\[]+)[;<]");

    private CapTierWall() {
    }

    /**
     * Scans every {@code .class} file under {@code classesDir}.
     *
     * @param classesDir the module's compiled-output root (Maven's {@code target/classes});
     *                   a missing directory yields no violations rather than an error, so a
     *                   resources-only or not-yet-compiled module is not a failure
     * @param ownCapNames the cap names this build owns, as derived by
     *                    {@link #ownCapNames(List)}. References into
     *                    {@code eu.exeris.caps.<name>.internal.*} are legal for these and
     *                    forbidden for every other name — a cap may use its own internals.
     * @return violations in deterministic order (hard-constraint #3)
     * @throws UncheckedIOException if a class file exists but cannot be read
     */
    public static List<WallViolation> scan(Path classesDir, Set<String> ownCapNames) {
        if (classesDir == null || !Files.isDirectory(classesDir)) {
            return List.of();
        }
        Set<WallViolation> violations = new TreeSet<>();
        try (Stream<Path> tree = Files.walk(classesDir)) {
            List<Path> classFiles = tree
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList();
            for (Path classFile : classFiles) {
                violations.addAll(scanOne(classFile, ownCapNames));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cap-tier Wall scan failed under " + classesDir, e);
        }
        return List.copyOf(violations);
    }

    /**
     * The cap names owned by this build — the segment after {@code eu.exeris.caps.} in each
     * {@code @CapabilityModule}'s package. A module outside that namespace (a third-party cap
     * in its own package space) contributes no name, which is deliberate: it then gets no
     * licence to read any {@code eu.exeris.caps.*.internal} package.
     */
    public static Set<String> ownCapNames(List<CapabilityModuleDescriptor> modules) {
        Set<String> names = new LinkedHashSet<>();
        for (CapabilityModuleDescriptor m : modules) {
            String capName = capNameOf(m.qualifiedName());
            if (capName != null) {
                names.add(capName);
            }
        }
        return Collections.unmodifiableSet(names);
    }

    private static List<WallViolation> scanOne(Path classFile, Set<String> ownCapNames) throws IOException {
        ClassModel model = ClassFile.of().parse(classFile);
        String owner = binaryName(model.thisClass().asInternalName());

        Set<String> referenced = new TreeSet<>();
        collectReferences(model, referenced);

        List<WallViolation> found = new ArrayList<>();
        for (String type : referenced) {
            WallViolation.Rule rule = classify(type, ownCapNames);
            if (rule != null) {
                found.add(new WallViolation(owner, type, rule));
            }
        }
        return found;
    }

    /** The five-source union described in the class Javadoc. */
    private static void collectReferences(ClassModel model, Set<String> out) {
        // (1) constant-pool class references
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof ClassEntry classEntry) {
                String internal = classEntry.asInternalName();
                // An array type appears as a descriptor ("[Lfoo/Bar;"), not a bare name.
                if (internal.startsWith("[")) {
                    addFromDescriptor(internal, out);
                } else {
                    out.add(binaryName(internal));
                }
            }
        }
        // (4a) class-level generic signature + (5a) class-level annotations
        model.findAttribute(Attributes.signature())
                .ifPresent(sig -> addFromDescriptor(sig.signature().stringValue(), out));
        addAnnotations(model, out);

        for (FieldModel field : model.fields()) {
            // (2) field descriptor
            addFromDescriptor(field.fieldType().stringValue(), out);
            // (4b) field generic signature
            field.findAttribute(Attributes.signature())
                    .ifPresent(sig -> addFromDescriptor(sig.signature().stringValue(), out));
            // (5b) field annotations
            addAnnotations(field, out);
        }

        for (MethodModel method : model.methods()) {
            // (3) method descriptor — parameters and return type
            addFromDescriptor(method.methodType().stringValue(), out);
            // (4c) method generic signature
            method.findAttribute(Attributes.signature())
                    .ifPresent(sig -> addFromDescriptor(sig.signature().stringValue(), out));
            // (5c) method annotations
            addAnnotations(method, out);
        }
    }

    /**
     * Annotation types, visible and invisible alike. A cap annotated
     * {@code @org.springframework.stereotype.Component} is a Wall breach whether or not
     * the annotation is retained at runtime — the coupling is in the source either way.
     */
    private static void addAnnotations(java.lang.classfile.AttributedElement element, Set<String> out) {
        element.findAttribute(Attributes.runtimeVisibleAnnotations())
                .ifPresent(a -> a.annotations()
                        .forEach(ann -> addFromDescriptor(ann.className().stringValue(), out)));
        element.findAttribute(Attributes.runtimeInvisibleAnnotations())
                .ifPresent(a -> a.annotations()
                        .forEach(ann -> addFromDescriptor(ann.className().stringValue(), out)));
    }

    private static void addFromDescriptor(String descriptorOrSignature, Set<String> out) {
        Matcher matcher = OBJECT_TYPE.matcher(descriptorOrSignature);
        while (matcher.find()) {
            out.add(binaryName(matcher.group(1)));
        }
    }

    /**
     * Which Wall rule a referenced type breaks, or {@code null} when it is legal.
     * Order matters only for readability — the three boundaries do not overlap.
     */
    private static WallViolation.Rule classify(String type, Set<String> ownCapNames) {
        for (String prefix : HOST_RUNTIME_PREFIXES) {
            if (type.startsWith(prefix)) {
                return WallViolation.Rule.HOST_RUNTIME;
            }
        }
        if (type.startsWith(KERNEL_ROOT) && hasInternalSegment(type)) {
            return WallViolation.Rule.KERNEL_INTERNAL;
        }
        if (type.startsWith(CAPS_ROOT) && hasInternalSegment(type)) {
            String capName = capNameOf(type);
            // A cap reading its OWN internals is normal encapsulation, not a Wall breach.
            if (capName != null && !ownCapNames.contains(capName)) {
                return WallViolation.Rule.SIBLING_CAP_INTERNAL;
            }
        }
        return null;
    }

    /**
     * True when {@code internal} appears as a whole package segment. Segment-exact on
     * purpose: a package named {@code internals} or a class named {@code InternalCache}
     * is not a private package, and a substring test would flag both.
     */
    private static boolean hasInternalSegment(String binaryName) {
        for (String segment : binaryName.split("\\.")) {
            if (INTERNAL_SEGMENT.equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /** The cap name in {@code eu.exeris.caps.<name>.…}, or {@code null} outside that namespace. */
    private static String capNameOf(String binaryName) {
        if (!binaryName.startsWith(CAPS_ROOT)) {
            return null;
        }
        String rest = binaryName.substring(CAPS_ROOT.length());
        int dot = rest.indexOf('.');
        String name = dot < 0 ? rest : rest.substring(0, dot);
        return name.isEmpty() ? null : name;
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }
}
