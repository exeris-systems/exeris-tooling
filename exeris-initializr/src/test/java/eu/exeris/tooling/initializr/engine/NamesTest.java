package eu.exeris.tooling.initializr.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamesTest {

    @Test
    @DisplayName("kebab-cases a type name")
    void kebab() {
        assertThat(Names.kebab("Item")).isEqualTo("item");
        assertThat(Names.kebab("OrderLine")).isEqualTo("order-line");
        assertThat(Names.kebab("Order2Line")).isEqualTo("order2-line");
    }

    @Test
    @DisplayName("pluralises the regular English endings")
    void plural() {
        assertThat(Names.plural("item")).isEqualTo("items");
        assertThat(Names.plural("category")).isEqualTo("categories");
        assertThat(Names.plural("day")).isEqualTo("days");
        assertThat(Names.plural("box")).isEqualTo("boxes");
        assertThat(Names.plural("address")).isEqualTo("addresses");
        assertThat(Names.plural("batch")).isEqualTo("batches");
        assertThat(Names.plural("y")).isEqualTo("ys");
    }

    @Test
    @DisplayName("derives a database name that starts with a letter")
    void databaseName() {
        assertThat(Names.databaseName("order-service")).isEqualTo("order_service");
        assertThat(Names.databaseName("2fa.App")).isEqualTo("app_2fa_app");
        assertThat(Names.databaseName("-_-")).isEqualTo("app_");
    }

    @Test
    @DisplayName("derives an npm name that does not start with '.' or '_'")
    void npmName() {
        assertThat(Names.npmName("Order-Service")).isEqualTo("order-service");
        assertThat(Names.npmName("_x")).isEqualTo("app-_x");
        assertThat(Names.npmName(".x")).isEqualTo("app-.x");
    }

    @Test
    @DisplayName("escapes the XML-special characters of element text")
    void xml() {
        assertThat(Names.xml("A & B <C>")).isEqualTo("A &amp; B &lt;C&gt;");
    }
}
