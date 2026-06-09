(ns alida.lang
  (:require [clojure.string :as str])
  (:import [com.github.pemistahl.lingua.api Language LanguageDetectorBuilder]))

(def unknown-language Language/UNKNOWN)

(defn normalize-locale
  [locale]
  (some-> locale
          str
          (str/replace "_" "-")
          (str/split #"-")
          first
          str/lower-case
          not-empty))

(defn language->locale
  [^Language language]
  (when (and language (not= unknown-language language))
    (some-> language
            .getIsoCode639_1
            str
            str/lower-case
            normalize-locale)))

(def locale->language
  (->> (Language/allSpokenOnes)
       (keep (fn [language]
               (when-let [locale (language->locale language)]
                 [locale language])))
       (into {})))

(def supported-locales
  (set (keys locale->language)))

(defn supported-locale?
  [locale]
  (contains? supported-locales (normalize-locale locale)))

(defn require-supported-locale!
  [locale]
  (let [normalized (normalize-locale locale)]
    (when-not (supported-locale? normalized)
      (throw (ex-info (str "Unsupported language locale: " locale)
                      {:type :alida.lang/unsupported-locale
                       :locale locale
                       :supported-locales supported-locales})))
    normalized))

(defn normalize-allowed-locales
  [locales]
  (when (seq locales)
    (->> locales
         (map require-supported-locale!)
         distinct
         vec)))

(defonce detectors
  (atom {}))

(defn- detector-key
  [allowed-locales]
  (if (seq allowed-locales)
    (vec (sort allowed-locales))
    :all-spoken))

(defn- build-detector
  [allowed-locales]
  (if (seq allowed-locales)
    (let [languages (mapv locale->language allowed-locales)]
      (if (= 1 (count languages))
        nil
        (-> (LanguageDetectorBuilder/fromLanguages (into-array Language languages))
            .build)))
    (-> (LanguageDetectorBuilder/fromAllSpokenLanguages)
        .build)))

(defn detector
  [allowed-locales]
  (let [allowed-locales (normalize-allowed-locales allowed-locales)
        k (detector-key allowed-locales)]
    (or (get @detectors k)
        (let [created (build-detector allowed-locales)]
          (swap! detectors assoc k created)
          created))))

(defn language-confidence
  [detector language text]
  (some-> detector
          (.computeLanguageConfidenceValues text)
          (get language)))

(defn detect
  ([text] (detect text nil))
  ([text allowed-locales]
   (let [allowed-locales (normalize-allowed-locales allowed-locales)]
     (when (seq text)
       (if (= 1 (count allowed-locales))
         {:locale (first allowed-locales)
          :source :configured
          :confidence 1.0}
         (let [detector (detector allowed-locales)
               language (.detectLanguageOf detector text)]
           (when-let [locale (language->locale language)]
             {:locale locale
              :source :detected
              :confidence (language-confidence detector language text)})))))))

(defn detect-locale
  ([text] (:locale (detect text)))
  ([text allowed-locales] (:locale (detect text allowed-locales))))

(defn- allowed-locales
  [index-cfg source-cfg]
  (or (seq (get-in source-cfg [:language :allowed]))
      (seq (get-in index-cfg [:languages :allowed]))))

(defn- fallback-locale
  [index-cfg source-cfg]
  (or (get-in source-cfg [:language :fallback])
      (get-in index-cfg [:languages :fallback])))

(defn- allowed?
  [locale allowed-locales]
  (or (not (seq allowed-locales))
      (contains? (set allowed-locales) (normalize-locale locale))))

(defn- html-language
  [document allowed-locales]
  (let [locale (normalize-locale (:html_locale document))]
    (when (and locale (allowed? locale allowed-locales))
      {:locale locale
       :source :html
       :confidence 1.0})))

(defn- configured-language
  [source-cfg allowed-locales]
  (let [locale (normalize-locale (get-in source-cfg [:language :locale]))]
    (when (and locale (allowed? locale allowed-locales))
      {:locale locale
       :source :configured
       :confidence 1.0})))

(defn- fallback-language
  [index-cfg source-cfg allowed-locales]
  (let [locale (normalize-locale (fallback-locale index-cfg source-cfg))]
    (when (and locale (allowed? locale allowed-locales))
      {:locale locale
       :source :fallback
       :confidence nil})))

(defn language-result
  ([document] (language-result nil nil document))
  ([index-cfg source-cfg document]
   (let [allowed-locales (normalize-allowed-locales (allowed-locales index-cfg source-cfg))
         mode (keyword (or (get-in source-cfg [:language :mode]) "auto"))]
     (or (when (= :configured mode)
           (configured-language source-cfg allowed-locales))
         (when (#{:auto :html} mode)
           (html-language document allowed-locales))
         (when (#{:auto :detect} mode)
           (detect (:normalized_content document) allowed-locales))
         (fallback-language index-cfg source-cfg allowed-locales)
         {:locale nil
          :source :unknown
          :confidence nil}))))

(defn annotate-document
  ([document] (annotate-document nil nil document))
  ([index-cfg source-cfg document]
   (let [{:keys [locale source confidence]} (language-result index-cfg source-cfg document)]
     (assoc document
            :locale locale
            :language_source source
            :language_confidence confidence))))
