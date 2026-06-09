(ns alida.lang-test
  (:require [alida.lang :as lang]
            [clojure.test :refer [deftest is]]))

(deftest detects-supported-locales
  (is (= "en" (lang/detect-locale "This support article explains how to configure the application.")))
  (is (= "de" (lang/detect-locale "Dieser Hilfeartikel erklaert, wie man die Anwendung konfiguriert.")))
  (is (= "nl" (lang/detect-locale "Dit helpartikel legt uit hoe je de applicatie configureert.")))
  (is (= "fr" (lang/detect-locale "Cet article explique comment configurer l'application."))))

(deftest detects-broader-lingua-locales-by-default
  (is (= "es" (:locale (lang/detect "Este articulo explica como configurar la aplicacion.")))))

(deftest constrains-detection-to-allowed-locales
  (is (= "fr"
         (:locale (lang/detect "Cet article explique comment configurer l'application."
                               ["en" "fr"])))))

(deftest language-result-prefers-html-in-auto-mode
  (is (= {:locale "nl" :source :html :confidence 1.0}
         (lang/language-result {:languages {:allowed ["en" "nl"]}}
                               {:language {:mode "auto"}}
                               {:html_locale "nl-NL"
                                :normalized_content "This English text would otherwise be detected as English."}))))

(deftest configured-source-language-is-explicit
  (is (= {:locale "de" :source :configured :confidence 1.0}
         (lang/language-result {:languages {:allowed ["en" "de"]}}
                               {:language {:mode "configured"
                                           :locale "de"}}
                               {:normalized_content "This text is ignored because the source is configured."}))))

(deftest fallback-is-used-when-language-is-unknown
  (is (= {:locale "en" :source :fallback :confidence nil}
         (lang/language-result {:languages {:allowed ["en" "de"]
                                            :fallback "en"}}
                               {:language {:mode "html"}}
                               {:normalized_content "Bonjour"
                                :html_locale "fr"}))))

(deftest annotates-document-locale
  (let [document (lang/annotate-document {:languages {:allowed ["en" "nl"]}}
                                         {:language {:mode "detect"}}
                                         {:normalized_content "This is a useful English document."})]
    (is (= "en" (:locale document)))
    (is (= :detected (:language_source document)))
    (is (number? (:language_confidence document)))))
