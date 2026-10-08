package eu.exeris.tooling.processor;

import eu.exeris.sdk.annotation.Field;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolves every key of the strict-audit registries in {@link ExerisDomainProcessor} against the
 * SDK annotations jar this build resolves.
 *
 * <p>The registries are keyed by hand-written names, and the processor matches them by string
 * equality against what it meets in user source. A key that names no SDK annotation, or an
 * attribute the annotation does not declare, never matches: the warning it was written for is
 * silently off, and the audit cannot report that about itself. The annotation types are on this
 * classpath even though their uses are {@code @Retention(SOURCE)}, so each key resolves
 * reflectively — and an SDK rename or removal fails here instead of leaving a dead entry.
 *
 * <p>A simple name resolves when exactly one annotation type under
 * {@code eu.exeris.sdk.annotation} has it, which is how the processor reads a mirror: by the
 * segment after the last dot of its qualified name. Every failing key is listed in one run.
 */
@DisplayName("Strict-audit registry keys resolve against the SDK annotations")
class StrictAuditRegistryKeysTest {

    private static final String SDK_PACKAGE = "eu.exeris.sdk.annotation.";

    /** SDK annotation types by simple name; a list, so an ambiguous name is visible. */
    private static Map<String, List<Class<?>>> annotationsBySimpleName;
    private static Path sdkJar;

    @BeforeAll
    static void indexSdkAnnotations() throws IOException, URISyntaxException, ClassNotFoundException {
        sdkJar = Path.of(Field.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        annotationsBySimpleName = new TreeMap<>();
        try (JarFile jar = new JarFile(sdkJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class")
                        || name.endsWith("package-info.class")) {
                    continue;
                }
                String binaryName = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                if (!binaryName.startsWith(SDK_PACKAGE)) {
                    continue;
                }
                Class<?> type = Class.forName(binaryName, false, StrictAuditRegistryKeysTest.class.getClassLoader());
                if (type.isAnnotation()) {
                    annotationsBySimpleName.computeIfAbsent(type.getSimpleName(), k -> new ArrayList<>()).add(type);
                }
            }
        }
        assertThat(annotationsBySimpleName)
                .as("annotation types found under %s in %s", SDK_PACKAGE, sdkJar)
                .isNotEmpty();
    }

    @Test
    @DisplayName("every INERT_ATTRIBUTES key names an SDK annotation that declares the attribute")
    void inertAttributesResolve() throws ReflectiveOperationException {
        List<Object> entries = registry("INERT_ATTRIBUTES");
        List<String> unresolved = new ArrayList<>();
        for (Object entry : entries) {
            String annotation = component(entry, "annotation");
            String attribute = component(entry, "attribute");
            String key = "@" + annotation + "." + attribute;
            Class<?> type = resolve(annotation, key, unresolved);
            if (type != null && !declares(type, attribute)) {
                unresolved.add(key + ": " + type.getName() + " declares no attribute '" + attribute + "'");
            }
        }
        assertThat(entries).isNotEmpty();
        assertThat(unresolved).as(failureHeader("INERT_ATTRIBUTES")).isEmpty();
    }

    @Test
    @DisplayName("every INERT_ANNOTATIONS entry names an SDK annotation by FQN, with its own simple name")
    void inertAnnotationsResolve() throws ReflectiveOperationException {
        List<Object> entries = registry("INERT_ANNOTATIONS");
        List<String> unresolved = new ArrayList<>();
        for (Object entry : entries) {
            String fqn = component(entry, "fqn");
            String display = component(entry, "display");
            Class<?> type = loadAnnotation(fqn);
            if (type == null) {
                unresolved.add("@" + display + ": " + fqn + " is not an annotation type in the SDK jar");
            } else if (!type.getSimpleName().equals(display)) {
                unresolved.add("@" + display + ": " + fqn + " has simple name " + type.getSimpleName());
            }
        }
        assertThat(entries).isNotEmpty();
        assertThat(unresolved).as(failureHeader("INERT_ANNOTATIONS")).isEmpty();
    }

    @Test
    @DisplayName("every UNREAD_NOTES entry names exactly one SDK annotation")
    void unreadNotesResolve() throws ReflectiveOperationException {
        List<Object> entries = registry("UNREAD_NOTES");
        List<String> unresolved = new ArrayList<>();
        for (Object entry : entries) {
            String display = component(entry, "display");
            resolve(display, "@" + display, unresolved);
        }
        assertThat(entries).isNotEmpty();
        assertThat(unresolved).as(failureHeader("UNREAD_NOTES")).isEmpty();
    }

    @Test
    @DisplayName("every EXTRACTED_ANNOTATIONS and TYPE_LEVEL_EXTRACTION name is exactly one SDK annotation")
    void extractedAnnotationsResolve() throws ReflectiveOperationException {
        List<String> unresolved = new ArrayList<>();
        for (String registry : List.of("EXTRACTED_ANNOTATIONS", "TYPE_LEVEL_EXTRACTION")) {
            Collection<String> names = registryNames(registry);
            assertThat(names).as(registry).isNotEmpty();
            for (String name : names.stream().sorted().toList()) {
                resolve(name, registry + " @" + name, unresolved);
            }
        }
        assertThat(unresolved).as(failureHeader("EXTRACTED_ANNOTATIONS / TYPE_LEVEL_EXTRACTION")).isEmpty();
    }

    private static Class<?> resolve(String simpleName, String key, List<String> unresolved) {
        List<Class<?>> matches = annotationsBySimpleName.getOrDefault(simpleName, List.of());
        if (matches.size() == 1) {
            return matches.get(0);
        }
        unresolved.add(matches.isEmpty()
                ? key + ": no annotation type named " + simpleName + " under " + SDK_PACKAGE
                : key + ": ambiguous, " + matches.stream().map(Class::getName).sorted().toList());
        return null;
    }

    private static boolean declares(Class<?> annotationType, String attribute) {
        for (Method method : annotationType.getDeclaredMethods()) {
            if (method.getName().equals(attribute) && method.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> loadAnnotation(String fqn) {
        try {
            Class<?> type = Class.forName(fqn, false, StrictAuditRegistryKeysTest.class.getClassLoader());
            return type.isAnnotation() ? type : null;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static String failureHeader(String registry) {
        return registry + " keys that resolve to nothing in " + sdkJar.getFileName()
                + " (each one is a strict-mode warning that can never fire)";
    }

    @SuppressWarnings("unchecked")
    private static List<Object> registry(String fieldName) throws ReflectiveOperationException {
        java.lang.reflect.Field field = ExerisDomainProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (List<Object>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> registryNames(String fieldName) throws ReflectiveOperationException {
        java.lang.reflect.Field field = ExerisDomainProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Collection<String>) field.get(null);
    }

    private static String component(Object entry, String name) throws ReflectiveOperationException {
        for (RecordComponent component : entry.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                return (String) accessor.invoke(entry);
            }
        }
        throw new NoSuchFieldException(entry.getClass().getName() + " has no record component '" + name + "'");
    }
}
