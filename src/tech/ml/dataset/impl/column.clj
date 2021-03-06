(ns tech.ml.dataset.impl.column
  (:require [tech.ml.protocols.column :as ds-col-proto]
            [tech.ml.dataset.string-table :refer [make-string-table]]
            [tech.v2.datatype.protocols :as dtype-proto]
            [tech.v2.datatype :as dtype]
            [tech.v2.datatype.casting :as casting]
            [tech.v2.datatype.functional :as dtype-fn]
            [tech.v2.datatype.typecast :as typecast]
            [tech.v2.datatype.pprint :as dtype-pp]
            [tech.v2.datatype.readers.indexed :as indexed-rdr]
            [tech.v2.datatype.bitmap :refer [->bitmap] :as bitmap]
            [tech.v2.datatype.datetime :as dtype-dt]
            [tech.parallel.for :as parallel-for])
  (:import [java.util ArrayList HashSet Collections Set]
           [it.unimi.dsi.fastutil.longs LongArrayList]
           [org.roaringbitmap RoaringBitmap]
           [clojure.lang IPersistentMap IMeta Counted IFn IObj Indexed]
           [tech.v2.datatype ObjectReader DoubleReader ObjectWriter]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(def dtype->missing-val-map
  (atom
   {:boolean false
    :int16 Short/MIN_VALUE
    :int32 Integer/MIN_VALUE
    :int64 Long/MIN_VALUE
    :float32 Float/NaN
    :float64 Double/NaN
    :packed-instant (dtype-dt/pack (dtype-dt/milliseconds-since-epoch->instant 0))
    :packed-local-date-time (dtype-dt/pack
                             (dtype-dt/milliseconds-since-epoch->local-date-time 0))
    :packed-local-date (dtype-dt/pack
                        (dtype-dt/milliseconds-since-epoch->local-date 0))
    :packed-local-time (dtype-dt/pack
                        (dtype-dt/milliseconds->local-time 0))
    :packed-duration 0
    :instant (dtype-dt/milliseconds-since-epoch->instant 0)
    :zoned-date-time (dtype-dt/milliseconds-since-epoch->zoned-date-time 0)
    :offset-date-time (dtype-dt/milliseconds-since-epoch->offset-date-time 0)
    :local-date-time (dtype-dt/milliseconds-since-epoch->local-date-time 0)
    :local-date (dtype-dt/milliseconds-since-epoch->local-date 0)
    :local-time (dtype-dt/milliseconds->local-time 0)
    :duration (dtype-dt/milliseconds->duration 0)
    :string ""
    :text ""
    :keyword nil
    :symbol nil}))



(defn datatype->missing-value
  [dtype]
  (let [dtype (if (dtype-dt/packed-datatype? dtype)
                dtype
                (casting/un-alias-datatype dtype))]
    (if (contains? @dtype->missing-val-map dtype)
      (get @dtype->missing-val-map dtype)
      nil)))


(defn make-container
  ([dtype n-elems]
   (case dtype
     :string (make-string-table n-elems "")
     :text (let [list-data (ArrayList.)]
             (dotimes [iter n-elems]
               (.add list-data ""))
             list-data)
     (dtype/make-container :list dtype n-elems)))
  ([dtype]
   (make-container dtype 0)))


(defmacro create-missing-reader
  [datatype missing data n-elems options]
  `(let [rdr# (typecast/datatype->reader ~datatype ~data (:unchecked ~options))
         missing# ~missing
         missing-val# (casting/datatype->cast-fn
                       :unknown ~datatype
                       (datatype->missing-value ~datatype))
         n-elems# (long ~n-elems)]
     (reify ~(typecast/datatype->reader-type datatype)
       (getDatatype [this#] (.getDatatype rdr#))
       (lsize [this#] n-elems#)
       (read [this# idx#]
         (if (.contains missing# idx#)
           missing-val#
           (.read rdr# idx#))))))


(defn create-string-text-missing-reader
  [^RoaringBitmap missing data n-elems options]
  (let [rdr (typecast/datatype->reader :object data)
         missing-val ""
         n-elems (long n-elems)]
    (reify ObjectReader
      (getDatatype [this] (.getDatatype rdr))
      (lsize [this] n-elems)
      (read [this idx]
        (if (.contains missing idx)
          missing-val
          (.read rdr idx))))))


(defn create-object-missing-reader
  [^RoaringBitmap missing data n-elems options]
  (let [rdr (typecast/datatype->reader :object data)
        missing-val nil
        n-elems (long n-elems)]
    (reify ObjectReader
      (getDatatype [this] (.getDatatype rdr))
      (lsize [this] n-elems)
      (read [this idx]
        (if (.contains missing idx)
          missing-val
          (.read rdr idx))))))


(defn ->persistent-map
  ^IPersistentMap [item]
  (if (instance? IPersistentMap item)
    item
    (into {} item)))


(defn- ->efficient-reader
  [item]
  (cond
    (instance? RoaringBitmap item)
    (bitmap/bitmap->efficient-random-access-reader item)
    (dtype-proto/convertible-to-reader? item)
    item
    :else
    (long-array item)))


(deftype Column
    [^RoaringBitmap missing
     data
     ^IPersistentMap metadata]
  dtype-proto/PDatatype
  (get-datatype [this] (dtype-proto/get-datatype data))
  dtype-proto/PSetDatatype
  (set-datatype [this new-dtype]
    (Column. missing (dtype-proto/set-datatype data new-dtype)
             metadata))
  dtype-proto/PCountable
  (ecount [this] (dtype-proto/ecount data))
  dtype-proto/PToReader
  (convertible-to-reader? [this] true)
  (->reader [this options]
    (let [missing-policy (get options :missing-policy :include)
          n-missing (dtype/ecount missing)
          any-missing? (not= 0 n-missing)
          missing-policy (if-not any-missing?
                            :include
                            missing-policy)
          n-elems (dtype/ecount data)
          col-reader
          (if (or (= :elide missing-policy)
                  (not any-missing?))
            (dtype/->reader data options)
            (case (casting/un-alias-datatype
                   (or (:datatype options)
                       (dtype/get-datatype this)))
              :int16 (create-missing-reader :int16 missing data n-elems options)
              :int32 (create-missing-reader :int32 missing data n-elems options)
              :int64 (create-missing-reader :int64 missing data n-elems options)
              :float32 (create-missing-reader :float32 missing data n-elems options)
              :float64 (create-missing-reader :float64 missing data n-elems options)
              :string (create-string-text-missing-reader missing data n-elems options)
              :text (create-string-text-missing-reader missing data n-elems options)
              (create-object-missing-reader missing data n-elems options)))
          new-reader (case missing-policy
                       :elide
                       (let [valid-indexes (->> (range (dtype/ecount data))
                                                (remove #(.contains missing (long %)))
                                                long-array)]
                         (indexed-rdr/make-indexed-reader valid-indexes
                                                          col-reader
                                                          {}))
                       :include
                       col-reader
                       :error
                       (if (not any-missing?)
                         col-reader
                         (throw (ex-info (format "Column has missing indexes: %s"
                                                 (vec missing))
                                         {}))))]
      (dtype-proto/->reader new-reader options)))
  dtype-proto/PToWriter
  (convertible-to-writer? [this] (dtype-proto/convertible-to-writer? data))
  (->writer [this options]
    (let [col-dtype (dtype/get-datatype data)
          options (update options :datatype
                          #(or % (dtype/get-datatype data)))
          data-writer (typecast/datatype->writer :object data)
          missing-val (get @dtype->missing-val-map col-dtype)
          n-elems (dtype/ecount data)]
      ;;writing to columns like this is inefficient due to the necessity to
      ;;keep the missing set accurate.  In most cases you are better off
      ;;simply creating a new column of some sort.
      (-> (reify ObjectWriter
            (lsize [this] n-elems)
            (write [this idx val]
              (locking this
                (if (or (nil? val)
                        (and (not (boolean? val))
                             (.equals ^Object val missing-val)))
                  (do
                    (.add missing idx)
                    (.write data-writer idx missing-val))
                  (do
                    (.remove missing idx)
                    (.write data-writer idx val))))))
          (dtype-proto/->writer options))))
  dtype-proto/PBuffer
  (sub-buffer [this offset len]
    (let [offset (long offset)
          len (long len)]
      (let [new-missing (->> missing
                             (filter #(let [arg (long %)]
                                        (or (< arg offset)
                                            (>= (- arg offset) len))))
                             (map #(+ (long %) offset))
                             (->bitmap))
            new-data (dtype-proto/sub-buffer data offset len)]
        (Column. new-missing new-data metadata))))
  dtype-proto/PToNioBuffer
  (convertible-to-nio-buffer? [item]
    (and (== 0 (dtype/ecount missing))
         (dtype-proto/convertible-to-nio-buffer? data)))
  (->buffer-backing-store [item]
    (dtype-proto/->buffer-backing-store data))
  ;;This also services to make concrete definitions of the data so this must
  ;;store the result realized.
  dtype-proto/PClone
  (clone [col]
    (let [new-data (if (dtype/writer? data)
                     (dtype/clone data)
                     ;;It is important that the result of this operation be writeable.
                     (dtype/make-container :java-array
                                           (dtype/get-datatype data) data))]
      (Column. (dtype/clone missing)
               new-data
               metadata)))
  dtype-proto/PPrototype
  (from-prototype [col datatype shape]
    (let [n-elems (long (apply * shape))]
      (Column. (->bitmap)
               (make-container datatype n-elems)
               {})))
  dtype-proto/PToArray
  (->sub-array [col]
    (when-let [data-ary (when (== 0 (dtype/ecount missing))
                          (dtype-proto/->sub-array data))]
      data-ary))
  (->array-copy [col] (dtype-proto/->array-copy (dtype/->reader col)))
  Iterable
  (iterator [col]
    (.iterator ^Iterable (dtype/->reader col :object)))

  ds-col-proto/PIsColumn
  (is-column? [this] true)
  ds-col-proto/PColumn
  (column-name [col] (:name metadata))
  (set-name [col name] (Column. missing data (assoc metadata :name name)))
  (supported-stats [col] dtype-fn/supported-descriptive-stats)
  (metadata [col]
    (merge metadata
           {:size (dtype/ecount col)
            :datatype (dtype/get-datatype col)}))
  (set-metadata [col data-map] (Column. missing data (->persistent-map data-map)))
  (missing [col] missing)
  (is-missing? [col idx] (.contains missing (long idx)))
  (set-missing [col long-rdr]
    (let [long-rdr (if (dtype/reader? long-rdr)
                     long-rdr
                     ;;handle infinite seq's
                     (take (dtype/ecount data) long-rdr))
          bitmap (->bitmap long-rdr)]
      (.runOptimize bitmap)
      (Column. bitmap
               data
               metadata)))
  (unique [this]
    (let [rdr (dtype/->reader this)]
      (->> (parallel-for/indexed-map-reduce
            (dtype/ecount rdr)
            (fn [^long start-idx ^long len]
              (let [data (HashSet.)]
                (dotimes [iter len]
                  (.add data (rdr (unchecked-add iter start-idx))))
                data))
            (partial reduce (fn [^Set lhs ^Set rhs]
                              (.addAll lhs rhs)
                              lhs)))
           (into #{}))))
  (stats [col stats-set]
    (when-not (casting/numeric-type? (dtype-proto/get-datatype col))
      (throw (ex-info "Stats aren't available on non-numeric columns"
                      {:column-type (dtype/get-datatype col)
                       :column-name (:name metadata)})))
    (dtype-fn/descriptive-stats (dtype/->reader
                                 col
                                 (dtype/get-datatype col)
                                 {:missing-policy :elide})
                                stats-set))
  (correlation [col other-column correlation-type]
    (case correlation-type
      :pearson (dtype-fn/pearsons-correlation col other-column)
      :spearman (dtype-fn/spearmans-correlation col other-column)
      :kendall (dtype-fn/kendalls-correlation col other-column)))
  (select [col idx-rdr]
    (let [idx-rdr (->efficient-reader idx-rdr)]
      (if (== 0 (dtype/ecount missing))
        ;;common case
        (Column. (->bitmap) (dtype/indexed-reader idx-rdr data)
                 metadata)
        ;;Uggh.  Construct a new missing set
        (let [idx-rdr (typecast/datatype->reader :int64 idx-rdr)
              n-idx-elems (.lsize idx-rdr)
              ^RoaringBitmap result-set (->bitmap)]
          (parallel-for/serial-for
           idx
           n-idx-elems
           (when (.contains missing (.read idx-rdr idx))
             (.add result-set idx)))
          (Column. result-set
                   (dtype/indexed-reader idx-rdr data)
                   metadata)))))
  (to-double-array [col error-on-missing?]
    (let [n-missing (dtype/ecount missing)
          any-missing? (not= 0 n-missing)
          col-dtype (dtype/get-datatype col)]
      (when (and any-missing? error-on-missing?)
        (throw (Exception. "Missing values detected and error-on-missing set")))
      (when-not (or (= :boolean col-dtype)
                    (casting/numeric-type? (dtype/get-datatype col)))
        (throw (Exception. "Non-numeric columns do not convert to doubles.")))
      (dtype/make-container :java-array :float64 col)))
  IObj
  (meta [this] (ds-col-proto/metadata this))
  (withMeta [this new-meta] (Column. missing data new-meta))
  Counted
  (count [this] (int (dtype/ecount data)))
  Indexed
  (nth [this idx]
    (.read (typecast/datatype->reader :object this) (long idx)))
  (nth [this idx def-val]
    (if (< (long idx) (dtype/ecount this))
      (.read (typecast/datatype->reader :object this) (long idx))
      def-val))
  ;;Not efficient but it will work.
  IFn
  (invoke [this idx]
    (.read (typecast/datatype->reader :object this) idx))
  (applyTo [this args]
    (when-not (= 1 (count args))
      (throw (Exception. "Too many arguments to column")))
    (.invoke this (first args)))
  Object
  (toString [item]
    (let [n-elems (dtype/ecount data)
          format-str (if (> n-elems 20)
                       "#tech.ml.dataset.column<%s>%s\n%s\n[%s...]"
                       "#tech.ml.dataset.column<%s>%s\n%s\n[%s]")
          ;;Make the data printable
          src-rdr (dtype-pp/reader-converter item)
          data-rdr (dtype/make-reader
                    :object
                    (dtype/ecount src-rdr)
                    (if (.contains missing idx)
                      nil
                      (src-rdr idx)))]
      (format format-str
              (name (dtype/get-datatype item))
              [n-elems]
              (ds-col-proto/column-name item)
              (-> (dtype-proto/sub-buffer data-rdr 0 (min 20 n-elems))
                  (dtype-pp/print-reader-data))))))


(defmethod print-method Column
  [col ^java.io.Writer w]
  (.write w (.toString ^Object col)))


(defn new-column
  "Given a map of (something convertible to a long reader) missing indexes,
  (something convertible to a reader) data
  and a (string or keyword) name, return an implementation of enough of the
  column and datatype protocols to allow efficient columnwise operations of
  the rest of tech.ml.dataset"
  ([name data metadata missing]
   (when-not (or (nil? metadata)
                 (map? metadata))
     (throw (Exception. "Metadata must be a persistent map")))
   (let [missing (->bitmap missing)
         metadata (if (and (not (contains? metadata :categorical?))
                           (#{:string :keyword :symbol} (dtype/get-datatype data)))
                    (assoc metadata :categorical? true)
                    metadata)]
     (.runOptimize missing)
     (Column. missing data (assoc metadata :name name))))
  ([name data metadata]
   (new-column name data metadata (->bitmap)))
  ([name data]
   (new-column name data {} (->bitmap))))
