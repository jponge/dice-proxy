Buildr.settings.build['scala.version'] = '2.9.1'
require 'buildr/scala'

repositories.remote << 'http://repo1.maven.org/maven2/'
repositories.remote << 'http://www.ibiblio.org/maven2'
repositories.remote << 'http://maven.twttr.com/'

FINAGLE = "com.twitter:finagle-http_#{Scala.version}:jar:1.11.1"
CASBAH = "com.mongodb.casbah:casbah_2.9.0-1:pom:2.1.5.0"

define 'finagle-test' do    
  project.group = 'my.app'
  project.version = '0.1'
  
  package :jar
  manifest['Main-Class'] = 'dice.searchengine.httpproxy.SearchEngineHttpProxy'
  
  compile.with transitive(FINAGLE), transitive(CASBAH)
end

task :execute => :package do
  all_deps = transitive(FINAGLE) + transitive(CASBAH)
  classpath = all_deps.map { |dep| dep.name }.delete_if { |dep| dep.include? 'scala-library' }.join(':')
  puts "\nRun >>> \n"
  puts "Classpath: #{classpath}\n\n"
  sh "scala -cp #{classpath}:target/finagle-test-0.1.jar my.app.MyApp"
end
