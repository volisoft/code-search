package voli

import org.jsoup.Jsoup
import org.scalatest.FlatSpec

import scala.collection.mutable

class IndexerSandbox extends FlatSpec {

  private val docString = io.Source.fromURL(getClass.getResource("/test.html")).getLines().mkString("\n")

  "Index" should "produce lowercase terms" in {
    val document = Jsoup.parse(docString)
    val indexer = new Index(0, "delete_me_1")
    indexer.update(document)
    io.Source.fromURL(getClass.getResource("/delete_me_1"))
  }

  it should "match index sorting and priority queue order" in {
    val idx = new Index(0).documentIndex(Jsoup.parse(docString))
    val sortedterms = idx.toSeq.sortBy(_._1).map(_._1)
    val keys: Iterable[String] = idx.keys
    val queue = mutable.PriorityQueue[String](keys.toArray: _*)(Ordering.String.reverse)

    sortedterms.foreach( term => assert(term == queue.dequeue()))
  }


}
