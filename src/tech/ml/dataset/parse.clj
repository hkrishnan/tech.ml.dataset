(ns tech.ml.dataset.parse
  (:require [tech.io :as io]
            [tech.v2.datatype :as dtype]
            [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.datatype.typecast :as typecast])
  (:import [com.univocity.parsers.common AbstractParser]
           [com.univocity.parsers.csv CsvFormat CsvParserSettings CsvParser]
           [java.io Reader InputStream]
           [java.util Iterator HashMap ArrayList List Map RandomAccess]
           [java.util.function Function]
           [it.unimi.dsi.fastutil.booleans BooleanArrayList]
           [it.unimi.dsi.fastutil.shorts ShortArrayList]
           [it.unimi.dsi.fastutil.ints IntArrayList IntList IntIterator]
           [it.unimi.dsi.fastutil.longs LongArrayList]
           [it.unimi.dsi.fastutil.floats FloatArrayList]
           [it.unimi.dsi.fastutil.doubles DoubleArrayList]))


(set! *warn-on-reflection* true)


(defn create-csv-parser
  ^AbstractParser []
  (let [settings (CsvParserSettings.)]
    (.setDelimiterDetectionEnabled settings true (into-array Character/TYPE
                                                             [\, \tab]))
    (CsvParser. settings)))


(def test-file "data/ames-house-prices/train.csv")


(defn raw-row-iterable
  "Returns an iterable that produces string[] rows"
  (^Iterable [input ^AbstractParser parser]
   (reify Iterable
     (iterator [this]
       (let [^Reader reader (io/reader input)
             cur-row (atom nil)]
         (.beginParsing parser reader)
         (reset! cur-row (.parseNext parser))
         (reify
           java.util.Iterator
           (hasNext [this]
             (not (nil? @cur-row)))
           (next [this]
             (let [retval @cur-row]
               (reset! cur-row (.parseNext parser))
               retval)))))))
  (^Iterable [input]
   (raw-row-iterable input (create-csv-parser))))


(defprotocol PSimpleColumnParser
  (can-parse? [parser str-val])
  (simple-missing-string? [parser str-val])
  (simple-parse! [parser container str-val])
  (simple-missing! [parser container]))


(defmacro dtype->parse-fn
  [datatype val]
  (case datatype
    :boolean `(boolean
               (cond
                 (or (.equalsIgnoreCase "t" ~val)
                     (.equalsIgnoreCase "y" ~val)
                     (.equalsIgnoreCase "yes" ~val)
                     (.equalsIgnoreCase "True" ~val))
                 true
                 (or (.equalsIgnoreCase "f" ~val)
                     (.equalsIgnoreCase "n" ~val)
                     (.equalsIgnoreCase "no" ~val)
                     (.equalsIgnoreCase "false" ~val))
                 false
                 :else
                 (throw (Exception. "Parse failure"))))
    :int16 `(Short/parseShort ~val)
    :int32 `(Integer/parseInt ~val)
    :int64 `(Long/parseLong ~val)
    :float32 `(Float/parseFloat ~val)
    :float64 `(Double/parseDouble ~val)))


(defmacro dtype->missing-val
  [datatype]
  (case datatype
    :boolean `false
    :int16 `Short/MAX_VALUE
    :int32 `Integer/MAX_VALUE
    :int64 `Long/MAX_VALUE
    :float32 `Float/NaN
    :float64 `Double/NaN))


