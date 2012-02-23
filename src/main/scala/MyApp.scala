package my.app

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
import com.twitter.finagle.http.Http

object MyApp {

  class HandleExceptions extends SimpleFilter[HttpRequest, HttpResponse] {

    def apply(request: HttpRequest, service: Service[HttpRequest, HttpResponse]) = {

      // `handle` asynchronously handles exceptions.
      service(request) handle { case error =>
        val statusCode = error match {
          case _: IllegalArgumentException =>
            FORBIDDEN
          case _ =>
            INTERNAL_SERVER_ERROR
        }
        val errorResponse = new DefaultHttpResponse(HTTP_1_1, statusCode)
        errorResponse.setContent(copiedBuffer(error.getStackTraceString, UTF_8))

        errorResponse
      }
    }
  }

  class ProxyHttpClient extends Service[HttpRequest, HttpResponse] {

    def apply(request: HttpRequest): Future[HttpResponse] = {

      val host = request.getHeader("Host")
      request.setUri(request.getUri().substring("http://".length + host.length))

      val target = host.indexOf(":") match {
        case -1  => host + ":80"
        case pos => host + ":" + host.substring(pos + 1)
      }

      val client = ClientBuilder()
        .codec(Http())
        .hosts(target)
        .hostConnectionLimit(1)
        .build()

      client(request) ensure { client.release() }
    }
  }

  case class SearchEngineQuery(
    query: String,
    keywords: Seq[String]
  )
  
  trait SearchEngineProcessor extends PartialFunction[String, SearchEngineQuery] {

    def searchEngineTest: Regex
    def queryExtractor: Regex
    def keywordSplitter: Regex

    def isDefinedAt(uri: String) = searchEngineTest.findFirstIn(uri).isDefined

    def apply(uri: String): SearchEngineQuery = {
      val query = queryExtractor.findFirstMatchIn(uri).get.group(1)
      val keywords = keywordSplitter.split(query)

      SearchEngineQuery(query, keywords)
    }
  }

  class GoogleSearch extends SearchEngineProcessor {
    val searchEngineTest   = "www.google.*q=.*".r
    val queryExtractor = "q=([^&]*)".r
    val keywordSplitter = "(%20)|(\\+)".r
  }

  class BingSearch extends SearchEngineProcessor {
    val searchEngineTest   = "www.bing.com.*q=.*".r
    val queryExtractor = "q=([^&]*)".r
    val keywordSplitter = "\\+".r
  }

  class YahooSearch extends SearchEngineProcessor {
    val searchEngineTest   = "search.yahoo.com.*p=.*".r
    val queryExtractor = "p=([^&]*)".r
    val keywordSplitter = "(%20)|(\\+)".r
  }

  class WikipediaSearch extends SearchEngineProcessor {
    val searchEngineTest   = "wikipedia.org.*search=.*".r
    val queryExtractor = "search=([^&]*)".r
    val keywordSplitter = "\\+".r
  }
  
  class SearchEngineFilter(val processor: String => Option[SearchEngineQuery]) extends SimpleFilter[HttpRequest, HttpResponse] {

    def apply(request: HttpRequest, service: Service[HttpRequest, HttpResponse]) = {

      val query = processor(request.getUri())
      if (query.isDefined) {
        println(query.get)
      }

      service(request)
    }
  }

  def main(args: Array[String]) {
    val handleExceptions = new HandleExceptions
    val proxyClient = new ProxyHttpClient
    
    val google = new GoogleSearch
    val bing = new BingSearch
    val yahoo = new YahooSearch
    val wikipedia = new WikipediaSearch

    val searchEngineProcessor = (google orElse bing orElse yahoo orElse wikipedia).lift
    val searchEngineFilter = new SearchEngineFilter(searchEngineProcessor)

    val myService = handleExceptions andThen searchEngineFilter andThen proxyClient

    val server: Server = ServerBuilder()
      .codec(Http())
      .bindTo(new InetSocketAddress(8080))
      .name("proxy")
      .build(myService)
  }
}