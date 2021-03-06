;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns hoplon.core
  (:require
    [goog.Uri]
    [goog.object    :as obj]
    [clojure.set    :refer [difference intersection]]
    [javelin.core   :refer [cell? cell lift destroy-cell!]]
    [cljs.reader    :refer [read-string]]
    [clojure.string :refer [split join blank?]])
  (:require-macros
    [javelin.core   :refer [with-let cell= prop-cell]]
    [hoplon.core    :refer [cache-key with-timeout with-dom]]))

(declare do! on! $text add-children!)

(enable-console-print!)

(def prerendering?
  "Is the application running in a prerendering container (eg. PhantomJS via
  the prerender task)?"
  (.getParameterValue (goog.Uri. (.. js/window -location -href)) "prerendering"))

(def ^:no-doc static-elements
  "This is an internal implementation detail, exposed for the convenience of
  the hoplon.core/static macro. Experimental."
  (-> #(assoc %1 (.getAttribute %2 "static-id") %2)
      (reduce {} (.querySelector js/document "[static-id]"))))

;;;; public helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn do-watch
  "Public helper.
  Adds f as a watcher to ref and evaluates (f init @ref) once. The watcher
  f is a function of two arguments: the previous and next values. If init is
  not provided the default (nil) will be used."
  ([ref f]
   (do-watch ref nil f))
  ([ref init f]
   (with-let [k (gensym)]
     (f init @ref)
     (add-watch ref k (fn [_ _ old new] (f old new))))))

