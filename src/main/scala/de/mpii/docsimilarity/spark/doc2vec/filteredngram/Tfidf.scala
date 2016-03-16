package de.mpii.docsimilarity.spark.doc2vec.filteredngram

import java.io.{ByteArrayInputStream, DataInputStream}

import de.mpii.docsimilarity.mr.input.clueweb.ClueWebWarcRecord
import de.mpii.docsimilarity.mr.utils.CleanContentTxt
import de.mpii.docsimilarity.spark.doc2vec.{DocVec, VectorizeDoc}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.{BytesWritable, IntWritable}
import org.apache.spark.{SparkConf, SparkContext}
import org.jsoup.Jsoup

/**
  * Created by khui on 10/26/15.
  */
object Tfidf extends DocVec {
  private var inputdir: String = ""
  private var outputdir: String = ""
  private var parNumber: Int = 1024
  // 0 use all, positive use select number
  private var termnum2select: Int = 1000
  private var term2selectfile: String = ""
  private var cwyear: String = "cw4y"


  def parse(args: List[String]): Unit = args match {
    case ("-i") :: dir :: tail =>
      inputdir = dir
      parse(tail)
    case ("-o") :: file :: tail =>
      outputdir = file
      parse(tail)
    case ("-n") :: tnum :: tail =>
      termnum2select = tnum.toInt
      parse(tail)
    case ("-t") :: tfile :: tail =>
      term2selectfile = tfile
      parse(tail)
    case ("-c") :: coreNum :: tail =>
      parNumber = coreNum.toInt
      parse(tail)
    case ("-y") :: year :: tail =>
      cwyear = year
      parse(tail)
    case (Nil) =>
  }


  def main(args: Array[String]) {
    parse(args.toList)
    val conf: SparkConf = new SparkConf()
      .setAppName("Filtered tfidf one-hot doc vector for " + cwyear + ", " + termnum2select)
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.local.dir", "/GW/D5data-2/khui/cw-docvector-termdf/tmp")
      .set("spark.akka.frameSize", "256")
      .set("spark.kryoserializer.buffer.max.mb","1024")
      .registerKryoClasses(
        Array(classOf[String],
          classOf[(String, String)],
          classOf[(Int, String)],
          classOf[(String, Double)],
          classOf[Map[String, (Map[String, (Int, Int, Int)], Int, String)]],
          classOf[(Int, Int, Int)],
          classOf[Map[String, (Int, Int, Int)]],
          classOf[Seq[(Int, String, String)]],
          classOf[VectorizeDoc],
          classOf[(Int, String, String)]
        ))
    val sc: SparkContext = new SparkContext(conf)

    val termselectorids = term2selectfile.split("/")

    val termselectorname = termselectorids(termselectorids.length - 2)
    val termselectorparams = termselectorids.last

    // check existence of output directory
    val fs: FileSystem = FileSystem
      .get(sc.hadoopConfiguration)
    outputdir = outputdir + "/tfidf/" + termselectorname + "_" + termselectorparams + "_" + termnum2select
    if (fs.exists(new Path(outputdir))) {
      println(outputdir + " is being removed")
      fs.delete(new Path(outputdir), true)
    }

    val qidTermVal: Map[Int, Map[String, Double]] =
    sc
      .textFile(term2selectfile + "/part*", 1)
      .map(line => line.split(" "))
      .map(cols => cols.head.toInt ->
       cols.filter(c => c.contains(":")).map(c => c.split(":")).map(tidVal => tidVal.head -> tidVal.last.toDouble))
      .map{case(qid, termVals) => qid -> termVals.sortBy(v => -v._2).slice(0, termnum2select).toMap}
      .collect()
      .toMap

    println("Read in " + qidTermVal.size + " from " + term2selectfile)

    val pathList: List[String] = fs
      .listStatus(new Path(inputdir))
      .filter(f => !f.getPath.getName.startsWith("_"))
      .map(f => f.getPath.toString)
      .toList

    val qids = cwyear match {
      case "cw09" => 101 to 200
      case "cw12" => 201 to 300
      case "cw4y" => 101 to 300
    }

    val years = cwyear match {
      case "cw09" => Array("cw09")
      case "cw12" => Array("cw12")
      case "cw4y" => Array("cw09", "cw12")
    }

    val psf: VectorizeDoc = new VectorizeDoc()
    val cleanpipeline: CleanContentTxt = new CleanContentTxt(64)
    val yearTermidxdfcf: Map[String, Map[String, (Int, Int, Int)]] =
      years
        .par
        .map(year => year -> termIdxDfCf(sc, year, parNumber))
        .toMap
        .seq


    val pathSlices = pathList
      .par
      .map(p => (p, psf.path2qid(p)))
      .filter(t2 => qids.toArray.contains(t2._2))
      .foreach(
        pathQid => {
          val path = pathQid._1
          val qid = pathQid._2
          val (year, collen, cwrecord) = psf.qid2yearrecord(qid)
          val parNumber = this.parNumber
          val outputdir = this.outputdir
          val termTidDfCf: Map[String, (Int, Int, Int)] = yearTermidxdfcf(year)
          val termVal: Map[Int, Double] =
            qidTermVal(qid)
            .filter(termval => termTidDfCf.contains(termval._1))
            .map(termval => termTidDfCf(termval._1)._1 -> termval._2)


          println("Start " + qid + " with " + termVal.size + " to filter.")

          val cwidTerms: Map[String, Map[Int, Double]] =
            sc
              // read in for each query
              .sequenceFile(path, classOf[IntWritable], classOf[BytesWritable], parNumber)
              .map { kv =>
                val cwdoc: ClueWebWarcRecord = Class.forName(cwrecord).newInstance().asInstanceOf[ClueWebWarcRecord]
                cwdoc.readFields(new DataInputStream(new ByteArrayInputStream(kv._2.getBytes)))
                (kv._1.get(), cwdoc.getDocid, cwdoc.getContent)
              }
              .filter(v => v._3 != null)
              .collect()
              .map{case(q, cwid,doccontent) => (q, cwid, Jsoup.parse(doccontent))}
              .map{case(q, cwid, htmlDoc) => (q, cwid, cleanpipeline.cleanTxtStr(htmlDoc, cwid, true))}
              .map { case (qid: Int, cwid: String, content: String) =>
                var termTfidfSeq: Seq[(Int, Double)] =
                // termnum2select = 0
                  psf
                    .docstr2TfidfSeq(content, collen, yearTermidxdfcf(year))
                if (termnum2select > 0) {
                  termTfidfSeq = termTfidfSeq.filter(tidTfidf => termVal.contains(tidTfidf._1))
                }
                (qid, cwid, termTfidfSeq)
              }
              .map(t3 => t3._2 -> t3._3.toMap)
              .toMap

          val avgLen = cwidTerms.map(v => v._2.size).sum / cwidTerms.size.toDouble

          println("Finished read in " + cwidTerms.size + " docs with avgLength " + avgLen +" for query " + qid)

          sc
            .parallelize(cwidTerms.toSeq)
            .map(cwtv => mkstring(cwtv._1, cwtv._2))
            .coalesce(1)
            .saveAsTextFile(outputdir + "/" + qid)

          println("Finished " + qid)
        }
      )
  }

}