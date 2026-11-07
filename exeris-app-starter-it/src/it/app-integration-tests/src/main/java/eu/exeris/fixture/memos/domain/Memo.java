package eu.exeris.fixture.memos.domain;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Field;
import eu.exeris.sdk.annotation.Validation;

import java.util.UUID;

/**
 * A GLOBAL entity with bounds, so its generated integration test carries the round trip and the
 * {@code @Validation} pairs.
 */
@ExerisDomain(module = "memos", path = "/memos", versioned = true)
public class Memo {

    private UUID id;

    @Field(label = "Title", required = true)
    @Validation(minLength = 2, maxLength = 40)
    private String title;

    private Long version;

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

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
