package voli.web

import java.io.RandomAccessFile

import com.google.gson.Gson
import ro.pippo.core.Application
import ro.pippo.core.route.RouteContext
import voli.{Crawler, config}

import scala.collection.JavaConverters._

class App extends  Application {
  override def onInit(): Unit = {
    addPublicResourceRoute()
    addWebjarsResourceRoute()

    GET("/", (ctx: RouteContext) => ctx.render("main.mustache"))
    GET("/api/search", (ctx: RouteContext) => {

//      val pointer: Long = Crawler.index0.dictionary.getOrElse(ctx.getRequest.getQueryParameter("q").toString, 0)
//      val indexFile = new RandomAccessFile(config.indexFile.toFile, "r")
//      indexFile.seek(pointer)
//      val line = new Index(1).Line(indexFile.readLine())
      val gson = new Gson()
      val o1 = Map("title" -> "test1", "description" -> "desc1").asJava
      val o2 = Map("title" -> "test2", "description" -> "desc2").asJava
      ctx.json().send(gson.toJson(List(o1, o2).asJava))
    })
  }
}
