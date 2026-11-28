package eu.exeris.fixture.billing.domain;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Field;

import java.util.UUID;

/** The entity the contract publishes; it holds an enum field so the contract carries an enum file. */
@ExerisDomain(module = "billing", path = "/invoices")
public class Invoice {

    private UUID id;

    @Field(label = "Number", required = true)
    private String number;

    @Field(label = "Status")
    private InvoiceStatus status;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }
}
