package eu.exeris.tooling.diagnostics;

/**
 * Registry of every stable diagnostic identifier exeris-tooling prints: the annotation processor's
 * {@code javac} diagnostics, the warnings the code-generation pipeline logs, and the
 * {@code exeris-codegen-maven-plugin} failures and warnings. ADR-095 is the contract this type
 * implements.
 *
 * <h2>Format</h2>
 * <pre>
 * EXT     – exeris-tooling prefix, distinct from the kernel's EX- namespace
 * [AREA]  – PROC (annotation processor, inside javac), PLUG (Maven plugin goals),
 *           GEN (code-generation pipeline); TS is reserved for the TypeScript emitter
 * [ID]    – 4-digit identifier: 1xxx in PROC, 2xxx in PLUG, 3xxx in GEN, 4xxx reserved for TS
 * </pre>
 *
 * <p>Within an area the hundreds digit groups identifiers by what they were allocated for. In
 * {@code PROC} and {@code GEN} it is the kind: {@code x0xx} errors and refusals, {@code x1xx}
 * warnings on an ordinary build, {@code x2xx} the {@code -Aexeris.strict} audit, {@code x9xx}
 * {@code -Aexeris.verbose} notes. In {@code PLUG} it is the goal: {@code 20xx}
 * {@code exeris:generate}, {@code 21xx} {@code exeris:detach}, {@code 22xx}
 * {@code exeris:verify-capabilities}, {@code 23xx} {@code exeris:verify-runtime}. The group is
 * where an identifier was allocated, not a property it keeps: a diagnostic whose severity changes
 * keeps its identifier. So is the area: an event that two layers report carries the one
 * identifier allocated for it, whichever layer prints it.
 *
 * <h2>Stability</h2>
 * <p>An identifier names one meaning for as long as it exists. The message text after it may be
 * reworded freely; the identifier is what a tool matches on. An identifier is never renumbered
 * and never reused for a different meaning; one that stops being emitted is retired, and its
 * number stays unallocated.
 *
 * <h2>Printed shape</h2>
 * <p>Every message carries its identifier in one fixed position, through {@link #format(String)}:
 * <pre>[Exeris] EXT-PROC-1001: &lt;message&gt;</pre>
 * The {@code [Exeris] } prefix is not always first on the line: {@code javac} puts its own
 * {@code warning: } ahead of it, and Maven prints a goal failure as
 * {@code [ERROR] Failed to execute goal …: [Exeris] EXT-PLUG-2001: …}. A consumer finds an
 * identifier anywhere in a line with the regular expression
 * {@code \[Exeris\] (EXT-[A-Z]+-\d{4}): }.
 *
 * <h2>Placement</h2>
 * <p>This type is the whole of the {@code exeris-diagnostics} artefact, which depends on nothing
 * and uses nothing beyond {@code java.lang}, so every tooling module can depend on it, the
 * build-time-only annotation processor included.
 *
 * <p>{@code docs/diagnostics.md} lists every identifier with what to do about it; a test keeps
 * that table and this registry in step.
 *
 * @since 0.9
 */
public enum DiagnosticId {

    // -----------------------------------------------------------------------
    // EXT-PROC-10xx — declaration refusals (javac ERROR)
    // -----------------------------------------------------------------------

    /**
     * {@code @ExerisDomain}, {@code @Saga}, {@code @CapabilityModule} or {@code @View} is on an
     * element that is not a class or type.
     */
    ANNOTATION_ON_WRONG_ELEMENT("EXT-PROC-1001",
            "A type-level Exeris annotation is on an element that is not a class or type."),

    /** Extracting an enum, entity, saga, capability module or view threw; nothing was written for it. */
    PROCESSING_FAILURE("EXT-PROC-1002",
            "Extracting metadata for an element threw an exception; no metadata was written for it."),

    /** {@code dataScope} and the deprecated {@code tenantScoped} name different tiers. */
    DATA_SCOPE_CONTRADICTS_TENANT_SCOPED("EXT-PROC-1003",
            "@ExerisDomain declares dataScope and tenantScoped with values that contradict each other."),

    /** {@code @RouteAccess(PUBLIC)} on a type or action method that also declares permissions. */
    PUBLIC_ROUTE_WITH_PERMISSIONS("EXT-PROC-1004",
            "@RouteAccess(PUBLIC) is on a type or action method that also declares permissions."),

