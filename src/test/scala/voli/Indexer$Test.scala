package voli

import java.nio.file.Paths

import org.scalatest.FlatSpec

class Indexer$Test extends FlatSpec {

  "Merge blocks" should "combine and output to file in sorted order" in {
    new Indexer().mergeBlocks(Paths.get(getClass.getClassLoader.getResource("blocks/test2").toURI).toAbsolutePath.toString)
  }



}
