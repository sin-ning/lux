;;  Copyright (c) Eduardo Julian. All rights reserved.
;;  This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
;;  If a copy of the MPL was not distributed with this file,
;;  You can obtain one at http://mozilla.org/MPL/2.0/.

(ns lux.compiler.lux
  (:require (clojure [string :as string]
                     [set :as set]
                     [template :refer [do-template]])
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [|do return* return fail fail* |let |case]]
                 [type :as &type]
                 [lexer :as &lexer]
                 [parser :as &parser]
                 [analyser :as &analyser]
                 [host :as &host]
                 [optimizer :as &o])
            [lux.host.generics :as &host-generics]
            (lux.analyser [base :as &a]
                          [module :as &a-module]
                          [meta :as &a-meta])
            (lux.compiler [base :as &&]
                          [lambda :as &&lambda]))
  (:import (org.objectweb.asm Opcodes
                              Label
                              ClassWriter
                              MethodVisitor)
           java.lang.reflect.Field))

;; [Exports]
(defn compile-bool [?value]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [_ (.visitFieldInsn *writer* Opcodes/GETSTATIC "java/lang/Boolean" (if ?value "TRUE" "FALSE") "Ljava/lang/Boolean;")]]
    (return nil)))

(do-template [<name> <class> <prim> <caster>]
  (defn <name> [value]
    (|do [^MethodVisitor *writer* &/get-writer
          :let [_ (doto *writer*
                    (.visitLdcInsn (<caster> value))
                    (.visitMethodInsn Opcodes/INVOKESTATIC <class> "valueOf" (str "(" <prim> ")" (&host-generics/->type-signature <class>))))]]
      (return nil)))

  compile-nat  "java/lang/Long"      "J" long
  compile-int  "java/lang/Long"      "J" long
  compile-deg "java/lang/Long"      "J" long
  compile-real "java/lang/Double"    "D" double
  compile-char "java/lang/Character" "C" char
  )

(defn compile-text [?value]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [_ (.visitLdcInsn *writer* ?value)]]
    (return nil)))

(defn compile-tuple [compile ?elems]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [num-elems (&/|length ?elems)]]
    (|case num-elems
      0
      (|do [:let [_ (.visitLdcInsn *writer* &/unit-tag)]]
        (return nil))

      1
      (compile (&/|head ?elems))
      
      _
      (|do [:let [_ (doto *writer*
                      (.visitLdcInsn (int num-elems))
                      (.visitTypeInsn Opcodes/ANEWARRAY "java/lang/Object"))]
            _ (&/map2% (fn [idx elem]
                         (|do [:let [_ (doto *writer*
                                         (.visitInsn Opcodes/DUP)
                                         (.visitLdcInsn (int idx)))]
                               ret (compile elem)
                               :let [_ (.visitInsn *writer* Opcodes/AASTORE)]]
                           (return ret)))
                       (&/|range num-elems) ?elems)]
        (return nil)))))

(defn compile-variant [compile tag tail? value]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [_ (.visitLdcInsn *writer* (int tag))
              _ (if tail?
                  (.visitLdcInsn *writer* "")
                  (.visitInsn *writer* Opcodes/ACONST_NULL))]
        _ (compile value)
        :let [_ (.visitMethodInsn *writer* Opcodes/INVOKESTATIC "lux/LuxRT" "sum_make" "(ILjava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;")]]
    (return nil)))

(defn compile-local [compile ?idx]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [_ (.visitVarInsn *writer* Opcodes/ALOAD (int ?idx))]]
    (return nil)))

(defn compile-captured [compile ?scope ?captured-id ?source]
  (|do [:let [??scope (&/|reverse ?scope)]
        ^MethodVisitor *writer* &/get-writer
        :let [_ (doto *writer*
                  (.visitVarInsn Opcodes/ALOAD 0)
                  (.visitFieldInsn Opcodes/GETFIELD
                                   (str (&host/->module-class (&/|head ??scope)) "/" (&host/location (&/|tail ??scope)))
                                   (str &&/closure-prefix ?captured-id)
                                   "Ljava/lang/Object;"))]]
    (return nil)))

(defn compile-global [compile ?owner-class ?name]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [_ (.visitFieldInsn *writer* Opcodes/GETSTATIC (str (&host/->module-class ?owner-class) "/" (&host/def-name ?name)) &/value-field "Ljava/lang/Object;")]]
    (return nil)))

