// What a green fixture build does not show by itself: that each step ran, and that the fixture
// still reaches the imports exeris-app-starter exists to carry. Without the import checks, an
// edit to the fixture could stop exercising Jackson 3 or the composition runtime and the build
// would stay green while checking less.

def log = new File(basedir, 'build.log').text

// D2: the first pass seeds the metadata with generation skipped.
assert log.contains('exeris:generate skipped (exeris.codegen.skip=true)')

// The second pass ran the three bound goals against fresh metadata.
assert log.contains('Code generation complete')
assert log.contains('Capability graph valid (1 module(s), fresh metadata)')
assert log.contains('Cap-tier Wall clean (1 module(s)')
assert log.contains('Runtime drivers present for all')

def generated = new File(basedir, 'src/main/generated/java/eu/exeris/fixture/notes')
assert new File(generated, 'Application.java').text
        .contains('import eu.exeris.sdk.composition.runtime.CompositionConductor;')
assert new File(generated, 'repository/NoteRepository.java').text
        .contains('import tools.jackson.databind.ObjectMapper;')

// exeris-app-parent's <resource> entry: the generated migrations and OpenAPI reach the classpath.
def classes = new File(basedir, 'target/classes')
def migrations = new File(classes, 'db/migration').listFiles()
assert migrations != null && migrations.any { it.name.endsWith('.sql') }
assert new File(classes, 'openapi/note-api.yaml').isFile()

// The generated tests ran (ADR-058), on the surefire exeris-app-parent configures.
def reports = new File(basedir, 'target/surefire-reports')
['repository.NoteRepositoryTest', 'handler.NoteHandlerTest', 'service.NoteServiceTest'].each {
    assert new File(reports, "TEST-eu.exeris.fixture.notes.${it}.xml").isFile()
}

// Effective POM (pass 3, under -P release): the application's own blanks, none of Exeris's
// publication metadata, and no publishing inherited from exeris-app-bom's profile.
def pom = new groovy.xml.XmlSlurper().parse(new File(basedir, 'target/effective-pom.xml'))
assert pom.url.text() == ''
assert pom.licenses.license.name.text() == ''
assert pom.developers.developer.name.text() == ''
assert pom.scm.url.text() == '' && pom.scm.connection.text() == '' && pom.scm.developerConnection.text() == ''
assert pom.description.text() == 'An application built on exeris-app-parent and exeris-app-starter.'
assert pom.distributionManagement.size() == 0
def effective = new File(basedir, 'target/effective-pom.xml').text
['github.com/exeris-systems', 'exeris.eu', 'Apache License'].each { assert !effective.contains(it) }
def plugins = pom.build.plugins.plugin.artifactId*.text()
['maven-gpg-plugin', 'central-publishing-maven-plugin', 'maven-source-plugin', 'maven-javadoc-plugin'].each {
    assert !plugins.contains(it)
}

// The literal versions exeris-app-bom passes on are the ones this reactor built (ADR-091
// obligation 1): the property, the plugin the parent binds, and the processor on the compiler's
// annotationProcessorPaths.
assert pom.properties.'exeris.tooling.version'.text() == toolingVersion :
        "exeris.tooling.version is ${pom.properties.'exeris.tooling.version'.text()}, the reactor built ${toolingVersion}"
def codegen = pom.build.plugins.plugin.find { it.artifactId.text() == 'exeris-codegen-maven-plugin' }
assert codegen.version.text() == toolingVersion :
        "exeris-codegen-maven-plugin is ${codegen.version.text()}, the reactor built ${toolingVersion}"
def compiler = pom.build.plugins.plugin.find { it.artifactId.text() == 'maven-compiler-plugin' }
def processor = compiler.configuration.annotationProcessorPaths.path.find { it.artifactId.text() == 'exeris-processor' }
assert processor.version.text() == toolingVersion :
        "exeris-processor on annotationProcessorPaths is ${processor.version.text()}, the reactor built ${toolingVersion}"

return true
