package edu.berkeley.cs.amplab.carat

import spark._
import spark.SparkContext._
import spark.timeseries._
import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq
import scala.collection.immutable.Set
import scala.collection.immutable.HashSet
import scala.collection.immutable.TreeMap
import collection.JavaConversions._
import com.amazonaws.services.dynamodb.model.AttributeValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import edu.berkeley.cs.amplab.carat.dynamodb.DynamoAnalysisUtil
import com.amazonaws.services.dynamodb.model.Key
import edu.berkeley.cs.amplab.carat.dynamodb.DynamoDbDecoder
import edu.berkeley.cs.amplab.carat.dynamodb.DynamoDbEncoder

/**
 * Analyzes data in the Carat Amazon DynamoDb to obtain probability distributions
 * of battery rates for each of the following case pairs:
 * 1) App X is running & App X is not running
 * 2) App X is running on uuId U & App X is running on uuId != U
 * 3) OS Version == V & OS Version != V
 * 4) Device Model == M & Device Model != M
 * 5) uuId == U & uuId != U
 * 6) Similar apps to uuId U are running vs dissimilar apps are running.
 *    This is calculated by taking the set A of all apps ever reported running on uuId U
 *    and taking the data from samples where (A intersection sample.getAllApps()).size >= ln(A)
 *    and comparing it with (A intersection sample.getAllApps()).size < ln(A).
 *
 * Where uuId is a unique device identifier.
 *
 * Note: We do not store hogs or bugs with negative distance values.
 *
 * @author Eemil Lagerspetz
 */

object CaratDynamoDataAnalysis {

  // Bucketing and decimal constants
  val BUCKETS = 100
  val SMALLEST_BUCKET = 0.0001
  val DECIMALS = 3
  var DEBUG = false
  val LIMIT_SPEED = false
  val ABNORMAL_RATE = 9

  // For saving rates so they do not have to be fetched from DynamoDB every time
  val RATES_CACHED = "/mnt/TimeSeriesSpark/spark-temp/stable-cached-rates.dat"
  // to make sure that the old RATES_CACHED is not overwritten while it is being worked on
  val RATES_CACHED_NEW = "/mnt/TimeSeriesSpark/spark-temp/stable-cached-rates-new.dat"
  val LAST_SAMPLE = "/mnt/TimeSeriesSpark/spark-temp/stable-last-sample.txt"
  val LAST_REG = "/mnt/TimeSeriesSpark/spark-temp/stable-last-reg.txt"

  val last_sample = DynamoAnalysisUtil.readDoubleFromFile(LAST_SAMPLE)

  var last_sample_write = 0.0

  val last_reg = DynamoAnalysisUtil.readDoubleFromFile(LAST_REG)

  var last_reg_write = 0.0

  /**
   * Main program entry point.
   */
  def main(args: Array[String]) {
    var master = "local[1]"
    if (args != null && args.length >= 1) {
      master = args(0)
      if (args.length > 1 && args(1) == "DEBUG")
        DEBUG = true
    }
    // turn off INFO logging for spark:
    System.setProperty("hadoop.root.logger", "WARN,console")
    // Hopefully turn on ProbUtil debug logging:
    System.setProperty("log4j.logger.spark.timeseries.ProbUtil", "DEBUG")
    // Fix Spark running out of space on AWS.
    System.setProperty("spark.local.dir", "/mnt/TimeSeriesSpark/spark-temp")
    val sc = new SparkContext(master, "CaratDynamoDataAnalysis")
    analyzeData(sc)
    // replace old rate file
    DynamoAnalysisUtil.replaceOldRateFile(RATES_CACHED, RATES_CACHED_NEW)
    sys.exit(0)
  }

  /**
   * Main function. Called from main() after sc initialization.
   */

