package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.support.ForeignKeys;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The part of the emitted repository that turns a foreign-key violation into the typed refusal the
 * handler answers (ADR-076 Amendment 1).
 *
 * <p>The kernel's persistence SPI reports every statement failure as a
 * {@code PersistenceProviderException} with code {@code EX-PERS-5003}, whose raw arguments are
 * {@code [sqlState, detail]}. A foreign-key violation is SQLState {@code 23503} on PostgreSQL in both
 * directions; H2 reports {@code 23503} for a referenced row being deleted and {@code 23506} for a
 * written row naming a missing one. The emitted check walks the cause chain for that exception, so a
 * wrapper the executor adds does not hide it.
 *
 * <p>Which relationship refused the write is read from the constraint name, {@code
 * fk_<table>_<column>}, which both engines put in their message and the foreign-key migration
 * declares ({@link ForeignKeys}). A violation of a constraint the build did not declare is not
 * attributed to a relationship and propagates unchanged. A delete can violate a constraint only as
 * the referenced side, so any foreign-key violation of a delete means the row is still referenced.
 */
final class KernelForeignKeyRefusals {

    /** Name of the emitted method that reads a failure as a foreign-key violation. */
    static final String VIOLATION_METHOD = "foreignKeyViolation";
    /** Name of the emitted method that turns a write's failure into its refusal. */
    static final String REFUSED_REFERENCE_METHOD = "refusedReference";
    /** SQLState of a foreign-key violation: PostgreSQL both ways, H2 for a referenced row. */
    static final String FOREIGN_KEY_VIOLATION = "23503";
    /** SQLState H2 reports for a written row naming a missing one. */
    static final String MISSING_PARENT = "23506";

    private static final ClassName PERSISTENCE_PROVIDER_EXCEPTION =
            ClassName.get("eu.exeris.kernel.spi.exceptions.persistence", "PersistenceProviderException");
    private static final ClassName KERNEL_ERROR_CODES =
            ClassName.get("eu.exeris.kernel.spi.exceptions", "KernelErrorCodes");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName LOCALE = ClassName.get("java.util", "Locale");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get(RuntimeException.class);
    private static final String FAILURE = "failure";

    /** How far down the cause chain the emitted check looks. */
    private static final int CAUSE_DEPTH = 16;

    private KernelForeignKeyRefusals() {}

    /**
     * Whether the repository of {@code metadata} recognises a foreign-key violation at all: its
     * writes reference an entity of the build, or a delete of its rows can be refused.
     */
    static boolean applies(DomainMetadata metadata, List<DomainMetadata> build) {
        return ForeignKeys.refusesMissingReference(metadata, build)
                || ForeignKeys.refusesReferencedDelete(metadata, build);
    }

