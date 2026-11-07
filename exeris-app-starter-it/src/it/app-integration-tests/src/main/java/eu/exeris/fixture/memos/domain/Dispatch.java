package eu.exeris.fixture.memos.domain;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Field;

import java.util.UUID;

/**
 * An entity whose routes demand a scope, so the application boots the security subsystem and the
 * generated integration tests verify tokens against the loopback key set they serve.
 */
@ExerisDomain(module = "memos", path = "/dispatches", permissions = {"dispatch:read"})
public class Dispatch {

    private UUID id;

    @Field(label = "Subject")
    private String subject;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }
}