  def analyzeData(sc: SparkContext) {
    val startTime = DynamoAnalysisUtil.start
    // Unique uuIds, Oses, and Models from registrations.
    val uuidToOsAndModel = new scala.collection.mutable.HashMap[String, (String, String)]
    val allModels = new scala.collection.mutable.HashSet[String]
    val allOses = new scala.collection.mutable.HashSet[String]

    // Master RDD for old data.
    println("Getting old rates from %s".format(RATES_CACHED))
    val oldRates: spark.RDD[CaratRate] = {
      val f = new File(RATES_CACHED)
      if (f.exists()) {
        sc.objectFile(RATES_CACHED)
      } else
        null
    }

    if (oldRates != null) {
      val devices = oldRates.map(x => {
        (x.uuid, (x.os, x.model))
      }).collect()
      for (k <- devices) {
        uuidToOsAndModel += ((k._1, (k._2._1, k._2._2)))
        allOses += k._2._1
        allModels += k._2._2
      }
    }
    // Master RDD for all data
    var allRates: spark.RDD[CaratRate] = oldRates
    println("Retrieving regs from DynamoDb starting with timestamp=%f".format(last_reg))
    if (last_reg > 0) {
      DynamoAnalysisUtil.DynamoDbItemLoop(DynamoDbDecoder.filterItemsAfter(registrationTable, regsTimestamp, last_reg + ""),
        DynamoDbDecoder.filterItemsAfter(registrationTable, regsTimestamp, last_reg + "", _),
        handleRegs(_, _, uuidToOsAndModel, allOses, allModels))
    } else {
      DynamoAnalysisUtil.DynamoDbItemLoop(DynamoDbDecoder.getAllItems(registrationTable),
        DynamoDbDecoder.getAllItems(registrationTable, _),
        handleRegs(_, _, uuidToOsAndModel, allOses, allModels))
    }

    /* TODO: Limit attributesToGet here so that bandwidth is not used for nothing. Right now the memory attributes of samples are not considered. */
    println("Retrieving samples from DynamoDb starting with timestamp=%f".format(last_sample))
    if (last_sample > 0) {
      allRates = DynamoAnalysisUtil.DynamoDbItemLoop(DynamoDbDecoder.filterItemsAfter(samplesTable, sampleTime, last_sample + ""),
        DynamoDbDecoder.filterItemsAfter(samplesTable, sampleTime, last_sample + "", _),
        handleSamples(sc, _, uuidToOsAndModel, _),
        true,
        allRates)
    } else {
      allRates = DynamoAnalysisUtil.DynamoDbItemLoop(DynamoDbDecoder.getAllItems(samplesTable),
        DynamoDbDecoder.getAllItems(samplesTable, _),
        handleSamples(sc, _, uuidToOsAndModel, _),
        true,
        allRates)
    }
    println("All uuIds: " + uuidToOsAndModel.keySet.mkString(", "))
    println("All oses: " + allOses.mkString(", "))
    println("All models: " + allModels.mkString(", "))

    if (allRates != null) {
      allRates.saveAsObjectFile(RATES_CACHED_NEW)
      DynamoAnalysisUtil.saveDoubleToFile(last_sample_write, LAST_SAMPLE)
      DynamoAnalysisUtil.saveDoubleToFile(last_reg_write, LAST_REG)
      println("Analysing data")
      // cache RDD here
      DynamoAnalysisUtil.finish(startTime)
      analyzeRateData(allRates.cache(), uuidToOsAndModel, allOses, allModels)
    }else
      DynamoAnalysisUtil.finish(startTime)
  }

