{{/* Get common metadata fields */}}
{{- define "loculus.commonMetadata" }}
fields:
  - name: accession
    type: string
    notSearchable: true
    hideOnSequenceDetailsPage: true
  - name: version
    type: int
    notSearchable: true
    hideOnSequenceDetailsPage: true
  - name: submissionId
    type: string
    header: Submission Details
  - name: accessionVersion
    type: string
    notSearchable: true
    hideOnSequenceDetailsPage: true
  - name: isRevocation
    type: boolean
    notSearchable: true
    hideOnSequenceDetailsPage: true
  - name: submitter
    type: string
    generateIndex: true
    autocomplete: true
    header: Submission Details
  - name: groupId
    type: int
    autocomplete: true
    header: Submission Details
  - name: groupName
    type: string
    generateIndex: true
    autocomplete: true
    header: Submission Details
  - name: submittedAt
    type: timestamp
    displayName: Date submitted
    header: Submission Details
  - name: releasedAt
    type: timestamp
    displayName: Date released
    header: Submission Details
  - name: dataUseTerms
    type: string
    generateIndex: true
    autocomplete: true
    displayName: Data Use Terms
    initiallyVisible: true
    customDisplay:
      type: dataUseTerms
    header: Data Use Terms
  - name: dataUseTermsRestrictedUntil
    type: date
    displayName: Data use terms restricted until
    hideOnSequenceDetailsPage: true
    header: Data Use Terms
  - name: versionStatus
    type: string
    notSearchable: true
    hideOnSequenceDetailsPage: true
  {{- if $.Values.dataUseTermsUrls }}
  - name: dataUseTermsUrl
    type: string
    notSearchable: true
    header: Data Use Terms
    customDisplay:
      type: link
      url: "__value__"
  {{- end}}
{{- end}}

{{/* Patches schema by adding to it */}}
{{- define "loculus.patchMetadataSchema" -}}
{{- $patchedSchema := deepCopy . }}
{{- $toAdd := . | dig "metadataAdd" list -}}
{{- $patchedMetadata := concat .metadata $toAdd -}}
{{- set $patchedSchema "metadata" $patchedMetadata | toYaml -}}
{{- end -}}

{{/* Generate website config from passed config object */}}
{{- define "loculus.generateWebsiteConfig" }}
name: {{ quote $.Values.name }}
logo: {{ $.Values.logo | toYaml | nindent 6 }}
{{ if $.Values.bannerMessage }}
bannerMessage: {{ quote $.Values.bannerMessage }}
{{ end }}
{{ if $.Values.additionalHeadHTML }}
additionalHeadHTML: {{ quote $.Values.additionalHeadHTML }}
{{end}}

accessionPrefix: {{ quote $.Values.accessionPrefix }}
{{- $commonMetadata := (include "loculus.commonMetadata" . | fromYaml).fields }}
organisms:
  {{- range $key, $instance := (.Values.organisms | default .Values.defaultOrganisms) }}
  {{ $key }}:
    schema:
      {{- with ($instance.schema | include "loculus.patchMetadataSchema" | fromYaml) }}
      instanceName: {{ quote .instanceName }}
      loadSequencesAutomatically: {{ .loadSequencesAutomatically | default false }}
      {{ if .image }}
      image: {{ .image }}
      {{ end }}
      {{ if .description }}
      description: {{ quote .description }}
      {{ end }}
      primaryKey: accessionVersion
      inputFields: {{- include "loculus.inputFields" . | nindent 8 }}
      metadata:
        {{ $metadata := concat $commonMetadata .metadata
            | include "loculus.generateWebsiteMetadata"
            | fromYaml
         }}
        {{ $metadata.fields | toYaml | nindent 8 }}
      {{ .website | toYaml | nindent 6 }}
      {{- end }}
    referenceGenomes:
      {{ $instance.referenceGenomes | toYaml | nindent 6 }}
  {{- end }}
{{- end }}

{{/* Generate website metadata from passed metadata array */}}
{{- define "loculus.generateWebsiteMetadata" }}
fields:
{{- range . }}
  - name: {{ quote .name }}
    type: {{ .type | default "string" | quote }}
    {{- if .autocomplete }}
    autocomplete: {{ .autocomplete }}
    {{- end }}
    {{- if .notSearchable }}
    notSearchable: {{ .notSearchable }}
    {{- end }}
    {{- if .initiallyVisible }}
    initiallyVisible: {{ .initiallyVisible }}
    {{- end }}
    {{- if or (or (eq .type "timestamp")  (eq .type "date")) ( .rangeSearch) }}
    rangeSearch: true
    {{- end }}
    {{- if .hideOnSequenceDetailsPage }}
    hideOnSequenceDetailsPage: {{ .hideOnSequenceDetailsPage }}
    {{- end }}
    {{- if .displayName }}
    displayName: {{ quote .displayName }}
    {{- end }}
    {{- if .truncateColumnDisplayTo }}
    truncateColumnDisplayTo: {{ .truncateColumnDisplayTo }}
    {{- end }}
    {{- if .customDisplay }}
    customDisplay:
      type: {{ quote .customDisplay.type }}
      url: {{ .customDisplay.url }}
    {{- end }}
    header: {{ default "Other" .header }}
{{- end}}
{{- end}}

{{/* Generate backend config from passed config object */}}
{{- define "loculus.generateBackendConfig" }}
accessionPrefix: {{ quote $.Values.accessionPrefix }}
name: {{ quote $.Values.name }}
dataUseTermsUrls:
  {{$.Values.dataUseTermsUrls | toYaml | nindent 2}}
organisms:
  {{- range $key, $instance := (.Values.organisms | default .Values.defaultOrganisms) }}
  {{ $key }}:
    schema:
      {{- with $instance.schema }}
      instanceName: {{ quote .instanceName }}
      metadata:
        {{ $metadata := (include "loculus.patchMetadataSchema" .
          | fromYaml).metadata
          | include "loculus.generateBackendMetadata"
          | fromYaml }}
        {{ $metadata.fields | toYaml | nindent 8 }}
      {{- end }}
    referenceGenomes:
      {{ $instance.referenceGenomes | toYaml | nindent 6 }}
  {{- end }}
{{- end }}

{{/* Generate backend metadata from passed metadata array */}}
{{- define "loculus.generateBackendMetadata" }}
fields:
{{- range . }}
  - name: {{ quote .name }}
    type: {{ .type | default "string" | quote }}
    {{- if .required }}
    required: {{ .required }}
    {{- end }}
{{- end}}
{{- end}}

{{- define "loculus.publicRuntimeConfig" }}
            {{- if $.Values.codespaceName }}
            "backendUrl": "https://{{ .Values.codespaceName }}-8079.app.github.dev",
            {{- else if eq $.Values.environment "server" }}
            "backendUrl": "https://{{ printf "backend-%s" .Values.host }}",
            {{- else }}
            "backendUrl": "http://localhost:8079",
            {{- end }}
            "lapisUrls": {{- include "loculus.generateExternalLapisUrls" .externalLapisUrlConfig | fromYaml | toJson }},
            "keycloakUrl":  "https://{{ printf "authentication-%s" .Values.host }}"
{{- end }}

