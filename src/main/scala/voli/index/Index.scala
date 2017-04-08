package voli.index

import java.io.{BufferedReader, _}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import com.rklaehn.radixtree.RadixTree
import com.wantedtech.common.xpresso.x
import org.jsoup.nodes.Document

import scala.collection.JavaConverters._
import scala.collection.immutable.Set
import scala.collection.mutable
import scala.util.Properties

class Index(indexDir: String = "blocks") {
  type Index = Map[Term, (Frequency, Set[Postings])]

  private val orderByTerm = Ordering.by[Pair, String](_.line0.term).reverse

  Files.createDirectories(systemConfig.indexDir)

  private lazy val indexFile: RandomAccessFile = {
    if (Files.notExists(systemConfig.indexFilePath)) Files.createFile(systemConfig.indexFilePath)
    new RandomAccessFile(systemConfig.indexFilePath.toFile, "r")
  }

  private var tempIndex: Index = Map()
  private var blockNo = 0
  private[this] var _dictionary: RadixTree[String, Long] = RadixTree.empty


  def dictionary: RadixTree[Term, Long] = _dictionary

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

  def mergeBlocks(idxDir: String): Unit = {
    val out = new RandomAccessFile(Paths.get(s"$idxDir/index.txt").toFile, "rw")

    val dir = new File(idxDir)
    val filter: FilenameFilter = (_, name: String) => name.contains("block")

    if (dir.exists && dir.isDirectory) {
      val files = dir.listFiles(filter).map(io.Source.fromFile(_).bufferedReader())
      val readers: Seq[Pair] = toPairs(files)

      val queue = mutable.PriorityQueue[Pair](readers :_*)(orderByTerm)

      val dictionary = Stream.continually(queue).takeWhile(_.nonEmpty)
        .foldLeft(List[(String, Long)]())((dict, q) => q.headOption match {
          case None => dict
          case Some(head) =>
            val sameTermListings = dequeueWhile[Pair](q, _.line0.term == head.line0.term)
            val (lines, files) = sameTermListings.map(x => x.line0 -> x.file).unzip
            val merged = lines.foldLeft(EMPTY_LINE)(_.combine(_))

            queue.enqueue(toPairs(files): _*)
            val pointer = merged.term -> out.getFilePointer
            out.write((merged.toString + Properties.lineSeparator).getBytes(StandardCharsets.UTF_8))
            pointer :: dict
        })

      out.close()

      _dictionary = RadixTree(dictionary:_*).packed
      val dict = dictionary.map{case (term, pointer) => s"$term $pointer"}.mkString(Properties.lineSeparator)
      Files.write(systemConfig.dictionaryFilePath, dict.getBytes(StandardCharsets.UTF_8))
    }
  }

  def documentIndex(document: Document): Index = {
    val text = document.text()

    val tokens = for {
      sentence <- x.String.EN.tokenize(text).asScala
      word <- sentence.getWords.toArrayList.asScala if word != ";" //TODO get list of words to ignore from properties
    } yield word.toLowerCase

    tokens.groupBy(token => token)
      .map { case (term, terms) => term -> (terms.size, Set(document.location())) }
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

  def update(document: Document): Unit = {
    val index = documentIndex(document)
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

  def search(term: Term): List[String]  = {
    if (dictionary.isEmpty) {
      val pointers = Files.readAllLines(systemConfig.dictionaryFilePath, StandardCharsets.UTF_8)
        .asScala.map(_.split(" "))
        .map{ case Array(term_, pointer) => term_ -> pointer.trim.toLong }
      _dictionary = RadixTree(pointers:_*).packed
    }
    if (!dictionary.contains(term)) List()
    else {
      val pointer: Long = dictionary(term)
      indexFile.seek(pointer)
      val line = indexFile.readLine()
      Line(line).postings
    }
  }
}

