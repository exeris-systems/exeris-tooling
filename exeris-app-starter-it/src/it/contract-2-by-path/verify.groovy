def log = new File(basedir, 'build.log').text
assert log.contains("Peer 'billing': contract read (1 entity file(s), 1 enum file(s))")
assert !log.contains('EXT-PLUG-2006')
// This build is itself a backend, and publishes the contract of an empty composition.
assert new File(basedir, 'target/classes/META-INF/exeris/contract/cap-manifest.json').isFile()
return true
