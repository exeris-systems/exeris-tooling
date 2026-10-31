package eu.exeris.tooling.processor.tck;

import eu.exeris.sdk.tck.AbstractMetadataProducerTck;
import eu.exeris.sdk.tck.Facet;

import java.util.Set;

/** The SDK conformance kit's producer suite, with the processor as the producer. */
class ProcessorProducerTckTest extends AbstractMetadataProducerTck {

    @Override
    protected String produce(String entitySource) {
        return TckCorpusProducer.produce(entitySource);
    }

    @Override
    protected Set<Facet> unsupportedFacets() {
        return TckCorpusProducer.UNSUPPORTED;
    }
}
