(set-env!
  :asset-paths    #{"tst/rsc"}
  :source-paths   #{"src" "tst/src"}
  :resource-paths #{"tst/tst"}
  :dependencies (template [[adzerk/boot-cljs                      "1.7.48-3" :scope "test"]
                           [adzerk/bootlaces                      "0.1.13"   :scope "test"]
                           [adzerk/boot-reload                    "0.4.11"   :scope "test"]
                           [adzerk/boot-test                      "1.1.2"    :scope "test"]
                           [clj-webdriver                         "0.7.2"    :scope "test"]
                           [tailrecursion/boot-static             "0.1.0"    :scope "test"]
                           [org.seleniumhq.selenium/selenium-java "2.53.1"   :scope "test"]
                           [com.codeborne/phantomjsdriver         "1.2.1"    :scope "test" :exclusions [org.seleniumhq.selenium/selenium-java]]
                           [boot-codox                            "0.10.1"   :scope "test"]
                           [org.clojure/clojure                   ~(clojure-version)]
                           [org.clojure/clojurescript             "1.7.122"]
                           [cljsjs/jquery                         "1.9.1-0"]
                           [hoplon/javelin                        "3.9.0"]]))

(require
  '[adzerk.bootlaces          :refer [bootlaces! build-jar push-release]]
  '[hoplon.boot-hoplon        :refer [hoplon ns+ prerender]]
  '[adzerk.boot-reload        :refer [reload]]
  '[adzerk.boot-cljs          :refer [cljs]]
  '[adzerk.boot-test          :refer [test]]
  '[tailrecursion.boot-static :refer [serve]]
  '[codox.boot                :refer [codox]])

(def +version+ "7.0.4")

(bootlaces! +version+)

(replace-task!
  [t test] (fn [& xs] (comp (hoplon) (ns+) (cljs) (serve) (apply t xs))))

(deftask develop-tests []
  (comp (watch) (speak) (test)))

(deftask develop []
  (comp (watch) (speak) (build-jar)))

(deftask deploy []
  (comp (build-jar) (push-release)))

(task-options!
  pom    {:project     'jumblerg/hoplon
          :version     +version+
          :description "Hoplon web development environment."
          :url         "https://github.com/jumblerg/hoplon"
          :scm         {:url "https://github.com/jumberg/hoplon"}
          :license     {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}}
  test   {:namespaces  '#{hoplon.app-test}}
  serve  {:port        3020}
  target {:dir         #{"target"}})
