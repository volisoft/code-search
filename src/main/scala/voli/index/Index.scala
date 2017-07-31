package voli.index

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util

import com.rklaehn.radixtree.RadixTree
import org.jsoup.nodes.Document

import scala.collection.JavaConverters._
import scala.util.Properties

class Index {
  type Occurrences = RadixTree[Postings, Frequency]
  type Index = util.Map[Term, Occurrences]

  Files.createDirectories(systemConfig.indexDir)

  private var tempIndex: Index = newIndex()

  private def newIndex(): Index = new util.HashMap[Term, Occurrences](10000)

  private val emptyTree: Occurrences = RadixTree.empty

  def sameTerm(p1: Option[(Line, Any)], p2: Option[(Line, Any)]): Boolean = (p1, p2) match {
    case (Some((l1, _)), Some((l2, _))) => l1.term == l2.term
    case _ => false
  }

  def documentIndex(document: String, location: String): Index = {
    val map: Index = new util.HashMap(10000)
    tokenize(document).foldLeft(map)
    { (m, token) =>
      val maybeOccurrences = m.getOrDefault(token, emptyTree)
      val occs = RadixTree.singleton(location, 1)
      val merged = maybeOccurrences.mergeWith(occs, _ + _)
      m.put(token, merged)
      m
    }
  }

  def update(document: Document): Unit = update(document.text(), document.location())

  def update(document: String, url: String): Unit = {
    val docIndex = documentIndex(document, url)
    val entries = docIndex.entrySet()
    val it = entries.iterator()
    while (it.hasNext) {
      val entry = it.next()
      val key: Term = entry.getKey
      val indexedOcc: Occurrences = tempIndex.getOrDefault(key, emptyTree)
      val updatedOcc: Occurrences = indexedOcc.mergeWith(entry.getValue, _ + _)
      tempIndex.put(key, updatedOcc)
    }

    if (tempIndex.size() > 40000) flush()
  }

  def flush(): Unit = {
    val tmpFile = Files.createTempFile(systemConfig.indexDir, "", ".idx")
    val out = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8)

    tempIndex.entrySet().asScala.toList
      .sortBy(_.getKey)
      .foreach {
        entry =>
          val freq = entry.getValue.values.sum
          val postings = entry.getValue.keys.mkString(",")
          val line = Line(entry.getKey, freq, postings).toString + Properties.lineSeparator
          out.write(line)
      }

    out.close()
    tempIndex = newIndex()
  }

  private val nbsp: Char = 0x00A0
  private val sp: Char = 0x0020
  private val nl: Char = 0xA
  private val cr: Char = 0xD

  def tokenize(s: String): List[String] =  {
    var strings = List[String]()
    var index = 0
    val chars = s.toCharArray
    var i = 0
    while (i < chars.length) {
      val ch = chars(i)
      if (ch == sp || ch == nbsp || ch == nl || ch == cr) {
        strings = s.substring(index, i) :: strings
        index = i + 1
      }
      i += 1
    }

    s.substring(index, chars.length) :: strings
  }
}

