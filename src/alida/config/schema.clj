(ns alida.config.schema)

(def DeterministicThresholds
  [:map
   [:max_removed_absolute {:optional true} :int]
   [:max_removed_percentage {:optional true} :double]
   [:max_changed_percentage {:optional true} :double]
   [:max_item_failure_percentage {:optional true} :double]
   [:max_empty_or_short_document_percentage {:optional true} :double]])

(def Chunking
  [:map
   [:max_input_tokens :int]
   [:max_tokens :int]
   [:safety_multiplier :double]])

(def Embedding
  [:map
   [:provider [:enum "openai" "azure-openai" "vertex-ai"]]
   [:model {:optional true} :string]
   [:deployment_name {:optional true} :string]
   [:embedding_dimensions :int]
   [:max_batch_size {:optional true} :int]
   [:api_key {:optional true} :string]])

(def Source
  [:map {:closed false}
   [:id :string]
   [:type [:enum "website" "jira-service-management" "s3" "gcs" "local"]]])

(def Verification
  [:map
   [:provider [:enum "openai" "azure-openai" "vertex-ai"]]
   [:model {:optional true} :string]
   [:deployment_name {:optional true} :string]
   [:api_key {:optional true} :string]
   [:prompt_policy_version {:optional true} :string]
   [:deterministic_gate_version {:optional true} :string]
   [:deterministic_thresholds {:optional true} DeterministicThresholds]])

(def Notifications
  [:map {:closed false}
   [:slack_webhook_url {:optional true} :string]])

(def Database
  [:map
   [:jdbc_url :string]
   [:user {:optional true} :string]
   [:username {:optional true} :string]
   [:password {:optional true} :string]])

(def Index
  [:map
   [:name :string]
   [:auto_activate {:optional true} :boolean]
   [:embedding Embedding]
   [:chunking Chunking]
   [:sources [:sequential Source]]])

(def Config
  [:map
   [:database Database]
   [:verification Verification]
   [:notifications {:optional true} Notifications]
   [:indexes [:sequential Index]]])
