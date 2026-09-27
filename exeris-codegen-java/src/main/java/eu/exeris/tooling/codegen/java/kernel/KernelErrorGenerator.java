package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Kernel Error Generator.
 *
 * <p>Emits the exception types the generated repository raises when it refuses a write:
 * <ul>
 *   <li>{@code <Entity>NotFoundException} — always; the write matched no row (ADR-076).</li>
 *   <li>{@code <Entity>VersionConflictException} — only for a {@code versioned} entity,
 *       whose {@code update} also fails on a stale expected version (ADR-076).</li>
 *   <li>{@code <Entity>TenantMismatchException} — only for a tenant-partitioned entity: the
 *       written row names a tenant other than the bound one (ADR-090).</li>
 *   <li>{@code <Entity>SharedScopeMismatchException} — only for a {@code UNIVERSE} entity with a
 *       {@code @SharedScope} field: the row is tagged with a shared scope other than the bound one
 *       (ADR-090).</li>
 * </ul>
 *
 * <p>The last two are <em>caller</em> faults, the first two facts about the addressed row; all four
 * carry a type so the handler can answer each with its own status instead of the {@code 500} a bare
 * {@code RuntimeException} gets.
 *
 * <p>They exist because "no row matched" used to be carried only by the message of a bare
 * {@code RuntimeException}, which the emitted handler could not tell apart from an
 * infrastructure failure — so a {@code PUT} or {@code DELETE} against an absent id answered
 * {@code 500} while the emitted OpenAPI spec promised {@code 404}. A type is the smallest
 * thing a {@code catch} clause can act on.
 *
 * <h2>Why per entity rather than one shared type</h2>
 * A single {@code EntityNotFoundException} would have to live under the project base package,
 * and a per-domain emitter cannot resolve that: {@code basePackage} is a pipeline-level input
 * (explicit {@code -Aexeris.basePackage}, else auto-detected from the first domain), while a
 * {@link KernelArtifactGenerator} sees only {@code metadata.packageName()}. The
 * {@code .replace(".domain", ".repository")} derivation every emitter already uses is correct
 * under both. The cost is that a consumer cannot catch one supertype across entities; that is
 * recorded in ADR-076 rather than designed around.
 *
 * <h2>Why the repository package and not the domain package</h2>
 * The domain package is the consumer's own — it holds their {@code @ExerisDomain} entity — and
 * {@code <Entity>NotFoundException} is a name a consumer is likely to have written there
 * already. The repository package is generated-owned, and it is where the throw sites are.
 *
 * <p>An exception type is <b>not</b> an ADR-070 component: nothing constructs it through a
 * {@code create*} factory, so it does not join {@code RuntimeComponents}.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @author Exeris Team
 * @since 0.8.0
 */
public class KernelErrorGenerator implements KernelArtifactGenerator {

    private static final ClassName UUID = ClassName.get("java.util", "UUID");

    /** The package the emitted repository lives in, and where these types join it. */
    static String errorPackage(DomainMetadata metadata) {
        return metadata.packageName().replace(".domain", ".repository");
    }

    /** {@code <Entity>NotFoundException} — emitted for every entity. */
    static ClassName notFoundType(DomainMetadata metadata) {
        return ClassName.get(errorPackage(metadata), metadata.entityName() + "NotFoundException");
    }

    /**
     * {@code <Entity>VersionConflictException}, or {@code null} for an unversioned entity —
     * whose {@code update} has no stale-version failure mode to report.
     */
    static ClassName versionConflictType(DomainMetadata metadata) {
        return metadata.versioned()
                ? ClassName.get(errorPackage(metadata),
                        metadata.entityName() + "VersionConflictException")
                : null;
    }

    /**
     * {@code <Entity>TenantMismatchException}, or {@code null} for an entity whose rows have no
     * owner (ADR-090).
     */
    static ClassName tenantMismatchType(DomainMetadata metadata) {
        return DataScopeSupport.isTenantPartitioned(metadata)
                ? ClassName.get(errorPackage(metadata), metadata.entityName() + "TenantMismatchException")
                : null;
    }

