package eu.exeris.tooling.processor.tck;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.io.SourceModelReader;
import eu.exeris.sdk.tck.AbstractMetadataParityTck;
import eu.exeris.sdk.tck.Facet;

import java.util.Set;

/**
 * The SDK conformance kit's parity suite: what the processor writes and what the SDK's
 * {@code SourceModelReader} reads from the same source must agree (ADR-042).
 */
class ProcessorReaderParityTckTest extends AbstractMetadataParityTck {

    @Override
    protected String produce(String entitySource) {
        return TckCorpusProducer.produce(entitySource);
    }

    @Override
    protected DomainMetadata read(String entitySource) {
        return new SourceModelReader().read(entitySource)
                .orElseThrow(() -> new IllegalStateException("the SDK reader read no entity"));
    }

    @Override
    protected Set<Facet> unsupportedFacets() {
        return TckCorpusProducer.UNSUPPORTED;
    }
}
