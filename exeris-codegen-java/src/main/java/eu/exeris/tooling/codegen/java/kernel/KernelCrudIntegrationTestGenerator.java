package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.tooling.codegen.core.access.RouteRequirements;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.kernel.KernelIntegrationSamples.Member;
import eu.exeris.tooling.codegen.java.kernel.KernelIntegrationSamples.Value;
import eu.exeris.tooling.codegen.java.kernel.KernelValidationRules.FieldRules;
import eu.exeris.tooling.codegen.java.kernel.KernelValidationRules.Kind;
import eu.exeris.tooling.codegen.java.kernel.KernelValidationRules.Rule;
import eu.exeris.tooling.codegen.java.support.BlobFields;
import eu.exeris.tooling.codegen.java.support.ForeignKeys;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import eu.exeris.tooling.codegen.java.support.PrimaryKeys;
import eu.exeris.tooling.codegen.java.support.ServerOwnedFields;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Emits {@code <Entity>CrudIT}, the generated integration test of one entity's CRUD routes on the
 * booted application (ADR-058 Amendment 1).
 *
 * <p>Each case asserts statuses and identifiers over HTTP, never SQL text or emitted source, and is
 * emitted only where its precondition holds:
 *
 * <ul>
 *   <li>always: a {@code GET} of an absent row is {@code 404}, and of a malformed key {@code 400};</li>
 *   <li>when a valid create body can be synthesized ({@link KernelIntegrationSamples}): the round
 *       trip — {@code POST} is {@code 201} with a key, {@code GET} is {@code 200} with the posted
 *       values, the list carries the key, {@code PUT} is {@code 200}, {@code DELETE} is
 *       {@code 204}, after which the row is {@code 404} and gone from the list — and the
 *       {@code @Validation} pairs: a value on each declared bound is accepted, and one past it, or
 *       a required field left out, is {@code 400}. A floating-point bound, a pattern-constrained
 *       field and a unique column are not paired, since the probe would not sit exactly on the bound,
 *       could not be synthesized, or would not be unique;</li>
 *   <li>for a versioned entity: a second {@code PUT} carrying the version the first one replaced
 *       is {@code 409} (ADR-076);</li>
 *   <li>for each {@code MANY_TO_ONE} relationship to an entity of the build: a {@code POST}
 *       naming an absent row is {@code 422} with a problem body naming the relationship, and,
 *       where the referenced entity's delete is restricted, a {@code DELETE} of a row still
 *       referenced is {@code 409} (ADR-076 Amendment 1). The referenced row is created first,
 *       through its own {@code POST}.</li>
 * </ul>
 *
 * <p>A required field with no synthesizable value, a required relationship whose target cannot be
 * created on its own, and a required field only the server sets each suppress every case that
 * creates a row. When the entity's routes demand an identity, every request carries a token with
 * the first declared scope.
 *
 * @since 0.11
 */
public final class KernelCrudIntegrationTestGenerator {

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName DISPLAY_NAME = ClassName.get("org.junit.jupiter.api", "DisplayName");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName STRING = ClassName.get(String.class);

    private static final String PATH = "PATH";
    private static final String KEY = "KEY";
    private static final String ABSENT_ID = "ABSENT_ID";
    private static final String TARGET_SUFFIX = "Target";
    private static final String BEARER = "bearer()";
    private static final String BEARER_FOR = "bearerFor";
    private static final String CREATE = "create";
    private static final String POST_ROW = "postRow";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String PUT = "PUT";
    private static final String DELETE = "DELETE";
    private static final String SEND_BY_ID = "$T $N = $T.send($S, $N + \"/\" + id, $L, $L)";
    private static final String SEND_LIST = "$T $N = $T.send($S, $N + $S, null, $L)";
    private static final String SEND_TO_PATH = "$T $N = $T.send($S, $N, $L, $L)";
    private static final String LISTED_LOCAL = "listed";
    private static final String LISTED = "$T.assertThat($T.listed($N, id, $L)).as($S).$L()";
    private static final String STATUS_IS = "$T.assertThat($N.status()).as($N.body()).isEqualTo($L)";
    private static final String STATUS_OK = "200";
    private static final String STATUS_CREATED = "201";
    private static final String STATUS_NO_CONTENT = "204";
    private static final String STATUS_BAD_REQUEST = "400";
    private static final String STATUS_NOT_FOUND = "404";
    private static final String STATUS_CONFLICT = "409";
    private static final String STATUS_UNPROCESSABLE = "422";
    private static final String LIST_QUERY = "?size=100";
    private static final String NO_BODY = "null";
    private static final String RESPONSE = "response";
    private static final String REFUSED = "refused";
    private static final String CREATED = "created";

