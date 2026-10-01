package eu.exeris.fixture.notes.domain;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Field;

import java.util.List;
import java.util.UUID;

/**
 * The fixture's entity. The {@code List<String>} field is what makes the generated repository
 * import Jackson 3, one of the two conditional imports exeris-app-starter carries.
 */
@ExerisDomain(module = "notes", path = "/notes")
public class Note {

    private UUID id;

    @Field(label = "Title", required = true, filterable = true)
    private String title;

    @Field(label = "Tags")
    private List<String> tags;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }
}