  /**
   * Handles a set of registration messages from the Carat DynamoDb.
   * uuids, oses and models are filled in.
   */
  def handleRegs(key: Key, regs: java.util.List[java.util.Map[String, AttributeValue]],
    uuidToOsAndModel: scala.collection.mutable.HashMap[String, (String, String)],
    oses: scala.collection.mutable.Set[String],
    models: scala.collection.mutable.Set[String]) {

    // Get last reg timestamp for set saving
    if (regs.size > 0) {
      last_reg_write = regs.last.get(regsTimestamp).getN().toDouble
    }

    for (x <- regs) {
      val uuid = { val attr = x.get(regsUuid); if (attr != null) attr.getS() else "" }
      val model = { val attr = x.get(regsModel); if (attr != null) attr.getS() else "" }
      val os = { val attr = x.get(regsOs); if (attr != null) attr.getS() else "" }
      uuidToOsAndModel += ((uuid, (os, model)))
      models += model
      oses += os
    }

    /*
     * TODO: Stddev of samples per user over time,
     * stddev of distributions (hog, etc) per all users over increasing number of users,
     * change of distance of distributions (hog, etc) over increasing number of users.
     */
    //analyzeRateDataStdDevsOverTime(sc, distRet, uuid, os, model, plotDirectory)
  }

  /**
   * Process a bunch of samples, assumed to be in order by uuid and timestamp.
   * will return an RDD of CaratRates. Samples need not be from the same uuid.
   */
  def handleSamples(sc: SparkContext, samples: java.util.List[java.util.Map[java.lang.String, AttributeValue]],
    uuidToOsAndModel: scala.collection.mutable.HashMap[String, (String, String)],
    rates: RDD[CaratRate]) = {

    if (samples.size > 0) {
      val lastSample = samples.last
      last_sample_write = lastSample.get(sampleTime).getN().toDouble
    }

    var rateRdd = sc.parallelize[CaratRate]({
      val mapped = samples.map(x => {
        /* See properties in package.scala for data keys. */
        val uuid = x.get(sampleKey).getS()
        val apps = x.get(sampleProcesses).getSS().map(w => {
          if (w == null)
            ""
          else {
            val s = w.split(";")
            if (s.size > 1)
              s(1).trim
            else
              ""
          }
        })

        val time = { val attr = x.get(sampleTime); if (attr != null) attr.getN() else "" }
        val batteryState = { val attr = x.get(sampleBatteryState); if (attr != null) attr.getS() else "" }
        val batteryLevel = { val attr = x.get(sampleBatteryLevel); if (attr != null) attr.getN() else "" }
        val event = { val attr = x.get(sampleEvent); if (attr != null) attr.getS() else "" }
        (uuid, time, batteryLevel, event, batteryState, apps)
      })
      DynamoAnalysisUtil.rateMapperPairwise(uuidToOsAndModel, mapped)
    })
    if (rates != null)
      rateRdd = rateRdd.union(rates)
    rateRdd
  }

