package eu.exeris.e2e.site.ui;

import eu.exeris.sdk.annotation.Bind;
import eu.exeris.sdk.annotation.Block;
import eu.exeris.sdk.annotation.Region;
import eu.exeris.sdk.annotation.View;

/** A page with authored built-in blocks, a form leaf and a list bound to the Order entity. */
@View(name = "Storefront", kind = View.Kind.PAGE, route = "/storefront", title = "Storefront")
public final class Storefront {

    @Region(slot = "header")
    HeaderRegion header;

    @Region
    BodyRegion body;

    static final class HeaderRegion {

        @Block(type = Block.BlockType.HERO)
        @Bind(source = Bind.Source.STATIC)
        String banner;
    }

    static final class BodyRegion {

        @Block(type = Block.BlockType.GRID)
        Cards cards;

        @Block(type = Block.BlockType.FORM)
        String contact;

        @Block(type = Block.BlockType.LIST)
        @Bind(source = Bind.Source.ENTITY, ref = "Order", path = "reference")
        String orders;
    }

    static final class Cards {

        @Block(type = Block.BlockType.CARD)
        @Bind(source = Bind.Source.STATIC)
        String first;

        @Block(type = Block.BlockType.CARD)
        @Bind(source = Bind.Source.STATIC)
        String second;
    }
}
