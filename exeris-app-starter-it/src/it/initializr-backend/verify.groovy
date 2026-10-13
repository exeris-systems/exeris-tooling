// What a green two-pass build of the generated project does not show by itself: that the
// initializr wrote the project it documents, and that the build generated and ran what the parent
// and the starter promise.

def project = new File(basedir, 'generated')
def log = new File(basedir, 'build.log').text

// The initializr wrote a backend on the parent this reactor built, with the starter and nothing
// beyond what the starter leaves to the application.
def pom = new groovy.xml.XmlSlurper().parse(new File(project, 'pom.xml'))
assert pom.parent.artifactId.text() == 'exeris-app-parent'
assert pom.parent.version.text() == toolingVersion
def dependencies = pom.dependencies.dependency.collect { "${it.groupId.text()}:${it.artifactId.text()}" }
assert dependencies == ['eu.exeris:exeris-app-starter', 'com.h2database:h2',
                        'org.junit.jupiter:junit-jupiter', 'org.assertj:assertj-core']
assert !new File(project, 'pom.xml').text.contains('exeris-caps')

// first-build.sh runs the same two passes as invoker.properties.
def script = new File(project, 'first-build.sh').text
assert script.contains('\nmvn -Dexeris.codegen.skip=true compile "$@"\nmvn verify "$@"\n')

// Pass 1 seeded the metadata with generation skipped; pass 2 generated, verified and tested.
assert log.contains('exeris:generate skipped (exeris.codegen.skip=true)')
assert log.contains('Code generation complete')
assert log.contains('Runtime drivers present for all')
assert !log.contains('EXT-PLUG-2004')
// No capability module: exeris:verify-capabilities, which the parent binds, has nothing to verify.
assert log.contains('No capability metadata under')

def generated = new File(project, 'src/main/generated/java/eu/exeris/fixture/initializr/backend')
['Application.java', 'RuntimeComponents.java', 'RuntimeLifecycle.java',
 'handler/ItemHandler.java', 'service/ItemService.java', 'repository/ItemRepository.java'].each {
    assert new File(generated, it).isFile() : "not generated: ${it}"
}
def classes = new File(project, 'target/classes')
assert new File(classes, 'openapi/item-api.yaml').isFile()
assert new File(classes, 'db/migration').listFiles().any { it.name.endsWith('.sql') }

// The generated tests ran and passed (ADR-058).
def reports = new File(project, 'target/surefire-reports')
def suites = reports.listFiles().findAll { it.name.startsWith('TEST-') && it.name.endsWith('.xml') }
assert suites.size() >= 3
def tests = suites.sum { new groovy.xml.XmlSlurper().parse(it).@tests.toInteger() }
def failed = suites.sum {
    def suite = new groovy.xml.XmlSlurper().parse(it)
    suite.@failures.toInteger() + suite.@errors.toInteger()
}
assert tests > 0 && failed == 0 : "${tests} generated test(s), ${failed} failed"
println "initializr-backend: ${tests} generated test(s) passed"

return true