    /**
     * Creates the generator. It keeps no state, so one instance serves every entity.
     */
    public KernelCrudIntegrationTestGenerator() {
        // no state to initialise
    }

    /** The types one emitted class works with. */
    private record Context(DomainMetadata entity, List<DomainMetadata> build, ClassName http,
                           ClassName response, ClassName tokens) {
    }

    /** One request body: its members, and the required relationships whose target rows it names. */
    private record Body(List<Member> members, List<ForeignKeys.Reference> targets) {
    }

    /**
     * Emits the integration test of {@code metadata}'s CRUD routes.
     *
     * @param metadata    the entity
     * @param basePackage the build's base package, which holds the support classes
     * @param build       every entity of the build, {@code metadata} included
     * @return the emitted test
     */
    public GeneratedFile generate(DomainMetadata metadata, String basePackage, List<DomainMetadata> build) {
        KernelApplicationGenerator.requireDomainPackages(List.of(metadata));
        String domainPackage = metadata.packageName();
        String packageName = domainPackage.substring(0, domainPackage.lastIndexOf(".domain")) + ".handler";
        String className = metadata.entityName() + "CrudIT";
        String support = KernelTestSupportGenerator.supportPackage(basePackage);
        ClassName http = ClassName.get(support, KernelIntegrationSupportGenerator.INTEGRATION_HTTP);
        Context context = new Context(metadata, build, http, http.nestedClass("Response"),
                ClassName.get(support, KernelIntegrationSupportGenerator.INTEGRATION_TOKENS));

        TypeSpec.Builder type = KernelScaffold.publicClass(className)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated integration test of the {@code $L} routes of {@code $L}, on the booted\n",
                        metadata.effectivePath(), metadata.entityName())
                .addJavadoc("application (ADR-058 Amendment 1). Failsafe runs it, by name.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, H2 and a kernel driver at\n")
                .addJavadoc("test runtime, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addAnnotation(displayName(metadata.entityName() + " over HTTP on the booted application"))
                .addField(privateConstant(PATH, CodeBlock.of("$S", metadata.effectivePath())))
                .addField(privateConstant(KEY, CodeBlock.of("$S", PrimaryKeys.field(metadata))))
                .addField(privateConstant(ABSENT_ID, CodeBlock.of("$S", KernelTestSamples.FIXED_ID)));

        type.addMethod(bodylessCase("anAbsentRowIsNotFound", "a GET of a row that does not exist is 404",
                context, CodeBlock.of("$N + \"/\" + $N", PATH, ABSENT_ID), STATUS_NOT_FOUND));
        type.addMethod(bodylessCase("aMalformedKeyIsBadRequest", "a GET of a key that is not a UUID is 400",
                context, CodeBlock.of("$N + $S", PATH, "/not-a-uuid"), STATUS_BAD_REQUEST));

        boolean creatable = createBody(metadata, build, http).map(create -> {
            addCreationCases(type, context, create);
            return true;
        }).orElse(false);
        type.addMethod(bearerMethod(context, "bearer", metadata));
        if (creatable) {
            type.addMethod(postRowMethod(context));
        }
        return new GeneratedFile(packageName, className, KernelScaffold.render(packageName, type.build()),
                ArtifactType.TEST);
    }

