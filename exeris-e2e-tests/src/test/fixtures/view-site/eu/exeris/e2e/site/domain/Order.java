package eu.exeris.e2e.site.domain;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Field;

import java.util.UUID;

@ExerisDomain(module = "site", path = "/orders")
public class Order {
    private UUID id;
    @Field(label = "Reference", required = true)
    private String reference;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
}
