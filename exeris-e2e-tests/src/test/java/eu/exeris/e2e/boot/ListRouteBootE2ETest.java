package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list route pages, sorts, filters and searches in the database, on a <b>real</b> kernel boot.
 *
 * <p>Generator tests assert the emitted SQL fragments and the compile gate proves they compile; only
 * a statement run against an engine shows that the assembled {@code WHERE}, {@code ORDER BY} and
 * {@code LIMIT ? OFFSET ?} are SQL that engine accepts, that the count and the page agree, and that
 * the envelope reaches the wire with the member names the front reads. So the whole path runs:
 *
 * <pre>
 *   @ExerisDomain + @Field(sortable / filterable / searchable) source
 *        → javac + ExerisDomainProcessor      (real metadata: the processor's reading of the flags)
 *        → CodegenPipeline.run                (list query, page, repository, handler, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2, PostgreSQL mode)
 *        → a real socket                      (GET /lamps?…, GET /memos?search=…)
 * </pre>
 *
 * <p>The table is created by the harness, not by the emitted migration: the kernel applies its own
 * migrations only, and the emitted ones carry PostgreSQL row-level security that H2 does not
 * implement. The entity is global (no tenant), so no policy is in play and nothing about tenant
 * visibility is asserted here — {@code SharedScopePostgresE2ETest} is where that runs on a real
 * engine.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("The list route pages, sorts, filters and searches on a real kernel boot")
class ListRouteBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.listing";
    private static final Pattern LABEL = Pattern.compile("\"label\":\"([^\"]*)\"");
    private static final Pattern TITLE = Pattern.compile("\"title\":\"([^\"]*)\"");
    private static final String OK = "HTTP/1.1 200";

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    @BeforeAll
    static void bootWithSeededRows() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".ListingApplication");
        assertThat(RawHttp.request(booted.port(), "POST", "/seed")).startsWith("HTTP/1.1 204");
    }

    @AfterAll
    static void stop() throws Exception {
        if (booted != null) {
            booted.close();
        }
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("the processor's reading of @Field(sortable / filterable) is the whitelist the "
            + "emitted list query carries")
    void processorFlagsReachTheWhitelist() throws IOException {
        String query = Files.readString(app.generatedRoot()
                .resolve("eu/exeris/e2e/listing/repository/LampListQuery.java"));
        assertThat(query.replaceAll("\\s+", " "))
                .contains("SORTABLE = List.of(\"label\", \"watts\");")
                .contains("record Filter(String label, Boolean lit, LampStatus status)");
    }

    @Test
    @DisplayName("no query string: the first page of twenty, in id order, with the totals")
    void defaultPage() {
        String response = list("");

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(response)
                .contains("\"totalElements\":6")
                .contains("\"totalPages\":1")
                .contains("\"size\":20")
                .contains("\"number\":0")
                .contains("\"first\":true")
                .contains("\"last\":true");
        // The ids are seeded in label order, so id order is label order.
        assertThat(labels(response)).containsExactly("a", "b", "c", "d", "e", "two words");
    }

    @Test
    @DisplayName("sort and page: the second page of two, by watts descending")
    void sortedSecondPage() {
        String response = list("?page=1&size=2&sort=watts,desc");

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(labels(response)).containsExactly("c", "b");
        assertThat(response)
                .contains("\"totalElements\":6")
                .contains("\"totalPages\":3")
                .contains("\"size\":2")
                .contains("\"number\":1")
                .contains("\"first\":false")
                .contains("\"last\":false");
    }

    @Test
    @DisplayName("filters combine: an enum and a boolean, counted and paged on the same predicate")
    void filtered() {
        String response = list("?status=ON&lit=true&sort=label,asc");

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(labels(response)).containsExactly("a", "e");
        assertThat(response).contains("\"totalElements\":2");
    }

    @Test
    @DisplayName("a filter value is percent-decoded and bound, never spliced into SQL")
    void percentDecodedValue() {
        assertThat(labels(list("?label=two%20words"))).containsExactly("two words");
        // Text that would break out of a quoted SQL literal matches nothing — it is a bound value.
        String response = list("?label=" + "x%27%20OR%20%271%27%3D%271");
        assertThat(response).startsWith("HTTP/1.1 200").contains("\"totalElements\":0");
    }

    @Test
    @DisplayName("refused before the database: unknown parameter, unsortable property, bad direction, "
            + "malformed value, out-of-range size, negative page, repeated parameter, the primary key")
    void refusals() {
        for (String query : List.of(
                "?search=a",
                "?sort=status,asc",
                "?sort=label,sideways",
                "?lit=maybe",
                "?status=DIMMED",
                "?size=101",
                "?size=0",
                "?page=-1",
                "?page=x",
                "?page=0&page=1",
                "?id=00000000-0000-4000-8000-000000000001",
                "?sort=id,asc")) {
            assertThat(list(query)).as(query).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    @DisplayName("search matches any searchable column case-insensitively, never a column that is not "
            + "searchable, never a soft-deleted row, and the count is the matched rows")
    void searchMatchesCaseInsensitively() {
        String response = memos("?search=KOW&sort=title,asc");

        assertThat(response).startsWith(OK);
        // Kowalski in the title, KOWALCZYK in the body; kowabunga sits in the unsearchable code
        // column and kowloon in a deleted row.
        assertThat(titles(response)).containsExactly("Kowalski report", "notes");
        assertThat(response).contains("\"totalElements\":2");
    }

    @Test
    @DisplayName("search combines with a filter, a sort and paging, and the totals count the matches")
    void searchWithFilterSortAndPaging() {
        String filtered = memos("?search=kow&kind=TASK");
        assertThat(titles(filtered)).containsExactly("notes");
        assertThat(filtered).contains("\"totalElements\":1");

        String paged = memos("?search=kow&sort=title,asc&size=1&page=1");
        assertThat(titles(paged)).containsExactly("notes");
        assertThat(paged).contains("\"totalElements\":2").contains("\"totalPages\":2")
                .contains("\"last\":true");
    }

    @Test
    @DisplayName("%, _ and \\ in the term match only themselves")
    void likeMetacharactersAreLiteral() {
        String percent = memos("?search=50%25");
        assertThat(titles(percent)).containsExactly("50% off");
        assertThat(percent).contains("\"totalElements\":1");

        String underscore = memos("?search=_");
        assertThat(titles(underscore)).containsExactly("50 percent off");
        assertThat(underscore).contains("\"totalElements\":1");

        String backslash = memos("?search=a%5Cb");
        assertThat(titles(backslash)).containsExactly("path a\\\\b");
        assertThat(backslash).contains("\"totalElements\":1");
    }

    @Test
    @DisplayName("a blank search is no search; a long or repeated one is refused")
    void blankLongAndRepeatedSearch() {
        assertThat(memos("?search=%20%20")).startsWith(OK).contains("\"totalElements\":6");
        assertThat(memos("?search=")).startsWith(OK).contains("\"totalElements\":6");
        assertThat(memos("?search=" + "x".repeat(100))).startsWith(OK).contains("\"totalElements\":0");
        assertThat(memos("?search=" + "x".repeat(101))).startsWith("HTTP/1.1 400");
        assertThat(memos("?search=a&search=b")).startsWith("HTTP/1.1 400");
    }

    @Test
    @DisplayName("the body decodes into the generated page record, entities and all — what the "
            + "generated client receives")
    void bodyDecodesIntoThePageRecord() throws Exception {
        String response = list("?size=2");
        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        Class<?> pageType = app.loader().loadClass(BASE_PACKAGE + ".repository.LampPage");

        Object page = JsonMapper.builder().build().readValue(body, pageType);

        Map<String, Object> components = new LinkedHashMap<>();
        for (RecordComponent component : pageType.getRecordComponents()) {
            components.put(component.getName(), component.getAccessor().invoke(page));
        }
        assertThat(components).containsEntry("totalElements", 6L).containsEntry("size", 2);
        assertThat((List<?>) components.get("content")).hasSize(2)
                .allSatisfy(lamp -> assertThat(lamp.getClass().getName())
                        .isEqualTo(BASE_PACKAGE + ".domain.Lamp"));
    }

    // ------------------------------------------------------------------ harness

    private static String list(String query) {
        return RawHttp.request(booted.port(), "GET", "/lamps" + query);
    }

    private static String memos(String query) {
        return RawHttp.request(booted.port(), "GET", "/memos" + query);
    }

    private static List<String> labels(String response) {
        return matches(LABEL, response);
    }

    private static List<String> titles(String response) {
        return matches(TITLE, response);
    }

    private static List<String> matches(Pattern pattern, String response) {
        List<String> values = new ArrayList<>();
        Matcher m = pattern.matcher(response);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/listing/domain/LampStatus.java",
                """
                package eu.exeris.e2e.listing.domain;

                public enum LampStatus { ON, OFF }
                """);
        sources.put("eu/exeris/e2e/listing/domain/Lamp.java",
                """
                package eu.exeris.e2e.listing.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "listing", path = "/lamps")
                public class Lamp {

                    // No @Field: the processor records it sortable and filterable, and the route
                    // still offers neither — the primary key is only the ORDER BY tiebreak.
                    private UUID id;

                    @Field(label = "Label", required = true, sortable = true, filterable = true)
                    private String label;

                    @Field(label = "Watts", sortable = true)
                    private int watts;

                    @Field(label = "Status", filterable = true)
                    private LampStatus status;

                    @Field(label = "Lit", filterable = true)
                    private boolean lit;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public int getWatts() { return watts; }
                    public void setWatts(int watts) { this.watts = watts; }
                    public LampStatus getStatus() { return status; }
                    public void setStatus(LampStatus status) { this.status = status; }
                    public boolean isLit() { return lit; }
                    public void setLit(boolean lit) { this.lit = lit; }
                }
                """);
        sources.put("eu/exeris/e2e/listing/domain/MemoKind.java",
                """
                package eu.exeris.e2e.listing.domain;

                public enum MemoKind { NOTE, TASK }
                """);
        sources.put("eu/exeris/e2e/listing/domain/Memo.java",
                """
                package eu.exeris.e2e.listing.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "listing", path = "/memos", softDelete = true)
                public class Memo {

                    private UUID id;

                    @Field(label = "Title", sortable = true, searchable = true)
                    private String title;

                    // No @Field: the processor records it searchable.
                    private String body;

                    // @Field without searchable: never matched by search.
                    @Field(label = "Code")
                    private String code;

                    @Field(label = "Kind", filterable = true)
                    private MemoKind kind;

                    private boolean deleted;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public String getBody() { return body; }
                    public void setBody(String body) { this.body = body; }
                    public String getCode() { return code; }
                    public void setCode(String code) { this.code = code; }
                    public MemoKind getKind() { return kind; }
                    public void setKind(MemoKind kind) { this.kind = kind; }
                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }
                }
                """);
        return sources;
    }

    /**
     * {@code POST /seed} creates the tables the emitted repositories read and writes six lamps and
     * seven memos through the emitted repositories' own {@code save}, so the rows are stored the way
     * the application stores them; the seventh memo is then deleted through {@code deleteById},
     * which on a soft-delete entity sets its flag. Lamp ids are fixed and ascending in label order,
     * so id order is predictable.
     */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/listing/ListingApplication.java",
                """
                package eu.exeris.e2e.listing;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class ListingApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new ListingComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/listing/ListingComponents.java",
                """
                package eu.exeris.e2e.listing;

                import eu.exeris.e2e.listing.domain.Lamp;
                import eu.exeris.e2e.listing.domain.LampStatus;
                import eu.exeris.e2e.listing.domain.Memo;
                import eu.exeris.e2e.listing.domain.MemoKind;
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.util.UUID;

                public class ListingComponents extends RuntimeComponents {

                    private final TransactionalExecutor executor;

                    public ListingComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> conn.executeUpdate(
                                    "CREATE TABLE lamps (id UUID PRIMARY KEY, label VARCHAR(255), "
                                            + "watts INT, status VARCHAR(32), lit BOOLEAN)"));
                            seed(1, "a", 10, LampStatus.ON, true);
                            seed(2, "b", 20, LampStatus.OFF, true);
                            seed(3, "c", 30, LampStatus.ON, false);
                            seed(4, "d", 40, LampStatus.OFF, false);
                            seed(5, "e", 50, LampStatus.ON, true);
                            seed(6, "two words", 5, LampStatus.OFF, false);
                            executor.executeManaged(conn -> conn.executeUpdate(
                                    "CREATE TABLE memos (id UUID PRIMARY KEY, title VARCHAR(255), "
                                            + "body VARCHAR(255), code VARCHAR(255), kind VARCHAR(32), "
                                            + "deleted BOOLEAN)"));
                            memo(1, "Kowalski report", "quarterly", "q1", MemoKind.NOTE);
                            memo(2, "notes", "Mr KOWALCZYK called", null, MemoKind.TASK);
                            memo(3, "50% off", null, null, MemoKind.NOTE);
                            memo(4, "50 percent off", "_x", null, MemoKind.NOTE);
                            memo(5, "path a\\\\b", "", null, MemoKind.TASK);
                            memo(6, "other", "nothing", "kowabunga", MemoKind.NOTE);
                            memo(7, "kowloon trip", "", null, MemoKind.NOTE);
                            memoRepository().deleteById(UUID.fromString("00000000-0000-4000-8000-000000000107"));
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }

                    private void memo(int n, String title, String body, String code, MemoKind kind) {
                        Memo memo = new Memo();
                        memo.setId(UUID.fromString("00000000-0000-4000-8000-000000000" + (100 + n)));
                        memo.setTitle(title);
                        memo.setBody(body);
                        memo.setCode(code);
                        memo.setKind(kind);
                        memoRepository().save(memo);
                    }

                    private void seed(int n, String label, int watts, LampStatus status, boolean lit) {
                        Lamp lamp = new Lamp();
                        lamp.setId(UUID.fromString("00000000-0000-4000-8000-00000000000" + n));
                        lamp.setLabel(label);
                        lamp.setWatts(watts);
                        lamp.setStatus(status);
                        lamp.setLit(lit);
                        lampRepository().save(lamp);
                    }
                }
                """);
        return sources;
    }
}