    /**
     * Adds the constants and methods {@link #guardWrite} and {@link #guardDelete} call, when
     * {@link #applies}.
     */
    static void addMembers(TypeSpec.Builder repository, DomainMetadata metadata, List<DomainMetadata> build) {
        if (!applies(metadata, build)) {
            return;
        }
        repository.addField(FieldSpec.builder(ParameterizedTypeName.get(SET, ClassName.get(String.class)),
                                "FOREIGN_KEY_STATES", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("SQLStates of a foreign-key violation: {@code $L} on PostgreSQL, and on H2\n",
                                FOREIGN_KEY_VIOLATION)
                        .addJavadoc("for a referenced row; {@code $L} on H2 for a missing one.\n", MISSING_PARENT)
                        .initializer("$T.of($S, $S)", SET, FOREIGN_KEY_VIOLATION, MISSING_PARENT)
                        .build())
                .addField(FieldSpec.builder(TypeName.INT, "CAUSE_DEPTH",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$L", CAUSE_DEPTH)
                        .build())
                .addMethod(violationMethod());
        ClassName referenceNotFound = KernelErrorGenerator.referenceNotFoundType(metadata, build);
        if (referenceNotFound != null) {
            repository.addMethod(refusedReferenceMethod(metadata, build, referenceNotFound));
        }
    }

    /**
     * Wraps a write's {@code executeManaged} block so a foreign-key violation leaves it as
     * {@code <Entity>ReferenceNotFoundException}; the block is returned unchanged for an entity whose
     * writes no foreign key checks.
     */
    static CodeBlock guardWrite(CodeBlock write, DomainMetadata metadata, List<DomainMetadata> build) {
        if (!ForeignKeys.refusesMissingReference(metadata, build)) {
            return write;
        }
        return CodeBlock.builder()
                .beginControlFlow("try")
                .add(write)
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("throw $L(e)", REFUSED_REFERENCE_METHOD)
                .endControlFlow()
                .build();
    }

    /**
     * Wraps the delete's {@code executeManaged} block so a foreign-key violation leaves it as
     * {@code <Entity>StillReferencedException}; the block is returned unchanged for an entity whose
     * delete no foreign key can refuse.
     */
    static CodeBlock guardDelete(CodeBlock delete, DomainMetadata metadata, List<DomainMetadata> build) {
        ClassName stillReferenced = KernelErrorGenerator.stillReferencedType(metadata, build);
        if (stillReferenced == null) {
            return delete;
        }
        return CodeBlock.builder()
                .beginControlFlow("try")
                .add(delete)
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .beginControlFlow("if ($L(e) != null)", VIOLATION_METHOD)
                .addStatement("throw new $T(id, e)", stillReferenced)
                .endControlFlow()
                .addStatement("throw e")
                .endControlFlow()
                .build();
    }

    private static MethodSpec violationMethod() {
        return MethodSpec.methodBuilder(VIOLATION_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(RUNTIME_EXCEPTION, FAILURE)
                .addJavadoc("The failure's account of a foreign-key violation — every message and raw\n")
                .addJavadoc("argument down its cause chain, lower-cased — or {@code null} when it is not one.\n")
                .addJavadoc("<p>The persistence SPI reports a statement failure as a\n")
                .addJavadoc("{@code PersistenceProviderException} whose raw arguments are\n")
                .addJavadoc("{@code [sqlState, detail]}; the constraint name is in the detail and in the\n")
                .addJavadoc("driver's message.\n")
                .addStatement("boolean violation = false")
                .addStatement("$T text = new $T()", StringBuilder.class, StringBuilder.class)
                .addStatement("$T cause = $L", Throwable.class, FAILURE)
                .beginControlFlow("for (int depth = 0; cause != null && depth < CAUSE_DEPTH; depth++)")
                .addStatement("text.append(cause.getMessage()).append('\\n')")
                .beginControlFlow("if (cause instanceof $T persistence\n"
                                + "$>$>&& $T.EX_PERS_5003.equals(persistence.errorCode())$<$<)",
                        PERSISTENCE_PROVIDER_EXCEPTION, KERNEL_ERROR_CODES)
                .addStatement("Object[] args = persistence.rawArgs()")
                .addStatement("violation |= args.length > 0 && FOREIGN_KEY_STATES.contains(args[0])")
                .beginControlFlow("for (Object arg : args)")
                .addStatement("text.append(arg).append('\\n')")
                .endControlFlow()
                .endControlFlow()
                .addStatement("cause = cause.getCause()")
                .endControlFlow()
                .addStatement("return violation ? text.toString().toLowerCase($T.ROOT) : null", LOCALE)
                .build();
    }

    /**
     * The constraint names are tested longest first, so a name that is a prefix of another —
     * {@code fk_t_customer_id} and {@code fk_t_customer_id2} — cannot claim the other's violation.
     */
    private static MethodSpec refusedReferenceMethod(DomainMetadata metadata, List<DomainMetadata> build,
                                                     ClassName referenceNotFound) {
        List<ForeignKeys.Reference> references = new ArrayList<>(ForeignKeys.outgoing(metadata, build));
        references.sort(Comparator.comparingInt((ForeignKeys.Reference r) -> r.constraint().length())
                .reversed()
                .thenComparing(ForeignKeys.Reference::constraint));
        MethodSpec.Builder method = MethodSpec.methodBuilder(REFUSED_REFERENCE_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(RUNTIME_EXCEPTION)
                .addParameter(RUNTIME_EXCEPTION, FAILURE)
                .addJavadoc("The exception a failed write leaves with: {@link $T} naming the\n",
                        referenceNotFound)
                .addJavadoc("relationship when the write violated one of this table's foreign keys, the\n")
                .addJavadoc("failure itself otherwise.\n")
                .addStatement("String violation = $L($L)", VIOLATION_METHOD, FAILURE)
                .beginControlFlow("if (violation == null)")
                .addStatement("return $L", FAILURE)
                .endControlFlow();
        for (ForeignKeys.Reference reference : references) {
            method.beginControlFlow("if (violation.contains($S))", reference.constraint())
                    .addStatement("return new $T($S, $L)", referenceNotFound, reference.relation(), FAILURE)
                    .endControlFlow();
        }
        return method.addStatement("return $L", FAILURE).build();
    }
}