    private static void addCreationCases(TypeSpec.Builder type, Context context, Body create) {
        DomainMetadata entity = context.entity();
        Body update = members(entity, context.build(), context.http(),
                ServerOwnedFields.notInUpdateBody(entity), true).orElse(create);
        type.addMethod(roundTrip(context, create, update));
        if (entity.versioned()) {
            type.addMethod(staleVersion(context, create, update));
        }
        addReferenceCases(type, context, create);
        addValidationCases(type, context, create);
        addTargetCreators(type, context);
    }

    private static void addReferenceCases(TypeSpec.Builder type, Context context, Body create) {
        for (ForeignKeys.Reference reference : ForeignKeys.outgoing(context.entity(), context.build())) {
            type.addMethod(absentReference(context, create, reference));
            DomainMetadata target = byName(context.build(), reference.targetEntity());
            if (deleteIsRestricted(reference, target, context.build())
                    && standaloneBody(target, context.build(), context.http()).isPresent()) {
                type.addMethod(referencedDelete(context, create, reference, target));
            }
        }
    }

    private static void addValidationCases(TypeSpec.Builder type, Context context, Body create) {
        for (FieldRules rules : KernelValidationRules.onCreate(context.entity())) {
            FieldMetadata field = rules.field();
            if (field.unique()) {
                continue;
            }
            for (Rule rule : rules.rules()) {
                validationCase(context, create, field, rule).ifPresent(type::addMethod);
            }
        }
    }

    /** One creator, and its bearer, per referenced entity whose row can be created on its own. */
    private static void addTargetCreators(TypeSpec.Builder type, Context context) {
        Set<String> targets = new TreeSet<>();
        for (ForeignKeys.Reference reference : ForeignKeys.outgoing(context.entity(), context.build())) {
            targets.add(reference.targetEntity());
        }
        for (String name : targets) {
            DomainMetadata target = byName(context.build(), name);
            standaloneBody(target, context.build(), context.http()).ifPresent(body -> {
                type.addMethod(targetCreator(context, target, body));
                type.addMethod(bearerMethod(context, BEARER_FOR + target.entityName(), target));
            });
        }
    }

    // ------------------------------------------------------------------ cases

    private static MethodSpec bodylessCase(String name, String display, Context context, CodeBlock path,
                                           String status) {
        return testMethod(name, display)
                .addStatement("$T $N = $T.send($S, $L, null, $L)", context.response(), RESPONSE, context.http(),
                        GET, path, BEARER)
                .addStatement(STATUS_IS, ASSERTIONS, RESPONSE, RESPONSE, status)
                .build();
    }

    private static MethodSpec roundTrip(Context context, Body create, Body update) {
        MethodSpec.Builder method = testMethod("aRowRoundTripsThroughEveryRoute",
                "POST 201, GET 200 with the posted values, listed, PUT 200, DELETE 204, then 404 and unlisted");
        method.addCode(targets(context, create, null));
        method.addStatement("String id = $N($N, $L)", POST_ROW, PATH, KernelIntegrationSamples.body(create.members()));
        send(method, context, "read", GET, CodeBlock.of(NO_BODY), STATUS_OK);
        for (Member member : create.members()) {
            if (member.value().fixed() != null && member.value().assertable() && plainProperty(member.name())) {
                method.addStatement("$T.assertThat(read.body()).contains($S)", ASSERTIONS,
                        KernelIntegrationSamples.quote(member.name()) + ":" + member.value().fixed());
            }
        }
        method.addStatement(SEND_LIST, context.response(), LISTED_LOCAL, context.http(), GET, PATH, LIST_QUERY, BEARER);
        method.addStatement(STATUS_IS, ASSERTIONS, LISTED_LOCAL, LISTED_LOCAL, STATUS_OK);
        method.addStatement(LISTED, ASSERTIONS, context.http(), PATH, BEARER, LISTED_LOCAL, "isTrue");
        send(method, context, "updated", PUT,
                KernelIntegrationSamples.body(withVersion(context.entity(), update.members())), STATUS_OK);
        send(method, context, "deleted", DELETE, CodeBlock.of(NO_BODY), STATUS_NO_CONTENT);
        send(method, context, "gone", GET, CodeBlock.of(NO_BODY), STATUS_NOT_FOUND);
        method.addStatement(LISTED, ASSERTIONS, context.http(), PATH, BEARER, "listed after the delete", "isFalse");
        return method.build();
    }

