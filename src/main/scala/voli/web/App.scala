package voli.web

import com.google.gson.Gson
import ro.pippo.core.Application
import ro.pippo.core.route.RouteContext
import voli.index.IndexUtility

import scala.collection.JavaConverters._

class App extends  Application {
  val gson = new Gson()

  override def onInit(): Unit = {
    addPublicResourceRoute()
    addWebjarsResourceRoute()

    GET("/", (ctx: RouteContext) => ctx.render("main.ftl"))
    GET("/api/search", (ctx: RouteContext) => {
      val results = IndexUtility.search(ctx.getRequest.getQueryParameter("q").toString)
      ctx.json().send(gson.toJson(results.asJava))
    })
  }
}
