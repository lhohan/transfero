

import akka.actor._

import java.nio.file._
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import StandardWatchEventKinds._
import scala.collection.JavaConversions._

/**
 * User: hanlho
 * DateTime: 31/08/13 12:03
 *
 * Playing with akka.
 *
 * A contrived code sample that monitors a file location
 * and when files are found they are copied/moved to different location.
 *
 */


package object transfero {

  type PathFilter = Path => Boolean

  case class StartMonitoring()

  sealed trait FileCommand

  case class CopyFile(file: Path, target: Path) extends FileCommand

  case class MoveFile(file: Path, target: Path) extends FileCommand

  case class Monitor()

  case class MonitorConfig(srcLocation: String, targetLocation: String, processingType: ProcessingType, filter: PathFilter = {
    path: Path => true
  })


  // logging
  val IsDebugEnabled = true
  val IsInfoEnabled = true

  // quick log debug
  def ld(msg: String) = {
    // akka logger?
    if (IsDebugEnabled) println("DEBUG " + msg)
  }

  // quick log info
  def li(msg: String) = {
    // akka logger?
    if (IsInfoEnabled) println("INFO " + msg)
  }

  sealed trait ProcessingType {
    def command(fileToProcess: Path, target: Path): FileCommand
  }

  case object Move extends ProcessingType {
    override def command(file: Path, target: Path): FileCommand = {
      new MoveFile(file, target)
    }
  }

  case object Copy extends ProcessingType {
    override def command(file: Path, target: Path): FileCommand = {
      new CopyFile(file, target)
    }
  }


}


package transfero {

object LocationMonitorMain extends App {


  //  val configs = testSetup
  val configs = readConfigFromFile

  for (config <- configs) {
    new FileProcessor(config).monitor()
  }

  def readConfigFromFile: List[MonitorConfig] = {

    def toProcessingType(processingTypeString: String): ProcessingType = processingTypeString.toLowerCase.trim match {
      case "copy" => Copy
      case "move" => Move
      case _ => throw new IllegalArgumentException("Unknown processing type: " + processingTypeString + ". Should be 'copy, 'move', ..")
    }

    val configFile = {
      if (args.size > 0) {
        Paths.get(args(0))
      } else {
        Paths.get("monitor.config")
      }
    }

    if (!Files.exists(configFile)) {
      throw new IllegalArgumentException("Monitor config file is missing.\n" +
        "Create a 'monitor.config' file. Example content:\n" +
        "\tCopy,src/test/resources/file-copy-test-dir/src,target\n" +
        "\tMove,src/test/resources/file-move-test-dir/src,target")
    }

    //TODO : try to improve :

    val lines: Iterator[String] = Source.fromFile(configFile.toFile).getLines()
      .map {
      _.trim
    }
      .filter {
      !_.isEmpty
    }
      .filter {
      !_.startsWith("#")
    }

    val ext: String = "mda"
    val filter: PathFilter = {
      path: Path =>
        path.toString.endsWith(ext)
    }


    val configs = new ArrayBuffer[MonitorConfig]()
    for (configLine <- lines) {
      ld("reading config line: " + configLine)
      val config: Array[String] = configLine.split(",")
      try {
        configs += new MonitorConfig(config(1), config(2), toProcessingType(config(0)), filter)
      } catch {
        case e: Throwable =>
          li("Error reading config line: \n" + configLine + "\n => Error is: " + e.getMessage)
      }
    }
    configs.toList
  }

}


object FileProcessor {

  val system = ActorSystem("FileCopySystem")
  val fileCopier = system.actorOf(Props(new FileProcessor()), name = "file-copier")
  val monitorCounter = new AtomicInteger()

  class FileProcessor() extends Actor {
    def receive = {
      case CopyFile(file, targetLocation) =>
        li("copying file " + file.getFileName + " to " + targetLocation)
        copyFileToLocation(file, targetLocation)
      case MoveFile(file, targetLocation) =>
        li("moving file " + file.getFileName + " to " + targetLocation)
        FileProcessor.moveFileToLocation(file, targetLocation)
    }
  }


  def copyFileToLocation(file: Path, targetLocation: Path) = {
    Files.copy(file, targetLocation.resolve(file.getFileName), StandardCopyOption.REPLACE_EXISTING)
  }

  def moveFileToLocation(file: Path, targetLocation: Path) = {
    Files.move(file, targetLocation.resolve(file.getFileName), StandardCopyOption.REPLACE_EXISTING)
  }
}

class FileProcessor(config: MonitorConfig) {

  // TODO : I do not understand why I need to import this class' companion object...

  import FileProcessor._

  val monitorLocation = Paths.get(config.srcLocation)
  val targetLocation = Paths.get(config.targetLocation)

  if (!Files.isDirectory(monitorLocation)) {
    throw new IllegalArgumentException("Location to monitor [" + monitorLocation + "]should be directory.")
  }
  if (!Files.isDirectory(targetLocation)) {
    throw new IllegalArgumentException("Target location [" + targetLocation + "]should be directory.")
  }

  def monitor() {
    val locationMonitor = system.actorOf(Props(
      new LocationMonitor(monitorLocation, targetLocation, config.processingType, fileCopier, config.filter)
    ), name = "locationMonitor-" + monitorCounter.incrementAndGet())
    locationMonitor ! StartMonitoring()
  }

  class LocationMonitor(location: Path, targetLocation: Path, processingType: ProcessingType, fileProcessor: ActorRef, pathFilter: PathFilter) extends Actor {
    val watcher = FileSystems.getDefault().newWatchService()
    location.register(watcher, ENTRY_CREATE)

    def receive = {
      case Monitor() =>
        val watchKey = watcher.take()
        for (event <- watchKey.pollEvents()) {
          val path = event.context().asInstanceOf[Path]
          ld("watcher found: " + path)
          if (pathFilter(path)) {
            fileProcessor ! processingType.command(location.resolve(path), targetLocation)
          } else {
            ld("... skipped, does not match filter")
          }
        }
        watchKey.reset()
        self ! Monitor()
      case StartMonitoring() =>
        li("Start monitoring location for new files: " + location + "\n" +
          "\ttarget (using " + processingType + ") is " + targetLocation)
        self ! Monitor()
    }
  }

}

}

