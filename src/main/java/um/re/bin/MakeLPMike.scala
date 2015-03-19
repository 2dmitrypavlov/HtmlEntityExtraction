package um.re.bin

import org.apache.spark.mllib.linalg.Vector
import org.apache.hadoop.io.MapWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.JobConf
import org.apache.spark._
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd._
import org.apache.spark.serializer.KryoSerializer
import scala.collection.JavaConversions._
import play.api.libs.json._
import org.elasticsearch.hadoop.mr.EsInputFormat
import org.apache.spark.SparkContext
import org.apache.hadoop.io.MapWritable
import org.apache.hadoop.io.Text
import org.apache.spark.serializer.KryoSerializer
import um.re.utils.Utils
import um.re.utils.EsUtils
import org.apache.spark.mllib.tree.GradientBoostedTrees
import org.apache.spark.mllib.tree.configuration.BoostingStrategy
import org.apache.spark.mllib.feature.IDF
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.feature.HashingTF
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.mllib.linalg.Vectors

object MakeLPMike extends App {

  val conf_s = new SparkConf().setAppName("es").set("master", "yarn-client").set("spark.serializer", classOf[KryoSerializer].getName)
  val sc = new SparkContext(conf_s)

  val conf = new JobConf()
  conf.set("es.resource", EsUtils.ESINDEX)
  conf.set("es.nodes", EsUtils.ESIP)
  val source = sc.newAPIHadoopRDD(conf, classOf[EsInputFormat[Text, MapWritable]], classOf[Text], classOf[MapWritable])
  val all = source.map { l => (l._1.toString(), l._2.map { case (k, v) => (k.toString, v.toString()) }.toMap) }.repartition(600)
  //merge text before and after

  val parsedData = all.map { l =>
    val before = Utils.tokenazer(l._2.apply("text_before"))
    val after = Utils.tokenazer(l._2.apply("text_after"))
    val domain = Utils.getDomain(l._2.apply("url"))
    val location = Integer.valueOf(l._2.apply("location")).toDouble
    val parts = before ++ after ++ Array(domain) //, location) 
    val parts_embedded = parts //.filter { w => (!w.isEmpty() && w.length > 3) }.map { w => w.toLowerCase }
    if ((l._2.apply("priceCandidate").contains(l._2.apply("price"))))
      (1, parts_embedded,location)
    else
      (0, parts_embedded,location)
  }.filter(l => l._2.length > 1)
  parsedData.take(10).foreach(println)

  val splits = parsedData.randomSplit(Array(0.7, 0.3))
  val (trainingData, test) = (splits(0), splits(1))

  //trainng idf
  val hashingTF = new HashingTF(50000)
  val tf: RDD[Vector] = hashingTF.transform(trainingData.map(l => l._2))

  val idf = (new IDF(minDocFreq = 10)).fit(tf)
  //val tfidf = idf.transform(tf)
  val idf_vector  = idf.idf.toArray
  
  def data_to_points(data:RDD[(Int,Seq[String],Double)]) = {
    val idf_vals = idf_vector
    val tf_model = hashingTF
    data.map{case(lable,txt,location)=>
      val tf_vals = tf_model.transform(txt).toArray
      val tfidf_vals = (tf_vals,idf_vals).zipped.map((d1,d2)=>d1*d2)
      val features = tfidf_vals++Array(location)
      val values = features.filter{l=>l!=0}
      val index = features.zipWithIndex.filter{l=> l._1!=0 }.map{l=>l._2}
      LabeledPoint(lable,Vectors.sparse(features.length,index, values))
      
    }
  }
  
  val training_points = data_to_points(trainingData)
  val test_points = data_to_points(test)
  
  val boostingStrategy =
    BoostingStrategy.defaultParams("Classification")
  boostingStrategy.numIterations = 40 // Note: Use more in practice
  boostingStrategy.treeStrategy.maxDepth = 2
  val model =
    GradientBoostedTrees.train(training_points, boostingStrategy)

