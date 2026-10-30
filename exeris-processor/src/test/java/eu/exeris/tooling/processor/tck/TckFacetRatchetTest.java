package eu.exeris.tooling.processor.tck;

import static org.assertj.core.api.Assertions.assertThat;

import eu.exeris.sdk.tck.Facet;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unsupported-facet set only shrinks. An extraction row removes its facet from
 * {@link TckCorpusProducer#UNSUPPORTED} and from {@link #CEILING} in the same change; adding a
 * facet back to either fails here. The 0.11.0 cut needs both empty.
 */
class TckFacetRatchetTest {

    private static final Set<Facet> CEILING = EnumSet.of(
            Facet.SAGA_COMPENSATION,
            Facet.SAGA_TRIGGER,
            Facet.ACTION_RESULT_TYPE,
            Facet.ROUTE_ACCESS,
            Facet.NAV_MENU,
            Facet.TABS,
            Facet.BLOBS);

    @Test
    @DisplayName("the binding declares no facet unsupported beyond the ceiling")
    void unsupportedFacetsStayWithinTheCeiling() {
        assertThat(CEILING).containsAll(TckCorpusProducer.UNSUPPORTED);
    }

    @Test
    @DisplayName("the ceiling is the binding's set, so a removed facet is removed from both")
    void theCeilingFollowsTheBinding() {
        assertThat(TckCorpusProducer.UNSUPPORTED).containsExactlyInAnyOrderElementsOf(CEILING);
    }
}
