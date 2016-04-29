/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.cds.spark.samples

import scala.collection.mutable._
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.spark.Accumulator
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import org.http4s._
import org.http4s.Http4s._
import org.http4s.Status._
import org.http4s.client.Client
import org.http4s.client.blaze.PooledHttp1Client
import org.http4s.headers.Authorization
import com.ibm.couchdb._
import scalaz._
import scalaz.concurrent.Task
import twitter4j.Status
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.spark.sql.DataFrame
import org.apache.spark.rdd.RDD
import org.apache.spark.rdd.EmptyRDD
import com.google.common.base.CharMatcher
import scala.math.BigDecimal
import com.ibm.cds.spark.samples.config.DemoConfig
import com.ibm.cds.spark.samples.ToneAnalyzer.ToneCategory
import org.apache.spark.Logging




/**
 * @author dtaieb
 */
object StreamingTwitter extends Logging{
  var ssc: StreamingContext = null
  var sqlContext: SQLContext = null
  var workingRDD: RDD[Row] = null
  var schemaTweets : StructType = null
  val logger: Logger = Logger.getLogger( "com.ibm.cds.spark.samples.StreamingTwitter" )
  
  //main method invoked when running as a standalone Spark Application
  def main(args: Array[String]) {
    
    val conf = new SparkConf().setAppName("Spark Streaming Twitter Demo")
    val sc = new SparkContext(conf)
    startTwitterStreaming(sc, Seconds(10));
  }
  
  //Hold configuration key/value pairs
  val config = new DemoConfig
  
  //Wrapper api for Notebook access
  def setConfig(key:String, value:String){
    config.setConfig(key, value)
  }
  