    /** One request to the row {@code id} names, and the assertion on its status. */
    private static void send(MethodSpec.Builder method, Context context, String local, String verb,
                             CodeBlock body, String status) {
        method.addStatement(SEND_BY_ID, context.response(), local, context.http(), verb, PATH, body, BEARER);
        method.addStatement(STATUS_IS, ASSERTIONS, local, local, status);
    }

    private static MethodSpec staleVersion(Context context, Body create, Body update) {
        MethodSpec.Builder method = testMethod("anUpdateCarryingAReplacedVersionIsConflict",
                "a PUT carrying the version an earlier PUT replaced is 409");
        method.addCode(targets(context, create, null));
        method.addStatement("String id = $N($N, $L)", POST_ROW, PATH, KernelIntegrationSamples.body(create.members()));
        CodeBlock body = KernelIntegrationSamples.body(withVersion(context.entity(), update.members()));
        send(method, context, "first", PUT, body, STATUS_OK);
        send(method, context, "stale", PUT, body, STATUS_CONFLICT);
        return method.build();
    }

    private static MethodSpec absentReference(Context context, Body create, ForeignKeys.Reference reference) {
        String relation = reference.relation();
        MethodSpec.Builder method = testMethod(
                lowerCamel(relation) + "NamingAnAbsentRowIsUnprocessable",
                "a POST whose " + relation + " names a row that does not exist is 422, naming the relationship");
        method.addCode(targets(context, create, relation));
        List<Member> members = replace(create.members(), relation,
                Value.of(KernelIntegrationSamples.quote(KernelTestSamples.FIXED_ID), false));
        method.addStatement(SEND_TO_PATH, context.response(), RESPONSE, context.http(), POST, PATH,
                KernelIntegrationSamples.body(members), BEARER);
        method.addStatement(STATUS_IS, ASSERTIONS, RESPONSE, RESPONSE, STATUS_UNPROCESSABLE);
        method.addStatement("$T.assertThat($N.body()).contains($S)", ASSERTIONS, RESPONSE,
                "\"field\":" + KernelIntegrationSamples.quote(relation));
        return method.build();
    }

    private static MethodSpec referencedDelete(Context context, Body create, ForeignKeys.Reference reference,
                                               DomainMetadata target) {
        String relation = reference.relation();
        MethodSpec.Builder method = testMethod(
                lowerCamel(relation) + "TargetStillReferencedCannotBeDeleted",
                "a DELETE of the row " + relation + " still names is 409");
        method.addStatement("String referenced = $L$L()", CREATE, target.entityName());
        method.addCode(targets(context, create, relation));
        List<Member> members = replace(create.members(), relation, Value.runtime(
                CodeBlock.of("$T.jsonString(referenced)", context.http())));
        method.addStatement("$N($N, $L)", POST_ROW, PATH, KernelIntegrationSamples.body(members));
        method.addStatement("$T $N = $T.send($S, $S + referenced, null, $L$L())", context.response(), RESPONSE,
                context.http(), DELETE, target.effectivePath() + "/", BEARER_FOR, target.entityName());
        method.addStatement(STATUS_IS, ASSERTIONS, RESPONSE, RESPONSE, STATUS_CONFLICT);
        return method.build();
    }

