package eu.exeris.tooling.processor.tck;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.tck.AbstractMetadataReaderTck;
import eu.exeris.sdk.tck.Facet;
import eu.exeris.sdk.tck.TckMappers;

import java.util.Set;

/**
 * The SDK conformance kit's reader suite, with the processor as the reader: each case's source is
 * compiled under the processor and its metadata read back with the kit's reference mapper.
 */
class ProcessorAsReaderTckTest extends AbstractMetadataReaderTck {

    @Override
    protected DomainMetadata read(String entitySource) {
        return TckMappers.canonical().readValue(TckCorpusProducer.produceAny(entitySource), DomainMetadata.class);
    }

    @Override
    protected Set<Facet> unsupportedFacets() {
        return TckCorpusProducer.UNSUPPORTED;
    }
}
