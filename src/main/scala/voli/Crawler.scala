package voli

import java.io._
import java.nio.file.Paths

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
import com.wantedtech.common.xpresso.x
import org.apache.qpid.server.{Broker, BrokerOptions}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConverters._
import scala.collection.immutable.{List, Set}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Success

object Crawler {


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
  private var dictionary: Map[String, (Int, Set[String])] = Map()
  private var block = 0
  private val indexDir = "blocks"
  private val sep = ";"

  def notVisited(url: String): Boolean = !cache(url)

  def saveVisited(url: String): String = {
    cache += url
    url
  }

  def hitMemoryLimit(obj: Any): Boolean = {
    val baos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(obj)
    baos.toByteArray.length > 5000000
  }

  def index0(document: Document): Unit = {
    val text = document.text()
    val index = x.String.EN.tokenize(text).asScala.toList
      .flatMap(_.getWords.toArrayList.asScala)
      .groupBy(token => token)
      .map { case (term, terms) => term -> (terms.size, Set(document.location())) }

    val merged = dictionary.toSeq ++ index.toSeq
    val grouped  = merged.groupBy(_._1)
    val emptyListing = (0, Set.empty[String])
    dictionary = grouped.mapValues(_.map(_._2).foldLeft(emptyListing){ case ((lFreq, lDocs), (rFreq, rDocs)) => (lFreq + rFreq, lDocs ++ rDocs) })
    if (hitMemoryLimit(dictionary.toList)) {
      block += 1
      val writer = new PrintWriter(Paths.get(s"$indexDir/block$block.txt").toFile)

      dictionary.toSeq.sortBy(_._1).foreach({
        case (term, (freq, documents)) =>
          writer.println(s"$term $sep $freq $sep ${documents.mkString(",")}")
      })

      writer.close()
      dictionary = Map()
    }
  }

  def mergeBlocks(): Unit = {
    val writer = new PrintWriter(Paths.get(s"$indexDir/index.txt").toFile)

    val dir = new File(indexDir)
    val filter: FilenameFilter = (_, name: String) => name.contains("block")
    if (dir.exists && dir.isDirectory) {
      dir.listFiles(filter).grouped(2).foreach { case Array(f1, f2) =>
        val line1 = io.Source.fromFile(f1).bufferedReader().readLine()
        val line2 = io.Source.fromFile(f2).bufferedReader().readLine()
        line1.split(sep)
        line2.split(sep)
      }
    }
  }

  def merge(line1: String, line2: String)(implicit writer: PrintWriter): Unit = (line1, line2) match {
    case (null, l2) => writer.println(l2)
    case (l1, null) => writer.println(l1)
    case (l1, l2) =>
      val (term1, freq1, docs1) = l1.split(sep)
      val (term2, freq2, docs2) = l2.split(sep)
      if (term1.eq(term2)) writer.println(s"$term1 $sep ${freq1 + freq2} $sep ${docs1 ++ docs2}")
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
        .mapAsyncUnordered(8){ case (Success(response: HttpResponse), url) => parse(response, url)}
      val filterSave = Flow[String].filter(notVisited).map(saveVisited).log(":filter")
      val extractLinks = Flow[Document].mapConcat(document => getUrls(document))
      val index = Flow[Document]
        .to(Sink.foreach[Document](document => index0(document)))

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