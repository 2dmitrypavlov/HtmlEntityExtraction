package um.re.utils

import org.jets3t.service.impl.rest.httpclient.RestS3Service
import org.jets3t.service.security.AWSCredentials

object S3Utils extends App {

  //val inputStream = IOUtils.toInputStream(source, "UTF-8");
  val awsAccessKey = ""
  val awsSecretKey = ""

  val awsCredentials = new AWSCredentials(awsAccessKey, awsSecretKey)
  val s3Service = new RestS3Service(awsCredentials)
  val bucketName = "pavlovout"
  val bucket = s3Service.getOrCreateBucket(bucketName)
  val buckets = s3Service.listAllBuckets();

  // List the objects in this bucket.
  val files = s3Service.listObjects(bucket)
  var size: Long = 0
  var nutchSize: Long = 0
  var numbNutchFile = 0
  for (a <- files) {
    size = size + a.getContentLength
    if (a.getKey().contains("nutch")) {
      nutchSize = nutchSize + a.getContentLength
      numbNutchFile = numbNutchFile + 1
    }
    // println(a.getKey())
    if (a.getKey() == "20-Sep-2014-12-36-52-GMT.txt")
      println("  file name " + a + "   file size :" + a.getContentLength)
  }
  println(" number of files  " + files.length + " size of files  " + size / 1000000000 + " GB ")

  println(" nutch size = " + nutchSize / 1000000000 + " GB " + " number of nutch files = " + numbNutchFile)
}