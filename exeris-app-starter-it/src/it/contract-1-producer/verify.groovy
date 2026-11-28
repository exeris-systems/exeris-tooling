// ADR-048 Amendment 3: a backend with no @CapabilityModule publishes its contract inside its jar.

import java.util.zip.ZipFile

def log = new File(basedir, 'build.log').text
assert log.contains('Contract written:')
assert !log.contains('EXT-PLUG-2401')

def classes = new File(basedir, 'target/classes')
def metadata = new File(classes, 'exeris-metadata')
def contract = new File(classes, 'META-INF/exeris/contract')

// The empty composition: schemaVersion 2, a validated stamp over no module, nothing else.
def manifest = new groovy.json.JsonSlurper().parse(new File(contract, 'cap-manifest.json'))
assert manifest.schemaVersion == 2
assert manifest.stamp.validated == true
assert manifest.stamp.contentBinding.startsWith('sha256:')
assert manifest.modules == []
assert manifest.resolutions == []
assert manifest.initOrder == []
assert manifest.warnings == []

// The generated tree holds no manifest: exeris:generate writes it for a build with a capability module only.
assert !new File(basedir, 'src/main/generated/java/cap-manifest.json').exists()

// Each entity and enum is byte for byte what the processor wrote; no view or capability file is there.
def expected = metadata.listFiles().findAll { it.name.endsWith('.json') &&
        (it.name.startsWith('enum_') || !(it.name.startsWith('view_') || it.name.startsWith('capability_'))) }
assert expected.any { it.name == 'Invoice.json' }
assert expected.any { it.name == 'enum_InvoiceStatus.json' }
expected.each { f ->
    assert Arrays.equals(new File(contract, f.name).bytes, f.bytes) : "${f.name} differs from the processor's output"
}
assert contract.list().sort() == (expected*.name + 'cap-manifest.json').sort()

// The jar carries the same entries under META-INF/exeris/contract/.
def jar = new File(basedir, 'target/contract-producer-1.0.0.jar')
assert jar.isFile()
def zip = new ZipFile(jar)
try {
    def entries = zip.entries().toList()*.name.findAll { it.startsWith('META-INF/exeris/contract/') && !it.endsWith('/') }
    assert entries.sort() == (expected*.name + 'cap-manifest.json').collect { "META-INF/exeris/contract/${it}".toString() }.sort()
    def inJar = zip.getInputStream(zip.getEntry('META-INF/exeris/contract/cap-manifest.json')).bytes
    assert Arrays.equals(inJar, new File(contract, 'cap-manifest.json').bytes)
} finally {
    zip.close()
}

return true
