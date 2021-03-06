package um.re.emr

import java.io.FileWriter

import play.api.libs.json._

/**
  * @author mike
  */
object WhoIsExtractor {
  def main(args: Array[String]) {

    //Set this 4  params before start
    val workDir = "/home/ec2-user/whois/"
    val tmsp = (new java.util.Date).getTime
    val domainListPath = workDir + "domains.csv"
    val apiKey = "f4533029deafbc63u0fde266f5210e4b"

    val rawWhoIsFW = new FileWriter(workDir + "rawWhoIs_" + tmsp + ".txt", true)
    val contactWhoIsFW = new FileWriter(workDir + "contactWhoIs_" + tmsp + ".txt", true)
    val missingRawWhoIsFW = new FileWriter(workDir + "missingRawWhoIs_" + tmsp + ".txt", true)
    val missingContactWhoIsFW = new FileWriter(workDir + "missingContactWhoIs_" + tmsp + ".txt", true)
    val rawDelimiter = "!@#@!"

    val apiPath = "http://api.whoxy.com/?key=" + apiKey + "&whois="


    val domainList = scala.io.Source.fromFile(domainListPath).getLines()
    println("dList " + domainList)
    domainList.foreach { domain =>
      val raw = scala.io.Source.fromURL(apiPath + domain).mkString
      println("post request on : " + domain)
      try {
        rawWhoIsFW.write(rawDelimiter + raw)
      } catch {
        case e: Exception => {
          missingRawWhoIsFW.write(domain)
          println(e.getMessage + "___" + e.getStackTraceString)
        }
      }
      try {
        val jsonResponse = Json.parse(raw)
        val whois_record = jsonResponse.\("whois_record")
        val registrant_contact = whois_record.\("registrant_contact")
        val administrative_contact = whois_record.\("administrative_contact")
        val technical_contact = whois_record.\("technical_contact")

        val contactDetails = domain + rawDelimiter + List(registrant_contact, administrative_contact, technical_contact).map { contactJsoon =>
          val full_name = contactJsoon.\("full_name").asOpt[String].getOrElse("N/A")
          val company_name = contactJsoon.\("company_name").asOpt[String].getOrElse("N/A")
          val mailing_address = contactJsoon.\("mailing_address").asOpt[String].getOrElse("N/A")
          val city_name = contactJsoon.\("city_name").asOpt[String].getOrElse("N/A")
          val state_name = contactJsoon.\("state_name").asOpt[String].getOrElse("N/A")
          val zip_code = contactJsoon.\("zip_code").asOpt[String].getOrElse("N/A")
          val country_name = contactJsoon.\("country_name").asOpt[String].getOrElse("N/A")
          val email_address = contactJsoon.\("email_address").asOpt[String].getOrElse("N/A")
          val phone_number = contactJsoon.\("phone_number").asOpt[String].getOrElse("N/A")

          List(full_name, company_name, mailing_address, city_name, state_name, zip_code, country_name, email_address, phone_number).mkString(rawDelimiter)
        }.mkString(rawDelimiter)
        contactWhoIsFW.write(contactDetails + "\n")
      } catch {
        case e: Exception => {
          missingContactWhoIsFW.write(domain)
          println(e.getMessage + "___" + e.getStackTraceString)
        }
      }
    }
    rawWhoIsFW.close()
    contactWhoIsFW.close()
    missingContactWhoIsFW.close()
    missingRawWhoIsFW.close()
  }
}
