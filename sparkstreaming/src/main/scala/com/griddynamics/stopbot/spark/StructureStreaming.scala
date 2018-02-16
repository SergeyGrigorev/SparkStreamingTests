package com.griddynamics.stopbot.spark

import com.griddynamics.stopbot.model.EventStructType
import com.griddynamics.stopbot.spark.Streaming.appConf
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import org.apache.spark.sql.{ForeachWriter, Row, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{StringType, TimestampType}

import scala.collection.JavaConverters._

/**
  * Structure streaming variant of the same task.
  */
object StructureStreaming extends App {
  val logger = Logger("streaming")

  /* application configuration */
  val appConf = ConfigFactory.load
  val windowSec = appConf.getDuration("spark.window").getSeconds
  val slideSec = appConf.getDuration("spark.slide").getSeconds
  val watermark = appConf.getDuration("spark.watermark").getSeconds
  val maxEvents = appConf.getLong("app.max-events")
  val minEvents = appConf.getLong("app.min-events")
  val minRate = appConf.getLong("app.min-rate")
  val banTimeMs = appConf.getDuration("app.ban-time").toMillis
  val banRecordTTL = (banTimeMs / 1000).toInt

  val spark = SparkSession
    .builder
    .config("spark.sql.shuffle.partitions", 4)
    .master("local[*]")
    .appName("SparkStructureStreaming")
    .getOrCreate()

  /* kafka streaming */
  val df = spark
    .readStream
    .format("kafka")
    .option("kafka.bootstrap.servers", appConf.getString("kafka.brokers"))
    .option("subscribe", appConf.getStringList("kafka.topic").asScala.mkString(","))
    .option("startingOffsets", appConf.getString("kafka.offset.reset"))
    .load()

  /* key = user_ip, value =  */
  val parsed =
    df.select(
      col("key").cast(StringType),
      from_json(col("value").cast(StringType), schema = EventStructType.schema).alias("value")
    )

  val aggregated =
    parsed
      .withColumn("eventTime", (col("value.unix_time") / 1000).cast(TimestampType))
      .selectExpr("key as ip", "value.type as action", "eventTime")
      .withWatermark("eventTime", s"$watermark seconds")
      .groupBy(
        col("ip"),
        window(col("eventTime"), s"$windowSec seconds", s"$slideSec seconds"))
      .agg(EventAggregationUdf(col("action"), col("eventTime")).alias("aggregation"))

  val filtered =
    aggregated
      .withColumn("total_events", col("aggregation.clicks") + col("aggregation.watches"))
      .filter(col("total_events") > minEvents)
      .withColumn(
        "rate",
        when(col("aggregation.clicks") > 0, col("aggregation.watches") / col("aggregation.clicks"))
          .otherwise(col("aggregation.watches")))
      .withColumn(
        "incident",
        when(
          col("total_events") > maxEvents,
          concat(
            lit("too much events: "),
            col("total_events"),
            lit(" from "),
            col("aggregation.firstEvent"),
            lit(" to "),
            col("aggregation.lastEvent"))
        ).when(
          col("rate") < minRate,
          concat(
            lit("too small rate: "),
            col("rate"),
            lit(" from "),
            col("aggregation.firstEvent"),
            lit(" to "),
            col("aggregation.lastEvent"))
        ).otherwise(null))
      .filter(col("incident").isNotNull)
      .select(col("ip"), col("window"), col("incident"))

  val output =
    filtered
      .writeStream
      .outputMode("update")
      .format("console")
      .start()


  output.awaitTermination()
}