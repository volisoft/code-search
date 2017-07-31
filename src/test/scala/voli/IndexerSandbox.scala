package voli

import org.jsoup.Jsoup
import org.scalatest.FlatSpec
import voli.index.Index

import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.mutable

class IndexerSandbox extends FlatSpec {

  private val docString = io.Source.fromURL(getClass.getResource("/test.html")).getLines().mkString("\n")

  "Index" should "produce lowercase terms" in {
    val document = Jsoup.parse(docString)
    val indexer = new Index()
    //todo init index with "delete_me_1" directory
    indexer.update(document)
    io.Source.fromURL(getClass.getResource("/delete_me_1"))
  }

  it should "match index sorting and priority queue order" in {
    val document = Jsoup.parse(docString)
    val idx = new Index().documentIndex(document.text(), document.location())
    val sortedterms = idx.entrySet().asScala.toList.sortBy(_.getKey).map(_.getKey)
    val keys = idx.keySet()
    val queue = mutable.PriorityQueue[String](keys.asScala.toArray: _*)(Ordering.String.reverse)

    sortedterms.foreach( term => assert(term == queue.dequeue()))
  }


}
