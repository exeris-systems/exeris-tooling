def log = new File(basedir, 'build.log').text
assert log.contains('EXT-PLUG-2006')
assert log.contains("peer 'billing'")
assert log.contains('inside the module')
return true