  /**
   * Main analysis function. Called on the entire collected set of CaratRates.
   */
  def analyzeRateData(allRates: RDD[CaratRate],
    uuidToOsAndModel: scala.collection.mutable.HashMap[String, (String, String)],
    oses: scala.collection.mutable.Set[String], models: scala.collection.mutable.Set[String]) {
    val startTime = DynamoAnalysisUtil.start
    /**
     * uuid distributions, xmax, ev and evNeg
     * FIXME: With many users, this is a lot of data to keep in memory.
     * Consider changing the algorithm and using RDDs.
     */
    var distsWithUuid = new TreeMap[String, TreeMap[Int, Double]]
    var distsWithoutUuid = new TreeMap[String, TreeMap[Int, Double]]
    /* xmax, ev, evNeg */
    var parametersByUuid = new TreeMap[String, (Double, Double, Double)]
    /* evDistances*/
    var evDistanceByUuid = new TreeMap[String, Double]
    // apps by Uuid for all devices
    var appsByUuid = new TreeMap[String, Set[String]]

    var allApps = allRates.flatMap(_.allApps).collect().toSet
    val DAEMONS_LIST_GLOBBED = DynamoAnalysisUtil.daemons_globbed(allApps)
    allApps --= DAEMONS_LIST_GLOBBED
    println("AllApps (no daemons): " + allApps)

    //Remove Daemons
    println("Removing daemons from the database")
    DynamoAnalysisUtil.removeDaemons(DAEMONS_LIST_GLOBBED)
    //Remove old bugs

    for (os <- oses) {
      val fromOs = allRates.filter(_.os == os)
      val notFromOs = allRates.filter(_.os != os)
      // no distance check, not bug or hog
      println("Considering os os=" + os)
      writeTripletUngrouped(fromOs, notFromOs, DynamoDbEncoder.put(osTable, osKey, os, _, _, _, _, _, _),
        { println("Delete not implemented for OS versions.") }, false)
    }

    for (model <- models) {
      val fromModel = allRates.filter(_.model == model)
      val notFromModel = allRates.filter(_.model != model)
      // no distance check, not bug or hog
      println("Considering model model=" + model)
      writeTripletUngrouped(fromModel, notFromModel, DynamoDbEncoder.put(modelsTable, modelKey, model, _, _, _, _, _, _),
        { println("Delete not implemented for models.") }, false)
    }

    // quick fix:
    /*
      1. delete all bugs
      2. add hogs
      3. remove non-hogs from hogs database
      4. add bugs
      5. remove similarApps sets for users with zero intersections while adding new similarApps sets
     */
    /* Right way:
      0. determine hogs and store their distributions in memory
      1. remove non-hogs
      2. remove new hogs from bugs table
      3. remove non-bugs from bugs table
      4. insert new hogs
      5. insert new bugs
      6. remove similarApps sets for users with zero intersections while adding new similarApps sets
    */
    println("Clearing bugs")
    DynamoDbDecoder.deleteAllItems(bugsTable, resultKey, hogKey)

    var allHogs = new HashSet[String]
    /* Hogs: Consider all apps except daemons. */
    for (app <- allApps) {
      val filtered = allRates.filter(_.allApps.contains(app))
      val filteredNeg = allRates.filter(!_.allApps.contains(app))
      println("Considering hog app=" + app)
      if (writeTripletUngrouped(filtered, filteredNeg, DynamoDbEncoder.put(hogsTable, hogKey, app, _, _, _, _, _, _),
        DynamoDbDecoder.deleteItem(hogsTable, app), true)) {
        // this is a hog
        allHogs += app
      }
    }

    val globalNonHogs = allApps -- allHogs
    println("Removing non-hogs from the hogs table: " + globalNonHogs)
    DynamoDbDecoder.deleteItems(hogsTable, hogKey, globalNonHogs.map(x => {
      (hogKey, x)
    }).toArray: _*)

    val uuidArray = uuidToOsAndModel.keySet.toArray.sortWith((s, t) => {
      s < t
    })

    for (i <- 0 until uuidArray.length) {
      val uuid = uuidArray(i)
      val fromUuid = allRates.filter(_.uuid == uuid).cache()

      var uuidApps = fromUuid.flatMap(_.allApps).collect().toSet
      uuidApps --= DAEMONS_LIST_GLOBBED
      val nonHogApps = uuidApps -- allHogs

      if (uuidApps.size > 0)
        similarApps(allRates, uuid, uuidApps)
      //else
      // Remove similar apps entry?

      val notFromUuid = allRates.filter(_.uuid != uuid).cache()
      // no distance check, not bug or hog
      println("Considering jscore uuid=" + uuid)
      val (xmax, bucketed, bucketedNeg, ev, evNeg, evDistance) = getDistanceAndDistributions(fromUuid, notFromUuid)
      if (bucketed != null && bucketedNeg != null) {
        distsWithUuid += ((uuid, bucketed))
        distsWithoutUuid += ((uuid, bucketedNeg))
        parametersByUuid += ((uuid, (xmax, ev, evNeg)))
        evDistanceByUuid += ((uuid, evDistance))
      }
      appsByUuid += ((uuid, uuidApps))

      /* Bugs: Only consider apps reported from this uuId. Only consider apps not known to be hogs. */
      for (app <- nonHogApps) {
        val appFromUuid = fromUuid.filter(_.allApps.contains(app))
        val appNotFromUuid = notFromUuid.filter(_.allApps.contains(app))
        println("Considering bug app=" + app + " uuid=" + uuid)
        writeTripletUngrouped(appFromUuid, appNotFromUuid, DynamoDbEncoder.putBug(bugsTable, (resultKey, hogKey), (uuid, app), _, _, _, _, _, _),
          DynamoDbDecoder.deleteItem(bugsTable, uuid, app), true)
      }
    }
    println("Saving J-Scores")
    // Save J-Scores of all users.
    writeJScores(distsWithUuid, distsWithoutUuid, parametersByUuid, evDistanceByUuid, appsByUuid)
    DynamoAnalysisUtil.finish(startTime)
  }