    /**
     * {@code <Entity>SharedScopeMismatchException}, or {@code null} for every entity that is not a
     * transcribable UNIVERSE entity (ADR-090).
     */
    static ClassName sharedScopeMismatchType(DomainMetadata metadata) {
        return DataScopeSupport.sharedScopeField(metadata).isPresent()
                ? ClassName.get(errorPackage(metadata), metadata.entityName() + "SharedScopeMismatchException")
                : null;
    }

    /**
     * The registry's single-file entry point. {@link #generateMultiple} is the real one: a
     * versioned entity gets two types, and two public top-level classes cannot share a file.
     */
    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        return notFound(metadata);
    }

    @Override
    public List<GeneratedFile> generateMultiple(DomainMetadata metadata) {
        List<GeneratedFile> files = new ArrayList<>();
        files.add(notFound(metadata));
        if (metadata.versioned()) {
            files.add(versionConflict(metadata));
        }
        if (tenantMismatchType(metadata) != null) {
            files.add(tenantMismatch(metadata));
        }
        if (sharedScopeMismatchType(metadata) != null) {
            files.add(sharedScopeMismatch(metadata));
        }
        return List.copyOf(files);
    }

    private GeneratedFile notFound(DomainMetadata metadata) {
        ClassName self = notFoundType(metadata);
        TypeSpec type = rowException(self)
                .addJavadoc("Raised when a write addresses a $L that no row matches.\n",
                        metadata.entityName())
                .addJavadoc("\n<p>The generated handler answers {@code 404 Not Found} for this\n")
                .addJavadoc("(ADR-076): the row's absence is a client-addressable fact, not a\n")
                .addJavadoc("server fault, which is why it carries a type and not only a message.\n")
                .addJavadoc("\n<p>Generated by Exeris Codegen. DO NOT EDIT.\n")
                .addMethod(idConstructor(metadata.entityName() + " not found: "))
                .addMethod(idAccessor())
                .build();
        return emit(self, type);
    }

    private GeneratedFile versionConflict(DomainMetadata metadata) {
        ClassName self = versionConflictType(metadata);
        TypeSpec type = rowException(self)
                .addJavadoc("Raised when an optimistic-lock update of a $L applies to no row.\n",
                        metadata.entityName())
                .addJavadoc("\n<p>The generated handler answers {@code 409 Conflict} for this\n")
                .addJavadoc("(ADR-076).\n")
                .addJavadoc("\n<p>The update matches on {@code id} <i>and</i> on the expected\n")
                .addJavadoc("{@code version} in a single statement, so \"the row is gone\" and\n")
                .addJavadoc("\"your version is stale\" are not separable without a second query.\n")
                .addJavadoc("{@code 409} is true of both — the write did not apply, re-read and\n")
                .addJavadoc("retry — where {@code 404} would be a lie about one of them.\n")
                .addJavadoc("\n<p>Generated by Exeris Codegen. DO NOT EDIT.\n")
                .addMethod(idConstructor(metadata.entityName() + " not found or stale version: "))
                .addMethod(idAccessor())
                .build();
        return emit(self, type);
    }

    private GeneratedFile tenantMismatch(DomainMetadata metadata) {
        ClassName self = tenantMismatchType(metadata);
        TypeSpec type = exception(self)
                .addField(FieldSpec.builder(UUID, "tenantId", Modifier.PRIVATE, Modifier.FINAL).build())
                .addJavadoc("Raised when a written $L names a tenant other than the one the request\n",
                        metadata.entityName())
                .addJavadoc("is bound to.\n")
                .addJavadoc("\n<p>The generated handler answers {@code 400 Bad Request} for this\n")
                .addJavadoc("(ADR-090): the body named an owner the caller does not act as, and the\n")
                .addJavadoc("same request fails the same way however often it is repeated — a caller\n")
                .addJavadoc("fault, not a server one. Checked only while a tenant is bound; with none\n")
                .addJavadoc("bound the repository leaves the row to row-level security.\n")
                .addJavadoc("\n<p>Generated by Exeris Codegen. DO NOT EDIT.\n")
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(UUID, "tenantId")
                        .addStatement("super($S + tenantId + $S)", metadata.entityName() + " names tenant ",
                                ", which is not the tenant this request is bound to")
                        .addStatement("this.tenantId = tenantId")
                        .build())
                .addMethod(MethodSpec.methodBuilder("tenantId")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(UUID)
                        .addJavadoc("The tenant the rejected write named.\n")
                        .addStatement("return tenantId")
                        .build())
                .build();
        return emit(self, type);
    }

    private GeneratedFile sharedScopeMismatch(DomainMetadata metadata) {
        ClassName self = sharedScopeMismatchType(metadata);
        TypeSpec type = exception(self)
                .addField(FieldSpec.builder(String.class, "sharedScope", Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                .addJavadoc("Raised when a written $L is tagged with a shared scope other than the one\n",
                        metadata.entityName())
                .addJavadoc("the request is bound to.\n")
                .addJavadoc("\n<p>The generated handler answers {@code 400 Bad Request} for this\n")
                .addJavadoc("(ADR-090), for the same reason as a mismatched tenant. Checked only while\n")
                .addJavadoc("a shared scope is bound; an untagged row, or a request that declares no\n")
                .addJavadoc("scope, is not refused here.\n")
                .addJavadoc("\n<p>Generated by Exeris Codegen. DO NOT EDIT.\n")
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(String.class, "sharedScope")
                        .addStatement("super($S + sharedScope + $S)",
                                metadata.entityName() + " is tagged with shared scope ",
                                ", which is not the shared scope this request is bound to")
                        .addStatement("this.sharedScope = sharedScope")
                        .build())
                .addMethod(MethodSpec.methodBuilder("sharedScope")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(String.class)
                        .addJavadoc("The shared scope the rejected write was tagged with.\n")
                        .addStatement("return sharedScope")
                        .build())
                .build();
        return emit(self, type);
    }

    private GeneratedFile emit(ClassName self, TypeSpec type) {
        return new GeneratedFile(self.packageName(), self.simpleName(),
                KernelScaffold.render(self.packageName(), type), ArtifactType.DOMAIN_ERROR);
    }

    /**
     * The shared shape: {@code public class X extends RuntimeException} with a
     * {@code serialVersionUID}, so the emitted type does not raise a serialization warning in a
     * consumer's build.
     */
    private TypeSpec.Builder exception(ClassName self) {
        return KernelScaffold.publicClass(self.simpleName())
                .superclass(RuntimeException.class)
                .addField(FieldSpec.builder(TypeName.LONG, "serialVersionUID",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("1L")
                        .build());
    }

    /** {@link #exception} carrying the id of the row a rejected write addressed (ADR-076). */
    private TypeSpec.Builder rowException(ClassName self) {
        return exception(self)
                .addField(FieldSpec.builder(UUID, "id", Modifier.PRIVATE, Modifier.FINAL).build());
    }

    /**
     * {@code public X(UUID id)} — the id is retained as well as formatted into the message, so a
     * consumer's own {@code catch} can act on it without parsing text. That is the whole point:
     * the fact stops being a string.
     */
    private MethodSpec idConstructor(String messagePrefix) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(UUID, "id")
                .addStatement("super($S + id)", messagePrefix)
                .addStatement("this.id = id")
                .build();
    }

    private MethodSpec idAccessor() {
        return MethodSpec.methodBuilder("id")
                .addModifiers(Modifier.PUBLIC)
                .returns(UUID)
                .addJavadoc("The id the rejected write addressed.\n")
                .addStatement("return id")
                .build();
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.DOMAIN_ERROR;
    }
}
