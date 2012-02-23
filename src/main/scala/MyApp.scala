package my.app

import com.twitter.finagle.{Service, SimpleFilter}
import my.app.MyApp.WikipediaSearchFilter
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.codec.http.HttpResponseStatus._
import org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1
import org.jboss.netty.buffer.ChannelBuffers.copiedBuffer
import org.jboss.netty.util.CharsetUtil.UTF_8
import com.twitter.finagle.builder._
import com.twitter.finagle.http.Http
import java.net.InetSocketAddress
import com.twitter.util.Future
import scala.util.matching.Regex

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
  
  trait SearchEngineFilter extends SimpleFilter[HttpRequest, HttpResponse] {

    def searchEngineTest: Regex
    def queryExtractor: Regex
    def keywordSplitter: Regex

    def apply(request: HttpRequest, service: Service[HttpRequest, HttpResponse]) = {

      val uri = request.getUri()
      if (searchEngineTest.findFirstIn(uri).isDefined) {
        val query = queryExtractor.findFirstMatchIn(uri).get.group(1)
        val keywords = keywordSplitter.split(query)
        println(keywords.reduce { _ + ", " + _ })
      }

      service(request)
    }
  }
  
  class GoogleSearchFilter extends SearchEngineFilter {

    val searchEngineTest   = "www.google.*q=.*".r
    val queryExtractor = "q=([^&]*)".r
    val keywordSplitter = "(%20)|(\\+)".r
  }

  class BingSearchFilter extends SearchEngineFilter {

    val searchEngineTest   = "www.bing.com.*q=.*".r
    val queryExtractor = "q=([^&]*)".r
    val keywordSplitter = "\\+".r
  }
  
  class YahooSearchFilter extends SearchEngineFilter {

    val searchEngineTest   = "search.yahoo.com.*p=.*".r
    val queryExtractor = "p=([^&]*)".r
    val keywordSplitter = "(%20)|(\\+)".r
  }

  class WikipediaSearchFilter extends SearchEngineFilter {

    val searchEngineTest   = "wikipedia.org.*search=.*".r
    val queryExtractor = "search=([^&]*)".r
    val keywordSplitter = "\\+".r
  }

  def main(args: Array[String]) {
    val handleExceptions = new HandleExceptions
    val proxyClient = new ProxyHttpClient
    val googleFilter = new GoogleSearchFilter
    val bingFilter = new BingSearchFilter
    val yahooFilter = new YahooSearchFilter
    val wikipediaFilter = new WikipediaSearchFilter

    val myService =
      handleExceptions andThen
      googleFilter     andThen
      bingFilter       andThen
      yahooFilter      andThen
      wikipediaFilter  andThen
      proxyClient

    val server: Server = ServerBuilder()
      .codec(Http())
      .bindTo(new InetSocketAddress(8080))
      .name("proxy")
      .build(myService)
  }
}