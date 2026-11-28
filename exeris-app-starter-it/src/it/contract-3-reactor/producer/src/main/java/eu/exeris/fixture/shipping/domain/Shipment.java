package eu.exeris.fixture.shipping.domain;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Field;

import java.util.UUID;

/** The entity the producer's contract publishes. */
@ExerisDomain(module = "shipping", path = "/shipments")
public class Shipment {

    private UUID id;

    @Field(label = "Tracking number", required = true)
    private String trackingNumber;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }
}