(defn ^:private compile-apply* [compile ?args]
  (|do [^MethodVisitor *writer* &/get-writer
        _ (&/map% (fn [?args]
                    (|do [:let [_ (.visitTypeInsn *writer* Opcodes/CHECKCAST &&/function-class)]
                          _ (&/map% compile ?args)
                          :let [_ (.visitMethodInsn *writer* Opcodes/INVOKEVIRTUAL &&/function-class &&/apply-method (&&/apply-signature (&/|length ?args)))]]
                      (return nil)))
                  (&/|partition &&/num-apply-variants ?args))]
    (return nil)))

(defn compile-apply [compile ?fn ?args]
  (|case ?fn
    [_ (&o/$var (&/$Global ?module ?name))]
    (|do [[_ [_ _ func-obj]] (&a-module/find-def ?module ?name)
          class-loader &/loader
          :let [func-class (class func-obj)
                func-arity (.get ^Field (.getDeclaredField func-class &&/arity-field) nil)
                func-partials (.get ^Field (.getDeclaredField (Class/forName "lux.Function" true class-loader) &&/partials-field) func-obj)
                num-args (&/|length ?args)
                func-class-name (->> func-class .getName &host-generics/->bytecode-class-name)]]
      (if (and (= 0 func-partials)
               (>= num-args func-arity))
        (|do [_ (compile ?fn)
              ^MethodVisitor *writer* &/get-writer
              :let [_ (.visitTypeInsn *writer* Opcodes/CHECKCAST func-class-name)]
              _ (&/map% compile (&/|take func-arity ?args))
              :let [_ (.visitMethodInsn *writer* Opcodes/INVOKEVIRTUAL func-class-name (if (= 1 func-arity) &&/apply-method "impl") (&&/apply-signature func-arity))]
              _ (if (= num-args func-arity)
                  (return nil)
                  (compile-apply* compile (&/|drop func-arity ?args)))]
          (return nil))
        (|do [_ (compile ?fn)]
          (compile-apply* compile ?args))))
    
    _
    (|do [_ (compile ?fn)]
      (compile-apply* compile ?args))
    ))

(defn compile-loop [compile-expression register-offset inits body]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [idxs+inits (&/zip2 (&/|range* 0 (dec (&/|length inits)))
                                 inits)]
        _ (&/map% (fn [idx+_init]
                    (|do [:let [[idx _init] idx+_init
                                idx+ (+ register-offset idx)]
                          _ (compile-expression nil _init)
                          :let [_ (.visitVarInsn *writer* Opcodes/ASTORE idx+)]]
                      (return nil)))
                  idxs+inits)
        :let [$begin (new Label)
              _ (.visitLabel *writer* $begin)]]
    (compile-expression $begin body)
    ))

(defn compile-iter [compile $begin register-offset ?args]
  (|do [^MethodVisitor *writer* &/get-writer
        :let [idxs+args (&/zip2 (&/|range* 0 (dec (&/|length ?args)))
                                ?args)]
        _ (&/map% (fn [idx+?arg]
                    (|do [:let [[idx ?arg] idx+?arg
                                idx+ (+ register-offset idx)
                                already-set? (|case ?arg
                                               [_ (&o/$var (&/$Local l-idx))]
                                               (= idx+ l-idx)

                                               _
                                               false)]]
                      (if already-set?
                        (return nil)
                        (compile ?arg))))
                  idxs+args)
        _ (&/map% (fn [idx+?arg]
                    (|do [:let [[idx ?arg] idx+?arg
                                idx+ (+ register-offset idx)
                                already-set? (|case ?arg
                                               [_ (&o/$var (&/$Local l-idx))]
                                               (= idx+ l-idx)

                                               _
                                               false)]
                          :let [_ (when (not already-set?)
                                    (.visitVarInsn *writer* Opcodes/ASTORE idx+))]]
                      (return nil)))
                  (&/|reverse idxs+args))
        :let [_ (.visitJumpInsn *writer* Opcodes/GOTO $begin)]]
    (return nil)))

(defn compile-let [compile _value _register _body]
  (|do [^MethodVisitor *writer* &/get-writer
        _ (compile _value)
        :let [_ (.visitVarInsn *writer* Opcodes/ASTORE _register)]
        _ (compile _body)]
    (return nil)))

(defn compile-record-get [compile _value _path]
  (|do [^MethodVisitor *writer* &/get-writer
        _ (compile _value)
        :let [_ (&/|map (fn [step]
                          (|let [[idx tail?] step]
                            (doto *writer*
                              (.visitTypeInsn Opcodes/CHECKCAST "[Ljava/lang/Object;")
                              (.visitLdcInsn (int idx))
                              (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT"
                                                (if tail? "product_getRight" "product_getLeft")
                                                "([Ljava/lang/Object;I)Ljava/lang/Object;"))))
                        _path)]]
    (return nil)))

