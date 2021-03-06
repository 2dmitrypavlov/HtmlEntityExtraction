package um.re.test
import java.io.File
import org.apache.hadoop.io.MapWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.JobConf
import org.apache.spark._
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd._
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.serializer.KryoRegistrator
import com.esotericsoftware.kryo.Kryo
import um.re.utils.URegistrator
import scala.math
import scala.collection.JavaConversions._
import play.api.libs.json._
import play.api.libs.json.{ Json, JsValue, JsObject, JsArray }
import org.elasticsearch.hadoop.mr.EsInputFormat
import org.elasticsearch.spark
import org.elasticsearch.spark.rdd.EsSpark
import org.apache.spark.SparkContext
import org.apache.hadoop.io.NullWritable
import org.elasticsearch.hadoop.mr.EsOutputFormat
import um.re.utils
import um.re.utils.Utils
import um.re.utils.PriceParcer
import um.re.utils.EsUtils


object TestWrite2ES {
 def main(args: Array[String]) {
	 val conf_w = new JobConf()
	 conf_w.set("es.resource", "process_count/candid")
	 conf_w.set("es.nodes", EsUtils.ESIP)
	 
	 val conf = new JobConf()
	 conf.set("es.resource", "htmls/data")
	 conf.set("es.query", "?q=prodId:23799864")
	 conf.set("es.nodes", EsUtils.ESIP)
  
	 val conf_s = new SparkConf().setAppName("es").setMaster("local[8]").set("spark.serializer", classOf[KryoSerializer].getName)
	 conf_s.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
	 conf_s.set("spark.kryo.registrator", "um.re.es.emr.MyRegistrator")
	 val sc = new SparkContext(conf_s)
	 sc.hadoopConfiguration.set("es.nodes", EsUtils.ESIP)
	 sc.hadoopConfiguration.set("es.query", "?q=html:xml")
	 sc.hadoopConfiguration.set("es.resource", "htmls/data")

	 val source = sc.newAPIHadoopRDD(conf, classOf[EsInputFormat[Text, MapWritable]], classOf[Text], classOf[MapWritable])
  
	 source.flatMap{r=>
	 	val nf = PriceParcer
	 	val doc = r._2.map{case (k, v) => (k.toString, v.toString) }.toMap
	 	val url = doc.get("url").get
	 	val html = doc.get("html").get
	 	nf.findM(url, html)
	 }.map{m=>
	 val mw = Utils.toWritable(m)
	 (NullWritable.get(), mw)
	 }.saveAsNewAPIHadoopFile("-", classOf[NullWritable], classOf[MapWritable], classOf[EsOutputFormat], conf_w)
 }
}