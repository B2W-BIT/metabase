(ns metabase.driver.athena
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [clojure
             [set :as set]
             [string :as string]
             [walk :as walk]]
            [honeysql
             [core :as hsql]
             [helpers :as h]]
            [metabase
             [config :as config]
             [db :as db]
             [driver :as driver]
             [util :as u]]
            [metabase.driver.generic-sql :as sql]
            [metabase.driver.generic-sql.query-processor :as sql-qp]
            [metabase.driver.generic-sql.util.unprepare :as unprepare]
            [metabase.driver.presto :as presto]
            [metabase.models
             [field :as field]
             [table :as table]]
            [metabase.query-processor.util :as qputil]
            [metabase.util
             [honeysql-extensions :as hx]
             [ssh :as ssh]]
            [metabase.driver.athena.athena-sql-parser :as schema-parser])

  (:import [java.sql DriverManager ResultSet]
           [java.util Properties]))

(defrecord AthenaDriver []
  clojure.lang.Named
  (getName [_] "Athena"))

(comment
 ; for REPL testing
 (require '[metabase.plugins :as plugins])
 (plugins/load-plugins!))

(defn- describe-database->clj
  "Workaround for wrong getColumnCount response by the driver"
  [^ResultSet rs]
  {:name (string/trim (.getString rs 1))
    :type (string/trim (.getString rs 2))})

(defn- describe-all-database->clj
  [^ResultSet rs]
  (loop [result [] more (.next rs)]
    (if-not more
      (->> result
        (remove #(= (:name %) ""))
        (remove #(string/starts-with? (:name %) "#")) ; remove comment
        (distinct)) ; driver can return twice the partitioning fields
      (recur
       (conj result (describe-database->clj rs))
       (.next rs)))))

(defn- run-query
  "Workaround for avoiding the usage of 'advance' jdbc feature that are not implemented by the driver yet.
   Such as prepare statement"
  [database query {:keys [read-fn]}]
  (let [current-read-fn (or read-fn #(into [] (jdbc/result-set-seq % {:identifiers identity})))]
    (log/infof "Running Athena query : '%s'..." query)
    (try
      (with-open [conn (jdbc/get-connection (sql/db->jdbc-connection-spec database))]
        (-> (.createStatement conn)
            (.executeQuery query)
            (current-read-fn)))
      (catch Exception e
        (log/error (u/format-color 'red "Failed to execute query: %s %s\n%s" query (.getMessage e) (u/pprint-to-str (u/filtered-stacktrace e))))))))

(defn- column->base-type [column-type]
  ({:array      :type/*
    :bigint     :type/BigInteger
    :binary     :type/*
    :boolean    :type/Boolean
    :date       :type/Date
    :decimal    :type/Decimal
    :double     :type/Float
    :float      :type/Float
    :int        :type/Integer
    :map        :type/*
    :smallint   :type/Integer
    :string     :type/Text
    :struct     :type/Dictionary
    :timestamp  :type/DateTime
    :tinyint    :type/Integer
    :uniontype  :type/*
    :varchar    :type/Text} (keyword column-type)))

(defn- connection-details->spec
  "Create a database specification for an Athena database. DETAILS should include keys for `:user`,
   `:password`, `:s3_staging_dir` `:log_path` and `region`"
  [{:keys [user password s3_staging_dir log_path region] :as details}]
  (assert (or user password s3_staging_dir log_path region))
  {:classname "com.simba.athena.jdbc.Driver"
   :log_path log_path
   :subprotocol "awsathena"
   :subname (str "//athena." region ".amazonaws.com:443")
   :user user
   :password password
   :s3_staging_dir s3_staging_dir})

(defn- can-connect? [driver details]
  (let [conn-details (-> details
                         (ssh/include-ssh-tunnel)
                         (connection-details->spec))]
    (with-open [connection (jdbc/get-connection conn-details)]
      (-> (.createStatement connection)
          (.executeQuery "SELECT 1")
          (jdbc/result-set-seq)
          (#(= 1 (first (vals (first %)))))))))

(defn- get-databases [driver database]
  (->> (run-query database "SHOW DATABASES" {})
       (remove #(= (:database_name %) "default"))
       (map (fn [{:keys [database_name]}]
              {:name database_name :schema nil}))
       (set)))

(defn- get-tables [driver database databases]
  (->> databases
       (map (fn [{:keys [name] :as table}]
              (let [tables (run-query database (str "SHOW TABLES IN `" name "`") {})]
                (map (fn [{:keys [tab_name]}] (assoc table :schema name :name tab_name))
                     tables))))
       (flatten)
       (set)))

(defn- describe-database
  [driver db]
  (let [databases (get-databases driver db)
        tables (get-tables driver db databases)]
    {:tables tables}))

(defn- describe-table-fields [db {:keys [name schema]}]
  (->> (run-query db (str "DESCRIBE `" schema "`.`" name "`;")
                     {:read-fn describe-all-database->clj})
       (map schema-parser/parse-schema)
       (set)))

(defn- describe-table [driver db {:keys [name schema] :as table}]
  {:name name
   :schema schema
   :fields (describe-table-fields db table)})

(defn- execute-query
  [driver {:keys [database settings], {sql :query, params :params} :native, :as outer-query}]
  (let [final-query (str "-- " (qputil/query->remark outer-query) "\n"
                               (unprepare/unprepare (cons sql params) :quote-escape "\"", :iso-8601-fn :from_iso8601_timestamp))
        results (run-query database final-query {})
        columns (into [] (keys (first results)))
        rows (->> results
                  (map vals)
                  (map #(into [] %)))]
    {:columns columns
     :rows rows}))

(defn- unquote-table-name
  "Workaround for unquoting table name as the JDBC api does not support this feature"
  [sql-string table-name]
  (string/replace sql-string (str "\"" table-name "\"") table-name))

(defn- mbql->native
  "Transpile MBQL query into a native SQL statement."
  [driver {inner-query :query, database :database, :as outer-query}]
  (binding [metabase.driver.generic-sql.query-processor/*query* outer-query]
    (let [honeysql-form (sql-qp/build-honeysql-form driver outer-query)
          [sql & args]  (sql/honeysql-form->sql+args driver honeysql-form)
          athena-sql (unquote-table-name sql (get-in inner-query [:source-table :name]))]
      {:query  athena-sql
       :params args})))

(u/strict-extend AthenaDriver
  driver/IDriver
  (merge (sql/IDriverSQLDefaultsMixin)
         {:can-connect?              can-connect?
          :date-interval             (u/drop-first-arg presto/date-interval)
          :describe-database         describe-database
          :describe-table            describe-table
          :details-fields (constantly (ssh/with-tunnel-config
                                       [{:name         "region"
                                         :display-name "Region"
                                         :placeholder  "us-east-1"
                                         :required     true}
                                        {:name         "log_path"
                                         :display-name "Log Path"
                                         :placeholder  "/tmp/athena.log"
                                         :default      "/tmp/athena.log"}
                                        {:name         "s3_staging_dir"
                                         :display-name "Staging dir"
                                         :placeholder  "s3://YOUR_BUCKET/aws-athena-query-results-report"}
                                        {:name         "user"
                                         :display-name "AWS access key"
                                         :required     true}
                                        {:name         "password"
                                         :display-name "AWS secret key"
                                         :type         :password
                                         :placeholder  "*******"
                                         :required     true}]))
          :execute-query             execute-query
          :features                  (constantly (set/union #{:basic-aggregations
                                                              :standard-deviation-aggregations
                                                              :expressions
                                                              :native-parameters
                                                              :expression-aggregations
                                                              :binning
                                                              :native-query-params
                                                              :nested-fields
                                                              :foreign-keys}))
          :mbql->native mbql->native
          :table-rows-seq (constantly nil)})
  sql/ISQLDriver
  (merge (sql/ISQLDriverDefaultsMixin)
         {:apply-page                (u/drop-first-arg presto/apply-page)
          :active-tables             sql/post-filtered-active-tables
          :column->base-type         (u/drop-first-arg column->base-type)
          :connection-details->spec  (u/drop-first-arg connection-details->spec)
          :current-datetime-fn       (constantly :%now)
          :date                      (u/drop-first-arg metabase.driver.presto/date)
          :excluded-schemas          (constantly #{"default"})
          :quote-style               (constantly :ansi)
          :string-length-fn          (u/drop-first-arg presto/string-length-fn)
          :unix-timestamp->timestamp (u/drop-first-arg presto/unix-timestamp->timestamp)}))



(when (u/ignore-exceptions
       (Class/forName "com.simba.athena.jdbc.Driver"))
  (driver/register-driver! :athena (AthenaDriver.)))
