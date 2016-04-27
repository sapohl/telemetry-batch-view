package telemetry.views

import com.github.nscala_time.time.Imports._
import com.typesafe.config._
import com.mozilla.spark.sql.hyperloglog.aggregates._
import com.mozilla.spark.sql.hyperloglog.functions._
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import org.apache.spark.sql.hive.HiveContext
import org.rogach.scallop._

class Conf(args: Array[String]) extends ScallopConf(args) {
  val from = opt[String]("from", descr = "From submission date", required = false)
  val to = opt[String]("to", descr = "To submission date", required = false)
  verify()
}

object ClientCountView {
  private val hllMerge = new HyperLogLogMerge
  private val base = List("normalized_channel", "country", "version", "e10s_enabled", "e10s_cohort", "os", "os_version")
  // 12 bits corresponds to an error of 0.0163
  private val selection = "hll_create(client_id, 12) as client_id" :: "substr(subsession_start_date, 0, 10) as activity_date" :: base

  val dimensions = "activity_date" :: base

  def aggregate(frame: DataFrame): DataFrame = {
    frame
      .selectExpr(selection:_*)
      .groupBy(dimensions.head, dimensions.tail:_*)
      .agg(hllMerge(col("client_id")).as("hll"))
  }

  def main(args: Array[String]) {
    val conf = new Conf(args)
    val fmt = DateTimeFormat.forPattern("yyyyMMdd")

    val to = conf.to.get match {
      case Some(t) => fmt.print(fmt.parseDateTime(t))
      case _ => fmt.print(DateTime.now.minusDays(1))
    }

    val from = conf.from.get match {
      case Some(f) => fmt.print(fmt.parseDateTime(f))
      case _ => fmt.print(DateTime.now.minusDays(180))
    }

    val sparkConf = new SparkConf().setAppName("ClientCountView")
    sparkConf.setMaster(sparkConf.get("spark.master", "local[*]"))
    val sc = new SparkContext(sparkConf)
    val sqlContext = new HiveContext(sc)

    val hadoopConf = sc.hadoopConfiguration
    hadoopConf.set("fs.s3n.impl", "org.apache.hadoop.fs.s3native.NativeS3FileSystem")
    sqlContext.udf.register("hll_create", hllCreate _)

    val df = sqlContext.read.load("s3://telemetry-parquet/main_summary/v2")
    val subset = df.where(s"submission_date_s3 >= $from and submission_date_s3 <= $to")
    val aggregates = aggregate(subset).coalesce(32)

    val appConf = ConfigFactory.load()
    val parquetBucket = appConf.getString("app.parquetBucket")
    aggregates.write.parquet(s"s3://$parquetBucket/client_count/v$from$to")
  }
}
