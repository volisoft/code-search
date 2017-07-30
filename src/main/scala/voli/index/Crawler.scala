package voli.index

import java.io.RandomAccessFile

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.alpakka.amqp._
import akka.stream.alpakka.amqp.scaladsl.{AmqpSink, AmqpSource}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.util.ByteString
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import com.netaporter.uri
import com.netaporter.uri.config.UriConfig
import kamon.Kamon
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.{List, Set}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Success

object Crawler {
  private val logger = LoggerFactory.getLogger(Crawler.getClass)

  private val index0 = new Index()

  private val decider: Supervision.Decider = { e =>
    logger.error("Unhandled exception in stream", e)
    Supervision.Stop
  }

  implicit val system = ActorSystem("Crawler")
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  private val settings = ActorMaterializerSettings(system).withSupervisionStrategy(decider)
  implicit val materializer: ActorMaterializer = ActorMaterializer(settings)(system)

  Kamon.start()
//  val sourceRate = Agent(1)
//  val sinkRate = Agent(1)
  scala.sys.addShutdownHook {
    Kamon.shutdown()
    system.terminate()
  }

  def extractResponse(response: HttpResponse, url: String): Future[(String, String)] = {
    Unmarshal(response.entity.withoutSizeLimit())
      .to[String]
      .map(_ -> url)(dispatcher)
  }

  def parse(response: HttpResponse, url: String): Future[Document] =
    Unmarshal(response.entity.withoutSizeLimit())
      .to[String]
      .map(Jsoup.parse(_, url))(dispatcher)

  def parse(text: String, url: String): Document = Jsoup.parse(text, url)

  def getUrls(document: Document): List[String] =
    document
      .select("a[abs:href]").asScala
      .map(_.absUrl("href"))
      .filter(url => systemConfig.rootUrls.asScala.exists(url.contains(_))).toList



  //todo make tread-safe
  var cache: Cache[String, String] = Caffeine.newBuilder()
    .maximumSize(100000000)
    .build[String, String]()

  def notVisited(url: String): Boolean = cache.getIfPresent(url) == null

  def acceptablePath(url: String): Boolean = {
    !url.contains('#') /*&&
      List("java", "html", "asp", "js", "properties", "xml", "jsp", "sql", "").contains(FilenameUtils.getExtension(url))*/
  }

  def updateCache(url: String): String = {
    cache.put(url, url)
    url
  }

  def main(args: Array[String]): Unit = {
    launch
//    val indexFile = new RandomAccessFile(systemConfig.indexFilePath.toFile, "rw")
//    index0.mergeBlocks(blockFiles(), indexFile, systemConfig.dictionaryFilePath)
  }

  def launch: NotUsed = {
    import com.netaporter.uri.dsl._
    import com.netaporter.uri.encoding._
    implicit val config = UriConfig(encoder = percentEncode)

    val queueName = "amqp-conn-it-spec-simple-queue-" + System.currentTimeMillis()
    val queueDeclaration = QueueDeclaration(queueName)

    val in = queueSource(queueName, queueDeclaration)./*takeWithin(5.seconds).*/log(":in")
      .via(meter("source"))
    val out = queueSink(queueName, queueDeclaration)

    val urlsSink = Flow[String].map(ByteString(_)).log(":out").via(meter("sink")).to(out)

    val connectionSettings = ConnectionPoolSettings(system)
      .withMaxConnections(32)
      .withMaxOpenRequests(32)
      .withMaxRetries(3)

    val pool = Http().superPool[String]()(materializer).log(":pool")
    val auth = Authorization(BasicHttpCredentials(systemConfig.credentials))

    val download = Flow[String]
      .map(url => HttpRequest(method = HttpMethods.GET, uri = Uri(url: uri.Uri), headers = List(auth)) -> url)
      .via(pool)
      .mapAsyncUnordered(8) {
        case (Success(response: HttpResponse), url) => extractResponse(response, url)
      }

    val filterAndCache = Flow[String]
      .filter(url => notVisited(url) && acceptablePath(url) )
      .map(updateCache)
      .log(":filter")

    val extractLinks = Flow[(String, String)]
      .mapConcat{ case (text, url) => getUrls(parse(text, url)) }

    val index = Flow[(String, String)]
      .to(Sink.foreach[(String, String)]{ case (text, url) => index0.update(text, url) })

    val g = RunnableGraph.fromGraph(GraphDSL.create(in, urlsSink)((_, _)) { implicit b =>
      (in, urlsSink0) =>
        import GraphDSL.Implicits._

        val bcast = b.add(Broadcast[(String, String)](3))

        in ~> filterAndCache ~> download ~> bcast ~> extractLinks ~> urlsSink0
                                            bcast ~> index
                                            bcast ~> Sink.onComplete(_ => {
                                              index0.flush()
                                              mergeBlocks(blockFiles(),
                                                new RandomAccessFile(systemConfig.indexFilePath.toFile, "rw"),
                                                systemConfig.dictionaryFilePath)
                                            })

        ClosedShape
    })

    g.run()

    Source(systemConfig.rootUrls.asScala.toList).map(s => ByteString(s)).runWith(out)
  }

  private val connectionDetails: AmqpConnectionSettings = AmqpConnectionDetails("localhost", 5672, Some(AmqpCredentials("guest", "guest")))

  private def queueSink(queueName: String, queueDeclaration: QueueDeclaration): Sink[ByteString, NotUsed] =
    AmqpSink
      .simple(AmqpSinkSettings(connectionDetails)
      .withRoutingKey(queueName)
      .withDeclarations(queueDeclaration))


  private def queueSource(queueName: String, queueDeclaration: QueueDeclaration): Source[String, NotUsed] = {
    val settings: AmqpSourceSettings = NamedQueueSourceSettings(connectionDetails, queueName).withDeclarations(queueDeclaration)
    AmqpSource(settings, bufferSize = 100).map(_.bytes.utf8String)
  }
}

object broker {
  def main(args: Array[String]): Unit = {
    startBroker()
  }
}



//
//val count = new AtomicInteger(0)
//
//val index = Flow[(String, String)]
//.mapConcat{ case (text, url) =>
//index0.update(text, url)
//List.fill(2)(url.concat(count.incrementAndGet().toString))
//}.log(":index")
//
//val downloadReal = Flow[String].via(download)
//
//val function = Flow[String].via(filterAndCache)
//.via(downloadReal)
////      .mapAsyncUnordered(2){case (text, url) =>
////        index0.update(text, url)
////        Future(url)
////      }
//.mapConcat{ case (text, url) =>
//index0.update(text, url)
//List.fill(2)(url.concat(count.incrementAndGet().toString))
//}
//
//in.via(function).runWith(urlsSink)