(defmacro simple-col-parser
  [datatype]
  `(reify
     dtype-proto/PDatatype
     (get-datatype [parser#] ~datatype)
     PSimpleColumnParser
     (can-parse? [parser# str-val#]
       (try
         (dtype->parse-fn ~datatype str-val#)
         true
         (catch Throwable e#
           false)))
     (simple-missing-string? [parser# str-val#]
       (.equalsIgnoreCase ^String str-val# "na"))
     (simple-parse! [parser# container# str-val#]
       (let [str-val# (str str-val#)
             parsed-val# (dtype->parse-fn ~datatype str-val#)]
         (if-not (== parsed-val# (dtype->missing-val ~datatype))
           (.add (typecast/datatype->list-cast-fn ~datatype container#)
                 parsed-val#)
           (throw (Exception. "Parse failure")))))
     (simple-missing! [parser# container#]
       (.add (typecast/datatype->list-cast-fn ~datatype container#)
             (dtype->missing-val ~datatype)))))


;;Of course boolean is just slightly different than then umeric parsers.
(defn simple-boolean-parser
  []
  (reify
    dtype-proto/PDatatype
    (get-datatype [this] :boolean)
    PSimpleColumnParser
    (can-parse? [parser str-val]
      (try
        (dtype->parse-fn :boolean str-val)
        true
        (catch Throwable e
          false)))
    (simple-missing-string? [parser str-val]
      (.equalsIgnoreCase ^String str-val "na"))
    (simple-parse! [parser container str-val]
       (let [str-val (str str-val)
             parsed-val (dtype->parse-fn :boolean str-val)]
         (.add (typecast/datatype->list-cast-fn :boolean container)
               parsed-val)))
     (simple-missing! [parser container]
       (.add (typecast/datatype->list-cast-fn :boolean container)
             (dtype->missing-val :boolean)))))


(defprotocol PStrTable
  (get-str-table [item]))


(deftype StringTable
    [^Map int->str
     ^Map str->int
     ^IntList data]
  dtype-proto/PDatatype
  (get-datatype [this] :string)
  tech.v2.datatype.Countable
  (lsize [this] (long (.size data)))
  PStrTable
  (get-str-table [this] {:int->str int->str
                         :str->int str->int})
  List
  (size [this] (.size data))
  (add [this str-val]
    (.add this (.size data) str-val)
    true)
  (add [this idx str-val]
    (when-not (instance? String str-val)
      (throw (Exception. "Can only use strings")))
    (let [item-idx (int (if-let [idx-val (.get str->int str-val)]
                          idx-val
                          (let [idx-val (.size str->int)]
                            (.put str->int str-val idx-val)
                            (.put int->str idx-val str-val)
                            idx-val)))]
      (.add data idx item-idx)
      true))
  (get [this idx] (.get int->str (.get data idx)))
  (set [this idx str-val]
    (when-not (instance? String str-val)
      (throw (Exception. "Can only use strings")))
    (let [item-idx (int (if-let [idx-val (.get str->int str-val)]
                          idx-val
                          (let [idx-val (.size str->int)]
                            (.put str->int str-val idx-val)
                            (.put int->str idx-val str-val)
                            idx-val)))]
      (.set data idx item-idx)))
  (subList [this start-offset end-offset]
    (StringTable. int->str str->int (.subList data start-offset end-offset)))
  RandomAccess
  Iterable
  (iterator [this]
    (let [^IntIterator src-iter (.iterator data)]
      (reify Iterator
        (hasNext [iter] (.hasNext src-iter))
        (next [iter] (.get int->str (.nextInt src-iter)))))))


(defn make-string-container
  (^List [n-elems ^HashMap int->str ^HashMap str->int]
   (let [^IntList data (dtype/make-container :list :int32 (long n-elems))]
     (.put int->str (int 0) "")
     (.put str->int "" (int 0))
     (.size data (int n-elems))
     (StringTable. int->str str->int data)))
  (^List [n-elems]
   (make-string-container n-elems (HashMap.) (HashMap.)))
  (^List []
   (make-string-container 0 (HashMap.) (HashMap.))))


(defn simple-string-parser
  []
  (reify
    dtype-proto/PDatatype
    (get-datatype [item#] :string)
    PSimpleColumnParser
    (can-parse? [this# item#] (< (count item#) 1024))
    (simple-missing-string? [parser str-val] nil)
    (simple-parse! [parser# container# str-val#]
      (when (> (count str-val#) 1024)
        (throw (Exception. "Text data not string data")))
      (.add ^List container# str-val#))
    (simple-missing! [parser# container#]
      (.add ^List container# ""))))


(defn simple-text-parser
  []
  (reify
    dtype-proto/PDatatype
    (get-datatype [item#] :text)
    PSimpleColumnParser
    (can-parse? [this# item#] true)
    (simple-missing-string? [parser str-val] nil)
    (simple-parse! [parser# container# str-val#]
      (.add ^List container# str-val#))
    (simple-missing! [parser# container#]
      (.add ^List container# ""))))


(def default-parser-seq (->> [:boolean (simple-boolean-parser)
                              :int16 (simple-col-parser :int16)
                              :int32 (simple-col-parser :int32)
                              :float32 (simple-col-parser :float32)
                              :int64 (simple-col-parser :int64)
                              :float64 (simple-col-parser :float64)
                              :string (simple-string-parser)
                              :text (simple-text-parser)]
                             (partition 2)
                             vec))


(defprotocol PColumnParser
  (parse! [parser str-val])
  (missing! [parser])
  (missing-string? [parser str-val])
  (column-data [parser]))


(defn default-column-parser
  []
  (let [initial-parser (first default-parser-seq)
        item-seq* (atom (rest default-parser-seq))
        container* (atom (dtype/make-container :list (first initial-parser) 0))
        simple-parser* (atom (second initial-parser))
        missing (LongArrayList.)]
    (reify PColumnParser
      (missing-string? [parser str-val]
        (.simple-missing-string?
         ^tech.ml.dataset.parse.PSimpleColumnParser @simple-parser*
         str-val))
      (parse! [this str-val]
        (let [parsed? (try (.simple-parse!
                            ^tech.ml.dataset.parse.PSimpleColumnParser @simple-parser*
                            @container* str-val)
                           true
                           (catch Throwable e
                             false))]
          (when-not parsed?
            (let [parser-seq (drop-while #(not (can-parse? (second %) str-val))
                                         @item-seq*)
                  next-parser (first parser-seq)]
              (reset! item-seq* (rest parser-seq))
              (if next-parser
                (do
                  (reset! simple-parser* (second next-parser))
                  (let [next-dtype (first next-parser)
                        converted-container (if (#{:string :text} (first next-parser))
                                              (dtype/reader-map #(.toString ^Object %)
                                                                @container*)
                                              @container*)
                        n-elems (dtype/ecount converted-container)
                        new-container (case next-dtype
                                        :string (make-string-container n-elems)
                                        :text (ArrayList. (int n-elems))
                                        (dtype/make-container :list next-dtype
                                                              n-elems))]
                    (reset! container* (dtype/copy! converted-container
                                                    new-container)))
                  (.simple-parse!
                   ^tech.ml.dataset.parse.PSimpleColumnParser @simple-parser*
                   @container* str-val)))))))
      (missing! [parser]
        (.add missing (dtype/ecount @container*))
        (.simple-missing! ^tech.ml.dataset.parse.PSimpleColumnParser @simple-parser*
                          @container*))
      (column-data [parser]
        {:missing missing
         :data @container*}))))


(defn csv->columns
  "Non-lazily and serially parse the columns"
  [input & {:keys [header-row? parser-fn
                   initial-scan-len]
            :or {header-row? true
                 initial-scan-len 100}}]
  (let [rows (raw-row-iterable input)
        data (iterator-seq (.iterator rows))
        initial-row (first data)
        data (if header-row?
               (rest data)
               data)
        n-cols (count initial-row)
        ^List column-parsers (vec (if parser-fn
                                    (let [scan-rows (take initial-scan-len data)
                                          scan-cols (->> (apply interleave scan-rows)
                                                         (partition initial-scan-len))]
                                      (map parser-fn initial-row scan-cols))
                                    (repeatedly n-cols default-column-parser)))]
    (doseq [^"[Ljava.lang.String;" row data]
      (loop [col-idx 0]
        (when (< col-idx n-cols)
          (let [^String row-data (aget row col-idx)
                parser (.get column-parsers col-idx)]
            (if (and row-data
                     (> (.length row-data) 0)
                     (not (.equalsIgnoreCase "na" row-data)))
              (parse! parser row-data)
              (missing! parser))
            (recur (unchecked-inc col-idx))))))
    (mapv (fn [init-row-data parser]
            (assoc (column-data parser)
                   :name init-row-data))
          initial-row column-parsers)))
