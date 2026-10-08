// The build fails at javac either way. What this fixture checks is that exeris:generate named the
// missing jar before javac named the missing package, and named only that one.

def log = new File(basedir, 'build.log').text

assert log.contains('Code generation complete')

def warnings = log.readLines().findAll { it.contains('[Exeris] EXT-PLUG-2004: ') }
assert warnings.size() == 1 : "expected one EXT-PLUG-2004 warning, found ${warnings.size()}"
assert warnings[0].startsWith('[WARNING] ')

def warning = log.substring(log.indexOf('[Exeris] EXT-PLUG-2004: '))
assert warning.contains('  - tools.jackson.core:jackson-databind, imported by the generated repository')
assert warning.contains('eu.exeris:exeris-app-starter')
// Declared at compile scope by this build, so not named.
assert !warning.substring(0, warning.indexOf('Declare each')).contains('exeris-kernel-spi')

// The warning comes before the compile failure it explains.
assert log.indexOf('[Exeris] EXT-PLUG-2004: ') < log.indexOf('COMPILATION ERROR')
assert log.contains('package tools.jackson')

return true
