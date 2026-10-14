// The e2e topology: the backend builds as the backend-only one does, and the frontend holds only
// what runs exeris-gen on that backend's metadata, at no path the generator writes itself.

def project = new File(basedir, 'generated')
def backend = new File(project, 'backend')
def log = new File(basedir, 'build.log').text

def pom = new groovy.xml.XmlSlurper().parse(new File(backend, 'pom.xml'))
assert pom.parent.artifactId.text() == 'exeris-app-parent'
assert pom.parent.version.text() == toolingVersion

assert log.contains('exeris:generate skipped (exeris.codegen.skip=true)')
assert log.contains('Code generation complete')
assert log.contains('Runtime drivers present for all')

// The metadata the frontend's generate script reads is where the backend build wrote it.
def metadata = new File(backend, 'target/classes/exeris-metadata')
assert metadata.listFiles().any { it.name.endsWith('.json') }

def frontend = new File(project, 'frontend')
assert frontend.list() as List == ['package.json']
def packageJson = new File(frontend, 'package.json').text
def generate = '../backend/target/classes/exeris-metadata'
assert packageJson.contains(
        "\"generate\": \"exeris-gen generate --input ${generate} --output app --app-name initializr-e2e\"")
assert packageJson.contains("\"@exeris/codegen-ts\": \"${toolingVersion}\"")
assert new File(frontend, generate).canonicalFile == metadata.canonicalFile

def reports = new File(backend, 'target/surefire-reports')
def suites = reports.listFiles().findAll { it.name.startsWith('TEST-') && it.name.endsWith('.xml') }
def tests = suites.sum { new groovy.xml.XmlSlurper().parse(it).@tests.toInteger() }
def failed = suites.sum {
    def suite = new groovy.xml.XmlSlurper().parse(it)
    suite.@failures.toInteger() + suite.@errors.toInteger()
}
assert tests > 0 && failed == 0 : "${tests} generated test(s), ${failed} failed"
println "initializr-e2e: ${tests} generated test(s) passed"

return true