  def startTwitterStreaming( sc: SparkContext, stopAfter: Duration = Seconds(0) ){
    if ( ssc != null ){
      println("Twitter Stream already running");
      println("Please use stopTwitterStreaming() first and try again");
      return;
    }
    
    if ( !config.validateConfiguration() ){
      return;
    }
    
    Logger.getLogger("org.apache.spark").setLevel(Level.OFF)
    
    workingRDD = sc.emptyRDD
    //Broadcast the config to each worker node
    val broadcastVar = sc.broadcast(config.toImmutableMap)
    
    var canStopTwitterStream = true
    var batchesProcessed=0
    
    ssc = new StreamingContext( sc, Seconds(5) )
    
    ssc.addStreamingListener( new StreamingListener )
    
    try{
      sqlContext = new SQLContext(sc)
      val keys = config.getConfig("tweets.key").split(",");
      var stream = org.apache.spark.streaming.twitter.TwitterUtils.createStream( ssc, None );
      
      if ( schemaTweets == null ){
        val schemaString = "author date lang text lat:double long:double"
        schemaTweets =
          StructType(
            schemaString.split(" ").map(
              fieldName => {
                val ar = fieldName.split(":")
                StructField(
                    ar.lift(0).get, 
                    ar.lift(1).getOrElse("string") match{
                      case "int" => IntegerType
                      case "double" => DoubleType
                      case _ => StringType
                    },
                    true)
              }
            ).union( 
                ToneAnalyzer.sentimentFactors.map( f => StructField( f._1, DoubleType )).toArray[StructField]
            )
          )
      }
      val tweets = stream.filter { status => 
        Option(status.getUser).flatMap[String] { 
          u => Option(u.getLang) 
        }.getOrElse("").startsWith("en") && CharMatcher.ASCII.matchesAllOf(status.getText) && ( keys.isEmpty || keys.exists{status.getText.contains(_)})
      }
      
      lazy val client = PooledHttp1Client()
      val rowTweets = tweets.map(status=> {
        val sentiment = ToneAnalyzer.computeSentiment( client, status, broadcastVar )
        
        var colValues = Array[Any](
          status.getUser.getName, //author
          status.getCreatedAt.toString,   //date
          status.getUser.getLang,  //Lang
          status.getText,               //text
          Option(status.getGeoLocation).map{ _.getLatitude}.getOrElse(0.0),      //lat
          Option(status.getGeoLocation).map{_.getLongitude}.getOrElse(0.0)    //long
          //exception
        )
        
        var scoreMap : Map[String, Double] = Map()
        if ( sentiment != null ){
          for( toneCategory <- Option(sentiment.tone_categories).getOrElse( Seq() )){
            for ( tone <- Option( toneCategory.tones ).getOrElse( Seq() ) ){
              scoreMap.put( tone.tone_id, tone.score )
            }
          }
        }
             
        colValues = colValues ++ ToneAnalyzer.sentimentFactors.map { f => (BigDecimal(scoreMap.get(f._2).getOrElse(0.0)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble) * 100.0  }
        //Return [Row, (sentiment, status)]
        (Row(colValues.toArray:_*),(sentiment, status))
      })

      rowTweets.foreachRDD( rdd => {
        if(batchesProcessed==0){
          canStopTwitterStream=false
        }
        try{
          if( rdd.count > 0 ){
            batchesProcessed += 1
            workingRDD = sc.parallelize( rdd.map( t => t._1 ).collect()).union( workingRDD )
        
            val saveToCloudant = broadcastVar.value.get("cloudant.save").get.toBoolean
            if ( saveToCloudant ){
              rdd.foreachPartition { iterator => 
                var db: CouchDbApi = null;
                val couch = CouchDb( broadcastVar.value.get("cloudant.hostName").get, 
                    broadcastVar.value.get("cloudant.port").get.toInt, 
                    broadcastVar.value.get("cloudant.https").get.toBoolean, 
                    broadcastVar.value.get("cloudant.username").get, 
                    broadcastVar.value.get("cloudant.password").get
                );
                val dbName = "spark-streaming-twitter"
                couch.dbs.get(dbName).attemptRun match{
                  case -\/(e) => logger.trace("Couch Database does not exist, creating it now"); couch.dbs.create(dbName).run
                  case \/-(a) => println("Connected to cloudant db " + dbName )
                }
                val typeMapping = TypeMapping(classOf[ToneAnalyzer.Tweet] -> "Tweet")
                db = couch.db(dbName, typeMapping)
                iterator.foreach( t => {
                    saveTweetToCloudant( client, db, t._2._2, t._2._1 )
                  }
                )
              }
            }
          }
        }catch{
          case e: InterruptedException=>//Ignore
          case e: Exception => logError(e.getMessage, e )
        }finally{
            canStopTwitterStream = true
        }        
      })

    }catch{
      case e : Exception => logError(e.getMessage, e )
      return
    }
    ssc.start()
    
    println("Twitter stream started");
    println("Tweets are collected real-time and analyzed")
    println("To stop the streaming and start interacting with the data use: StreamingTwitter.stopTwitterStreaming")
    
    if ( !stopAfter.isZero ){
      //Automatically stop it after 10s
      new Thread( new Runnable {
        var displayMessage = true;
        def run(){
          Thread.sleep( stopAfter.milliseconds )
          var loop = true
          while(loop){
            if (canStopTwitterStream){
              stopTwitterStreaming
              loop = false
            }else{
              if ( displayMessage ){
                displayMessage = false
                println("Received directive to stop twitter Stream: Waiting for already received tweets to be processed...")
              }
              Thread.sleep(5000L)
            }
          }
        }
      }).start
    }
  }
  
  def saveTweetToCloudant(client: Client, db: CouchDbApi, status:Status, sentiment: ToneAnalyzer.Sentiment) : Status = {    
    if ( db != null){
      logger.trace("Creating new Tweet in Couch Database " + status.getText())
      val task:Task[Res.DocOk] = db.docs.create( 
          ToneAnalyzer.Tweet(
              status.getUser().getName, 
              status.getCreatedAt().toString(),
              status.getUser().getLang(),
              status.getText(),
              ToneAnalyzer.Geo( 
                  Option(status.getGeoLocation).map{ _.getLatitude}.getOrElse(0.0), 
                  Option(status.getGeoLocation).map{_.getLongitude}.getOrElse(0.0) 
              ),
              sentiment
          ) 
      )
      
      // Execute the actions and process the result
      task.attemptRun match {
        case -\/(e) => logError(e.getMessage, e );
        case \/-(a) => logger.trace("Successfully create new Tweet in Couch Database " + status.getText() )
      }
    }
      
    status
  }
  
  def createTwitterDataFrames(sc: SparkContext) : (SQLContext, DataFrame) = {
    if ( workingRDD.count <= 0 ){
      println("No data receive. Please start the Twitter stream again to collect data")
      return null
    }
    
    try{
      val df = sqlContext.createDataFrame( workingRDD, schemaTweets )
      df.registerTempTable("tweets")
      
      println("A new table named tweets with " + df.count() + " records has been correctly created and can be accessed through the SQLContext variable")
      println("Here's the schema for tweets")
      df.printSchema()
      
      (sqlContext, df)
    }catch{
      case e: Exception => {logError(e.getMessage, e ); return null}
    }
  }
 
  def stopTwitterStreaming(){
    if ( ssc == null){
      println("No Twitter stream to stop");
      return;
    }
    
    println("Stopping Twitter stream. Please wait this may take a while")
    ssc.stop(stopSparkContext = false, stopGracefully = false)
    ssc = null
    println("Twitter stream stopped");
    
    println( "You can now create a sqlContext and DataFrame with " + workingRDD.count + " Tweets created. Sample usage: ")
    println("val (sqlContext, df) = com.ibm.cds.spark.samples.StreamingTwitter.createTwitterDataFrames(sc)")
    println("df.printSchema")
    println("sqlContext.sql(\"select author, text from tweets\").show")
  }
}