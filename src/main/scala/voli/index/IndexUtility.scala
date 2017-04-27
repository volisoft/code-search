package voli.index

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import com.rklaehn.radixtree.RadixTree

import scala.collection.JavaConverters._

object IndexUtility {
  private lazy val indexFile: RandomAccessFile = {
    if (Files.notExists(systemConfig.indexFilePath)) Files.createFile(systemConfig.indexFilePath)
    new RandomAccessFile(systemConfig.indexFilePath.toFile, "r")
  }

  private[this] var _dictionary: RadixTree[String, Long] = RadixTree.empty

  def dictionary: RadixTree[Term, Long] = _dictionary


  def search(term: Term): List[String]  = {
    initDictionary()

    if (!dictionary.contains(term)) List()
    else {
      val pointer: Long = dictionary(term)
      indexFile.seek(pointer)
      val line = indexFile.readLine()
      Line(line).postings
    }
  }

  private def initDictionary(): Unit = {
    if (dictionary.isEmpty) {
      val pointers = Files.readAllLines(systemConfig.dictionaryFilePath, StandardCharsets.UTF_8)
        .asScala.map(_.split(" "))
        .map{ case Array(term_, pointer) => term_ -> pointer.trim.toLong }
      _dictionary = RadixTree(pointers:_*).packed
    }
  }
}
