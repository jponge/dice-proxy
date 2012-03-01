package dice.searchengine.httpproxy

import com.twitter.finagle.{Service, SimpleFilter}
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.codec.http.HttpResponseStatus._
import org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1
import org.jboss.netty.buffer.ChannelBuffers.copiedBuffer
import org.jboss.netty.util.CharsetUtil.UTF_8
import com.twitter.finagle.builder._
import java.net.InetSocketAddress
import com.twitter.util.Future
import scala.util.matching.Regex
import org.joda.time.DateTime
import com.twitter.finagle.http.{Response, Request, RichHttp, Http}
import com.twitter.conversions.time._
import com.mongodb.casbah.MongoDB

/**
 * A HTTP Proxy server based on Twitter Finagle that detects common search
 * engine requests, and stores the extracted queries into a MongoDB database.
 *
 * @author Julien Ponge - julien.ponge@insa-lyon.fr
 */
object SearchEngineHttpProxy {

  /**
   * A filter for handling exceptions.
   */
  class HandleExceptions extends SimpleFilter[Request, Response] {

    def apply(request: Request, service: Service[Request, Response]) = {

      service(request) handle {
        case error =>
          println("Could not serve a request: " + error)
          val statusCode = error match {
            case _: IllegalArgumentException => FORBIDDEN
            case _ => INTERNAL_SERVER_ERROR
          }
          val errorResponse = new DefaultHttpResponse(HTTP_1_1, statusCode)
          errorResponse.setContent(copiedBuffer(error.getStackTraceString, UTF_8))

          Response(errorResponse)
      }
    }
  }

  /**
   * A HTTP proxy service.
   */
  class ProxyHttpClient extends Service[Request, Response] {

    def apply(request: Request): Future[Response] = {

      val host = request.getHeader("Host")
      request.setUri(request.getUri().substring("http://".length + host.length))

      val target = host.indexOf(":") match {
        case -1 => host + ":80"
        case pos => host + ":" + host.substring(pos + 1)
      }

      val client = ClientBuilder()
        .codec(Http())
        .hosts(target)
        .timeout(30.seconds)
        .tcpConnectTimeout(30.seconds)
        .hostConnectionLimit(1)
        .build()

      val requestFuture = client(request.httpRequest) ensure { client.release() }
      Future.value(Response(requestFuture.get()))
    }
  }

  /**
   * A search engine query data object.
   *
   * @param query the full query String.
   * @param keywords a sequence of keywords from the query string.
   */
  case class SearchEngineQuery(
    query: String,
    keywords: Seq[String],
    source: String
  )

  /**
   * A search engine processor trait. The variance is encapsulated in regular expressions
   * to be defined in concrete processors.
   */
  trait SearchEngineProcessor extends PartialFunction[String, SearchEngineQuery] {

    /**
     * Regular expression to check is a URI corresponds to those a given search engine.
     */
    def searchEngineTest: Regex

    /**
     * Regular expression to extract a query string from a URI.
     */
    def queryExtractor: Regex

    /**
     * Regular expression to split a query string into keywords.
     */
    def keywordSplitter: Regex

    /**
     * Symbolic name for the search engine.
     */
    def name: String

    def isDefinedAt(uri: String) = searchEngineTest.findFirstIn(uri).isDefined

    def apply(uri: String): SearchEngineQuery = {
      val query = queryExtractor.findFirstMatchIn(uri).get.group(1)
      val keywords = keywordSplitter.split(query)

      SearchEngineQuery(query, keywords, name)
    }
  }

  class GoogleSearch extends SearchEngineProcessor {
    val searchEngineTest = "www.google.*q=.*".r
    val queryExtractor = "q=([^&]*)".r
    val keywordSplitter = "(%20)|(\\+)".r
    val name = "google"
  }

  class BingSearch extends SearchEngineProcessor {
    val searchEngineTest = "www.bing.com.*q=.*".r
    val queryExtractor = "q=([^&]*)".r
    val keywordSplitter = "\\+".r
    val name = "bing"
  }

  class YahooSearch extends SearchEngineProcessor {
    val searchEngineTest = "search.yahoo.com.*p=.*".r
    val queryExtractor = "p=([^&]*)".r
    val keywordSplitter = "(%20)|(\\+)".r
    val name = "yahoo"
  }

  class WikipediaSearch extends SearchEngineProcessor {
    val searchEngineTest = "wikipedia.org.*search=.*".r
    val queryExtractor = "search=([^&]*)".r
    val keywordSplitter = "\\+".r
    val name = "wikipedia"
  }

  /**
   * Search engine filter that intercepts queries and stores them to MongoDB.
   *
   * @param processor a function to fetch a search engine query from a URI, usually done
   *                  by assembling instances of <code>SearchEngineProcessor</code> partial functions
   *                  using <code>orElse</code>, and using <code>lift</code> on the resulting
   *                  partial function to obtain a subclass of
   *                  <code>(String => Option[SearchEngineQuery])</code>.
   * @param mongodb   the storage facade to MongoDB.
   */
  class SearchEngineFilter(
    val processor: String => Option[SearchEngineQuery],
    val mongodb: MongoDBStore
  ) extends SimpleFilter[Request, Response] {

    def apply(request: Request, service: Service[Request, Response]) = {

      val query = processor(request.getUri())
      if (query.isDefined) {
        mongodb.insert(query.get, request)
      }

      service(request)
    }
  }

  /**
   * A facade to store extracted queries into MongoDB.
   *
   * @param host the MongoDB server host.
   * @param port the MongoDB server port.
   */
  class MongoDBStore(val anonymize: Boolean = true, val host: String = "127.0.0.1", val port: Int = 27017) {

    import com.mongodb.casbah.Imports._
    import com.mongodb.casbah.commons.conversions.scala._

    RegisterJodaTimeConversionHelpers()

    private[this] val connection = MongoConnection(host, port)
    private[this] val db = connection("HttpProxyQueries")

    val COLLECTION = "queries"

    /**
     * Insert a query into MongoDB, adding the current date to the resulting
     * entry in the underlying MongoDB collection.
     *
     * @param query the search engine query object.
     */
    def insert(query: SearchEngineQuery, request: Request) {

      val builder = MongoDBObject.newBuilder
      builder += "when" -> new DateTime()
      builder += "query" -> query.query
      builder += "source" -> query.source
      builder += "keywords" -> query.keywords

      if (!anonymize) {
        builder += "user-agent" -> request.userAgent.getOrElse("undefined")
        builder += "ip" -> request.remoteHost
      }

      db(COLLECTION) += builder.result()
    }

  }

  /**
   * The good old entry point.
   *
   * @param args the good old process arguments.
   */
  def main(args: Array[String]) {

    val handleExceptions = new HandleExceptions
    val proxyClient = new ProxyHttpClient

    val mongodb = new MongoDBStore()

    val google = new GoogleSearch
    val bing = new BingSearch
    val yahoo = new YahooSearch
    val wikipedia = new WikipediaSearch

    val searchEngineProcessor = (google orElse bing orElse yahoo orElse wikipedia).lift
    val searchEngineFilter = new SearchEngineFilter(searchEngineProcessor, mongodb)

    val myService = handleExceptions andThen searchEngineFilter andThen proxyClient

    val server: Server = ServerBuilder()
      .codec(RichHttp[Request](Http()))
      .bindTo(new InetSocketAddress(8080))
      .name("proxy")
      .readTimeout(30.seconds)
      .requestTimeout(30.seconds)
      .build(myService)
  }
}