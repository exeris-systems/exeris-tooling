// The contract this fixture reads is a directory built outside it: invoker runs the fixtures in no
// fixed order, so a sibling fixture's output cannot stand in. peer-contract/ holds the class-output
// shape (META-INF/exeris/contract/) as exeris:contract wrote it for an entity and an enum; this
// script lays it beside the project, outside its basedir, which is where a path is accepted.
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

Path source = new File(basedir, 'peer-contract').toPath()
Path target = new File(basedir.parentFile, 'contract-2-by-path-peer').toPath()
new File(target.toString()).deleteDir()
source.toFile().eachFileRecurse(groovy.io.FileType.FILES) { File file ->
    Path destination = target.resolve(source.relativize(file.toPath()).toString())
    Files.createDirectories(destination.parent)
    Files.copy(file.toPath(), destination, StandardCopyOption.REPLACE_EXISTING)
}
return true
