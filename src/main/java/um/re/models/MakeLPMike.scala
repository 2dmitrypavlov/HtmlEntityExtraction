package um.re.models

import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.mllib.feature.HashingTF
import org.apache.spark.mllib.linalg.Vector
import org.apache.hadoop.io.MapWritable
import org.apache.spark.SparkConf
import org.apache.hadoop.mapred.JobConf
import org.elasticsearch.hadoop.mr.EsInputFormat
import scala.Array.canBuildFrom
import scala.collection.JavaConversions.mapAsScalaMap
import org.apache.hadoop.io.MapWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext.doubleRDDToDoubleRDDFunctions
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.RandomForest
import org.apache.spark.mllib.tree.configuration.Strategy
import org.apache.spark.serializer.KryoSerializer
import um.re.utils.Utils
import org.elasticsearch.hadoop.mr.EsInputFormat
import scala.collection.concurrent.TrieMap
import org.apache.spark.mllib.tree.configuration.BoostingStrategy
import org.apache.spark.mllib.tree.GradientBoostedTrees
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.mllib.classification.SVMWithSGD
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.mllib.feature.IDF


object MakeLPMike extends App {

  val conf_s = new SparkConf().setAppName("es").set("master", "yarn-client").set("spark.serializer", classOf[KryoSerializer].getName)
  val sc = new SparkContext(conf_s)

  val conf = new JobConf()
  conf.set("es.resource", "candidl/data")
  conf.set("es.nodes", "ec2-54-145-93-208.compute-1.amazonaws.com")
  val source = sc.newAPIHadoopRDD(conf, classOf[EsInputFormat[Text, MapWritable]], classOf[Text], classOf[MapWritable])
  val all = source.map { l => (l._1.toString(), l._2.map { case (k, v) => (k.toString, v.toString()) }.toMap) }.repartition(100)
  //merge text before and after

  val parsedData = all.map { l =>
    val before = Utils.tokenazer(l._2.apply("text_before"))
    val after = Utils.tokenazer(l._2.apply("text_after"))
    val domain = Utils.getDomain(l._2.apply("url"))
    val location = l._2.apply("location")
    val parts = before ++ after ++ Array(domain) //, location) 
    val parts_embedded = parts //.filter { w => (!w.isEmpty() && w.length > 3) }.map { w => w.toLowerCase }
    if ((l._2.apply("priceCandidate").contains(l._2.apply("price"))))
      (1, parts_embedded)
    else
      (0, parts_embedded)
  }.filter(l => l._2.length > 1)
  parsedData.take(10).foreach(println)

  val splits = parsedData.randomSplit(Array(0.7, 0.3))
  val (trainingData, test) = (splits(0), splits(1))

  //trainng idf
  val hashingTF = new HashingTF(50000)
  val tf: RDD[Vector] = hashingTF.transform(trainingData.map(l => l._2))

  val idf = (new IDF(minDocFreq = 10)).fit(tf)
  val idf_vector  = idf.idf.toArray

  def tf_to_tfidf(rdd:RDD[Vector]):RDD[Vector] ={
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
  
  
  //test tfidf
  
  val tf_test_p: RDD[Vector] = hashingTF.transform(test.filter(t=> t._1==1).map(l => l._2))
  val tf_test_n: RDD[Vector] = hashingTF.transform(test.filter(t=> t._1==0).map(l => l._2))
  
 
  val tfidf_test_p = tf_to_tfidf(tf_test_p)
  val tfidf_test_n = tf_to_tfidf(tf_test_n)
  
  val positive = tfidf_test_p.map { l => LabeledPoint(1, l) }
  val negat = tfidf_test_n.map { l => LabeledPoint(0, l) }
  //ALL TOGETHER
  val points_test = positive ++ negat
  points_train.repartition(192)
  // train model
  val boostingStrategy =
    BoostingStrategy.defaultParams("Classification")
  boostingStrategy.numIterations = 3 // Note: Use more in practice
  val model =
    GradientBoostedTrees.train(points_train, boostingStrategy)

  // Evaluate model on test instances and compute test error
  val labelAndPreds = points_test.map { point =>
    val prediction = model.predict(point.features)
    (point.label, prediction)
  }

  val tp = labelAndPreds.filter{case (l,p) => (l==1)&&(p==1) }.count
  val tn = labelAndPreds.filter{case (l,p) => (l==0)&&(p==0) }.count
  val fn = labelAndPreds.filter{case (l,p) => (l==0)&&(p==1) }.count
  val fp = labelAndPreds.filter{case (l,p) => (l==1)&&(p==0) }.count
  
  
  
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