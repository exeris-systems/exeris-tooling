package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.diagnostics.DiagnosticId;

import java.util.List;

/**
 * Generation refused: an entity has a field whose type the generated repository cannot store and
 * read back ({@link DiagnosticId#FIELD_TYPE_NOT_PERSISTABLE}).
 *
 * <p>Every domain field is a column of the entity's table, written through a
 * {@code PersistenceStatement} bind and read through a {@code RowCursor} accessor. A type with
 * neither a typed SPI accessor nor a string round-trip the repository can emit has no column
 * encoding, and emitting one anyway produces a repository that does not compile, or a generator
 * that fails without naming the field. The message is already formatted with the identifier, so a
 * caller prints it unchanged.
 *
 * @since 0.9.0
 */
public final class UnpersistableFieldTypeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Not {@code transient}: the offending fields must survive serialization with the message. */
    private final List<String> fields;

    /**
     * Creates the refusal for the given fields. The message is prefixed with {@code EXT-GEN-3003}
     * and lists the fields in the order given, followed by the ways out; {@link #fields()} returns
     * an unmodifiable copy of the list.
     *
     * @param fields one entry per refused field, each naming the entity, the field, its type and
     *               why it is refused; in a stable order
     */
    public UnpersistableFieldTypeException(List<String> fields) {
        super(DiagnosticId.FIELD_TYPE_NOT_PERSISTABLE.format(buildMessage(fields)));
        this.fields = List.copyOf(fields);
    }

    /** The refused fields, one entry each, as the message lists them. */
    public List<String> fields() {
        return fields;
    }

    private static String buildMessage(List<String> fields) {
        return "The generated repository cannot store and read back "
                + (fields.size() == 1 ? "this entity field" : "these entity fields") + ":\n  "
                + String.join("\n  ", fields)
                + "\nDeclare each as a List<…> (stored as a JSON column) or as a supported scalar "
                + "(String, UUID, Long, Integer, Boolean, Double, BigDecimal, Instant, LocalDateTime, "
                + "OffsetDateTime, ZonedDateTime, LocalDate, or an enum); use BigDecimal in place of BigInteger.";
    }
}
