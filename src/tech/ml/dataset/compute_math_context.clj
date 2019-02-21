(ns tech.ml.dataset.compute-math-context
  (:require [tech.compute.cpu.tensor-math :as cpu-tm]
            [tech.compute.tensor :as ct]
            [tech.ml.dataset.column :as ds-col]
            [tech.ml.protocols.column :as col-proto])
  (:import [tech.compute.cpu UnaryOp BinaryOp]))



(cpu-tm/add-unary-op! :log1p (reify UnaryOp
                               (op [this val]
                                 (Math/log1p (double val)))))

(cpu-tm/add-binary-op! :** (reify BinaryOp
                             (op [this lhs rhs]
                               (Math/pow lhs rhs))))



(defrecord ComputeTensorMathContext []
  col-proto/PColumnMathContext
  (is-tensor? [ctx op-arg]
    (ct/acceptable-tensor-buffer? op-arg))
  (unary-op [ctx op-arg op-kwd]
    (ct/unary-op! (ct/clone op-arg) 1.0 op-arg op-kwd))
  (unary-reduce [ctx op-arg op-kwd]
    (throw (ex-info "Unavailable at moment" {})))
  (binary-op [ctx op-args op-scalar-fn op-kwd]
    (let [first-tensor (->> op-args
                            (filter ct/acceptable-tensor-buffer?)
                            first)
          _ (when-not first-tensor
              (throw (ex-info "Compute context used but no tensors in arguments." {})))
          first-pair (take 2 op-args)
          op-args (drop 2 op-args)
          [first-arg second-arg] first-pair
          accumulator (ct/clone first-tensor)]
        (if (or (ct/acceptable-tensor-buffer? first-arg)
                (ct/acceptable-tensor-buffer? second-arg))
          (ct/binary-op! accumulator 1.0 first-arg 1.0 second-arg op-kwd)
          (ct/assign! accumulator (op-scalar-fn first-arg second-arg)))
        (reduce (fn [accumulator next-arg]
                  (ct/binary-op! accumulator 1.0 accumulator 1.0 next-arg op-kwd))
                accumulator
                op-args))))