  /**
   * Calculate similar apps for device `uuid` based on all rate measurements and apps reported on the device.
   * Write them to DynamoDb.
   */
  def similarApps(all: RDD[CaratRate], uuid: String, uuidApps: Set[String]) {
    val startTime = DynamoAnalysisUtil.start
    val sCount = similarityCount(uuidApps.size)
    printf("SimilarApps uuid=%s sCount=%s uuidApps.size=%s\n", uuid, sCount, uuidApps.size)
    val similar = all.filter(_.allApps.intersect(uuidApps).size >= sCount)
    val dissimilar = all.filter(_.allApps.intersect(uuidApps).size < sCount)
    //printf("SimilarApps similar.count=%s dissimilar.count=%s\n",similar.count(), dissimilar.count())
    // no distance check, not bug or hog
    println("Considering similarApps uuid=" + uuid)
    writeTripletUngrouped(similar, dissimilar, DynamoDbEncoder.put(similarsTable, similarKey, uuid, _, _, _, _, _, _),
      { DynamoDbDecoder.deleteItem(similarsTable, uuid) }, false)
      DynamoAnalysisUtil.finish(startTime)
  }

  /**
   * Get the distributions, xmax, ev's and ev distance of two collections of CaratRates.
   */
  def getDistanceAndDistributions(one: RDD[CaratRate], two: RDD[CaratRate]) = {
    val startTime = DynamoAnalysisUtil.start
    // probability distribution: r, count/sumCount

    /* Figure out max x value (maximum rate) and bucket y values of 
     * both distributions into n buckets, averaging inside a bucket
     */

    /* FIXME: Should not flatten RDD's, but figure out how to transform an
     * RDD of Rates => RDD of UniformDists => RDD of Double,Double pairs (Bucketed values)  
     */
    val flatOne = one.map(x => {
      if (x.isRateRange())
        x.rateRange
      else
        new UniformDist(x.rate, x.rate)
    }).collect()
    val flatTwo = two.map(x => {
      if (x.isRateRange())
        x.rateRange
      else
        new UniformDist(x.rate, x.rate)
    }).collect()

    var evDistance = 0.0

    if (flatOne.size > 0 && flatTwo.size > 0) {
      println("rates=" + flatOne.size + " ratesNeg=" + flatTwo.size)
      if (flatOne.size < 10) {
        println("Less than 10 rates in \"with\": " + flatOne.mkString("\n"))
      }

      if (flatTwo.size < 10) {
        println("Less than 10 rates in \"without\": " + flatTwo.mkString("\n"))
      }

      if (DEBUG) {
        ProbUtil.debugNonZero(flatOne.map(_.getEv), flatTwo.map(_.getEv), "rates")
      }
      // Log bucketing:
      val (xmax, bucketed, bucketedNeg, ev, evNeg) = ProbUtil.logBucketDistributionsByX(flatOne, flatTwo, BUCKETS, SMALLEST_BUCKET, DECIMALS)

      evDistance = DynamoAnalysisUtil.evDiff(ev, evNeg)

      if (evDistance > 0) {
        var imprHr = (100.0 / evNeg - 100.0 / ev) / 3600.0
        val imprD = (imprHr / 24.0).toInt
        imprHr -= imprD * 24.0
        printf("evWith=%s evWithout=%s evDistance=%s improvement=%s days %s hours\n", ev, evNeg, evDistance, imprD, imprHr)
      } else
        printf("evWith=%s evWithout=%s evDistance=%s\n", ev, evNeg, evDistance)

      if (DEBUG) {
        ProbUtil.debugNonZero(bucketed.map(_._2), bucketedNeg.map(_._2), "bucket")
      }
      DynamoAnalysisUtil.finish(startTime)
      (xmax, bucketed, bucketedNeg, ev, evNeg, evDistance)
    } else
      (0.0, null, null, 0.0, 0.0, 0.0)
  }

