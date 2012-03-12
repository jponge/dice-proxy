# DICE HTTP Proxy

This is a simple transparent HTTP proxy that detects common search engine queries, and puts query data into a MongoDB database.

Written by [Julien Ponge](http://julien.ponge.info/).

## License

    Copyright 2012 Julien Ponge, Institut National des Sciences Appliquées de Lyon.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## Supported sites

* Google
* Bing
* Yahoo!
* Wikipedia

## What works and not

* The proxy works for any HTTP connection. It also detects requests on other ports than 80.
* It does not support HTTPS connections.
* Some streaming / websocket connections as used by sites such as Youtube won't work through this proxy.

## Dependencies

Runtime:

* [Scala 2.9.1](http://www.scala-lang.org/)
* [MongoDB](http://www.mongodb.org/)

Development:

* [Apache BuildR](http://buildr.apache.org/)
* [Ruby](http://www.ruby-lang.org/) (required to run BuildR)
* [Twitter Finagle](http://twitter.github.com/finagle/) (automatically fetched by BuildR)
* [Netty](http://netty.io/) (automatically fetched by BuildR as Finagle dependency)
* [Casbash](http://api.mongodb.org/scala/casbah/current/) (automatically fetch by BuildR)
* (other misc. dependencies fetched as dependencies of the projects above)

Those are fairly easy to install dependencies.

Apache BuildR can be installed using Ruby gems: `gem install buildr`. Scala is automatically downloaded by BuildR to compile, but it remains best to install it system-wide for the runtime.

If you are on MacOSX using [Homebrew](http://mxcl.github.com/homebrew/), then installing the dependencies is as simple as:

    brew install scala
    brew install mongodb
    gem install buildr

## Building

Building is based on the common BuildR tasks, such as `buildr compile` or `buildr package`.

Other tasks are available too (see `buildr -T` to list them all):

* `buildr execute` launches the application
* `buildr dist` will create a distributable ZIP image of the application along with a `run.sh` script.

## Running

Use the distributable image and launch `run.sh`.

Alternatively you may assemble a classpath from the dependencies being put in the exploded image folder, and run the `dice.searchengine.httpproxy.SearchEngineHttpProxy` main class.

You will also need a working MongoDB instance. In development mode, I suggest that you use it with the provided configuration file called `mongod.conf`:

    mkdir db-mongodb
    mongod run --config=mongod.conf

You can tweak the MongoDB configuration as you want to add replication and sharding support. This is all transparent to the HTTP proxy application, and you can even start a cluster of those!

## Use it with a web browser

If you don't know how to configure a proxy for your web server then Google is your friend.

## Recognizing new search engines

A search engine is defined as a partial function through the following trait:

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
    
        SearchEngineQuery(query, keywords)
      }
    }

The variance is captured by regular expressions. For example here is how Google queries can be captured:

    class GoogleSearch extends SearchEngineProcessor {
      val searchEngineTest = "www.google.*q=.*".r
      val queryExtractor = "q=([^&]*)".r
      val keywordSplitter = "(%20)|(\\+)".r
      val name = "google"
    }

Processors can then be elegantly chained as partial functions, then lifted to form a single function returning an optional type, such as in:

    val searchEngineProcessor = (google orElse bing orElse yahoo orElse wikipedia).lift

Thus:

    searchEngineProcessor("http://www.autosport.com/")
    => None
    
    searchEngineProcessor("http://www.google.com/?q=les+muscles+merguez+party")
    => Some(SearchEngineQuery(
        "les+muscles+merguez+party",
        Seq("les", "muscles", "merguez", "party"),
        "google"
       ))

## Why *xyz*?

* Twitter Finagle: because it is a fairly well-tested asynchronous server stack, and it works on the JVM.
* Scala: because of Twitter Finagle and that their Java API was much more verbose. At last, there are people who write maintainable Scala code, see [Effective Scala](http://twitter.github.com/effectivescala/).
* BuildR: because I can't get my head around Scala's SBT, and because Maven was just too rigid for the need.
* MongoDB: because it is a solid NoSQL database, and that their Scala driver API (Casbah) is so easy.
