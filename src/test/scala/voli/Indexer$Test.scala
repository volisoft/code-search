package voli

import java.io.RandomAccessFile

import org.jsoup.Jsoup
import org.scalatest.FlatSpec
import voli.index.Index

class Indexer$Test extends FlatSpec with TestIO{
  private val docString = io.Source.fromURI(testDirPath.resolve("test.html").toUri).getLines().mkString("\n")

  "Merge blocks" should "combine and output to file in sorted order" in {
    new Index().mergeBlocks(voli.index.blockFiles(),
      new RandomAccessFile(testDirPath.resolve("blocks/test2").toFile, "rw"),
      voli.index.systemConfig.dictionaryFilePath)
  }

  it should "create dictionary" in {
    val idx = new Index(indexDir = s"$testDirStringPath/out/indexTest1")
    idx.update(Jsoup.parse(docString))
  }



}
