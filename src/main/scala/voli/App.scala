package voli

import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.actor.ActorSubscriberMessage.{OnComplete, OnError, OnNext}
import akka.stream.actor._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, MergePreferred, RunnableGraph, Sink, SinkQueueWithCancel, Source, SourceQueue, SourceQueueWithComplete}
import com.google.common.io
import com.google.common.io.Files
import org.apache.qpid.server.{Broker, BrokerOptions}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConverters._
import scala.collection.immutable._
import scala.collection.mutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Success


/**
  * Created by vadym.oliinyk on 12/22/16.
  */
object App {


  implicit val system = ActorSystem("Crawler")
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  implicit val settings: ActorMaterializerSettings = ActorMaterializerSettings(system).withDebugLogging(true).withAutoFusing(false)
  implicit val materializer: ActorMaterializer = ActorMaterializer(settings)(system)

  val rootUrl = "http://citforum.ru"


  def parse(response: HttpResponse, url: String): Future[Document] = {
    val doc = Unmarshal(response.entity.withoutSizeLimit())
      .to[String]
      .map(Jsoup.parse(_, url))(dispatcher)
    response.entity.discardBytes()
    doc
  }

  def index0(document: Document) = document

  def getUrls(document: Document): List[String] =
    document
      .select("a[abs:href]").asScala
      .map { h => h.attr("href") }
      .filter(url => url.contains(document.location()))
      .toList

  var saved: Set[String] = Set()

  def notVisited(url: String): Boolean =
    !saved(url)
  def saveVisited(url: String): String = {
    saved += url
    url
  }

  def main(args: Array[String]): Unit = {

    val count = new AtomicInteger()

    val in: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](2056, OverflowStrategy.fail).log(":in")
//    val urlsSink: Sink[String, SinkQueueWithCancel[String]] = Sink.queue[String]()
    val urlsSink = Sink.ignore

    startBroker()

    val g = RunnableGraph.fromGraph(GraphDSL.create(in, urlsSink)((_,_)){ implicit b => (in, urlsSink) =>
      import GraphDSL.Implicits._


//      val in = Source((1 to 1000).map(x => "http://citforum.ru"))
//      val in = Source.queue[String](1000, OverflowStrategy.backpressure)
//      val urlsSink: Sink[String, NotUsed] = Sink.fromSubscriber(urlConsumer)

      val indexSink = Sink.foreach[Document](_ => println(count.incrementAndGet()))

      val pool = Http().superPool[String]()(materializer).log(":pool")
      val download: Flow[String, Document, NotUsed] = Flow[String]
            .map(url => (HttpRequest(method = HttpMethods.GET, Uri(url)), url) )
            .via(pool)
            .mapAsyncUnordered(50){ case (Success(response: HttpResponse), url) => parse(response, url)}


      val bcast = b.add(Broadcast[Document](2))

      val filter = Flow[String].filter(notVisited)
      val save = Flow[String].map(saveVisited)


      val index = Flow[Document].map(document => index0(document)).log(":index", doc => doc.location())
      val extractLinks: Flow[Document, String, NotUsed] = Flow[Document].mapConcat(document => getUrls(document))
        .log(":extract")

      val merge = b.add(MergePreferred[String](1))
      val bcast1 = b.add(Broadcast[String](2))

            merge.preferred                        <~                       bcast1
      in ~> merge ~> save ~> download ~> bcast ~> extractLinks ~> filter ~> bcast1 ~> urlsSink
                                         bcast ~> index        ~> indexSink


      ClosedShape
    })

  val (inQueue: SourceQueueWithComplete[String], outQueue) = g.run()


    inQueue offer rootUrl
    inQueue.watchCompletion().map(x => println(":inqueue -> " + x))

    var buffer = Set[String]()

    def pull(q:SinkQueueWithCancel[String]):Unit = {
      q.pull().map {
        case Some(url) =>
          buffer = buffer + url
          inQueue offer url map( x => println(":next " + x))
          pull(q)
        case None => println(":completed")
        case _ => println(":error")
      }

    }

//    pull(outQueue)

    outQueue.map{
      case x =>
        println(x)
        x
    }


//    inQueue.complete()

  }

  val BROKER_PORT = "5672"

  def startBroker() = {
    val broker = new Broker()
    val brokerOptions = new BrokerOptions()

    val configFileName = "/qpid-config.json"

    brokerOptions.setConfigProperty("broker.name", "embedded-broker")
    brokerOptions.setConfigProperty("qpid.amqp_port", BROKER_PORT)
    brokerOptions.setConfigProperty("qpid.work_dir", Files.createTempDir().getAbsolutePath)
    brokerOptions.setInitialConfigurationLocation(getClass.getResource(configFileName).toString)
    broker.startup(brokerOptions)
  }

}