    /** An action method declares permissions and inherits {@code @RouteAccess(PUBLIC)} from its type. */
    ACTION_PERMISSIONS_ON_INHERITED_PUBLIC_ROUTE("EXT-PROC-1005",
            "An action method declares permissions but inherits @RouteAccess(PUBLIC) from its type."),

    /** A {@code DataScope.UNIVERSE} entity has no owning tenant field. */
    UNIVERSE_WITHOUT_OWNER_FIELD("EXT-PROC-1006",
            "A DataScope.UNIVERSE entity declares no owning tenant field."),

    /** A {@code DataScope.UNIVERSE} entity has no {@code @SharedScope} field. */
    UNIVERSE_WITHOUT_SHARED_SCOPE("EXT-PROC-1007",
            "A DataScope.UNIVERSE entity declares no @SharedScope field."),

    /** The {@code @SharedScope} field is neither {@code UUID} nor {@code String}. */
    SHARED_SCOPE_WRONG_TYPE("EXT-PROC-1008",
            "The @SharedScope field is neither java.util.UUID nor java.lang.String."),

    /** The {@code @SharedScope} field is also the owning tenant field. */
    SHARED_SCOPE_ON_OWNER_FIELD("EXT-PROC-1009",
            "The @SharedScope field is also the entity's owning tenant field."),

    /** The {@code @SharedScope} field is declared required. */
    SHARED_SCOPE_REQUIRED("EXT-PROC-1010",
            "The @SharedScope field is declared required."),

    /** One system-field role marker ({@code @TenantId}, {@code @Version}, …) is on more than one field. */
    SYSTEM_FIELD_ROLE_REPEATED("EXT-PROC-1011",
            "A system-field role marker is declared on more than one field."),

    /** A system-field role marker and the matching {@code @ExerisDomain} override name different fields. */
    SYSTEM_FIELD_ROLE_CONFLICTS_WITH_OVERRIDE("EXT-PROC-1012",
            "A system-field role marker and the matching @ExerisDomain override name different fields."),

    /** {@code @GraphEdge} is repeated on one field. */
    GRAPH_EDGE_REPEATED_ON_FIELD("EXT-PROC-1013",
            "@GraphEdge is declared more than once on one field."),

    /**
     * {@code @ExerisDomain(realTimeApi = true)} on a {@code TENANT} or {@code UNIVERSE} entity: kernel
     * events carry no isolation key, so the generated live view would send every tenant's events to
     * every subscriber.
     */
    REAL_TIME_API_ON_TENANT_PARTITIONED("EXT-PROC-1014",
            "@ExerisDomain(realTimeApi = true) is on a TENANT or UNIVERSE entity; its live view cannot be isolated per tenant."),

    /**
     * An {@code @ExerisDomain} type declares no field {@code id}, the primary key every generated
     * artefact identifies a row by.
     */
    ENTITY_WITHOUT_ID_FIELD("EXT-PROC-1015",
            "An @ExerisDomain type declares no field 'id'."),

    // -----------------------------------------------------------------------
    // EXT-PROC-11xx — warnings on an ordinary build (javac WARNING)
    // -----------------------------------------------------------------------

    /** {@code @ExerisDomain.tenantScoped} is deprecated; it is read as a fallback for this build. */
    TENANT_SCOPED_DEPRECATED("EXT-PROC-1101",
            "@ExerisDomain.tenantScoped is deprecated for removal; it is read as a fallback."),

    /** A deprecated {@code @Validation} attribute is set; it is read as a fallback for this build. */
    VALIDATION_ATTRIBUTE_DEPRECATED("EXT-PROC-1102",
            "A deprecated @Validation attribute is set; it is read as a fallback."),

    /** {@code @Validation.validateOn} holds a value other than CREATE or UPDATE; it is dropped. */
    VALIDATE_ON_UNRECOGNISED("EXT-PROC-1103",
            "@Validation.validateOn holds an unrecognised value, which is dropped."),

    // EXT-PROC-1104 is retired (docs/diagnostics.md, "Retired identifiers") and is never allocated again.

