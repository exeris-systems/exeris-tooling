package parity;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Graph;

@ExerisDomain(module = "m", path = "/p")
@Graph
public class GraphNoProperties {
    private String title;
}
