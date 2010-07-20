import sbt._
import sbt.FileUtilities._
import java.io.File

class EnsimeProject(info: ProjectInfo) extends DefaultProject(info){

  import Configurations.{Compile, CompilerPlugin, Default, Provided, Runtime, Test}


  // override def compileOptions = List(
  //   CompileOption("-g:notailcalls"), CompileOption("-uniqid")
  // )


  // Copy the ensime.jar, scala-library.jar and scala-compiler.jar to 
  // the bin directory, for conveniant running.
  lazy val dist = task {

    FileUtilities.clean(path("dist"), log)

    log.info("Copying runtime environment to ./dist....")

    createDirectories(List(
	path("dist"), 
	"dist" / "bin",
	"dist" / "lib",
	"dist" / "elisp"
      ), log)


    // Copy the emacs lisp to dist
    val elisp = "src" / "main" / "elisp" ** "*.el"
    copyFlat(elisp.get, "dist" / "elisp", log)


    // Copy the example configuration
    val dotEnsime = "." / ".ensime.example"
    copyFile(dotEnsime, "dist" / ".ensime.example", log)


    // Copy all the runtime dependencies over to dist
    copyFile(jarPath, "dist" / "lib" / "ensime.jar", log)
    copyFlat(mainDependencies.scalaJars.get, "dist" / "lib", log)
    val deps = managedClasspath(Runtime)
    copyFlat(deps.get, "dist" / "lib", log)


    // Grab all jars..
    val cpLibs = ("dist" / "lib" ** "*.jar").get.map{ p => 
      p.toString.replace("./dist/", "")
    }

    def writeScript(classpath:String, from:String, to:String){
      val tmplF = new File(from)
      readString(tmplF,log) match {
	case Right(tmpl) => {
	  val s = tmpl.replace("<RUNTIME_CLASSPATH>", classpath)
	  val f = new File(to)
	  write(f, s, log)
	  f.setExecutable(true)	
	}
	case _ => { 
	  log.error("Failed to load script template.") 
	}
      }
    }

    // Expand the server invocation script templates.

    writeScript(cpLibs.mkString(":"), 
      "./etc/scripts/server.sh",
      "./dist/bin/server.sh")

    writeScript("\"" + cpLibs.mkString(";") + "\"", 
      "./etc/scripts/server.bat",
      "./dist/bin/server.bat")



    copyFile(path("README.md"), "dist" / "README.md", log)
    copyFile(path("LICENSE"), "dist" / "LICENSE", log)

    val toZip = ("dist" ** "*")
    zip(toZip.get, path("ensime-" + version + ".zip"), false, log)

    None 
  } dependsOn(`package`) describedAs("Copy jars to bin folder for end-user conveniance.")


}

