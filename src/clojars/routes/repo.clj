(ns clojars.routes.repo
  (:require 
   [clojars.auth :as auth :refer [with-account]]
   [clojars.db :as db]
   [clojars.errors :refer [report-error]]
   [clojars.file-utils :as fu]
   [clojars.log :as log]
   [clojars.maven :as maven]
   [clojars.search :as search]
   [clojars.storage :as storage]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [compojure.core :as compojure :refer [PUT]]
   [compojure.route :refer [not-found]]
   [ring.util.codec :as codec]
   [ring.util.response :as response])
  (:import
   (java.io IOException File)
   (java.util Date UUID)
   org.apache.commons.io.FileUtils))

(defn save-to-file [dest input]
  (-> dest
      .getParentFile
      .mkdirs)
  (io/copy input dest)
  dest)

(defn- try-save-to-file [dest input]
  (try
    (save-to-file dest input)
    (catch IOException e
      (.delete dest)
      (throw e))))

(defn- pom? [file]
  (let [filename (if (string? file) file (.getName file))]
    (.endsWith filename ".pom")))

(def metadata-edn "_metadata.edn")

(defn read-metadata [dir]
  (let [md-file (io/file dir metadata-edn)]
    (when (.exists md-file)
      (read-string (slurp md-file)))))

(defn write-metadata [dir group-name group-path artifact version timestamp-version]
  (spit (io/file dir metadata-edn)
    (pr-str (merge-with #(if %2 %2 %1)
              (read-metadata dir)
              {:group group-name
               :group-path group-path
               :name artifact
               :version version
               :timestamp-version timestamp-version}))))

