package voli

import org.jsoup.Jsoup
import org.scalatest.FlatSpec
import voli.index.Index

class Indexer$Test extends FlatSpec with TestIO{
  private val docString = io.Source.fromURI(testDirPath.resolve("test.html").toUri).getLines().mkString("\n")

  "Merge blocks" should "combine and output to file in sorted order" in {
    new Index().mergeBlocks(testDirPath.resolve("blocks/test2").toString)
  }

  it should "create dictionary" in {
    val idx = new Index(indexDir = s"$testDirStringPath/out/indexTest1")
    idx.update(Jsoup.parse(docString))
    idx.dictionary
  }



}