(defn bust-cache
  "Public helper.
  Experimental."
  [path]
  (let [[f & more] (reverse (split path #"/"))
        [f1 f2]    (split f #"\." 2)]
    (->> [(str f1 "." (cache-key)) f2]
         (join ".")
         (conj more)
         (reverse)
         (join "/"))))

(defn normalize-class
  "Public helper.
  Class normalization for attribute providers."
  [kvs]
  (let [->map #(zipmap % (repeat true))]
    (if (map? kvs)
      kvs
      (->map (if (string? kvs) (.split kvs #"\s+") (seq kvs))))))

;;;; internal helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- child-vec
  [this]
  (let [x (.-childNodes this)
        l (.-length x)]
    (loop [i 0 ret (transient [])]
      (or (and (= i l) (persistent! ret))
          (recur (inc i) (conj! ret (.item x i)))))))

(defn- vflatten
 ([tree]
   (persistent! (vflatten tree (transient []))))
  ([tree ret]
   (let [l (count tree)]
     (loop [i 0]
        (if (= i l)
          ret
          (let [x (nth tree i)]
            (if-not (sequential? x)
              (conj! ret x)
              (vflatten x ret))
            (recur (inc i))))))))

;;;; custom nodes ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol INode
  (node [this]))

(extend-type string
  INode
  (node [this]
    ($text this)))

(extend-type number
  INode
  (node [this]
    ($text (str this))))

(defn- ->node
  [x]
  (if (satisfies? INode x) (node x) x))

;;;; custom elements ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private removeChild  (.. js/Element -prototype -removeChild))
(def ^:private appendChild  (.. js/Element -prototype -appendChild))
(def ^:private insertBefore (.. js/Element -prototype -insertBefore))
(def ^:private replaceChild (.. js/Element -prototype -replaceChild))
(def ^:private setAttribute (.. js/Element -prototype -setAttribute))

(defn- merge-kids
  [this _ new]
  (let [new  (->> (vflatten new) (reduce #(if (nil? %2) %1 (conj %1 %2)) []) (mapv ->node))
        new? (set new)]
    (loop [[x & xs] new
           [k & ks :as kids] (child-vec this)]
      (when (or x k)
        (recur xs (cond (= x k) ks
                        (not k) (with-let [ks ks]
                                  (.call appendChild this x))
                        (not x) (with-let [ks ks]
                                  (when-not (new? k)
                                    (.call removeChild this k)))
                        :else   (with-let [kids kids]
                                  (.call insertBefore this x k))))))))

(defn ensure-kids!
  [this]
  (with-let [this this]
    (when-not (.-hoplonKids this)
      (let [kids (atom (child-vec this))]
        (set! (.-hoplonKids this) kids)
        (do-watch kids (partial merge-kids this))))))

(defn- remove-all-kids!
  [this]
  (set! (.-hoplonKids this) nil)
  (merge-kids this nil nil))

(defn- native?
  "Returns true if elem is a native element. Native elements' children
  are not managed by Hoplon."
  [elem]
  (and (instance? js/Element elem)
       (-> elem .-hoplonKids nil?)))

(defn- managed?
  "Returns true if elem is a managed element. Managed elements have
  their children managed by Hoplon."
  [elem]
  (not (native? elem)))

(defn- managed-append-child
  "Appends `child` to `parent` for the case of `parent` being a
  managed element."
  [parent child kidfn]
  (with-let [child child]
    (ensure-kids! parent)
    (let [kids (kidfn parent)
          i    (count @kids)]
      (if (cell? child)
        (do-watch child #(swap! kids assoc i %2))
        (swap! kids assoc i child)))))

(defn- set-appendChild!
  [this kidfn]
  (set! (.-appendChild this)
        (fn [child]
          (this-as this
            (when (.-parentNode child)
              (.removeChild (.-parentNode child) child))
            (cond
              ;; Use the browser-native function for speed in the case
              ;; where no children are cells.
              (and (native? this) (not (cell? child)))
              (.call appendChild this child)

              (and (native? this) (cell? child))
              (managed-append-child this child kidfn)

              (managed? this)
              (managed-append-child this child kidfn)

              :else
              (throw (ex-info "Unexpected child type" {:reason    ::unexpected-child-type
                                                       :child     child
                                                       :native?   (native? child)
                                                       :managed? (managed? child)
                                                       :this      this})))))))

(defn- set-removeChild!
  [this kidfn]
  (set! (.-removeChild this)
        (fn [x]
          (this-as this
            (with-let [x x]
              (ensure-kids! this)
              (swap! (kidfn this) #(into [] (remove (partial = x) %))))))))

(defn- set-insertBefore!
  [this kidfn]
  (set! (.-insertBefore this)
        (fn [x y]
          (this-as this
            (with-let [x x]
              (ensure-kids! this)
              (cond
                (not y)     (swap! (kidfn this) conj x)
                (not= x y)  (swap! (kidfn this) #(vec (mapcat (fn [z] (if (= z y) [x z] [z])) %)))))))))

(defn- set-replaceChild!
  [this kidfn]
  (set! (.-replaceChild this)
        (fn [x y]
          (this-as this
            (with-let [y y]
              (ensure-kids! this)
              (swap! (kidfn this) #(mapv (fn [z] (if (= z y) x z)) %)))))))

(defn- set-setAttribute!
  [this attrfn]
  (set! (.-setAttribute this)
        (fn [k v]
          (this-as this
            (with-let [_ js/undefined]
              (let [kk   (keyword k)
                    attr (attrfn this)
                    has? (and attr (contains? @attr kk))]
                (if has?
                  (swap! attr assoc kk v)
                  (.call setAttribute this k v))))))))

(set-appendChild!  (.-prototype js/Element) #(.-hoplonKids %))
(set-removeChild!  (.-prototype js/Element) #(.-hoplonKids %))
(set-insertBefore! (.-prototype js/Element) #(.-hoplonKids %))
(set-replaceChild! (.-prototype js/Element) #(.-hoplonKids %))

;;;; custom elements ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ICustomElement
  (-set-attributes! [this kvs])
  (-set-styles!     [this kvs])
  (-append-child!   [this child])
  (-remove-child!   [this child])
  (-replace-child!  [this new existing])
  (-insert-before!  [this new existing]))

(defn set-attributes!
  ([this kvs]
   (-set-attributes! this kvs))
  ([this k v & kvs]
   (set-attributes! this (apply hash-map k v kvs))))

(defn set-styles!
  ([this kvs]
   (-set-styles! this kvs))
  ([this k v & kvs]
   (set-styles! this (apply hash-map k v kvs))))

(defn append-child!
  [this child]
  (-append-child! this child))

(defn remove-child!
  [this child]
  (-remove-child! this child))

(defn replace-child!
  [this new existing]
  (-replace-child! this new existing))

(defn insert-before!
  [this new existing]
  (-insert-before! this new existing))

;;;; custom attributes ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ICustomAttribute
  (-attr! [this elem value]))

(defn attribute? [this]
  (satisfies? ICustomAttribute this))

(extend-type Keyword
  ICustomAttribute
  (-attr! [this elem value]
    (cond (cell? value) (do-watch value #(do! elem this %2))
          (fn? value)   (on! elem this value)
          :else         (do! elem this value))))


;; helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private is-ie8 (not (obj/get js/window "Node")))

(def ^:private -head*
  (if-not is-ie8
    #(.-head %)
    #(.. % -documentElement -firstChild)))

(def ^:private vector?*
  (if-not is-ie8
    vector?
    #(try (vector? %) (catch js/Error _))))

(def ^:private seq?*
  (if-not is-ie8
    seq?
    #(try (seq? %) (catch js/Error _))))

(defn safe-nth
  "Like cljs.core/nth but returns nil or not found if the index is outside the coll"
  ([coll index] (safe-nth coll index nil))
  ([coll index not-found]
   (try (nth coll index not-found) (catch js/Error _ not-found))))

(defn timeout
  "Executes a fuction after a delay, if no delay is passed, 0 is used as a default."
  ([f] (timeout f 0))
  ([f t] (.setTimeout js/window f t)))

(defn when-dom
  [this f]
  (if-not (instance? js/Element this)
    (with-timeout 0 (f))
    (if-let [v (obj/get this "_hoplonWhenDom")]
      (.push v f)
      (do (obj/set this "_hoplonWhenDom" (array f))
          (with-timeout 0
            ((fn doit []
               (if-not (.contains (.-documentElement js/document) this)
                 (with-timeout 20 (doit))
                 (do (doseq [f (obj/get this "_hoplonWhenDom")] (f))
                     (obj/set this "_hoplonWhenDom" nil))))))))))

;; env ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-args
  [args]
  (loop [attr (transient {})
         kids (transient [])
         [arg & args] args]
    (if-not (or arg args)
      [(persistent! attr) (persistent! kids)]
      (cond (map? arg)       (recur (reduce-kv #(assoc! %1 %2 %3) attr arg) kids args)
            (attribute? arg) (recur (assoc! attr arg (first args)) kids (rest args))
            (seq?* arg)      (recur attr (reduce conj! kids (vflatten arg)) args)
            (vector?* arg)   (recur attr (reduce conj! kids (vflatten arg)) args)
            :else            (recur attr (conj! kids arg) args)))))

(defn- add-attributes!
  [this attr]
  (reduce-kv #(do (-attr! %2 %1 %3) %1) this attr))

(defn add-children!
  [this [child-cell & _ :as kids]]
  (with-let [this this]
    (doseq [x (vflatten kids)]
      (when-let [x (->node x)]
        (append-child! this x)))))

(defn invoke! [this & args]
  (let [[attr kids] (parse-args args)]
    (doto this
      (add-attributes! attr)
      (add-children!   kids))))

(extend-type js/Element
  IPrintWithWriter
  (-pr-writer
    ([this writer opts]
     (write-all writer "#<Element: " (.-tagName this) ">")))
  IFn
  (-invoke
    ([this]
     (invoke! this))
    ([this a]
     (invoke! this a))
    ([this a b]
     (invoke! this a b))
    ([this a b c]
     (invoke! this a b c))
    ([this a b c d]
     (invoke! this a b c d))
    ([this a b c d e]
     (invoke! this a b c d e))
    ([this a b c d e f]
     (invoke! this a b c d e f))
    ([this a b c d e f g]
     (invoke! this a b c d e f g))
    ([this a b c d e f g h]
     (invoke! this a b c d e f g h))
    ([this a b c d e f g h i]
     (invoke! this a b c d e f g h i))
    ([this a b c d e f g h i j]
     (invoke! this a b c d e f g h i j))
    ([this a b c d e f g h i j k]
     (invoke! this a b c d e f g h i j k))
    ([this a b c d e f g h i j k l]
     (invoke! this a b c d e f g h i j k l))
    ([this a b c d e f g h i j k l m]
     (invoke! this a b c d e f g h i j k l m))
    ([this a b c d e f g h i j k l m n]
     (invoke! this a b c d e f g h i j k l m n))
    ([this a b c d e f g h i j k l m n o]
     (invoke! this a b c d e f g h i j k l m n o))
    ([this a b c d e f g h i j k l m n o p]
     (invoke! this a b c d e f g h i j k l m n o p))
    ([this a b c d e f g h i j k l m n o p q]
     (invoke! this a b c d e f g h i j k l m n o p q))
    ([this a b c d e f g h i j k l m n o p q r]
     (invoke! this a b c d e f g h i j k l m n o p q r))
    ([this a b c d e f g h i j k l m n o p q r s]
     (invoke! this a b c d e f g h i j k l m n o p q r s))
    ([this a b c d e f g h i j k l m n o p q r s t]
     (invoke! this a b c d e f g h i j k l m n o p q r s t))
    ([this a b c d e f g h i j k l m n o p q r s t rest]
     (invoke! this a b c d e f g h i j k l m n o p q r s t rest)))
  ICustomElement
  (-set-attributes!
    ([this kvs]
      (let [e this]
        (doseq [[k v] kvs :let [k (name k)]]
          (if (= false v)
            (.removeAttribute e k)
            (.setAttribute e k (if (= true v) k v)))))))
  (-set-styles!
    ([this kvs]
     (let [e this]
       (doseq [[k v] kvs]
         (obj/set (.. e -style) (name k) (str v))))))
  (-append-child!
    ([this child]
     (if-not is-ie8
       (.appendChild this child)
       (try (.appendChild this child) (catch js/Error _)))))
  (-remove-child!
    ([this child]
     (.removeChild this child)))
  (-replace-child!
    ([this new existing]
     (.replaceChild this new existing)))
  (-insert-before!
    ([this new existing]
     (.insertBefore this new existing))))

(defn- make-singleton-ctor
  [elem]
  (fn [& args]
    (let [[attrs kids] (parse-args args)]
      (add-attributes! elem attrs)
      (when (not (:static attrs))
        (remove-all-kids! elem)
        (add-children! elem kids)))))

(defn- make-elem-ctor
  [tag]
  (let [mkelem #(-> js/document (.createElement tag) (apply %&))]
    (if-not is-ie8
      mkelem
      (fn [& args]
        (try (apply mkelem args)
          (catch js/Error _ (apply (make-elem-ctor "div") args)))))))

(defn html [& args]
  "Updates the document's `html` element in place."
  (-> (.-documentElement js/document)
      (add-attributes! (nth (parse-args args) 0))))

(def head
  "Updates the document's `head` element in place."
  (make-singleton-ctor (-head* js/document)))

(def body
  "Updates the document's `body` element in place."
  (make-singleton-ctor (.-body js/document)))

(def a              (make-elem-ctor "a"))
(def abbr           (make-elem-ctor "abbr"))
(def address        (make-elem-ctor "address"))
(def area           (make-elem-ctor "area"))
(def article        (make-elem-ctor "article"))
(def aside          (make-elem-ctor "aside"))
(def audio          (make-elem-ctor "audio"))
(def b              (make-elem-ctor "b"))
(def base           (make-elem-ctor "base"))
(def bdi            (make-elem-ctor "bdi"))
(def bdo            (make-elem-ctor "bdo"))
(def blockquote     (make-elem-ctor "blockquote"))
(def br             (make-elem-ctor "br"))
(def button         (make-elem-ctor "button"))
(def canvas         (make-elem-ctor "canvas"))
(def caption        (make-elem-ctor "caption"))
(def cite           (make-elem-ctor "cite"))
(def code           (make-elem-ctor "code"))
(def col            (make-elem-ctor "col"))
(def colgroup       (make-elem-ctor "colgroup"))
(def data           (make-elem-ctor "data"))
(def datalist       (make-elem-ctor "datalist"))
(def dd             (make-elem-ctor "dd"))
(def del            (make-elem-ctor "del"))
(def details        (make-elem-ctor "details"))
(def dfn            (make-elem-ctor "dfn"))
(def dialog         (make-elem-ctor "dialog")) ;; experimental
(def div            (make-elem-ctor "div"))
(def dl             (make-elem-ctor "dl"))
(def dt             (make-elem-ctor "dt"))
(def em             (make-elem-ctor "em"))
(def embed          (make-elem-ctor "embed"))
(def fieldset       (make-elem-ctor "fieldset"))
(def figcaption     (make-elem-ctor "figcaption"))
(def figure         (make-elem-ctor "figure"))
(def footer         (make-elem-ctor "footer"))
(def form           (make-elem-ctor "form"))
(def h1             (make-elem-ctor "h1"))
(def h2             (make-elem-ctor "h2"))
(def h3             (make-elem-ctor "h3"))
(def h4             (make-elem-ctor "h4"))
(def h5             (make-elem-ctor "h5"))
(def h6             (make-elem-ctor "h6"))
(def header         (make-elem-ctor "header"))
(def hgroup         (make-elem-ctor "hgroup")) ;; experimental
(def hr             (make-elem-ctor "hr"))
(def i              (make-elem-ctor "i"))
(def iframe         (make-elem-ctor "iframe"))
(def img            (make-elem-ctor "img"))
(def input          (make-elem-ctor "input"))
(def ins            (make-elem-ctor "ins"))
(def kbd            (make-elem-ctor "kbd"))
(def keygen         (make-elem-ctor "keygen"))
(def label          (make-elem-ctor "label"))
(def legend         (make-elem-ctor "legend"))
(def li             (make-elem-ctor "li"))
(def link           (make-elem-ctor "link"))
(def main           (make-elem-ctor "main"))
(def html-map       (make-elem-ctor "map"))
(def mark           (make-elem-ctor "mark"))
(def menu           (make-elem-ctor "menu")) ;; experimental
(def menuitem       (make-elem-ctor "menuitem")) ;; experimental
(def html-meta      (make-elem-ctor "meta"))
(def meter          (make-elem-ctor "meter"))
(def multicol       (make-elem-ctor "multicol"))
(def nav            (make-elem-ctor "nav"))
(def noframes       (make-elem-ctor "noframes"))
(def noscript       (make-elem-ctor "noscript"))
(def html-object    (make-elem-ctor "object"))
(def ol             (make-elem-ctor "ol"))
(def optgroup       (make-elem-ctor "optgroup"))
(def option         (make-elem-ctor "option"))
(def output         (make-elem-ctor "output"))
(def p              (make-elem-ctor "p"))
(def param          (make-elem-ctor "param"))
(def picture        (make-elem-ctor "picture")) ;; experimental
(def pre            (make-elem-ctor "pre"))
(def progress       (make-elem-ctor "progress"))
(def q              (make-elem-ctor "q"))
(def rp             (make-elem-ctor "rp"))
(def rt             (make-elem-ctor "rt"))
(def rtc            (make-elem-ctor "rtc"))
(def ruby           (make-elem-ctor "ruby"))
(def s              (make-elem-ctor "s"))
(def samp           (make-elem-ctor "samp"))
(def script         (make-elem-ctor "script"))
(def section        (make-elem-ctor "section"))
(def select         (make-elem-ctor "select"))
(def shadow         (make-elem-ctor "shadow"))
(def small          (make-elem-ctor "small"))
(def source         (make-elem-ctor "source"))
(def span           (make-elem-ctor "span"))
(def strong         (make-elem-ctor "strong"))
(def style          (make-elem-ctor "style"))
(def sub            (make-elem-ctor "sub"))
(def summary        (make-elem-ctor "summary"))
(def sup            (make-elem-ctor "sup"))
(def table          (make-elem-ctor "table"))
(def tbody          (make-elem-ctor "tbody"))
(def td             (make-elem-ctor "td"))
(def template       (make-elem-ctor "template"))
(def textarea       (make-elem-ctor "textarea"))
(def tfoot          (make-elem-ctor "tfoot"))
(def th             (make-elem-ctor "th"))
(def thead          (make-elem-ctor "thead"))
(def html-time      (make-elem-ctor "time"))
(def title          (make-elem-ctor "title"))
(def tr             (make-elem-ctor "tr"))
(def track          (make-elem-ctor "track"))
(def u              (make-elem-ctor "u"))
(def ul             (make-elem-ctor "ul"))
(def html-var       (make-elem-ctor "var"))
(def video          (make-elem-ctor "video"))
(def wbr            (make-elem-ctor "wbr"))

(def spliced        vector)
(def $text          #(.createTextNode js/document %))
(def $comment       #(.createComment js/document %))

(def <!--           $comment)
(def -->            ::-->)

(defn add-initfn!  [f] (.addEventListener js/window "load" #(with-timeout 0 (f))))
(defn page-load    []  (.dispatchEvent js/document "page-load"))
(defn on-page-load [f] (.addEventListener js/document "page-load" f))

(add-initfn!
  (fn []
    (. (.-body js/document)
       (addEventListener "submit"
           #(let [e (.-target %)]
              (when-not (or (.getAttribute e "action") (.getAttribute e "method"))
                (.preventDefault %)))))))

;; custom attributes ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti do!
  (fn [elem key val]
    (if-let [n (namespace key)] (keyword n "*") key)) :default ::default)

(defmethod do! ::default
  [elem key val]
  (do! elem :attr {key val}))

(defmethod do! :css/*
  [elem key val]
  (set-styles! elem key val))

(defmethod do! :html/*
  [elem key val]
  (set-attributes! elem key val))

(defmethod do! :svg/*
  [elem key val]
  (set-attributes! elem key val))

(defmethod do! :attr
  [elem _ kvs]
  (set-attributes! elem kvs))

(defmethod do! :css
  [elem _ kvs]
  (set-styles! elem kvs))

(defmulti on!
  (fn [elem key val]
    (if-let [n (namespace key)] (keyword n "*") key)) :default ::default)

(defmethod on! ::default
  [elem event callback]
  (when-dom elem #(.addEventListener elem (name event) callback)))

(defmethod on! :html/*
  [elem event callback]
  (when-dom elem #(.addEventListener elem (name event) callback)))

(defn loop-tpl*
  "Given a cell items containing a seqable collection, constructs a cell that
  works like a fill vector. The template tpl is a function of one argument: the
  formula cell containing the ith item in items. The tpl function is called
  once (and only once) for each index in items. When the items collection
  shrinks the DOM element created by the template is not destroyed--it is only
  removed from the DOM and cached. When the items collection grows again those
  cached elements will be reinserted into the DOM at their original index."
  [items tpl]
  (let [on-deck   (atom ())
        items-seq (cell= (seq items))
        ith-item  #(cell= (safe-nth items-seq %))
        shift!    #(with-let [x (first @%)] (swap! % rest))]
    (with-let [current (cell [])]
      (do-watch items-seq
        (fn [old-items new-items]
          (let [old  (count old-items)
                new  (count new-items)
                diff (- new old)]
            (cond (pos? diff)
                  (doseq [i (range old new)]
                    (let [e (or (shift! on-deck) (tpl (ith-item i)))]
                      (swap! current conj e)))
                  (neg? diff)
                  (dotimes [_ (- diff)]
                    (let [e (peek @current)]
                      (swap! current pop)
                      (swap! on-deck conj e))))))))))

(defn route-cell
  "Defines a cell whose value is the URI fragment."
  [& [default]]
  (let [c (cell (.. js/window -location -hash))]
    (with-let [_ (cell= (or (and (seq c) c) default))]
      (-> js/window
          (.addEventListener "hashchange" #(reset! c (.. js/window -location -hash)))))))
