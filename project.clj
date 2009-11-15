;; The only requirement of the project.clj file is that it includes a
;; defproject form. It can have other code in it as well, including
;; loading other task definitions.

(defproject leiningen "0.5.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.1.0-alpha-SNAPSHOT"]
                 [org.clojure/clojure-contrib "1.0-SNAPSHOT"]
                 [ant/ant-launcher "1.6.2"]
                 [org.apache.maven/maven-ant-tasks "2.0.10"]
		 
		 [org.apache.maven/maven-artifact-manager "2.2.1"]
		 [org.apache.maven/maven-model "2.2.1"]
		 [org.apache.maven/maven-project "2.2.1"]
		 [org.apache.maven.wagon/wagon-file "1.0-beta-6"]
		 
		 [org.clojars.ato/compojure "0.3.1"]
		 [org.clojars.ato/nailgun "0.7.1"]
		 [org.xerial/sqlite-jdbc "3.6.17"]])

