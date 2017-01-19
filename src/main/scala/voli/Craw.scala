package voli

import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.alpakka.amqp._
import akka.stream.alpakka.amqp.scaladsl.{AmqpSink, AmqpSource}
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Source}
import akka.util.ByteString
import com.google.common.io.Files
import org.apache.qpid.server.{Broker, BrokerOptions}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConverters._
import scala.collection.immutable.{List, Set}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Success

object Craw {


  implicit val system = ActorSystem("Crawler")
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  val settings = ActorMaterializerSettings(system)
  implicit val materializer: ActorMaterializer = ActorMaterializer(settings)(system)

  val rootUrl = "http://changeme"


  def parse(response: HttpResponse, url: String): Future[Document] = {
    Unmarshal(response.entity.withoutSizeLimit())
      .to[String]
      .map(Jsoup.parse(_, url))(dispatcher)
  }

  def getUrls(document: Document): List[String] = {
    val hrefs = document
      .select("a[abs:href]").asScala
      .map { h => h.absUrl("href") }
    hrefs
      .filter(url => url.contains(rootUrl))
      .toList
  }

  val BROKER_PORT = "5672"

  def startBroker(): Unit = {
    val broker = new Broker()
    val brokerOptions = new BrokerOptions()

    val configFileName = "/qpid-config.json"

    brokerOptions.setConfigProperty("broker.name", "embedded-broker")
    brokerOptions.setConfigProperty("qpid.amqp_port", BROKER_PORT)
    brokerOptions.setConfigProperty("qpid.work_dir", Files.createTempDir().getAbsolutePath)
    brokerOptions.setInitialConfigurationLocation(getClass.getResource(configFileName).toString)
    broker.startup(brokerOptions)
  }


  private var cache: Set[String] = Set()

  def notVisited(url: String): Boolean = !cache(url)

  def saveVisited(url: String): String = {
    cache += url
    url
  }


  def main(args: Array[String]): Unit = {
    startBroker()

    val queueName = "amqp-conn-it-spec-simple-queue-" + System.currentTimeMillis()
    val queueDeclaration = QueueDeclaration(queueName)
    val amqpSink = AmqpSink.simple(
      AmqpSinkSettings(AmqpConnectionDetails("localhost", 5672, Some(AmqpCredentials("guest", "guest"))))
        .withRoutingKey(queueName).withDeclarations(queueDeclaration)
    ).withAttributes(Attributes.logLevels(onElement = Logging.DebugLevel, onFailure = Logging.ErrorLevel))

    val in = AmqpSource(
      NamedQueueSourceSettings(AmqpConnectionDetails("localhost", 5672, Some(AmqpCredentials("guest", "guest"))), queueName)
        .withDeclarations(queueDeclaration),
      bufferSize = 1028
    ).map(_.bytes.utf8String).log(":in")

    val count = new AtomicInteger()

    val urlsSink = Flow[String].map(ByteString(_)).to(amqpSink)


    val g = RunnableGraph.fromGraph(GraphDSL.create(in, urlsSink)((_,_)){ implicit b => (in, urlsSink0) =>
      import GraphDSL.Implicits._

      val pool = Http().superPool[String]()(materializer).log(":pool")
      val download: Flow[String, Document, NotUsed] = Flow[String]
        .map(url => (HttpRequest(method = HttpMethods.GET, Uri(url)), url) )
        .via(pool)
        .mapAsyncUnordered(8){ case (Success(response: HttpResponse), url) => parse(response, url)}

      val filter = Flow[String].filter(notVisited).log(":filter")
      val save = Flow[String].map(saveVisited)

      val extractLinks: Flow[Document, String, NotUsed] = Flow[Document].mapConcat(document => getUrls(document))


      in ~> save ~> download ~> extractLinks ~> filter ~> urlsSink0


      ClosedShape
    })

    g.run()

    Source.single(rootUrl).map(s => ByteString(s)).runWith(amqpSink)
  }
}