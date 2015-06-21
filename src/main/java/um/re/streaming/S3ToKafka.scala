package um.re.streaming

import org.apache.spark.SparkContext
import org.apache.spark._
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.kafka.KafkaUtils
import kafka.serializer.StringDecoder
import kafka.producer._
import org.apache.spark.streaming.Seconds
import java.util.Properties
import um.re.utils.Utils
import com.utils.messages.MEnrichMessage
import kafka.serializer.DefaultEncoder

object S3ToKafka { //}extends App {

  def main(args: Array[String]) {
    val conf = new SparkConf().setMaster("local[*]")
      .setAppName("CountingSheep")
      .set("spark.executor.memory", "5g")
    val sc = new SparkContext(conf)
    
    //local
    val Array(brokers, topic) = Array("localhost:9092", "seeds")
    val path = "/Users/dmitry/umbrella/seeds_sample"
    
//    val Array(brokers, topic) = Array("54.83.9.85:9092", "seeds")
//    val path = Utils.S3STORAGE + "/dpavlov/seeds20150516"
    val seeds = sc.objectFile[(String)](path, 200)
    //for load testing for(i<- 1 to 100){
    val seeds2kafka = seeds.map { line =>
      MEnrichMessage.string2Message(line).toJson().toString().getBytes()
    }
    //Producer: launch the Array[Byte]result into kafka      
    seeds2kafka.foreachPartition { p =>
      val props = new Properties()
      props.put("metadata.broker.list", brokers)
      props.put("serializer.class", "kafka.serializer.DefaultEncoder")

      @transient val config = new ProducerConfig(props)
      @transient val producer = new Producer[String, Array[Byte]](config)
      p.foreach(rec => producer.send(new KeyedMessage[String, Array[Byte]](topic, rec)))
      producer.close()
    }
    //for testing  println(i*seeds2kafka.count) }
  }
}


