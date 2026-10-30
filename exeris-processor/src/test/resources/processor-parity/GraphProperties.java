package parity;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Graph;
import eu.exeris.sdk.annotation.GraphProperty;
import java.math.BigDecimal;

@ExerisDomain(module = "m", path = "/p")
@Graph
public class GraphProperties {
    @GraphProperty(indexed = true)
    private String title;
    @GraphProperty(name = "amt", graphType = GraphProperty.GraphType.DOUBLE)
    private BigDecimal amount;
    @GraphProperty(graphType = GraphProperty.GraphType.AUTO, unique = true)
    private String code;
    @GraphProperty(name = "tag")
    private String left, right;
    private String unmarked;
}
