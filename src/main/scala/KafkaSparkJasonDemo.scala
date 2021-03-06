import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.sql.SparkSession 
import org.apache.spark.sql.functions.count 
import org.apache.spark.sql.types.{StringType, StructType, TimestampType,IntegerType} 
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe 
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent 
import org.apache.spark.streaming.{Seconds, StreamingContext} 
import org.apache.kafka.common.serialization.StringDeserializer 

import org.apache.spark.streaming.kafka010.{ KafkaUtils, HasOffsetRanges}
import org.apache.spark.TaskContext

import org.apache.hadoop.hbase.{ HBaseConfiguration, HTableDescriptor, HColumnDescriptor }
import org.apache.hadoop.hbase.util.Bytes 
import org.apache.hadoop.hbase.mapreduce.{ TableInputFormat, TableOutputFormat }
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase._
import org.apache.kafka.common.TopicPartition
import kafka.utils._


 
object KafkaSparkJsonDemo { 
  def main(args: Array[String]): Unit = { 
    // Configurations for kafka consumer 
    /*val kafkaBrokers = sys.env.get("KAFKA_BROKERS") 
    val kafkaGroupId = sys.env.get("KAFKA_GROUP_ID") 
    val kafkaTopic = sys.env.get("KAFKA_TOPIC")*/ 
 
 
    // Verify that all settings are set 
   /* require(kafkaBrokers.isDefined, "KAFKA_BROKERS has not been set") 
    require(kafkaGroupId.isDefined, "KAFKA_GROUP_ID has not been set") 
    require(kafkaTopic.isDefined, "KAFKA_TOPIC has not been set")*/ 
 
 
    // Create Spark Session 
    val spark = SparkSession 
      .builder() 
      .appName("KafkaSparkDemo").master("local[*]") 
      .getOrCreate() 
      
 spark.sparkContext.setLogLevel("ERROR")
 
 def saveOffsets(TOPIC_NAME:String,GROUP_ID:String,offsetRanges:Array[OffsetRange],
                hbaseTableName:String,batchTime: org.apache.spark.streaming.Time) ={
      val hConf = new HBaseConfiguration() 
       hConf.set("hbase.zookeeper.quorum", "localhost:2181") 
      val tableName = "stream_kafka_offsets" 
      val hTable = new HTable(hConf, tableName) 
      val tableDescription = new HTableDescriptor(tableName)
            
        val rowKey = TOPIC_NAME + ":" + GROUP_ID + ":" +String.valueOf(batchTime.milliseconds)
        val put = new Put(rowKey.getBytes)
        for(offset <- offsetRanges){
          
          put.addColumn(Bytes.toBytes("offsets"),Bytes.toBytes(offset.partition.toString),
                Bytes.toBytes(offset.untilOffset.toString))
        }
        hTable.put(put)
 
}
   
    
def getLastCommittedOffsets(TOPIC_NAME:String,GROUP_ID:String,hbaseTableName:String,
zkQuorum:String,zkRootDir:String,sessionTimeout:Int,connectionTimeOut:Int):Map[TopicPartition,Long] ={
 
  val hbaseConf = HBaseConfiguration.create()
  val zkUrl = zkQuorum+"/"+zkRootDir
  val zkClientAndConnection = ZkUtils.createZkClientAndConnection(zkUrl,
                                                sessionTimeout,connectionTimeOut)
  val zkUtils = new ZkUtils(zkClientAndConnection._1, zkClientAndConnection._2,false)
  val zKNumberOfPartitionsForTopic = zkUtils.getPartitionsForTopics(Seq(TOPIC_NAME
                                                 )).get(TOPIC_NAME).toList.head.size
  zkClientAndConnection._1.close()
  zkClientAndConnection._2.close() 
 
  //Connect to HBase to retrieve last committed offsets 
  val conn = ConnectionFactory.createConnection(hbaseConf)
  val table = conn.getTable(TableName.valueOf(hbaseTableName))
  val startRow = TOPIC_NAME + ":" + GROUP_ID + ":" +
                                              String.valueOf(System.currentTimeMillis())
  val stopRow = TOPIC_NAME + ":" + GROUP_ID + ":" + 0
  val scan = new Scan()
  val scanner = table.getScanner(scan.setStartRow(startRow.getBytes).setStopRow(
                                                   stopRow.getBytes).setReversed(true))
  val result = scanner.next()
  var hbaseNumberOfPartitionsForTopic = 0 //Set the number of partitions discovered for a topic in HBase to 0
  if (result != null){
  //If the result from hbase scanner is not null, set number of partitions from hbase 
  //to the  number of cells
    hbaseNumberOfPartitionsForTopic = result.listCells().size()
  }
 
val fromOffsets = collection.mutable.Map[TopicPartition,Long]()
 
  if(hbaseNumberOfPartitionsForTopic == 0){
    // initialize fromOffsets to beginning
    for (partition <- 0 to zKNumberOfPartitionsForTopic-1){
      fromOffsets += (new TopicPartition(TOPIC_NAME,partition) -> 0)
    }
  } else if(zKNumberOfPartitionsForTopic > hbaseNumberOfPartitionsForTopic){
  // handle scenario where new partitions have been added to existing kafka topic
    for (partition <- 0 to hbaseNumberOfPartitionsForTopic-1){
      val fromOffset = Bytes.toString(result.getValue(Bytes.toBytes("offsets"),
                                        Bytes.toBytes(partition.toString)))
      fromOffsets += (new TopicPartition(TOPIC_NAME,partition) -> fromOffset.toLong)
    }
    for (partition <- hbaseNumberOfPartitionsForTopic to zKNumberOfPartitionsForTopic-1){
      fromOffsets += (new TopicPartition(TOPIC_NAME,partition) -> 0)
    }
  } else {
  //initialize fromOffsets from last run
    for (partition <- 0 to hbaseNumberOfPartitionsForTopic-1 ){
      val fromOffset = Bytes.toString(result.getValue(Bytes.toBytes("offsets"),
                                        Bytes.toBytes(partition.toString)))
      fromOffsets += (new TopicPartition(TOPIC_NAME,partition) -> fromOffset.toLong)
    }
  }
  scanner.close()
  conn.close()
  fromOffsets.toMap
}  
      
    import spark.implicits._ 
 
 
    // Create Streaming Context and Kafka Direct Stream with provided settings and 10 seconds batches 
    val ssc = new StreamingContext(spark.sparkContext, Seconds(10)) 
 
 
    val kafkaParams = Map[String, Object]( 
      "bootstrap.servers" -> "localhost:9092", 
      "key.deserializer" -> classOf[StringDeserializer], 
      "value.deserializer" -> classOf[StringDeserializer], 
      "group.id" -> "KafkaGroup", 
      "auto.offset.reset" -> "latest" 
    )
//
//    val mapx = Map[TopicPartition, Int](
//     new TopicPartition("TodaysTopic", 0) -> 1
// )
     

    val topics = Array("TodaysTopic") 
/*    val stream = KafkaUtils.createDirectStream[String, String]( 
      ssc, 
      PreferConsistent, 
    // ConsumerStrategies.Assign[String, String](mapx,kafkaParams,1))
     Subscribe[String, String](topics, kafkaParams))
*/   
 
    val fromOffsets= getLastCommittedOffsets("TodaysTopic","KafkaGroup","stream_kafka_offsets","localhost:2181",
                                        "\\Users\\AviShri\\kafka_home\\kafka_2.11-1.1.0",30,3000)
    
 
val inputDStream = KafkaUtils.createDirectStream[String,String](ssc,PreferConsistent,
                           ConsumerStrategies.Assign[String, String](fromOffsets.keys.toList,kafkaParams,fromOffsets))
 
    // Define a schema for JSON data 
    val schema = new StructType() 
      .add("contactId", IntegerType)
      .add("firstName", StringType) 
      .add("lastName",StringType)
 
 
    // Process batches: 
    // Parse JSON and create Data Frame 
    // Execute computation on that Data Frame and print result 
    inputDStream.foreachRDD 
    { (rdd, time) =>
     val offsetRanges=rdd.asInstanceOf[HasOffsetRanges].offsetRanges
     rdd.foreachPartition { iter =>
    val o: OffsetRange = offsetRanges(TaskContext.get.partitionId)
    println(s"${o.topic} ${o.partition} ${o.fromOffset} ${o.untilOffset}")}
     println("Offset "+offsetRanges)
     
      val data = rdd.map(record => record.value)
      val json = spark.read.schema(schema).json(data)
      json.show()
      
      saveOffsets("TodaysTopic","KafkaGroup",offsetRanges,
                "stream_kafka_offsets",new org.apache.spark.streaming.Time(1000))
      
   
      
 //     val json = spark.read.schema(schema).json(data) 
     // val result = json.groupBy($"action").agg(count("*").alias("count")) 
     // result.show 
       
      println("-------------------------------Gadiraju----------------------------") 
   
      //println(data) 
    } 
 
 
    // Start Stream 
    ssc.start() 
    ssc.awaitTermination() 
  } 
}