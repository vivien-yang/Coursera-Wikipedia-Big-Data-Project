package wikipedia

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._

import org.apache.spark.rdd.RDD

case class WikipediaArticle(title: String, text: String) {
  /**
    * @return Whether the text of this article mentions `lang` or not
    * @param lang Language to look for (e.g. "Scala")
    */
  def mentionsLanguage(lang: String): Boolean = text.split(' ').contains(lang)
}

object WikipediaRanking {

  val langs = List(
    "JavaScript", "Java", "PHP", "Python", "C#", "C++", "Ruby", "CSS",
    "Objective-C", "Perl", "Scala", "Haskell", "MATLAB", "Clojure", "Groovy")

  val conf = new SparkConf().setAppName("Spark_VivienYang").setMaster("local[1]")
  val sc = new SparkContext(conf)
  // Hint: use a combination of `sc.textFile`, `WikipediaData.filePath` and `WikipediaData.parse`
  val wikiRdd = sc.textFile(WikipediaData.filePath).map(line => WikipediaData.parse(line))

  /** Returns the number of articles on which the language `lang` occurs.
   *  Hint1: consider using method `aggregate` on RDD[T].
   *  Hint2: consider using method `mentionsLanguage` on `WikipediaArticle`
   */

  def occurrencesOfLang(lang: String, rdd: RDD[WikipediaArticle]) = {
    val result_occurences = rdd.map{line =>{
                                if (line.mentionsLanguage(lang)) {(1)}
                                else {(0)}}}
                           .reduce(_+_)
    result_occurences
      /*
                     .aggregate(0)(//can be used if return type is RDD[Int]
                       (acc,value) => (acc + value._2),
                       (acc1,acc2) => (acc1 + acc2))
                       */
  }

  /* (1) Use `occurrencesOfLang` to compute the ranking of the languages
   *     (`val langs`) by determining the number of Wikipedia articles that
   *     mention each language at least once. Don't forget to sort the
   *     languages by their occurrence, in decreasing order!
   */

  def rankLangs(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] = {
    var lang_rdd: org.apache.spark.rdd.RDD[(String, Int)] = sc.emptyRDD
    for (each <- langs) {
      lang_rdd = lang_rdd.union(sc.parallelize(List((each,occurrencesOfLang(each,rdd)))))
    }
    lang_rdd = lang_rdd.sortBy(_._2,false)

    return lang_rdd.collect().toList
   }

  /* Compute an inverted index of the set of articles, mapping each language
   * to the Wikipedia pages in which it occurs.
   */
  def helper(line: WikipediaArticle, langs: List[String]): List[String] = {
    var contains = Array.empty[String]
    for (each <- langs) {
      if (line.mentionsLanguage(each)){
        contains = contains :+ each
      }
    }
    return contains.toList
  }

  def makeIndex(langs: List[String], rdd: RDD[WikipediaArticle]): RDD[(String, Iterable[WikipediaArticle])] = {
    val index = rdd.map(line => (line,helper(line,langs)))
                     .flatMapValues(lang => lang.toIterator)
                     .map{case(k,v) => (v,k)}
                     .groupByKey()
    return index
  }

  /* (2) Compute the language ranking again, but now using the inverted index. Can you notice
   *     a performance improvement?
   */
  def rankLangsUsingIndex(index: RDD[(String, Iterable[WikipediaArticle])]): List[(String, Int)] = {
    val result = index.mapValues(articles => articles.size)
                      .sortBy(_._2,false)
    return result.collect().toList
  }

  /* (3) Use `reduceByKey` so that the computation of the index and the ranking are combined.
   *     Can you notice an improvement in performance compared to measuring *both* the computation of the index
   *     and the computation of the ranking? If so, can you think of a reason?
   */

  def rankLangsReduceByKey(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] = {
    val langs_rdd = rdd.map(line => helper(line,langs))
                       .flatMap(word => word)//flatten the words
                       .map(word => (word,1))
                       .reduceByKey(_+_)
                       .sortBy(_._2,false)

    return langs_rdd.collect().toList
  }


  def main(args: Array[String]) {

    /* Languages ranked according to (1) */
    //rankLangs(langs, wikiRdd).foreach(println)
    val langsRanked: List[(String, Int)] = timed("Part 1: naive ranking", rankLangs(langs, wikiRdd))

    /* An inverted index mapping languages to wikipedia pages on which they appear */
    def index: RDD[(String, Iterable[WikipediaArticle])] = makeIndex(langs, wikiRdd)

    /* Languages ranked according to (2), using the inverted index */
    //rankLangsUsingIndex(index).foreach(println)
    val langsRanked2: List[(String, Int)] = timed("Part 2: ranking using inverted index", rankLangsUsingIndex(index))

    /* Languages ranked according to (3) */
    //rankLangsReduceByKey(langs, wikiRdd).foreach(println)
    val langsRanked3: List[(String, Int)] = timed("Part 3: ranking using reduceByKey", rankLangsReduceByKey(langs, wikiRdd))

    /* Output the speed of each ranking */
    println(timing)
    sc.stop()
  }

  val timing = new StringBuffer
  def timed[T](label: String, code: => T): T = {
    val start = System.currentTimeMillis()
    val result = code
    val stop = System.currentTimeMillis()
    timing.append(s"Processing $label took ${stop - start} ms.\n")
    result
  }
}
