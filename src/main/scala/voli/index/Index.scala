package voli.index

import java.io.{BufferedReader, _}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import com.wantedtech.common.xpresso.x
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConverters._
import scala.collection.immutable.Set
import scala.collection.mutable
import scala.util.Properties

class Index(indexDir: String = "blocks") {
  type Index = Map[Term, (Frequency, Set[Postings])]

  private val orderByTerm = Ordering.by[Pair, String](_.line0.term).reverse

  Files.createDirectories(systemConfig.indexDir)

  private var tempIndex: Index = Map()
  private var blockNo = 0

  def sameTerm(p1: Option[(Line, Any)], p2: Option[(Line, Any)]): Boolean = (p1, p2) match {
    case (Some((l1, _)), Some((l2, _))) => l1.term == l2.term
    case _ => false
  }

  case class Pair(line0: Line, file: BufferedReader)

  def toPairs(files: Seq[BufferedReader]): Seq[Pair] = {
    for {
      f <- files
      l <- Option(f.readLine())
    } yield Pair(Line(l), f)
  }

  def dequeueWhile[T](queue: mutable.PriorityQueue[T], cond: (T) => Boolean): List[T] = {
    def loop(acc: List[T]): List[T] = {
      if(queue.nonEmpty && cond(queue.head)) loop(queue.dequeue::acc)
      else acc
    }
    loop(List())
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

  def documentIndex(document: String, location: String): Index = {
    val text = if (document.contains("<html>")) Jsoup.parse(document).text() else document
    val tokens = for {
      sentence <- x.String.EN.tokenize(text).asScala
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

