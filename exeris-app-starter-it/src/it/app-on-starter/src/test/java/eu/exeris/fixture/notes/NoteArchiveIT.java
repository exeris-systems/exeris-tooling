package eu.exeris.fixture.notes;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * An integration test of the application's own, named to match Failsafe's default includes. This
 * application leaves {@code exeris.integrationTests} off, so {@code exeris-app-parent} binds no
 * Failsafe and nothing runs this class; {@code verify.groovy} checks that no Failsafe report exists.
 */
class NoteArchiveIT {

    @Test
    void isNotRunWithoutTheIntegrationTestChannel() {
        fail("Failsafe ran an application's own *IT class with exeris.integrationTests off");
    }
}