    private static Optional<MethodSpec> validationCase(Context context, Body create, FieldMetadata field,
                                                       Rule rule) {
        if (rule.kind() == Kind.NOT_NULL) {
            return Optional.of(validationMethod(context, create, field, "LeftOutIsBadRequest",
                    "a POST leaving out the required " + field.name() + " is 400", null, null));
        }
        if (field.pattern() != null && !field.pattern().isEmpty()) {
            return Optional.empty();
        }
        String type = KernelValidationRules.simpleTypeName(field.type());
        Optional<String[]> probes = probes(field, type, rule);
        if (probes.isEmpty()) {
            return Optional.empty();
        }
        String kind = rule.kind().name().toLowerCase(Locale.ROOT);
        return Optional.of(validationMethod(context, create, field, "On" + NameCasing.pascal(kind) + "Bound",
                "a POST with " + field.name() + " on its " + kind.replace('_', ' ')
                        + " bound is 201, and one past it is 400",
                probes.get()[0], probes.get()[1]));
    }

    private static MethodSpec validationMethod(Context context, Body create, FieldMetadata field, String suffix,
                                               String display, String accepted, String refused) {
        MethodSpec.Builder method = testMethod(lowerCamel(field.name()) + suffix, display);
        method.addCode(targets(context, create, accepted == null ? field.name() : null));
        if (accepted != null) {
            List<Member> onBound = replace(create.members(), field.name(), Value.of(accepted, false));
            method.addStatement("$N($N, $L)", POST_ROW, PATH, KernelIntegrationSamples.body(onBound));
        }
        List<Member> refusedMembers = refused == null
                ? without(create.members(), field.name())
                : replace(create.members(), field.name(), Value.of(refused, false));
        method.addStatement(SEND_TO_PATH, context.response(), REFUSED, context.http(), POST, PATH,
                KernelIntegrationSamples.body(refusedMembers), BEARER);
        method.addStatement(STATUS_IS, ASSERTIONS, REFUSED, REFUSED, STATUS_BAD_REQUEST);
        return method.build();
    }

    /**
     * The JSON values on and past one numeric or length bound, or nothing where the pair would not
     * sit exactly on the bound or the accepted value would break another declared bound.
     */
    private static Optional<String[]> probes(FieldMetadata field, String type, Rule rule) {
        if (KernelValidationRules.isStringType(type)) {
            return stringProbes(field, rule);
        }
        if (KernelIntegrationSamples.isFloating(type) || rule.bound() == null) {
            return Optional.empty();
        }
        long bound = rule.bound();
        long min = field.min() == null ? Long.MIN_VALUE : field.min();
        long max = field.max() == null ? Long.MAX_VALUE : field.max();
        if (bound < min || bound > max || bound == Long.MIN_VALUE || bound == Long.MAX_VALUE) {
            return Optional.empty();
        }
        long past = rule.kind() == Kind.MIN ? bound - 1 : bound + 1;
        return Optional.of(new String[]{Long.toString(bound), Long.toString(past)});
    }

    private static Optional<String[]> stringProbes(FieldMetadata field, Rule rule) {
        if (rule.kind() != Kind.MIN_LENGTH && rule.kind() != Kind.MAX_LENGTH) {
            return Optional.empty();
        }
        int bound = rule.bound().intValue();
        int min = field.minLength() == null ? 0 : field.minLength();
        int max = field.maxLength() == null ? Integer.MAX_VALUE : field.maxLength();
        int past = rule.kind() == Kind.MIN_LENGTH ? bound - 1 : bound + 1;
        if (bound < min || bound > max || past < 0) {
            return Optional.empty();
        }
        return Optional.of(new String[]{
                KernelIntegrationSamples.quote(KernelIntegrationSamples.text(bound)),
                KernelIntegrationSamples.quote(KernelIntegrationSamples.text(past))});
    }

    // ------------------------------------------------------------------ helpers emitted per class

    private static MethodSpec bearerMethod(Context context, String name, DomainMetadata entity) {
        return MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(STRING)
                .addStatement("return $L", bearerExpression(context, entity))
                .build();
    }

    /** A token where the entity's routes demand an identity, with the first declared scope. */
    private static CodeBlock bearerExpression(Context context, DomainMetadata entity) {
        RouteRequirements.Requirement requirement = RouteRequirements.forEntity(entity);
        if (requirement == null || !requirement.demandsIdentity()) {
            return CodeBlock.of(NO_BODY);
        }
        return requirement.scopes().isEmpty()
                ? CodeBlock.of("$T.bearer()", context.tokens())
                : CodeBlock.of("$T.bearer($S)", context.tokens(), requirement.scopes().getFirst());
    }

