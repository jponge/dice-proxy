# Copyright 2012 Julien Ponge, Institut National des Sciences Appliquées de Lyon.
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#     http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

Buildr.settings.build['scala.version'] = '2.9.1'
require 'buildr/scala'

repositories.remote << 'http://repo1.maven.org/maven2/'
repositories.remote << 'http://www.ibiblio.org/maven2'
repositories.remote << 'http://maven.twttr.com/'

FINAGLE = "com.twitter:finagle-http_#{Scala.version}:jar:1.11.1"
CASBAH = "com.mongodb.casbah:casbah_2.9.0-1:pom:2.1.5.0"

VERSION = '0.1'
PROJECT = 'SearchEngineHttpProxy'
MAIN_CLASS = 'dice.searchengine.httpproxy.SearchEngineHttpProxy'

define PROJECT do
  project.group = 'dice.searchengine.httpproxy'
  project.version = VERSION
  
  package :jar
  manifest['Main-Class'] = MAIN_CLASS
  
  compile.with transitive(FINAGLE), transitive(CASBAH)
end

def all_deps
  all = transitive(FINAGLE) + transitive(CASBAH)
  all.map { |dep| dep.name }
end

def all_deps_bar_scalalib
  all_deps.delete_if { |dep| dep.include? 'scala-library' }
end

def classpath
  all_deps_bar_scalalib.join(':')
end

task :execute => :package do    
  puts "\nRun >>> \n"
  puts "Classpath: #{classpath}\n\n"
  sh "scala -cp #{classpath}:target/#{PROJECT}-#{VERSION}.jar #{MAIN_CLASS}"
end

task :dist => :package do
  mkdir_p 'dice-proxy'
  cp all_deps_bar_scalalib, 'dice-proxy'
  cp "target/#{PROJECT}-#{VERSION}.jar", 'dice-proxy'
  chdir 'dice-proxy'
  File.open('run.sh', 'w') do |f|
    f.puts '#!/bin/sh'
    f.puts "scala -cp #{Dir['*.jar'].join(':')} #{MAIN_CLASS}"
  end
  chdir '..'
  chmod 'u=wrx,go=rx', 'dice-proxy/run.sh'
  sh 'zip -r dice-proxy.zip dice-proxy dice-proxy/*'
end

task :dist_clean do
  rm_rf 'dist'
end
