package voli

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
import java.lang.reflect.Method
import java.nio.file.{Path, Paths}

import org.aeonbits.owner.Config.{ConverterClass, Key, Sources}
import org.aeonbits.owner.{Config, ConfigFactory, Converter}

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
      assert(other.term == term)
      Line(term, other.freq + freq, s"$docs,${other.docs}")
    }

    def postings: List[String] = docs.split(",").toList

    override def toString: String = s"$term${systemConfig.columnSeparator}$freq${systemConfig.columnSeparator}$docs"
  }

  object Line {
    def apply(line: String): Line = {
      val term::freq::docs::_ = line.split(systemConfig.columnSeparator).toList
      Line(term, freq.toInt, docs)
    }
  }

  case class SystemConfig(properties: SystemProperties) {
    def indexFilePath: Path = this.indexDir.resolve(this.indexFileName)
    def dictionaryFilePath: Path = this.indexDir.resolve(this.dictionaryFileName)
  }

  object SystemConfig {
    implicit def delegateToProperties(config: SystemConfig): SystemProperties = config.properties
  }

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
  }

  class PathConverter extends Converter[Path] {
    override def convert(method: Method, path: String): Path = Paths.get(path).toAbsolutePath
  }
}