  /**
   * Write the probability distributions, the distance, and the xmax value to DynamoDb. Ungrouped CaratRates variant.
   */
  def writeTripletUngrouped(one: RDD[CaratRate], two: RDD[CaratRate], putFunction: (Double, Seq[(Int, Double)], Seq[(Int, Double)], Double, Double, Double) => Unit,
    deleteFunction: => Unit, isBugOrHog: Boolean) = {
    val startTime = DynamoAnalysisUtil.start
    val (xmax, bucketed, bucketedNeg, ev, evNeg, evDistance) = getDistanceAndDistributions(one, two)
    if (bucketed != null && bucketedNeg != null) {
      if (evDistance > 0 || !isBugOrHog) {
        putFunction(xmax, bucketed.toArray[(Int, Double)], bucketedNeg.toArray[(Int, Double)], evDistance, ev, evNeg)
      } else if (evDistance <= 0 && isBugOrHog) {
        /* We should probably remove it in this case. */
        deleteFunction
      }
    } else
      deleteFunction
    DynamoAnalysisUtil.finish(startTime)
    isBugOrHog && evDistance > 0
  }

  /**
   * The J-Score is the % of people with worse = higher energy use.
   * therefore, it is the size of the set of evDistances that are higher than mine,
   * compared to the size of the user base.
   * Note that the server side multiplies the JScore by 100, and we store it here
   * as a fraction.
   */
  def writeJScores(distsWithUuid: TreeMap[String, TreeMap[Int, Double]],
    distsWithoutUuid: TreeMap[String, TreeMap[Int, Double]],
    parametersByUuid: TreeMap[String, (Double, Double, Double)],
    evDistanceByUuid: TreeMap[String, Double],
    appsByUuid: TreeMap[String, Set[String]]) {
    val startTime = DynamoAnalysisUtil.start
    val dists = evDistanceByUuid.map(_._2).toSeq.sorted

    for (k <- distsWithUuid.keys) {
      val (xmax, ev, evNeg) = parametersByUuid.get(k).getOrElse((0.0, 0.0, 0.0))

      /**
       * jscore is the % of people with worse = higher energy use.
       * therefore, it is the size of the set of evDistances that are higher than mine,
       * compared to the size of the user base.
       */
      val jscore = {
        val temp = evDistanceByUuid.get(k).getOrElse(0.0)
        if (temp == 0)
          0
        else
          ProbUtil.nDecimal(dists.filter(_ > temp).size * 1.0 / dists.size, DECIMALS)
      }
      val distWith = distsWithUuid.get(k).getOrElse(null)
      val distWithout = distsWithoutUuid.get(k).getOrElse(null)
      val apps = appsByUuid.get(k).getOrElse(null)
      if (distWith != null && distWithout != null && apps != null)
        DynamoDbEncoder.put(resultsTable, resultKey, k, xmax, distWith.toArray[(Int, Double)], distWithout.toArray[(Int, Double)], jscore, ev, evNeg, apps.toSeq)
      else
        printf("Error: Could not save jscore, because: distWith=%s distWithout=%s apps=%s\n", distWith, distWithout, apps)
    }
    //DynamoDbEncoder.put(xmax, bucketed.toArray[(Int, Double)], bucketedNeg.toArray[(Int, Double)], jScore, ev, evNeg)
    DynamoAnalysisUtil.finish(startTime)
  }
}
