// Runs the exeris-initializr jar this reactor built, as a separate process, into generated/.
// H2, not the PostgreSQL default: the offline reactor build resolves the H2 driver from its own
// repository, and building and testing the project need no database server.
def jar = new File(initializrJar)
assert jar.isFile() : "no initializr jar at ${jar}"
def java = new File(System.getProperty('java.home'), 'bin/java').path
def command = [java, '-jar', jar.path,
               '--topology=backend-only', '--database=h2',
               '--group-id=eu.exeris.fixture', '--artifact-id=initializr-backend',
               "--output-dir=${new File(basedir, 'generated').path}"]
def process = command.execute(null, basedir)
def out = new StringBuilder(), err = new StringBuilder()
process.waitForProcessOutput(out, err)
new File(basedir, 'initializr.log').text = out.toString() + err.toString()
assert process.exitValue() == 0 : "exeris-initializr exited ${process.exitValue()}: ${err}"
return true
