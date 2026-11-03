package eu.exeris.tooling.codegen.core.access;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.RouteAccess;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Kind;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Requirement;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RouteRequirements")
class RouteRequirementsTest {

    private static DomainMetadata.Builder order() {
        return DomainMetadata.builder("Order", "com.example.domain").path("/orders");
    }

    @Test
    @DisplayName("an entity resolves PUBLIC, then permissions, then AUTHENTICATED, else nothing")
    void entityResolutionTable() {
        assertThat(RouteRequirements.forEntity(order().routeAccess(RouteAccess.PUBLIC).build()))
                .isEqualTo(new Requirement(Kind.PERMIT_ALL, List.of(), Source.ENTITY));
        assertThat(RouteRequirements.forEntity(order().routeAccess(RouteAccess.AUTHENTICATED)
                .permissions(List.of("b", "a", "b")).build()))
                .isEqualTo(new Requirement(Kind.ANY_SCOPE, List.of("b", "a"), Source.ENTITY));
        assertThat(RouteRequirements.forEntity(order().routeAccess(RouteAccess.AUTHENTICATED).build()))
                .isEqualTo(new Requirement(Kind.AUTHENTICATED, List.of(), Source.ENTITY));
        assertThat(RouteRequirements.forEntity(order().build())).isNull();
    }

    @Test
    @DisplayName("an action's own declaration replaces the entity's; without one it inherits")
    void nearestDeclarationWins() {
        ActionMetadata approve = ActionMetadata.builder("approve").permissions(List.of("order:approve")).build();
        ActionMetadata cancel = ActionMetadata.builder("cancel").build();
        DomainMetadata domain = order().permissions(List.of("order:read")).actions(List.of(approve, cancel)).build();

        assertThat(RouteRequirements.forAction(domain, approve))
                .isEqualTo(new Requirement(Kind.ANY_SCOPE, List.of("order:approve"), Source.ACTION));
        assertThat(RouteRequirements.forAction(domain, cancel))
                .isEqualTo(new Requirement(Kind.ANY_SCOPE, List.of("order:read"), Source.ENTITY));
    }

    @Test
    @DisplayName("identity is demanded by authenticated and scoped routes, not by public ones")
    void demandIdentity() {
        assertThat(RouteRequirements.demandIdentity(List.of(order().routeAccess(RouteAccess.PUBLIC).build())))
                .isFalse();
        assertThat(RouteRequirements.anyDeclared(List.of(order().routeAccess(RouteAccess.PUBLIC).build())))
                .isTrue();
        assertThat(RouteRequirements.anyDeclared(List.of(order().build()))).isFalse();
        DomainMetadata publicWithScopedAction = order().routeAccess(RouteAccess.PUBLIC)
                .actions(List.of(ActionMetadata.builder("approve").routeAccess(RouteAccess.AUTHENTICATED).build()))
                .build();
        assertThat(RouteRequirements.demandIdentity(List.of(publicWithScopedAction))).isTrue();
    }
}
