package voli.index

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file
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

  private val readerOrdering = Ordering.by[(Line, BufferedReader), String]{case (line, _) => line.term}.reverse

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

  def mergeBlocks(idxDir: String): Unit = {
    val writer = file.Files.newBufferedWriter(Paths.get(s"$idxDir/index.txt"))

    val dir = new File(idxDir)
    val filter: FilenameFilter = (_, name: String) => name.contains("block")

    if (dir.exists && dir.isDirectory) {
      val readers: Array[(Line, BufferedReader)] = dir.listFiles(filter)
        .map(file => {
          val reader = io.Source.fromFile(file).bufferedReader()
          (Line(reader.readLine()), reader)
        })

      val queue = mutable.PriorityQueue[(Line, BufferedReader)](readers:_*)(readerOrdering)

      while(queue.nonEmpty) {
        val (line, reader) = queue.dequeue()
        val (linesToMerge, _) = queue.filter(_._1.term == line.term).unzip
        val merged = linesToMerge.foldLeft(line)(_.combine(_))

        (1 to linesToMerge.size).foreach { _ =>
          val (_, r) = queue.dequeue()
          val line0 = r.readLine()
          if (line0 != null) queue.enqueue((Line(line0), r))
        }

        val nextLine = reader.readLine()
        if (nextLine != null && nextLine.trim.nonEmpty) queue.enqueue((Line(nextLine), reader))

        writer.write(merged.toString)
        writer.newLine()
      }
      writer.close()
    }
  }

  def documentIndex(document: Document): Index = {
    val text = document.text()

    val tokens = for {
      sentence <- x.String.EN.tokenize(text).asScala
      word <- sentence.getWords.toArrayList.asScala if word != ";"
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
    val out = new RandomAccessFile(blockFile.toFile, "rw")

    val pointers = tempIndex.toSeq.map {
      case (term, (freq, documents)) =>
        val pointer = term -> out.getFilePointer
        val line = Line(term, freq, documents.mkString(",")).toString + Properties.lineSeparator
        out.write(StandardCharsets.UTF_8.encode(line).array())
        pointer
    }

    val dict = pointers.map{case (term, pointer) => s"$term $pointer"}.mkString(Properties.lineSeparator)
    Files.write(systemConfig.dictionaryFilePath, StandardCharsets.UTF_8.encode(dict).array())

    out.close()
    tempIndex = Map()
    _dictionary = RadixTree(pointers:_*).packed
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