    private static MethodSpec postRowMethod(Context context) {
        return MethodSpec.methodBuilder(POST_ROW)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(STRING)
                .addParameter(STRING, "path")
                .addParameter(STRING, "json")
                .addStatement("$T $N = $T.send($S, path, json, $L)", context.response(), CREATED, context.http(),
                        POST, BEARER)
                .addStatement(STATUS_IS, ASSERTIONS, CREATED, CREATED, STATUS_CREATED)
                .addStatement("return $T.idOf($N.body(), $N)", context.http(), CREATED, KEY)
                .build();
    }

    private static MethodSpec targetCreator(Context context, DomainMetadata target, Body body) {
        return MethodSpec.methodBuilder(CREATE + target.entityName())
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(STRING)
                .addStatement("$T $N = $T.send($S, $S, $L, $L$L())", context.response(), CREATED, context.http(),
                        POST, target.effectivePath(), KernelIntegrationSamples.body(body.members()), BEARER_FOR,
                        target.entityName())
                .addStatement(STATUS_IS, ASSERTIONS, CREATED, CREATED, STATUS_CREATED)
                .addStatement("return $T.idOf($N.body(), $S)", context.http(), CREATED, PrimaryKeys.field(target))
                .build();
    }

    /** Creates the target rows of the body's required relationships, except {@code skipped}'s. */
    private static CodeBlock targets(Context context, Body body, String skipped) {
        CodeBlock.Builder code = CodeBlock.builder();
        for (ForeignKeys.Reference reference : body.targets()) {
            if (!reference.relation().equals(skipped)) {
                code.addStatement("String $L = $L$L()", reference.relation() + TARGET_SUFFIX, CREATE,
                        byName(context.build(), reference.targetEntity()).entityName());
            }
        }
        return code.build();
    }

    // ------------------------------------------------------------------ bodies

    /** A valid create body for {@code entity}, or nothing when one cannot be synthesized. */
    private static Optional<Body> createBody(DomainMetadata entity, List<DomainMetadata> build, ClassName http) {
        if (!ServerOwnedFields.setByServerOnCreate(entity).isEmpty()) {
            return Optional.empty();
        }
        return members(entity, build, http, ServerOwnedFields.notInCreateBody(entity), true);
    }

    /**
     * A valid create body for {@code entity} that names no row the test must create first, or
     * nothing when the entity has a required relationship or no body can be synthesized.
     */
    private static Optional<Body> standaloneBody(DomainMetadata entity, List<DomainMetadata> build,
                                                 ClassName http) {
        if (!ServerOwnedFields.setByServerOnCreate(entity).isEmpty()) {
            return Optional.empty();
        }
        return members(entity, build, http, ServerOwnedFields.notInCreateBody(entity), false);
    }

    /**
     * The members of a valid body: each field the body carries that has a synthesizable value, and
     * each required relationship naming a target row the test creates when {@code withTargets}
     * allows it; nothing when a required field has no value.
     */
    private static Optional<Body> members(DomainMetadata entity, List<DomainMetadata> build, ClassName http,
                                          Set<String> excluded, boolean withTargets) {
        Map<String, ForeignKeys.Reference> references = new LinkedHashMap<>();
        for (ForeignKeys.Reference reference : ForeignKeys.outgoing(entity, build)) {
            references.put(reference.relation(), reference);
        }
        List<Member> members = new ArrayList<>();
        List<ForeignKeys.Reference> targets = new ArrayList<>();
        for (FieldMetadata field : entity.fields()) {
            if (!carried(entity, field, excluded)) {
                continue;
            }
            ForeignKeys.Reference reference = references.get(field.name());
            Optional<Value> value = reference == null
                    ? KernelIntegrationSamples.valid(field, http)
                    : targetValue(reference, field, build, http, withTargets);
            if (value.isPresent()) {
                members.add(new Member(field.name(), value.get()));
                if (reference != null) {
                    targets.add(reference);
                }
            } else if (field.required()) {
                return Optional.empty();
            }
        }
        return Optional.of(new Body(List.copyOf(members), List.copyOf(targets)));
    }