(defn find-upload-dir [group artifact version timestamp-version {:keys [upload-dirs]}]
  (if-let [dir (some (fn [dir]
                       (let [dir (io/file dir)
                             metadata (read-metadata dir)
                             if= #(if (and %1 %2) (= %1 %2) true)]
                         (when (and dir (.exists dir)
                                    (= [group artifact] ((juxt :group :name) metadata))
                                    (if= (:version metadata) version)
                                    (if= (:timestamp-version metadata) timestamp-version))
                           dir)))
                 upload-dirs)]
    dir
    (doto (io/file (FileUtils/getTempDirectory)
            (str "upload-" (UUID/randomUUID)))
      (FileUtils/forceMkdir))))

(def ^:private ^:dynamic *db*
  "Used to avoid passing the db to every fn that needs to audit."
  nil)

(defn- throw-invalid
  ([tag message]
   (throw-invalid tag message nil))
  ([tag message meta]
   (throw-invalid tag message meta nil))
  ([tag message meta cause]
   ;; don't log again if we threw this exception before
   (when-not (:throw-invalid? meta)
     (log/audit *db* {:tag tag
                      :message message})
     (log/info {:status :failed
                :message message}))
   (throw
    (ex-info message (merge {:report? false
                             :throw-invalid? true}
                            meta)
             cause))))

(defn- throw-forbidden
  [e-or-message meta]
  (let [throwable? (instance? Throwable e-or-message)
        [message cause] (if throwable?
                          [(.getMessage e-or-message) (.getCause e-or-message)]
                          [e-or-message])]
    (when throwable?
      (log/error {:tag :upload-exception
                  :error e-or-message}))
    (throw-invalid
     :deploy-forbidden
     message
     (merge
      {:status 403
       :status-message (str "Forbidden - " message)}
      meta
      (ex-data e-or-message))
     cause)))

(defn- check-group [db account group]
  (try
    (db/check-group (db/group-activenames db group) account group)
    (catch Exception e
      (throw-forbidden e
        {:account account
         :group group}))))

(defn upload-request [db groupname artifact version timestamp-version session f]
  (with-account
    (fn [account]
      (log/with-context {:tag :upload
                         :group groupname
                         :artifact artifact
                         :version version
                         :timestamp-version timestamp-version
                         :username account}
        (check-group db account groupname) ;; will throw if there are any issues
        (let [upload-dir (find-upload-dir groupname artifact version timestamp-version session)]
          (f account upload-dir)
          ;; should we only do 201 if the file didn't already exist?
          (log/info {:status :success})
          {:status 201
           :headers {}
           :session (update session
                            :upload-dirs #(cons (.getAbsolutePath upload-dir) %))
           :body nil})))))

(defn find-pom [dir]
  (->> dir
    file-seq
    (filter pom?)
    first))

(defn- match-file-name [match f]
  (let [name (.getName f)]
    (if (string? match)
      (= match name)
      (re-find match name))))

(defn find-artifacts
  ([dir]
   (find-artifacts dir true))
  ([dir remove-checksums?]
   (let [tx (comp
              (filter (memfn isFile))
              (remove (partial match-file-name metadata-edn)))]
     (into []
       (if remove-checksums?
         (comp tx
           (remove (partial match-file-name #".sha1$"))
           (remove (partial match-file-name #".md5$")))
         tx)
       (file-seq dir)))))

(defn- validate-regex [x re message]
  (when-not (re-matches re x)
    (throw-invalid :regex-validation-failed
                   message {:value x
                            :regex re})))

(defn- validate-pom-entry [pom-data key value]
  (when-not (= (key pom-data) value)
    (throw-invalid
      :pom-entry-mismatch
      (format "the %s in the pom (%s) does not match the %s you are deploying to (%s)"
        (name key) (key pom-data) (name key) value)
      {:pom pom-data})))

(defn- validate-pom [pom group name version]
  (validate-pom-entry pom :group group)
  (validate-pom-entry pom :name name)
  (validate-pom-entry pom :version version))

(defn assert-non-redeploy [db group-id artifact-id version]
  (when (and (not (maven/snapshot-version? version))
             (db/find-jar db group-id artifact-id version))
    (throw-invalid :non-snapshot-redeploy
                   "redeploying non-snapshots is not allowed (see https://git.io/v1IAs)")))

(defn assert-non-central-shadow [group-id artifact-id]
  (when-not (maven/can-shadow-maven? group-id artifact-id)
    (when-let [ret (maven/exists-on-central?* group-id artifact-id)]
      (let [meta {:group-id group-id
                  :artifact-id artifact-id
                  ;; report both failures to reach central and shadow attempts to sentry
                  :report? true}] 
        (if (= ret :failure)
          (throw-invalid :central-shadow-check-failure
                         "failed to contact Maven Central to verify project name (see https://git.io/vMUHN)"
            (assoc meta :status 503))
          (throw-invalid :central-shadow
                         "shadowing Maven Central artifacts is not allowed (see https://git.io/vMUHN)"
            meta))))))
 
(defn assert-jar-uploaded [artifacts pom]
  (when (and (= :jar (:packaging pom))
          (not (some (partial match-file-name #"\.jar$") artifacts)))
    (throw-invalid :missing-jar-file
                   "no jar file was uploaded")))

(defn validate-checksums [artifacts]
  (doseq [f artifacts]
    ;; verify that at least one type of checksum file exists
    (when (not (or (.exists (fu/checksum-file f :md5))
                 (.exists (fu/checksum-file f :sha1))))
      (throw-invalid :file-missing-checksum
                     (format "no checksums provided for %s" (.getName f))
        {:file f}))
    ;; verify provided checksums are valid
    (doseq [type [:md5 :sha1]]
      (when (not (fu/valid-checksum-file? f type false))
        (throw-invalid :file-invalid-checksum
                       (format "invalid %s checksum for %s" type (.getName f))
          {:file f})))))

(defn assert-signatures [artifacts]
  ;; if any signatures exist, require them for every artifact
  (let [asc-matcher (partial match-file-name #"\.asc$")]
    (when (some asc-matcher artifacts)
      (doseq [f artifacts
              :when (not (asc-matcher f))
              :when (not (.exists (io/file (str (.getAbsolutePath f) ".asc"))))]
        (throw-invalid :file-missing-signature
                       (format "%s has no signature" (.getName f)) {:file f})))))

(defn validate-gav [group name version]
    ;; We're on purpose *at least* as restrictive as the recommendations on
    ;; https://maven.apache.org/guides/mini/guide-naming-conventions.html
    ;; If you want loosen these please include in your proposal the
    ;; ramifications on usability, security and compatiblity with filesystems,
    ;; OSes, URLs and tools.
    (validate-regex name #"^[a-z0-9_.-]+$"
      (str "project names must consist solely of lowercase "
        "letters, numbers, hyphens and underscores (see https://git.io/v1IAl)"))

    (validate-regex group #"^[a-z0-9_.-]+$"
      (str "group names must consist solely of lowercase "
        "letters, numbers, hyphens and underscores (see https://git.io/v1IAl)"))

    ;; Maven's pretty accepting of version numbers, but so far in 2.5 years
    ;; bar one broken non-ascii exception only these characters have been used.
    ;; Even if we manage to support obscure characters some filesystems do not
    ;; and some tools fail to escape URLs properly.  So to keep things nice and
    ;; compatible for everyone let's lock it down.
    (validate-regex version #"^[a-zA-Z0-9_.+-]+$"
      (str "version strings must consist solely of letters, "
        "numbers, dots, pluses, hyphens and underscores (see https://git.io/v1IA2)")))

(defn validate-deploy [db dir pom {:keys [group name version]}]
  (validate-gav group name version)
  (validate-pom pom group name version)
  (assert-non-redeploy db group name version)
  (assert-non-central-shadow group name)

  (let [artifacts (find-artifacts dir)]
    (assert-jar-uploaded artifacts pom)
    (validate-checksums artifacts)
    (assert-signatures (remove (partial match-file-name "maven-metadata.xml") artifacts))))

(defmacro profile [meta & body]
  `(let [start# (System/currentTimeMillis)]
     ~@body
     (prn (assoc ~meta :time (- (System/currentTimeMillis) start#)))))

(defn finalize-deploy [storage db search account ^File dir]
  (if-let [pom-file (find-pom dir)]
    (let [pom (try
                (maven/pom-to-map pom-file)
                (catch Exception e
                  (throw-invalid :invalid-pom-file
                                 (str "invalid pom file: " (.getMessage e))
                    {:file pom-file}
                    e)))
          {:keys [group group-path name version] :as posted-metadata}
          (read-metadata dir)
          
          md-file (io/file dir group-path name "maven-metadata.xml")]
      (log/with-context {:version version}
        ;; since we trigger on maven-metadata.xml, we don't actually
        ;; have the sums for it because they are uploaded *after* the
        ;; metadata file itself. This means that it's possible for a
        ;; corrupted file to slip through, so we try to parse it
        (try
          (maven/read-metadata md-file)
          (catch Exception e
            (throw-invalid :invalid-maven-metadata-file
                           "Failed to parse maven-metadata.xml"
                           {:file md-file}
                           e)))

        ;; If that succeeds, we create checksums for it
        (fu/create-checksum-file md-file :md5)
        (fu/create-checksum-file md-file :sha1)

        (validate-deploy db dir pom posted-metadata)
        (db/check-and-add-group db account group)
        (run! #(storage/write-artifact
                storage
                (fu/subpath (.getAbsolutePath dir) (.getAbsolutePath %)) %)
              (->> (file-seq dir)
                   (remove (memfn isDirectory))
                   (remove #(some #{(.getName %)} [metadata-edn]))))

        (db/add-jar db account pom)
        (log/audit db {:tag :deployed})
        (log/info {:tag :deploy-finalized})
        (future
          (search/index! search (assoc pom
                                       :at (Date. (.lastModified pom-file))))
          (log/info {:tag :deploy-indexed}))
        (spit (io/file dir ".finalized") "")))
    (throw-invalid :missing-pom-file "no pom file was uploaded")))

(defn- deploy-finalized? [dir]
  (.exists (io/file dir ".finalized")))

(defn- deploy-post-finalized-file [storage tmp-repo file]
  (storage/write-artifact storage
    (fu/subpath (.getAbsolutePath tmp-repo) (.getAbsolutePath file)) file))

(defn- token-session-matches-group-artifact?
  [{:cemerick.friend/keys [identity]} group artifact]
  (let [{:keys [authentications current]}       identity
        {:as token :keys [group_name jar_name]} (get-in authentications [current :token])]
    (or
     ;; not a token request
     (nil? token)
     
     ;; token has no scope
     (and (nil? group_name)
          (nil? jar_name))
     
     ;; token is scoped to this group/artifact
     (and (= group group_name)
          (= artifact jar_name))

     ;; token is only group scoped and matches
     (and (nil? jar_name)
          (= group group_name)))))

(defn- maybe-assert-token-matches-group+artifact
  [session group artifact]
  (when-not (token-session-matches-group-artifact? session group artifact)
    (throw-forbidden
     "The provided token's scope doesn't allow deploying this artifact (see https://git.io/JfwjM)"
     {:group group
      :artifact artifact})))

(defn- handle-versioned-upload [storage db body session group artifact version filename]
  (let [groupname (fu/path->group group)
        timestamp-version (when (maven/snapshot-version? version) (maven/snapshot-timestamp-version filename))]
    (upload-request
      db
      groupname
      artifact
      version
      timestamp-version
      session
      (fn [account upload-dir]
        (maybe-assert-token-matches-group+artifact session groupname artifact)
        (write-metadata
          upload-dir
          groupname
          group
          artifact
          version
          timestamp-version)
        (let [file (try-save-to-file (io/file upload-dir group artifact version filename) body)]
          (when (deploy-finalized? upload-dir)
            ;; a deploy should never get this far with a bad group,
            ;; but since this includes the group authorization check,
            ;; we do it here just in case. Will throw if there are any
            ;; issues.
            (check-group db account groupname)
            (deploy-post-finalized-file storage upload-dir file)))))))

;; web handlers
(defn routes [storage db search]
  (compojure/routes
   (PUT ["/:group/:artifact/:file"
         :group #".+" :artifact #"[^/]+" :file #"maven-metadata\.xml[^/]*"]
        {body :body session :session {:keys [group artifact file]} :params}
        (binding [*db* db]
          (if (maven/snapshot-version? artifact)
            ;; SNAPSHOT metadata will hit this route, but should be
            ;; treated as a versioned file upload.
            ;; See: https://github.com/clojars/clojars-web/issues/319
            (let [version artifact
                  group-parts (str/split group #"/")
                  group (str/join "/" (butlast group-parts))
                  artifact (last group-parts)]
              (handle-versioned-upload storage db body session group artifact version file))
            (if (re-find #"maven-metadata\.xml$" file)
              ;; ignore metadata sums, since we'll recreate those when
              ;; the deploy is finalizied
              (let [groupname (fu/path->group group)]
                (upload-request
                 db
                 groupname
                 artifact
                 nil
                 nil
                 session
                 (fn [account upload-dir]
                   (let [file (io/file upload-dir group artifact file)
                         existing-sum (when (.exists file) (fu/checksum file :sha1))]
                     (try-save-to-file file body)
                     ;; only finalize if we haven't already or the
                     ;; maven-metadata.xml file doesn't match the one
                     ;; we already have
                     ;; https://github.com/clojars/clojars-web/issues/640
                     (when-not (or (deploy-finalized? upload-dir)
                                   (= (fu/checksum file :sha1) existing-sum))
                       (try
                         (finalize-deploy storage db search
                                          account upload-dir)
                         (catch Exception e
                           ;; FIXME: we may have already thrown-invalid here
                           ;; - should we check for that and only
                           ;; throw on other exceptions?
                           (throw-forbidden e
                                            {:account account
                                             :group group
                                             :artifact artifact}))))))))
              {:status 201
               :headers {}
               :body nil}))))
   (PUT ["/:group/:artifact/:version/:filename"
         :group #"[^\.]+" :artifact #"[^/]+" :version #"[^/]+"
         :filename #"[^/]+(\.pom|\.jar|\.sha1|\.md5|\.asc)$"]
        {body :body session :session {:keys [group artifact version filename]} :params}
        (binding [*db* db]
          (handle-versioned-upload storage db body session group artifact version filename)))
   (PUT "*" _ {:status 400 :headers {}})
   (not-found "Page not found")))

(defn wrap-file [app dir]
  (fn [req]
    (if-not (= :get (:request-method req))
      (app req)
      (let [path (codec/url-decode (:path-info req))]
        (or (response/file-response path {:root dir})
            (app req))))))

(defn wrap-reject-double-dot [f]
  (fn [req]
    (if (re-find #"\.\." (:uri req))
      {:status 400 :headers {}}
      (f req))))

(defn wrap-reject-non-token [f db]
  (fn [req]
    (if (auth/unauthed-or-token-request? req)
      (f req)
      (let [{:keys [username]} (auth/parse-authorization-header (get-in req [:headers "authorization"]))
            message "a deploy token is required to deploy (see https://git.io/JfwjM)"]
        (log/audit db {:tag :deploy-password-rejection
                       :message message
                       :username username})
        (log/info {:tag :deploy-password-rejection
                   :username username})
        {:status 401
         :headers {"status-message" (format "Unauthorized - %s" message)}}))))

(defn wrap-exceptions [app reporter]
  (fn [req]
    (let [request-id (log/trace-id)]
      (try
        (log/with-context {:trace-id request-id}
          (app req))
        (catch Exception e
          (report-error reporter e nil request-id)
          (let [data (ex-data e)]
            {:status (or (:status data) 403)
             :headers {"status-message" (:status-message data)}
             :body (.getMessage e)}))))))
