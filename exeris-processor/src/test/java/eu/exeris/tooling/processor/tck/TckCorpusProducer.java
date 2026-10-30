package eu.exeris.tooling.processor.tck;

import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import eu.exeris.sdk.tck.Facet;
import eu.exeris.sdk.tck.TckCorpus;
import eu.exeris.tooling.processor.ExerisDomainProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * The processor as the SDK conformance kit's producer: the whole corpus compiled once under
 * {@link ExerisDomainProcessor}, and each entity's {@code exeris-metadata/<Entity>.json} handed to
 * the case that asks for it.
 *
 * <p>The corpus entities reference one another, so they compile as one unit, as an application's
 * sources do; a case passes one entity's source and gets that entity's file.
 */
final class TckCorpusProducer {

    /**
     * The facets the processor does not extract yet. Each extraction row removes its facet in the
     * same change, and {@code TckFacetRatchetTest} holds the set to shrinking only.
     */
    static final Set<Facet> UNSUPPORTED = Collections.unmodifiableSet(EnumSet.of(
            Facet.SAGA_COMPENSATION,
            Facet.SAGA_TRIGGER,
            Facet.ACTION_RESULT_TYPE,
            Facet.ROUTE_ACCESS,
            Facet.NAV_MENU,
            Facet.TABS,
            Facet.BLOBS));

    private static final Map<String, String> METADATA = compileCorpus();

    private TckCorpusProducer() {
    }

    /** The metadata JSON the processor writes for the corpus entity whose source is {@code entitySource}. */
    static String produce(String entitySource) {
        for (String name : TckCorpus.entityNames()) {
            if (TckCorpus.sourceOf(name).equals(entitySource)) {
                return METADATA.get(name);
            }
        }
        throw new IllegalArgumentException("not a corpus entity source");
    }

    private static Map<String, String> compileCorpus() {
        List<JavaFileObject> sources = new ArrayList<>();
        for (Map.Entry<String, String> entity : TckCorpus.all().entrySet()) {
            sources.add(JavaFileObjects.forSourceString(
                    TckCorpus.packageName() + "." + entity.getKey(), entity.getValue()));
        }
        Compilation compilation = javac().withProcessors(new ExerisDomainProcessor()).compile(sources);
        if (compilation.status() != Compilation.Status.SUCCESS) {
            throw new IllegalStateException("the TCK corpus does not compile under the processor: "
                    + compilation.diagnostics());
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String name : TckCorpus.entityNames()) {
            JavaFileObject file = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/" + name + ".json").orElseThrow(
                            () -> new IllegalStateException("no exeris-metadata/" + name + ".json"));
            metadata.put(name, read(file));
        }
        return Collections.unmodifiableMap(metadata);
    }

    private static String read(JavaFileObject file) {
        try (InputStream in = file.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
