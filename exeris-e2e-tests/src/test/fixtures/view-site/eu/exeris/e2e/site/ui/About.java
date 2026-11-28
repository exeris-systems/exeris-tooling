package eu.exeris.e2e.site.ui;

import eu.exeris.sdk.annotation.Bind;
import eu.exeris.sdk.annotation.Block;
import eu.exeris.sdk.annotation.Region;
import eu.exeris.sdk.annotation.View;

/** A page of authored blocks that reads no entity, so a static build prerenders it. */
@View(name = "About", kind = View.Kind.PAGE, route = "/about", title = "About")
public final class About {

    @Region
    BodyRegion body;

    static final class BodyRegion {

        @Block(type = Block.BlockType.HERO)
        @Bind(source = Bind.Source.STATIC)
        String banner;

        @Block(type = Block.BlockType.CARD)
        @Bind(source = Bind.Source.STATIC)
        String team;
    }
}