(defn compile-if [compile _test _then _else]
  (|do [^MethodVisitor *writer* &/get-writer
        _ (compile _test)
        :let [$else (new Label)
              $end (new Label)
              _ (doto *writer*
                  &&/unwrap-boolean
                  (.visitJumpInsn Opcodes/IFEQ $else))]
        _ (compile _then)
        :let [_ (.visitJumpInsn *writer* Opcodes/GOTO $end)]
        :let [_ (.visitLabel *writer* $else)]
        _ (compile _else)
        :let [_ (.visitJumpInsn *writer* Opcodes/GOTO $end)
              _ (.visitLabel *writer* $end)]]
    (return nil)))

(defn ^:private de-ann [optim]
  (|case optim
    [_ (&o/$ann value-expr _)]
    value-expr

    _
    optim))

(defn ^:private throwable->text [^Throwable t]
  (let [base (->> t
                  .getStackTrace
                  (map str)
                  (cons (.getMessage t))
                  (interpose "\n")
                  (apply str))]
    (if-let [cause (.getCause t)]
      (str base "\n\n" "Caused by: " (throwable->text cause))
      base)))

(let [class-flags (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_SUPER)
      field-flags (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_STATIC)]
  (defn compile-def [compile ?name ?body ?meta]
    (|do [module-name &/get-module-name
          class-loader &/loader]
      (|case (&a-meta/meta-get &a-meta/alias-tag ?meta)
        (&/$Some (&/$IdentA [r-module r-name]))
        (if (= 1 (&/|length ?meta))
          (|do [:let [current-class (&host-generics/->class-name (str (&host/->module-class r-module) "/" (&host/def-name r-name)))
                      def-class (&&/load-class! class-loader current-class)
                      def-meta ?meta
                      def-value (-> def-class (.getField &/value-field) (.get nil))]
                def-type (&a-module/def-type r-module r-name)
                _ (&/without-repl-closure
                   (&a-module/define module-name ?name def-type def-meta def-value))]
            (return nil))
          (fail (str "[Compilation Error] Aliases cannot contain meta-data: " module-name ";" ?name)))

        (&/$Some _)
        (fail "[Compilation Error] Invalid syntax for lux;alias meta-data. Must be an Ident.")
        
        _
        (|case (de-ann ?body)
          [_ (&o/$function _ _ __scope _ _)]
          (|let [[_ (&o/$function _ _arity _scope _captured ?body+)] (&o/shift-function-body (&/|tail __scope) __scope
                                                                                             false
                                                                                             (de-ann ?body))]
            (|do [:let [=value-type (&a/expr-type* ?body)]
                  [file-name _ _] &/cursor
                  :let [datum-sig "Ljava/lang/Object;"
                        def-name (&host/def-name ?name)
                        current-class (str (&host/->module-class module-name) "/" def-name)
                        =class (doto (new ClassWriter ClassWriter/COMPUTE_MAXS)
                                 (.visit &host/bytecode-version class-flags
                                         current-class nil &&/function-class (into-array String []))
                                 (-> (.visitField field-flags &/name-field "Ljava/lang/String;" nil ?name)
                                     (doto (.visitEnd)))
                                 (-> (.visitField field-flags &/value-field datum-sig nil nil)
                                     (doto (.visitEnd)))
                                 (.visitSource file-name nil))]
                  instancer (&&lambda/compile-function compile (&/$Some =class) _arity _scope _captured ?body+)
                  _ (&/with-writer (.visitMethod =class Opcodes/ACC_STATIC "<clinit>" "()V" nil nil)
                      (|do [^MethodVisitor **writer** &/get-writer
                            :let [_ (.visitCode **writer**)]
                            _ instancer
                            :let [_ (.visitTypeInsn **writer** Opcodes/CHECKCAST "java/lang/Object")
                                  _ (.visitFieldInsn **writer** Opcodes/PUTSTATIC current-class &/value-field datum-sig)]
                            :let [_ (doto **writer**
                                      (.visitInsn Opcodes/RETURN)
                                      (.visitMaxs 0 0)
                                      (.visitEnd))]]
                        (return nil)))
                  :let [_ (.visitEnd =class)]
                  _ (&&/save-class! def-name (.toByteArray =class))
                  :let [def-class (&&/load-class! class-loader (&host-generics/->class-name current-class))
                        def-type (&a/expr-type* ?body)
                        is-type? (|case (&a-meta/meta-get &a-meta/type?-tag ?meta)
                                   (&/$Some (&/$BoolA true))
                                   true

                                   _
                                   false)
                        def-meta ?meta]
                  def-value (try (return (-> def-class (.getField &/value-field) (.get nil)))
                              (catch Throwable t
                                (&/assert! "Error during value initialization." (throwable->text t))))
                  _ (&/without-repl-closure
                     (&a-module/define module-name ?name def-type def-meta def-value))
                  _ (|case (&/T [is-type? (&a-meta/meta-get &a-meta/tags-tag def-meta)])
                      [true (&/$Some (&/$ListA tags*))]
                      (|do [:let [was-exported? (|case (&a-meta/meta-get &a-meta/export?-tag def-meta)
                                                  (&/$Some _)
                                                  true

                                                  _
                                                  false)]
                            tags (&/map% (fn [tag*]
                                           (|case tag*
                                             (&/$TextA tag)
                                             (return tag)

                                             _
                                             (fail "[Compiler Error] Incorrect format for tags.")))
                                         tags*)
                            _ (&a-module/declare-tags module-name tags was-exported? def-value)]
                        (return nil))

                      [false (&/$Some _)]
                      (fail "[Compiler Error] Can't define tags for non-type.")

                      [true (&/$Some _)]
                      (fail "[Compiler Error] Incorrect format for tags.")

                      [_ (&/$None)]
                      (return nil))
                  :let [_ (println 'DEF (str module-name ";" ?name))]]
              (return nil)))

          _
          (|do [:let [=value-type (&a/expr-type* ?body)]
                [file-name _ _] &/cursor
                :let [datum-sig "Ljava/lang/Object;"
                      def-name (&host/def-name ?name)
                      current-class (str (&host/->module-class module-name) "/" def-name)
                      =class (doto (new ClassWriter ClassWriter/COMPUTE_MAXS)
                               (.visit &host/bytecode-version class-flags
                                       current-class nil "java/lang/Object" (into-array String []))
                               (-> (.visitField field-flags &/name-field "Ljava/lang/String;" nil ?name)
                                   (doto (.visitEnd)))
                               (-> (.visitField field-flags &/value-field datum-sig nil nil)
                                   (doto (.visitEnd)))
                               (.visitSource file-name nil))]
                _ (&/with-writer (.visitMethod =class Opcodes/ACC_STATIC "<clinit>" "()V" nil nil)
                    (|do [^MethodVisitor **writer** &/get-writer
                          :let [_ (.visitCode **writer**)]
                          _ (compile nil ?body)
                          :let [_ (.visitTypeInsn **writer** Opcodes/CHECKCAST "java/lang/Object")
                                _ (.visitFieldInsn **writer** Opcodes/PUTSTATIC current-class &/value-field datum-sig)]
                          :let [_ (doto **writer**
                                    (.visitInsn Opcodes/RETURN)
                                    (.visitMaxs 0 0)
                                    (.visitEnd))]]
                      (return nil)))
                :let [_ (.visitEnd =class)]
                _ (&&/save-class! def-name (.toByteArray =class))
                :let [def-class (&&/load-class! class-loader (&host-generics/->class-name current-class))
                      def-type (&a/expr-type* ?body)
                      is-type? (|case (&a-meta/meta-get &a-meta/type?-tag ?meta)
                                 (&/$Some (&/$BoolA true))
                                 true

                                 _
                                 false)
                      def-meta ?meta]
                def-value (try (return (-> def-class (.getField &/value-field) (.get nil)))
                            (catch Throwable t
                              (&/assert! "Error during value initialization." (throwable->text t))))
                _ (&/without-repl-closure
                   (&a-module/define module-name ?name def-type def-meta def-value))
                _ (|case (&/T [is-type? (&a-meta/meta-get &a-meta/tags-tag def-meta)])
                    [true (&/$Some (&/$ListA tags*))]
                    (|do [:let [was-exported? (|case (&a-meta/meta-get &a-meta/export?-tag def-meta)
                                                (&/$Some _)
                                                true

                                                _
                                                false)]
                          tags (&/map% (fn [tag*]
                                         (|case tag*
                                           (&/$TextA tag)
                                           (return tag)

                                           _
                                           (fail "[Compiler Error] Incorrect format for tags.")))
                                       tags*)
                          _ (&a-module/declare-tags module-name tags was-exported? def-value)]
                      (return nil))

                    [false (&/$Some _)]
                    (fail "[Compiler Error] Can't define tags for non-type.")

                    [true (&/$Some _)]
                    (fail "[Compiler Error] Incorrect format for tags.")

                    [_ (&/$None)]
                    (return nil))
                :let [_ (println 'DEF (str module-name ";" ?name))]]
            (return nil)))
        ))))

(defn compile-program [compile ?body]
  (|do [module-name &/get-module-name
        ^ClassWriter *writer* &/get-writer]
    (&/with-writer (doto (.visitMethod *writer* (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC) "main" "([Ljava/lang/String;)V" nil nil)
                     (.visitCode))
      (|do [^MethodVisitor main-writer &/get-writer
            :let [$loop (new Label)
                  $end (new Label)
                  _ (doto main-writer
                      ;; Tail: Begin
                      (.visitLdcInsn (->> #'&/$Nil meta ::&/idx int)) ;; I
                      (.visitInsn Opcodes/ACONST_NULL) ;; I?
                      (.visitLdcInsn &/unit-tag) ;; I?U
                      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "sum_make" "(ILjava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;") ;; V
                      ;; Tail: End
                      ;; Size: Begin
                      (.visitVarInsn Opcodes/ALOAD 0) ;; VA
                      (.visitInsn Opcodes/ARRAYLENGTH) ;; VI
                      ;; Size: End
                      ;; Loop: Begin
                      (.visitLabel $loop)
                      (.visitLdcInsn (int 1)) ;; VII
                      (.visitInsn Opcodes/ISUB) ;; VI
                      (.visitInsn Opcodes/DUP) ;; VII
                      (.visitJumpInsn Opcodes/IFLT $end) ;; VI
                      ;; Head: Begin
                      (.visitInsn Opcodes/DUP) ;; VII
                      (.visitVarInsn Opcodes/ALOAD 0) ;; VIIA
                      (.visitInsn Opcodes/SWAP) ;; VIAI
                      (.visitInsn Opcodes/AALOAD) ;; VIO
                      (.visitInsn Opcodes/SWAP) ;; VOI
                      (.visitInsn Opcodes/DUP_X2) ;; IVOI
                      (.visitInsn Opcodes/POP) ;; IVO
                      ;; Head: End
                      ;; Tuple: Begin
                      (.visitLdcInsn (int 2)) ;; IVOS
                      (.visitTypeInsn Opcodes/ANEWARRAY "java/lang/Object") ;; IVO2
                      (.visitInsn Opcodes/DUP_X1) ;; IV2O2
                      (.visitInsn Opcodes/SWAP) ;; IV22O
                      (.visitLdcInsn (int 0)) ;; IV22OI
                      (.visitInsn Opcodes/SWAP) ;; IV22IO
                      (.visitInsn Opcodes/AASTORE) ;; IV2
                      (.visitInsn Opcodes/DUP_X1) ;; I2V2
                      (.visitInsn Opcodes/SWAP) ;; I22V
                      (.visitLdcInsn (int 1)) ;; I22VI
                      (.visitInsn Opcodes/SWAP) ;; I22IV
                      (.visitInsn Opcodes/AASTORE) ;; I2
                      ;; Tuple: End
                      ;; Cons: Begin
                      (.visitLdcInsn (->> #'&/$Cons meta ::&/idx int)) ;; I2I
                      (.visitLdcInsn "") ;; I2I?
                      (.visitInsn Opcodes/DUP2_X1) ;; II?2I?
                      (.visitInsn Opcodes/POP2) ;; II?2
                      (.visitMethodInsn Opcodes/INVOKESTATIC "lux/LuxRT" "sum_make" "(ILjava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;") ;; IV
                      ;; Cons: End
                      (.visitInsn Opcodes/SWAP) ;; VI
                      (.visitJumpInsn Opcodes/GOTO $loop)
                      ;; Loop: End
                      (.visitLabel $end) ;; VI
                      (.visitInsn Opcodes/POP) ;; V
                      (.visitVarInsn Opcodes/ASTORE (int 0)) ;;
                      )
                  ]
            _ (compile ?body)
            :let [_ (doto main-writer
                      (.visitTypeInsn Opcodes/CHECKCAST &&/function-class)
                      (.visitInsn Opcodes/ACONST_NULL)
                      (.visitMethodInsn Opcodes/INVOKEVIRTUAL &&/function-class &&/apply-method (&&/apply-signature 1)))]
            :let [_ (doto main-writer
                      (.visitInsn Opcodes/POP)
                      (.visitInsn Opcodes/RETURN)
                      (.visitMaxs 0 0)
                      (.visitEnd))]]
        (return nil)))))