    /** {@code @SharedScope} on an entity that is not {@code DataScope.UNIVERSE}; it is not recorded. */
    SHARED_SCOPE_OUTSIDE_UNIVERSE("EXT-PROC-1105",
            "@SharedScope is on an entity that is not DataScope.UNIVERSE, so it has no effect."),

    /** {@code @Bind(source = STATIC | NONE)} carries attributes that are ignored. */
    BIND_WITHOUT_SOURCE_IGNORED("EXT-PROC-1106",
            "@Bind with source STATIC or NONE carries attributes that are ignored."),

    /** {@code @Action(streaming = true)}: the generated stream route does not run the action. */
    STREAMING_ACTION_NOT_INVOKED("EXT-PROC-1107",
            "@Action(streaming = true): the generated stream route does not run the action."),

    // -----------------------------------------------------------------------
    // EXT-PROC-12xx — the -Aexeris.strict audit (javac WARNING, opt-in)
    // -----------------------------------------------------------------------

    /** An annotation attribute is set that no code generator consumes. */
    STRICT_INERT_ATTRIBUTE("EXT-PROC-1201",
            "An annotation attribute is set that no code generator consumes."),

    /** An annotation the processor reads is set, but no code generator consumes it. */
    STRICT_INERT_ANNOTATION("EXT-PROC-1202",
            "An annotation is set that the processor reads but no code generator consumes."),

    /** An SDK annotation is set that the processor never reads. */
    STRICT_UNREAD_ANNOTATION("EXT-PROC-1203",
            "An SDK annotation is set that the processor never reads."),

    // -----------------------------------------------------------------------
    // EXT-PROC-19xx — the -Aexeris.verbose trail (javac NOTE, opt-in)
    // -----------------------------------------------------------------------

    /** Per-element progress: what is being processed and what metadata was written. */
    VERBOSE_PROGRESS("EXT-PROC-1901",
            "Progress trace: an element is being processed, or its metadata was written."),

    /** The source of an element could not be read for the ADR-042 digest; the digest is omitted. */
    VERBOSE_SOURCE_UNREADABLE("EXT-PROC-1902",
            "An element's source could not be read for the ADR-042 digest, which is omitted."),

    // -----------------------------------------------------------------------
    // EXT-PLUG-20xx — exeris:generate
    // -----------------------------------------------------------------------

    /** Generation found no entities but would delete a committed generated tree; it refused. */
    EMPTY_METADATA_REFUSED("EXT-PLUG-2001",
            "No @ExerisDomain metadata was found, and generation refused to delete the committed generated tree."),

    /** Main-source generation failed on I/O. */
    GENERATION_FAILED("EXT-PLUG-2002",
            "Source generation failed reading metadata or writing the generated tree."),

    /** Generated-test emission failed on I/O. */
    TEST_GENERATION_FAILED("EXT-PLUG-2003",
            "Test generation failed reading metadata or writing the generated test tree."),

    // -----------------------------------------------------------------------
    // EXT-PLUG-21xx — exeris:detach
    // -----------------------------------------------------------------------

    /** Detach failed on I/O while moving files or editing the ignore file. */
    DETACH_FAILED("EXT-PLUG-2101",
            "Detach failed moving the generated sources or updating the ignore file."),

    /** Generated files already existed at the detach target and were left in place. */
    DETACH_CONFLICTS("EXT-PLUG-2102",
            "Generated files already existed at the detach target and were left in place."),

    // -----------------------------------------------------------------------
    // EXT-PLUG-22xx — exeris:verify-capabilities (and generate's capability pass)
    // -----------------------------------------------------------------------

    /** The capability graph has an unsatisfied {@code @Requires}, a version mismatch or a cycle. */
    CAPABILITY_GRAPH_UNRESOLVED("EXT-PLUG-2201",
            "The capability graph has an unsatisfied @Requires, a version mismatch or a dependency cycle."),

    /** Capability metadata could not be read. */
    CAPABILITY_VERIFICATION_FAILED("EXT-PLUG-2202",
            "Capability metadata could not be read."),

    /** A capability module's compiled classes reference a type the cap-tier Wall forbids. */
    CAP_TIER_WALL_VIOLATED("EXT-PLUG-2203",
            "A capability module's compiled classes reference a type the cap-tier Wall forbids."),

