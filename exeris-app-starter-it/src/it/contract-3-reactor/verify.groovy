def log = new File(basedir, 'build.log').text
assert log.contains("Peer 'shipping': contract read (1 entity file(s), 0 enum file(s))")
assert !log.contains('EXT-PLUG-2006')
// Producer first, though the consumer is listed first.
assert log.indexOf('reactor-producer') < log.indexOf('reactor-consumer')
// The consumer's class output holds no class of the producer's.
assert !new File(basedir, 'consumer/target/classes').list().any { it.contains('Shipment') }
assert new File(basedir, 'producer/target/classes/META-INF/exeris/contract/Shipment.json').isFile()
// The aggregator has no jar, so no contract directory.
assert !new File(basedir, 'target/classes/META-INF/exeris/contract').exists()
return true
