package voli

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.alpakka.amqp._
import akka.stream.alpakka.amqp.scaladsl.{AmqpSink, AmqpSource}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.util.ByteString
import com.google.common.io.Files
import org.apache.qpid.server.{Broker, BrokerOptions}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConverters._
import scala.collection.immutable.{List, Set}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Success

object Crawler {

  private val indexer = new Indexer(100000)
  implicit val system = ActorSystem("Crawler")
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  val settings = ActorMaterializerSettings(system)
  implicit val materializer: ActorMaterializer = ActorMaterializer(settings)(system)

  val rootUrl = "http://citforum.ru/"


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

    val in = queueSource(queueName, queueDeclaration)
    val out = queueSink(queueName, queueDeclaration)

    val urlsSink = Flow[String].map(ByteString(_)).log(":out").to(out)

    val g = RunnableGraph.fromGraph(GraphDSL.create(in, urlsSink)((_,_)){ implicit b => (in, urlsSink0) =>
      import GraphDSL.Implicits._


      val pool = Http().superPool[String]()(materializer).log(":pool")

      val download = Flow[String]
        .map(url => (HttpRequest(method = HttpMethods.GET, Uri(url)), url) )
        .via(pool)
        .mapAsyncUnordered(8){
          case (Success(response: HttpResponse), url) => parse(response, url)
        }
      val filterSave = Flow[String].filter(notVisited).map(saveVisited).log(":filter")
      val extractLinks = Flow[Document].mapConcat(document => getUrls(document))
      val index = Flow[Document]
        .to(Sink.foreach[Document](document => indexer.index(document)))

      val bcast = b.add(Broadcast[Document](2))

      in ~> filterSave ~> download ~> bcast ~> extractLinks ~> urlsSink0
                                      bcast ~> index

      ClosedShape
    })

    g.run()

    Source.single(rootUrl).map(s => ByteString(s)).runWith(out)
  }

  private val connectionDetails: AmqpConnectionSettings = AmqpConnectionDetails("localhost", 5672, Some(AmqpCredentials("guest", "guest")))

  private def queueSink(queueName: String, queueDeclaration: QueueDeclaration): Sink[ByteString, NotUsed] = {
    AmqpSink.simple(AmqpSinkSettings(connectionDetails).withRoutingKey(queueName).withDeclarations(queueDeclaration))
  }

  private def queueSource(queueName: String, queueDeclaration: QueueDeclaration): Source[String, NotUsed] = {
    val settings: AmqpSourceSettings = NamedQueueSourceSettings(connectionDetails, queueName).withDeclarations(queueDeclaration)
    AmqpSource(settings, bufferSize = 100).map(_.bytes.utf8String).log(":in")
  }
}