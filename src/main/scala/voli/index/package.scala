package voli

import java.io._
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import kamon.Kamon
import org.aeonbits.owner.Config._
import org.aeonbits.owner.{Config, ConfigFactory, Converter}
import org.apache.qpid.server.{Broker, BrokerOptions}
import voli.index.mergeBlocks

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.compat.java8.StreamConverters._
import scala.concurrent.duration.{FiniteDuration, SECONDS, _}
import scala.util.Properties

package object index {
  type Term = String
  type Postings = String
  type Frequency = Int

  val systemConfig: SystemConfig = SystemConfig(ConfigFactory.create(classOf[SystemProperties]))


  def hitMemoryLimit(obj: Any): Boolean = {
    val baos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(obj)
    baos.toByteArray.length > systemConfig.maxMemory
  }


  case class Line(term: Term, freq: Frequency, docs: Postings) {
    def combine(other: Line): Line = {
      assert(other.term == term || other == EMPTY_LINE || this == EMPTY_LINE)
      val term0 = if (other.term == "") term else other.term
      Line(term0, other.freq + freq, s"$docs,${other.docs}")
    }

    def postings: List[String] = docs.split(systemConfig.columnSeparator).toList

    override def toString: String = s"$term${systemConfig.columnSeparator}$freq${systemConfig.columnSeparator}$docs"
  }

  object Line {
    def apply(line: String): Line = {
      val term::freq::docs::_ = line.split(systemConfig.columnSeparator).toList
      Line(term.trim, freq.toInt, docs)
    }
  }

  val EMPTY_LINE = Line("", 0, "")

  case class SystemConfig(properties: SystemProperties) {
    def indexFilePath: Path = this.indexDir.resolve(this.indexFileName)
    def dictionaryFilePath: Path = this.indexDir.resolve(this.dictionaryFileName)
    def excludedTokens: List[String] = (systemConfig.javaReservedWords.asScala ++ systemConfig.specialChars.asScala).toList
  }

  object SystemConfig {
    implicit def delegateToProperties(config: SystemConfig): SystemProperties = config.properties
  }

  @HotReload(value = 10L, unit = TimeUnit.SECONDS)
  @Sources(Array("file:/etc/index.properties", "classpath:index.properties"))
  trait SystemProperties extends Config {
    @Key("index.max-memory")
    def maxMemory: Int

    @Key("index.dir") @ConverterClass(classOf[PathConverter])
    def indexDir: Path

    @Key("index.filename")
    def indexFileName: String

    @Key("dictionary.filename")
    def dictionaryFileName: String

    @Key("index.column-separator")
    def columnSeparator: String

    @Separator(",")
    @Key("index.exclude.java-reserved")
    def javaReservedWords: java.util.List[String]

    @Separator(",")
    @Key("index.exclude.special")
    def specialChars: java.util.List[String]

    @Key("index.document_repo_credentials")
    def credentials: String

    @Separator(",")
    @Key("index.root_urls")
    def rootUrls: java.util.List[String]
  }

  class PathConverter extends Converter[Path] {
    override def convert(method: Method, path: String): Path = Paths.get(path).toAbsolutePath
  }

  def blockFiles(): Seq[File] = {
    val dir = systemConfig.indexDir
    val filter = dir.getFileSystem.getPathMatcher("glob:**/.idx*")
    if (Files.exists(dir) && Files.isDirectory(dir)){
      Files.list(dir)
        .filter(filter.matches(_))
        .toScala[Seq]
        .map(_.toFile())
    }
    else throw new Error("Cannot find blocks")
  }

  def dequeueWhile[T](queue: mutable.PriorityQueue[T], cond: (T) => Boolean): List[T] = {
    def loop(acc: List[T]): List[T] = {
      if(queue.nonEmpty && cond(queue.head)) loop(queue.dequeue::acc)
      else acc
    }
    loop(List())
  }

  private val orderByTerm = Ordering.by[Pair, String](_.line0.term).reverse

  case class Pair(line0: Line, file: BufferedReader)

  def toPairs(files: Seq[BufferedReader]): Seq[Pair] = {
    for {
      f <- files
      l <- Option(f.readLine())
    } yield Pair(Line(l), f)
  }

  def mergeBlocks(blockFiles: Seq[File], indexFile: RandomAccessFile, dictionaryFile: Path): Unit = {
    val files = blockFiles.map(io.Source.fromFile(_).bufferedReader())
    val readers: Seq[Pair] = toPairs(files)

    val queue = mutable.PriorityQueue[Pair](readers :_*)(orderByTerm)

    val dictionary = Stream
      .continually(queue)
      .takeWhile(_.nonEmpty)
      .foldLeft(List[(String, Long)]())((dictionary0, queue0) => queue0.headOption match {
        case None => dictionary0
        case Some(head) =>
          val sameTermListings = dequeueWhile[Pair](queue0, _.line0.term == head.line0.term)
          val (lines, files) = sameTermListings.map(x => x.line0 -> x.file).unzip
          queue.enqueue(toPairs(files): _*)
          val merged = lines.foldLeft(EMPTY_LINE)(_.combine(_))
          val pointer = merged.term -> indexFile.getFilePointer
          indexFile.write((merged.toString + Properties.lineSeparator).getBytes(StandardCharsets.UTF_8))
          pointer :: dictionary0
      })

    indexFile.close()

    val dict = dictionary.map{case (term, pointer) => s"$term $pointer"}.mkString(Properties.lineSeparator)
    Files.write(dictionaryFile, dict.getBytes(StandardCharsets.UTF_8))
  }

  def flowRate[T](metric: T => Int = (_: T) => 1, outputDelay: FiniteDuration = 1 second): Flow[T, Double, NotUsed] =
    Flow[T]
      .conflateWithSeed(metric(_)){ case (acc, x) ⇒ acc + metric(x) }
      .zip(Source.tick(outputDelay, outputDelay, NotUsed))
      .map(_._1.toDouble / outputDelay.toUnit(SECONDS))

//  def printFlowRate[T](name: String, metric: T => Int = (_: T) => 1,
//                       outputDelay: FiniteDuration = 1 second): Flow[T, T, NotUsed] =
//    Flow[T]
//      .alsoTo(flowRate[T](metric, outputDelay)
//        .to(Sink.foreach(r => logger.info(s"Rate($name): $r"))))

  def meter[T](name: String): Flow[T, T, NotUsed] = {
    val msgCounter = Kamon.metrics.counter(name)

    Flow[T].map { x =>
      msgCounter.increment()
      x
    }
  }

  def startBroker(): Unit = {
    val broker = new Broker()
    val brokerOptions = new BrokerOptions()

    val configFileName = "/qpid-config.json"

    brokerOptions.setConfigProperty("broker.name", "embedded-broker")
    brokerOptions.setConfigProperty("qpid.amqp_port", "5672")
    brokerOptions.setConfigProperty("qpid.work_dir", com.google.common.io.Files.createTempDir().getAbsolutePath)
    brokerOptions.setInitialConfigurationLocation(getClass.getResource(configFileName).toString)
    broker.startup(brokerOptions)
  }

}

object test {
  def main(args: Array[String]): Unit = {
    val dir = Paths.get("/Users/user/workspace/wgu/delete/cvs-search/blocks")
    val pathMatcher = dir.getFileSystem.getPathMatcher("glob:**/block*")

    val b: Seq[File] = Files
      .list(dir)
      .filter(pathMatcher.matches(_))
      .toScala[Seq]
      .map(_.toFile)

    val indexFile = new RandomAccessFile(Paths.get("/Users/user/workspace/wgu/delete/cvs-search/blocks/index.txt").toFile, "rw")

    mergeBlocks(b,
      indexFile,
      Paths.get("/Users/user/workspace/wgu/delete/cvs-search/blocks/dict.txt"))
  }
}