    /** The cap-tier Wall scan could not read a compiled class. */
    CAP_TIER_WALL_SCAN_FAILED("EXT-PLUG-2204",
            "The cap-tier Wall scan could not read a compiled class."),

    /** The cap-tier Wall guard is switched off with {@code exeris.wall.skip=true}. */
    CAP_TIER_WALL_DISABLED("EXT-PLUG-2205",
            "The cap-tier Wall guard is switched off."),

    /** The build declares capability modules but the Wall found no compiled classes to scan. */
    CAP_TIER_WALL_SCANNED_NOTHING("EXT-PLUG-2206",
            "The build declares capability modules but the cap-tier Wall found no compiled classes to scan."),

    // -----------------------------------------------------------------------
    // EXT-PLUG-23xx — exeris:verify-runtime
    // -----------------------------------------------------------------------

    /** Domain metadata could not be read while checking runtime drivers. */
    RUNTIME_METADATA_UNREADABLE("EXT-PLUG-2301",
            "Domain metadata could not be read while checking for runtime drivers."),

    /** The generated application has no kernel driver on its runtime classpath. */
    RUNTIME_DRIVER_MISSING("EXT-PLUG-2302",
            "The generated application has no kernel driver on its runtime classpath."),

    // -----------------------------------------------------------------------
    // EXT-GEN-30xx — code-generation pipeline errors
    // -----------------------------------------------------------------------

    /** The {@code CodegenMain} command line failed to generate; the cause follows. */
    CLI_GENERATION_FAILED("EXT-GEN-3001",
            "The codegen command line failed to generate code."),

    /** The {@code CodegenMain} command line was given missing or malformed arguments. */
    CLI_ARGUMENTS_INVALID("EXT-GEN-3002",
            "The codegen command line was given missing or malformed arguments."),

    /**
     * An entity field's type is one the generated repository cannot store and read back — a
     * parameterised type other than {@code List<…>}, an array, {@code char} or {@code Character},
     * {@code BigInteger}, or a JDK value type with no static {@code valueOf(String)} such as
     * {@code LocalTime} or {@code Duration}. Nothing is generated.
     */
    FIELD_TYPE_NOT_PERSISTABLE("EXT-GEN-3003",
            "An entity field has a type the generated repository cannot store and read back."),

    // -----------------------------------------------------------------------
    // EXT-GEN-31xx — code-generation pipeline warnings
    // -----------------------------------------------------------------------

    /** Generation found neither domain nor capability metadata in the metadata directory. */
    NO_METADATA_FOUND("EXT-GEN-3101",
            "Generation found no domain or capability metadata to generate from."),

    /** An optional {@code @Requires} has no matching provider; the requirement is skipped. */
    OPTIONAL_REQUIREMENT_UNSATISFIED("EXT-GEN-3102",
            "An optional @Requires has no matching provider, so the requirement is skipped."),

    /**
     * The capability graph did not resolve on metadata that may be stale; the verdict is left to
     * the post-compile {@code exeris:verify-capabilities} gate.
     */
    CAPABILITY_GRAPH_DEFERRED("EXT-GEN-3103",
            "The capability graph did not resolve on possibly stale metadata; the post-compile gate decides.");

    /** Prepended to every message, ahead of the identifier. */
    public static final String PREFIX = "[Exeris] ";

    private final String code;
    private final String meaning;

    DiagnosticId(String code, String meaning) {
        this.code = code;
        this.meaning = meaning;
    }

    /**
     * Returns the stable identifier as it is printed in a message, e.g. {@code EXT-PROC-1001}.
     *
     * @return the identifier, in the form {@code EXT-<STAGE>-<nnnn>}
     */
    public String code() {
        return code;
    }

    /**
     * Returns one line saying what the diagnostic means, independent of the element it is reported on.
     *
     * @return the one-line meaning of this identifier
     */
    public String meaning() {
        return meaning;
    }

    /**
     * The message as printed: {@code [Exeris] <code>: <message>}.
     *
     * @param message the human-readable text; it follows the identifier unchanged
     * @return {@link #PREFIX}, the identifier, a colon and a space, then {@code message}
     */
    public String format(String message) {
        return PREFIX + code + ": " + message;
    }
}
