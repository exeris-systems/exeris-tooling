package eu.exeris.tooling.codegen.core.contract;

/**
 * Where a contract artifact lives and which files in it carry which meaning.
 *
 * @since 0.12
 */
public final class ContractLayout {

    /** Directory, relative to a jar or a class output root, that holds the contract. */
    public static final String DIRECTORY = "META-INF/exeris/contract";

    /** The resolved capability graph of the build; what makes a directory a contract. */
    public static final String MANIFEST = "cap-manifest.json";

    /** File-name prefix of an enum metadata file. */
    public static final String ENUM_PREFIX = "enum_";

    /** File-name prefix of a capability descriptor in the processor output; never in a contract. */
    public static final String CAPABILITY_PREFIX = "capability_";

    /** File-name prefix of a view descriptor in the processor output; never in a contract. */
    public static final String VIEW_PREFIX = "view_";

    /** Name reserved at the contract root for the per-event specification; readers skip it. */
    public static final String EVENTS = "events.json";

    /** Suffix of every file in a contract. */
    public static final String JSON_SUFFIX = ".json";

    private ContractLayout() {
    }

    /**
     * Reports whether a processor-output file name belongs to the entity family.
     *
     * @param fileName the file name
     * @return {@code true} for a {@code *.json} that is not an enum, capability or view file
     */
    public static boolean isEntityCandidate(String fileName) {
        return fileName.endsWith(JSON_SUFFIX)
                && !fileName.startsWith(ENUM_PREFIX)
                && !fileName.startsWith(CAPABILITY_PREFIX)
                && !fileName.startsWith(VIEW_PREFIX)
                && !EVENTS.equals(fileName)
                && !MANIFEST.equals(fileName);
    }

    /**
     * Reports whether a processor-output file name is an enum metadata file.
     *
     * @param fileName the file name
     * @return {@code true} for {@code enum_*.json}
     */
    public static boolean isEnum(String fileName) {
        return fileName.startsWith(ENUM_PREFIX) && fileName.endsWith(JSON_SUFFIX);
    }
}
