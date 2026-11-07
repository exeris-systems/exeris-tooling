// What a green fixture build does not show by itself: that the integration tests were generated,
// that Failsafe ran them rather than Surefire, and that they did more than run zero cases.

def log = new File(basedir, 'build.log').text

assert log.contains('exeris:generate skipped (exeris.codegen.skip=true)')
assert log.contains('Generating integration tests for the generated application')
assert !log.contains('EXT-GEN-3105')
assert !log.contains('EXT-PLUG-2004')

def it = new File(basedir, 'src/test/generated-it/java/eu/exeris/fixture/memos')
assert new File(it, 'testsupport/ApplicationUnderTest.java').isFile()
assert new File(it, 'testsupport/IntegrationTokens.java').isFile()
assert new File(it, 'handler/MemoCrudIT.java').isFile()
assert new File(it, 'handler/DispatchCrudIT.java').isFile()
assert new File(it, 'GeneratedRoutePolicyIT.java').isFile()

// Surefire's default includes do not match *IT, so the test phase ran none of them.
def surefire = new File(basedir, 'target/surefire-reports')
assert !surefire.exists() || !surefire.listFiles().any { it.name.contains('CrudIT') }

// Failsafe ran them, and every one passed.
def failsafe = new File(basedir, 'target/failsafe-reports')
['handler.MemoCrudIT', 'handler.DispatchCrudIT', 'GeneratedRoutePolicyIT'].each {
    assert new File(failsafe, "TEST-eu.exeris.fixture.memos.${it}.xml").isFile()
}
def summary = new groovy.xml.XmlSlurper().parse(new File(failsafe, 'failsafe-summary.xml'))
assert summary.completed.text().toInteger() > 0
assert summary.failures.text().toInteger() == 0
assert summary.errors.text().toInteger() == 0

return true
