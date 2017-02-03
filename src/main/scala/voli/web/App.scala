package voli.web

import com.google.gson.Gson
import ro.pippo.core.Application
import ro.pippo.core.route.RouteContext
import voli.index.Index

import scala.collection.JavaConverters._

class App extends  Application {
  val index = new Index()
  val gson = new Gson()

  override def onInit(): Unit = {
    addPublicResourceRoute()
    addWebjarsResourceRoute()

    GET("/", (ctx: RouteContext) => ctx.render("main.ftl"))
    GET("/api/search", (ctx: RouteContext) => {
      val results = index.search(ctx.getRequest.getQueryParameter("q").toString)
      ctx.json().send(gson.toJson(results.asJava))
    })
  }
}