    /** Whether a request body carries {@code field}: a column, not computed, neither key nor version. */
    private static boolean carried(DomainMetadata entity, FieldMetadata field, Set<String> excluded) {
        return !excluded.contains(field.name()) && BlobFields.isColumn(field) && !field.computed()
                && !PrimaryKeys.isKey(entity, field.name())
                && !(entity.versioned() && field.name().equals(ServerOwnedFields.versionField(entity)));
    }

    /**
     * The value of a relationship field: for a required relationship, the key of a target row the
     * test creates first, when {@code withTargets} allows it and the target can be created on its
     * own. An optional relationship is left out.
     */
    private static Optional<Value> targetValue(ForeignKeys.Reference reference, FieldMetadata field,
                                               List<DomainMetadata> build, ClassName http, boolean withTargets) {
        if (!field.required() || !withTargets) {
            return Optional.empty();
        }
        DomainMetadata target = byName(build, reference.targetEntity());
        if (standaloneBody(target, build, http).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Value.runtime(CodeBlock.of("$T.jsonString($L)", http,
                reference.relation() + TARGET_SUFFIX)));
    }

    private static List<Member> withVersion(DomainMetadata entity, List<Member> members) {
        if (!entity.versioned()) {
            return members;
        }
        List<Member> versioned = new ArrayList<>(members);
        versioned.add(new Member(ServerOwnedFields.versionField(entity), Value.of("0", false)));
        return versioned;
    }

    private static List<Member> replace(List<Member> members, String name, Value value) {
        List<Member> replaced = new ArrayList<>();
        boolean found = false;
        for (Member member : members) {
            if (member.name().equals(name)) {
                replaced.add(new Member(name, value));
                found = true;
            } else {
                replaced.add(member);
            }
        }
        if (!found) {
            replaced.add(new Member(name, value));
        }
        return replaced;
    }

    private static List<Member> without(List<Member> members, String name) {
        return members.stream().filter(member -> !member.name().equals(name)).toList();
    }

    /** Whether the referenced entity refuses the delete of a row still referenced through {@code reference}. */
    private static boolean deleteIsRestricted(ForeignKeys.Reference reference, DomainMetadata target,
                                              List<DomainMetadata> build) {
        RelationshipMetadata.CascadeType cascade = reference.relationship().cascade();
        return ForeignKeys.refusesReferencedDelete(target, build)
                && cascade != RelationshipMetadata.CascadeType.ALL
                && cascade != RelationshipMetadata.CascadeType.REMOVE;
    }

    /**
     * Whether the JSON codec names the property as the field is named: the bean convention renames
     * a property whose second character is upper-case.
     */
    private static boolean plainProperty(String name) {
        return name.length() < 2 || !Character.isUpperCase(name.charAt(1));
    }

    private static String lowerCamel(String name) {
        String pascal = NameCasing.pascal(name);
        return pascal.substring(0, 1).toLowerCase(Locale.ROOT) + pascal.substring(1);
    }

    private static DomainMetadata byName(List<DomainMetadata> build, String entityName) {
        for (DomainMetadata domain : build) {
            if (domain.entityName().equals(entityName)) {
                return domain;
            }
        }
        throw new IllegalArgumentException("no entity " + entityName + " in the build");
    }

    private static MethodSpec.Builder testMethod(String name, String display) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addAnnotation(displayName(display));
    }

    private static AnnotationSpec displayName(String text) {
        return AnnotationSpec.builder(DISPLAY_NAME).addMember("value", "$S", text).build();
    }

    private static FieldSpec privateConstant(String name, CodeBlock value) {
        return FieldSpec.builder(STRING, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(value)
                .build();
    }
}
