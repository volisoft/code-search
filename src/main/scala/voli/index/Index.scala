package voli.index

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import com.wantedtech.common.xpresso.x
import org.jsoup.nodes.Document

import scala.collection.JavaConverters._
import scala.collection.immutable.Set
import scala.util.Properties

class Index(indexDir: String = "blocks") {
  type Index = Map[Term, (Frequency, Set[Postings])]

  Files.createDirectories(systemConfig.indexDir)

  private var tempIndex: Index = Map()
  private var blockNo = 0

  def sameTerm(p1: Option[(Line, Any)], p2: Option[(Line, Any)]): Boolean = (p1, p2) match {
    case (Some((l1, _)), Some((l2, _))) => l1.term == l2.term
    case _ => false
  }

  def documentIndex(document: String, location: String): Index = {
    val text = /*if (document.contains("<html>")) Jsoup.parse(document).text() else*/ document
    val tokens = for {
      token<- text.split("[\\p{Z}\\s]+")
      sentence <- x.String.EN.tokenize(token).asScala
      word <- sentence.getWords.toArrayList.asScala if !systemConfig.excludedTokens.contains(word)
    } yield word.toLowerCase

    tokens.groupBy(token => token)
      .map { case (term, terms) => term -> (terms.size, Set(location)) }
  }

  def mergeIndices(i1: Index, i2: Index): Index = {
    val combined = i1.toSeq ++ i2.toSeq
    val grouped: Map[Term, Seq[(Term, (Frequency, Set[String]))]] = combined.groupBy(_._1)
    val emptyListing = (0, Set.empty[String])
    grouped.mapValues(
      _.map(_._2).foldLeft(emptyListing) {
        case ((lFreq, lDocs), (rFreq, rDocs)) => (lFreq + rFreq, lDocs ++ rDocs)
      })
  }

  def update(document: Document): Unit = update(document.text(), document.location())

  def update(document: String, url: String): Unit = {
    val index = documentIndex(document, url)
    tempIndex = mergeIndices(tempIndex, index)

    if (hitMemoryLimit(tempIndex.toList)) flush()
  }

  def flush(): Unit = {
    blockNo += 1
    val blockFile = Paths.get(s"$indexDir/block$blockNo.txt")
    Files.createFile(blockFile)
    val out = Files.newBufferedWriter(blockFile, StandardCharsets.UTF_8)

    tempIndex.toSeq.sortBy{ case (term, (_, _)) => term}.foreach{
      case (term, (freq, documents)) =>
        val line = Line(term, freq, documents.mkString(",")).toString + Properties.lineSeparator
        out.write(line)
    }

    out.close()
    tempIndex = Map()
  }
}