  // Evaluate model on test instances and compute test error
 def labelAndPred(input_points:RDD[LabeledPoint])={
    val local_model = model
	  val labelAndPreds = input_points.map { point =>
	  val prediction = local_model.predict(point.features)
	  (point.label, prediction)
	  }
   labelAndPreds
    
  }
  val labelAndPreds = labelAndPred(test_points)
  val tp = labelAndPreds.filter{case (l,p) => (l==1)&&(p==1) }.count
  val tn = labelAndPreds.filter{case (l,p) => (l==0)&&(p==0) }.count
  val fp = labelAndPreds.filter{case (l,p) => (l==0)&&(p==1) }.count
  val fn = labelAndPreds.filter{case (l,p) => (l==1)&&(p==0) }.count

  
  
  /*def tf_to_tfidf(rdd:RDD[Vector]):RDD[Vector] ={
    val idf_vals = idf_vector
    rdd.map{tf_vals => 
      		val tfidfArr = (tf_vals.toArray,idf_vals).zipped.map((d1,d2)=>d1*d2)
      		val values = tfidfArr.filter{l=>l!=0}
      		val index = tfidfArr.zipWithIndex.filter{l=> l._1!=0 }.map{l=>l._2}
      		Vectors.sparse(50000,index, values)}
  }
  
  //training tfidf
  val tf_train_p: RDD[Vector] = hashingTF.transform(trainingData.filter(t=> t._1==1).map(l => l._2))
  val tf_train_n: RDD[Vector] = hashingTF.transform(trainingData.filter(t=> t._1==0).map(l => l._2))
  
  val tfidf_train_p = tf_to_tfidf(tf_train_p)
  val tfidf_train_n = tf_to_tfidf(tf_train_n)
  
  val positive_train = tfidf_train_p.map { l => LabeledPoint(1, l) }
  val negat_train = tfidf_train_n.map { l => LabeledPoint(0, l) }
  //ALL TOGETHER
  val points_train = positive_train ++ negat_train
  
  points_train.partitions.size
  //test tfidf
  
  val tf_test_p: RDD[Vector] = hashingTF.transform(test.filter(t=> t._1==1).map(l => l._2))
  val tf_test_n: RDD[Vector] = hashingTF.transform(test.filter(t=> t._1==0).map(l => l._2))
  
 
  val tfidf_test_p = tf_to_tfidf(tf_test_p)
  val tfidf_test_n = tf_to_tfidf(tf_test_n)
  
  val positive = tfidf_test_p.map { l => LabeledPoint(1, l) }
  val negat = tfidf_test_n.map { l => LabeledPoint(0, l) }
  //ALL TOGETHER
  val points_test = positive ++ negat
  //points_train.repartition(192)
  // train model
  val boostingStrategy =
    BoostingStrategy.defaultParams("Classification")
  boostingStrategy.numIterations = 40 // Note: Use more in practice
  boostingStrategy.treeStrategy.maxDepth = 2
  val model =
    GradientBoostedTrees.train(points_train, boostingStrategy)

  // Evaluate model on test instances and compute test error
 def labelAndPred(input_points:RDD[LabeledPoint])={
    val local_model = model
	  val labelAndPreds = input_points.map { point =>
	  val prediction = local_model.predict(point.features)
	  (point.label, prediction)
	  }
   labelAndPreds
    
  }
  val labelAndPreds = labelAndPred(points_test)
  val tp = labelAndPreds.filter{case (l,p) => (l==1)&&(p==1) }.count
  val tn = labelAndPreds.filter{case (l,p) => (l==0)&&(p==0) }.count
  val fp = labelAndPreds.filter{case (l,p) => (l==0)&&(p==1) }.count
  val fn = labelAndPreds.filter{case (l,p) => (l==1)&&(p==0) }.count
  */
  //SAVE TO FILE ALL DATA
  /*MLUtils.saveAsLibSVMFile(points, "hdfs:///pavlovout/points")
  MLUtils.saveAsLibSVMFile(points, "s3://pavlovout/points")
  // val tf_ppoints = tf.map { l => LabeledPoint(1, l) } ++ tfn.map { l => LabeledPoint(0, l) }
  //SAVE TO FILE ALL DATA
  MLUtils.saveAsLibSVMFile(points, "hdfs:///pavlovout/points")
  test.saveAsTextFile("hdfs:///pavlovout/test_text")
  test.saveAsObjectFile("hdfs:///pavlovout/test_obj")
  val l = sc.textFile("hdfs:///pavlovout/test_text")
  val ll = sc.objectFile("hdfs:///pavlovout/test_obj")
*/
  
}